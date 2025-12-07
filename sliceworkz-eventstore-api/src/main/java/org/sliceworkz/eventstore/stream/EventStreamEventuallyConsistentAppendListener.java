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

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Listener interface for receiving eventually consistent notifications when events are appended to an event stream.
 * <p>
 * This listener provides asynchronous, eventually consistent notification of newly appended events. Unlike
 * {@link EventStreamConsistentAppendListener}, this listener receives only an {@link EventReference} indicating
 * the position of the last appended event, not the full event data. This makes it lightweight and suitable for
 * triggering asynchronous processing workflows.
 * <p>
 * The notification occurs asynchronously after the append operation has completed, providing eventual consistency
 * guarantees. This means:
 * <ul>
 *   <li>The listener is invoked after the append operation completes</li>
 *   <li>The listener receives only an event reference, not the actual events</li>
 *   <li>Processing occurs asynchronously, outside the append transaction</li>
 *   <li>Exceptions thrown by the listener do not affect the append operation</li>
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
 * For immediate, transactional event processing with full event data, use
 * {@link EventStreamConsistentAppendListener} instead.
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
 * // Subscribe to eventually consistent append notifications
 * stream.subscribe((EventReference atLeastUntil) -> {
 *     // Query new events since last processed position
 *     EventReference startAfter = lastProcessed.get();
 *
 *     EventQuery query = EventQuery.forEvents(
 *         EventTypesFilter.any(),
 *         Tags.none(),
 *         Optional.of(atLeastUntil) // process up to this reference
 *     );
 *
 *     stream.query(query, startAfter).forEach(event -> {
 *         // Process event asynchronously
 *         System.out.println("Processing: " + event.type());
 *         processEventAsync(event);
 *     });
 *
 *     // Update bookmark to track progress
 *     lastProcessed.set(atLeastUntil);
 *     stream.placeBookmark("myProcessor", atLeastUntil, Tags.of("status", "processed"));
 * });
 *
 * // Append events - listener is notified asynchronously
 * stream.append(
 *     AppendCriteria.none(),
 *     Event.of(new CustomerRegistered("123", "John Doe"), Tags.of("region", "EU"))
 * );
 * }</pre>
 *
 * @see EventStreamConsistentAppendListener
 * @see EventSource#subscribe(EventStreamEventuallyConsistentAppendListener)
 * @see EventStream
 * @see EventReference
 */
@FunctionalInterface
public interface EventStreamEventuallyConsistentAppendListener {

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
	 *   <li>This method is called asynchronously, outside the append transaction</li>
	 *   <li>Exceptions thrown by this method do not affect the append operation</li>
	 *   <li>The reference represents at least the last appended event, possibly more</li>
	 *   <li>Multiple appends may be batched into a single notification</li>
	 *   <li>Process events by querying the stream with the provided reference as the upper bound</li>
	 * </ul>
	 *
	 * @param atLeastUntil reference to at least the last appended event, never null
	 * @return the reference to the last event actually processed or queried by this listener,
	 *         which may be equal to or ahead of the {@code atLeastUntil} parameter if the
	 *         listener proactively queried further events; never null
	 */
	EventReference eventsAppended ( EventReference atLeastUntil );

}
