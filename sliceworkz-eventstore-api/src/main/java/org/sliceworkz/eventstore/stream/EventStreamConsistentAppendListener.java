/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
 * Listener interface for receiving strongly consistent notifications when events are appended to an event stream.
 * <p>
 * This listener provides immediate, synchronous notification of newly appended events as part of the append
 * operation itself. Unlike {@link EventStreamEventuallyConsistentAppendListener}, this listener receives
 * the full typed domain events, not just event references, enabling immediate processing of the event data.
 * <p>
 * The notification occurs synchronously within the append transaction, providing strong consistency guarantees.
 * This means:
 * <ul>
 *   <li>The listener is invoked before the append operation completes</li>
 *   <li>The listener receives the actual typed domain events</li>
 *   <li>Processing occurs in the same transaction context</li>
 *   <li>Any exceptions thrown by the listener may affect the append operation</li>
 * </ul>
 * <p>
 * This listener is ideal for scenarios requiring immediate, transactional event processing such as:
 * <ul>
 *   <li>Updating in-memory caches or projections</li>
 *   <li>Triggering synchronous business logic</li>
 *   <li>Enforcing cross-stream consistency</li>
 *   <li>Immediate event validation or enrichment</li>
 * </ul>
 * <p>
 * For eventual consistency scenarios where only the event reference is needed, use
 * {@link EventStreamEventuallyConsistentAppendListener} instead.
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
	 * Called synchronously when events are appended to the event stream.
	 * <p>
	 * This method is invoked as part of the append transaction, before the append operation
	 * completes. The events provided are the full typed domain events that were just appended,
	 * allowing immediate access to the event data.
	 * <p>
	 * Implementation notes:
	 * <ul>
	 *   <li>This method is called synchronously, so long-running operations will delay the append</li>
	 *   <li>Exceptions thrown by this method may affect the append operation</li>
	 *   <li>The events list is immutable and contains all events from a single append operation</li>
	 *   <li>Events are provided in the order they were appended</li>
	 * </ul>
	 *
	 * @param events the list of typed events that were appended, never null but may be empty
	 */
	void eventsAppended ( List<? extends Event<DOMAIN_EVENT_TYPE>> events );

}
