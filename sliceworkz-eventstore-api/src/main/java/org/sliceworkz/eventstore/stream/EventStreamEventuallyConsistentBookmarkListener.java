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
 * Listener interface for receiving eventually consistent notifications when bookmarks are updated in an event stream.
 * <p>
 * This listener provides asynchronous, eventually consistent notification when a bookmark is placed or updated
 * in the stream. Bookmarks are used by event processors to track their read position and enable resumable
 * event processing across restarts.
 * <p>
 * The notification occurs asynchronously after the bookmark operation has completed, providing eventual consistency
 * guarantees. This means:
 * <ul>
 *   <li>The listener is invoked after the bookmark is placed/updated</li>
 *   <li>Processing occurs asynchronously, outside the bookmark transaction</li>
 *   <li>Exceptions thrown by the listener do not affect the bookmark operation</li>
 *   <li>There may be a delay between the bookmark update and the notification</li>
 * </ul>
 * <p>
 * This listener is ideal for scenarios such as:
 * <ul>
 *   <li>Monitoring processing progress across multiple readers</li>
 *   <li>Coordinating distributed event processors</li>
 *   <li>Tracking which events have been processed by which systems</li>
 *   <li>Implementing health checks for event processing pipelines</li>
 *   <li>Detecting processing lag or stuck processors</li>
 * </ul>
 * <p>
 * Each bookmark is identified by a reader name (a unique identifier for the processor) and contains
 * an {@link EventReference} indicating the last event processed by that reader.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create event store and stream
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * // Subscribe to bookmark update notifications
 * stream.subscribe((String reader, EventReference processedUntil) -> {
 *     System.out.println("Reader '" + reader + "' processed up to: " +
 *         processedUntil.id().value() + "/" + processedUntil.position());
 *
 *     // Monitor processing progress
 *     metricsService.recordProcessingPosition(reader, processedUntil);
 *
 *     // Detect lag by comparing to latest event
 *     detectProcessingLag(reader, processedUntil);
 * });
 *
 * // Process events and place bookmarks
 * stream.query(EventQuery.matchAll()).forEach(event -> {
 *     processEvent(event);
 *
 *     // Place bookmark after processing - listener will be notified
 *     stream.placeBookmark(
 *         "myProcessor",
 *         event.reference(),
 *         Tags.of("processed-at", Instant.now().toString())
 *     );
 * });
 * }</pre>
 *
 * <h2>Coordinating Multiple Readers:</h2>
 * <pre>{@code
 * // Monitor all readers and detect when all have caught up
 * Map<String, EventReference> readerPositions = new ConcurrentHashMap<>();
 *
 * stream.subscribe((String reader, EventReference processedUntil) -> {
 *     readerPositions.put(reader, processedUntil);
 *
 *     // Check if all expected readers have processed to the same position
 *     if (allReadersCaughtUp(readerPositions)) {
 *         triggerNextPhase();
 *     }
 * });
 * }</pre>
 *
 * @see EventSource#subscribe(EventStreamEventuallyConsistentBookmarkListener)
 * @see EventSource#placeBookmark(String, EventReference, org.sliceworkz.eventstore.events.Tags)
 * @see EventSource#getBookmark(String)
 * @see EventStream
 * @see EventReference
 */
@FunctionalInterface
public interface EventStreamEventuallyConsistentBookmarkListener {

	/**
	 * Called asynchronously when a bookmark is placed or updated in the event stream.
	 * <p>
	 * This method is invoked after the bookmark operation has completed, providing eventual
	 * consistency guarantees. The parameters identify which reader updated their position
	 * and to which event reference they have processed.
	 * <p>
	 * Implementation notes:
	 * <ul>
	 *   <li>This method is called asynchronously, outside the bookmark transaction</li>
	 *   <li>Exceptions thrown by this method do not affect the bookmark operation</li>
	 *   <li>The same reader may place multiple bookmarks; you receive each update</li>
	 *   <li>Multiple readers can maintain independent bookmarks on the same stream</li>
	 *   <li>The reference indicates the last event the reader has successfully processed</li>
	 * </ul>
	 *
	 * @param reader the unique identifier of the reader/processor that placed the bookmark, never null
	 * @param processedUntil the event reference up to which the reader has processed, never null
	 */
	void bookmarkUpdated ( String reader, EventReference processedUntil );

}
