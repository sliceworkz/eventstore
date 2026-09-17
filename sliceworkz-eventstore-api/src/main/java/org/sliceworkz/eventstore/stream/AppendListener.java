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

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Listener notified, eventually consistently, when events are appended to an event stream.
 * <p>
 * This is the one kind of append listener there is, which is why its name says nothing about
 * <em>when</em> it is told: every append listener is told after the fact, on a notification thread,
 * once the events are committed and readable. A listener told <em>before</em> the commit, or on the
 * appending thread, does not exist — to react to your own append there, the events are the return
 * value of {@link EventSink#append(AppendCriteria, java.util.List)} — so the qualifier would
 * distinguish this listener from nothing.
 * <p>
 * It receives only an {@link EventReference} indicating the position of the last appended event, not
 * the full event data, which makes it lightweight and suitable for triggering asynchronous processing
 * workflows. It is notified about every append to the stream, whoever made it — including appends made
 * by another process.
 * <p>
 * The notification occurs asynchronously after the append operation has completed, providing eventual consistency
 * guarantees. This means:
 * <ul>
 *   <li>The listener is invoked after the append operation completes, and once the events up to the
 *       reference it is handed are readable — a backend whose reads lag its commits holds the
 *       notification back until they are, rather than wake a listener that would read nothing</li>
 *   <li>The listener receives only an event reference, not the actual events</li>
 *   <li>Processing occurs asynchronously, on a notification thread rather than the appending one</li>
 *   <li>Exceptions thrown by the listener do not affect the append operation: they are logged at ERROR by
 *       the event store, the remaining listeners are still notified, and the failing listener simply misses
 *       that notification. It will be notified again on the next append, but nothing replays the one it
 *       missed — a listener that must not lose progress should be driving a
 *       {@link org.sliceworkz.eventstore.projection.Projector} from a bookmark</li>
 *   <li>There may be a delay between the append and the notification</li>
 * </ul>
 * <p>
 * This listener is ideal for scenarios requiring asynchronous event processing such as:
 * <ul>
 *   <li>Triggering background jobs or workflows</li>
 *   <li>Updating eventually consistent read models</li>
 *   <li>Notifying external systems of changes</li>
 *   <li>Coordinating distributed processing</li>
 * </ul>
 * <p>
 * The listener receives an {@link EventReference} representing the position of at least the last appended event.
 * To process the actual events, query the stream starting after your last processed reference up to the
 * provided reference.
 * <p>
 * To react to your <em>own</em> append on the appending thread, there is nothing to subscribe: the typed
 * events, with their assigned references, are the return value of
 * {@link EventSink#append(org.sliceworkz.eventstore.stream.AppendCriteria, java.util.List)}. Note that this
 * listener does not run in a transaction either — by the time it is called the events are long committed.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create event store and stream
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * // Track last processed reference
 * AtomicReference<EventReference> lastProcessed = new AtomicReference<>();
 *
 * // Subscribe to append notifications; the handle ends this subscription alone
 * Subscription subscription = stream.subscribe((EventReference atLeastUntil) -> {
 *     // Query new events since the last processed position, up to the reference notified
 *     EventReference startAfter = lastProcessed.get();
 *     stream.query(EventQuery.matchAll().until(atLeastUntil), startAfter).forEach(event -> {
 *         System.out.println("Processing: " + event.type());
 *         processEventAsync(event);
 *     });
 *
 *     // Update bookmark to track progress
 *     lastProcessed.set(atLeastUntil);
 *     stream.placeBookmark("myProcessor", atLeastUntil, Tags.of("status", "processed"));
 *     return atLeastUntil;
 * });
 *
 * // Append events - listener is notified asynchronously
 * stream.append(Event.of(new CustomerRegistered("123", "John Doe"), Tags.of("region", "EU")));
 *
 * // Done listening: this listener is dropped, other listeners on the stream keep going
 * subscription.close();
 * }</pre>
 *
 * @see EventSource#subscribe(AppendListener)
 * @see Subscription
 * @see EventStream
 * @see EventReference
 */
@FunctionalInterface
public interface AppendListener {

	/**
	 * Called asynchronously when events are appended to the event stream.
	 * <p>
	 * This method is invoked after the append operation has completed, providing eventual
	 * consistency guarantees. The provided reference indicates the position of at least the
	 * last appended event in the stream.
	 * <p>
	 * To process the actual events, query the stream from your last processed position up to
	 * the provided reference. Use bookmarks to track your processing position across restarts.
	 * <p>
	 * The returned {@link EventReference} allows the listener to inform the caller about the actual
	 * last event it has processed or queried. This enables optimization strategies where listeners
	 * may proactively query ahead and report their actual position, allowing subsequent notifications
	 * to be skipped if they would be redundant.
	 * <p>
	 * Implementation notes:
	 * <ul>
	 *   <li>This method is called asynchronously, on a notification thread, after the events are committed</li>
	 *   <li>Exceptions thrown by this method do not affect the append operation; they are logged at ERROR
	 *       and the remaining listeners are still notified</li>
	 *   <li>The reference represents at least the last appended event, possibly more</li>
	 *   <li>Multiple appends may be batched into a single notification</li>
	 *   <li>Process events by querying the stream with the provided reference as the upper bound</li>
	 * </ul>
	 *
	 * @param atLeastUntil reference to at least the last appended event, never null
	 * @return the reference to the last event actually processed or queried by this listener,
	 *         which may be equal to or ahead of the {@code atLeastUntil} parameter if the
	 *         listener proactively queried further events. A reference <em>behind</em>
	 *         {@code atLeastUntil}, and null for "I processed nothing", both mean this listener is
	 *         caught up to {@code atLeastUntil}: the store stops delivering and the next append is
	 *         what brings it back. Returning null to ask to be told again does not work — it used to
	 *         make the store re-deliver without pausing, which is why null now has a defined meaning
	 *         rather than being left to the caller. {@link org.sliceworkz.eventstore.projection.Projector}
	 *         returns null whenever its query matched no events
	 */
	EventReference eventsAppended ( EventReference atLeastUntil );

}
