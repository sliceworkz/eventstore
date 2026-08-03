/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventstore.stream;

import java.util.List;

import org.sliceworkz.eventstore.events.Event;

/**
 * Listener interface for reacting to your own appends, inline, before {@code append} returns.
 * <p>
 * This listener is called synchronously by {@link EventSink#append}, on the appending thread, and receives
 * the full typed domain events rather than a reference to them — so the code that wrote the events can act
 * on them without querying them back. Unlike
 * {@link EventStreamEventuallyConsistentAppendListener}, there is no thread hop and no delay.
 *
 * <h2>What "consistent" means here, and what it does not</h2>
 * It means read-your-own-writes for the appending caller: when {@code append} returns, this listener has
 * already seen everything that append wrote. It does <em>not</em> mean transactional.
 * <ul>
 *   <li><b>There is no transaction.</b> The listener runs after the storage append has committed and
 *       returned. On PostgreSQL the {@code COMMIT} has already been issued; on the in-memory backends the
 *       events are already in the log. The listener cannot join a transaction, cannot be rolled back with
 *       one, and cannot veto the write: by the time it runs, the events are durable and visible to every
 *       other reader. Validation therefore has to happen <em>before</em> appending, not here.</li>
 *   <li><b>It is not a subscription to the stream.</b> A consistent listener is notified only about appends
 *       made through the very {@link EventStream} object it was subscribed on — not about appends made
 *       through another handle on the same logical stream, and not about appends made by another process.
 *       Streams are cheap per-operation handles, so in practice this is a callback on <em>your</em> writes.
 *       For everything appended to a stream, whoever appended it, use
 *       {@link EventStreamEventuallyConsistentAppendListener}.</li>
 *   <li><b>It is not notified first.</b> The eventually consistent listeners of this store are dispatched
 *       from the storage's own notification, which is raised inside the append; they may well have run
 *       before this listener is called. Nothing orders the two.</li>
 * </ul>
 *
 * <h2>Failure semantics</h2>
 * <b>An exception thrown by this listener never fails the append.</b> It is logged at ERROR by the event
 * store and the append returns normally with its events. This is deliberate and is the only coherent option:
 * the events are already committed, so reporting the append as failed would describe a write that succeeded
 * as one that did not, and a caller retrying on that failure would append them a second time.
 * <p>
 * Every subscriber is offered every append: one that throws does not stop the ones after it, which are
 * notified in subscription order. The failure costs exactly one notification to one listener.
 * <p>
 * A listener that must tell the appending caller it failed can simply do so — it runs on that caller's
 * thread, inside that caller's call stack, and is that caller's own code:
 * <pre>{@code
 * AtomicReference<Exception> cacheFailure = new AtomicReference<>();
 * stream.subscribe((List<? extends Event<CustomerEvent>> events) -> {
 *     try {
 *         cache.apply(events);
 *     } catch (Exception e) {
 *         cacheFailure.set(e);   // the events are stored either way; decide what that means to you
 *     }
 * });
 * }</pre>
 *
 * <h2>What it is good for</h2>
 * <ul>
 *   <li>Updating an in-memory read model or cache the appending caller is about to read from</li>
 *   <li>Recording what this caller has already seen, so a projection it also drives does not re-query it</li>
 *   <li>Triggering follow-on work synchronously, where doing it on the appending thread is wanted</li>
 * </ul>
 * Anything that must survive the process, must not be lost, or must run for appends made elsewhere belongs
 * in an {@link EventStreamEventuallyConsistentAppendListener} driving a
 * {@link org.sliceworkz.eventstore.projection.Projector} instead — that path is restartable from a bookmark,
 * this one is not.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create event store and stream
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * // Subscribe to strongly consistent append notifications
 * stream.subscribe((List<? extends Event<CustomerEvent>> events) -> {
 *     events.forEach(event -> {
 *         System.out.println("Event appended: " + event.type());
 *
 *         // Process the typed domain event immediately
 *         switch (event.data()) {
 *             case CustomerRegistered(String id, String name) ->
 *                 cache.put(id, name);
 *             case CustomerNameChanged(String id, String newName) ->
 *                 cache.update(id, newName);
 *             case CustomerChurned(String id) ->
 *                 cache.remove(id);
 *         }
 *     });
 * });
 *
 * // Append events - listener is notified synchronously
 * stream.append(
 *     AppendCriteria.none(),
 *     Event.of(new CustomerRegistered("123", "John Doe"), Tags.of("region", "EU"))
 * );
 * }</pre>
 *
 * @param <DOMAIN_EVENT_TYPE> the type of domain events in the stream (typically a sealed interface)
 * @see EventStreamEventuallyConsistentAppendListener
 * @see EventSource#subscribe(EventStreamConsistentAppendListener)
 * @see EventStream
 * @see Event
 */
@FunctionalInterface
public interface EventStreamConsistentAppendListener<DOMAIN_EVENT_TYPE> {

	/**
	 * Called synchronously, on the appending thread, once the events are stored.
	 * <p>
	 * The events provided are the full typed domain events that were just appended, with their assigned
	 * references — the same list {@link EventSink#append} is about to return to its caller.
	 * <p>
	 * Implementation notes:
	 * <ul>
	 *   <li>Called on the appending thread, so a long-running implementation delays that caller's
	 *       {@code append} for as long as it runs</li>
	 *   <li>Called <em>after</em> the events are committed, not before — see the class javadoc. There is no
	 *       transaction to affect and the write cannot be vetoed from here</li>
	 *   <li>Exceptions do not fail the append: they are logged at ERROR and the remaining listeners are
	 *       still notified. Handle what you care about inside this method</li>
	 *   <li>The events list is immutable and contains all events from a single append operation</li>
	 *   <li>Events are provided in the order they were appended</li>
	 * </ul>
	 *
	 * @param events the list of typed events that were appended, never null but may be empty
	 */
	void eventsAppended ( List<? extends Event<DOMAIN_EVENT_TYPE>> events );

}
