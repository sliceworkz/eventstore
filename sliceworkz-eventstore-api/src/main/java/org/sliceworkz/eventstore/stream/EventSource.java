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
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

/**
 * Interface for reading events from an event stream.
 * <p>
 * EventSource provides comprehensive querying capabilities for retrieving events from the event store,
 * including forward and backward queries, pagination, bookmarking, and event subscriptions.
 * <p>
 * Key features include:
 * <ul>
 *   <li>Flexible querying with {@link EventQuery} for filtering by event types and tags</li>
 *   <li>Forward and backward iteration through events</li>
 *   <li>Pagination support via {@link Limit} and {@link EventReference}</li>
 *   <li>Event subscriptions for reactive processing</li>
 *   <li>Bookmarking for tracking read positions</li>
 * </ul>
 * <p>
 * EventSource is typically accessed through {@link EventStream}, which combines reading and writing capabilities.
 *
 * <h2>Query Direction:</h2>
 * The direction of query traversal is controlled by the {@link EventQuery} itself via
 * {@link EventQuery#backwards()}. By default, queries run forward (oldest to newest).
 * <ul>
 *   <li><strong>Forward queries</strong>: Read events from earliest to latest (default)</li>
 *   <li><strong>Backward queries</strong>: Read events from latest to earliest (via {@link EventQuery#backwards()})</li>
 *   <li><strong>Pagination</strong>: Use cursor references and limits for efficient navigation</li>
 * </ul>
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(
 *     EventStreamId.forContext("customer").withPurpose("123"),
 *     CustomerEvent.class
 * );
 *
 * // Query all events (forward)
 * Stream<Event<CustomerEvent>> allEvents = stream.query(EventQuery.matchAll());
 *
 * // Query with filters
 * Stream<Event<CustomerEvent>> filtered = stream.query(
 *     EventQuery.forEvents(
 *         EventTypesFilter.of(CustomerRegistered.class, CustomerNameChanged.class),
 *         Tags.of("region", "EU")
 *     )
 * );
 *
 * // Paginated query (first 10 events)
 * Stream<Event<CustomerEvent>> page1 = stream.query(
 *     EventQuery.matchAll(),
 *     null, // start from beginning
 *     Limit.of(10)
 * );
 *
 * // Next page (after last event from page1)
 * EventReference lastRef = page1.reduce((first, second) -> second).get().reference();
 * Stream<Event<CustomerEvent>> page2 = stream.query(
 *     EventQuery.matchAll(),
 *     lastRef,
 *     Limit.of(10)
 * );
 *
 * // Backward query (most recent 10 events)
 * Stream<Event<CustomerEvent>> recent = stream.query(
 *     EventQuery.matchAll().backwards().limit(10)
 * );
 *
 * // Most recent single event
 * Optional<Event<CustomerEvent>> mostRecent = stream.query(
 *     EventQuery.matchAll().backwards().limit(1)
 * ).findFirst();
 *
 * // Get specific event by ID
 * Optional<Event<CustomerEvent>> event = stream.getEventById(eventId);
 *
 * // Bookmarking for resume capability
 * stream.placeBookmark("myReader", lastProcessedRef, Tags.of("status", "processed"));
 * Optional<EventReference> bookmark = stream.getBookmark("myReader");
 * }</pre>
 *
 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream (typically a sealed interface)
 * @see EventStream
 * @see EventSink
 * @see EventQuery
 * @see Event
 */
public interface EventSource<DOMAIN_EVENT_TYPE> {

	/**
	 * Queries events from the stream with full control over pagination and raw cursor tracking.
	 * <p>
	 * This method extends the standard query with a {@code storedEventCursorTracker} callback that
	 * is invoked once for each stored event fetched from storage, <em>before</em> upcasting and
	 * filtering. This enables callers to track the raw storage cursor even when upcasting
	 * produces zero enriched events (e.g., when legacy events are filtered out by an upcaster).
	 * <p>
	 * The cursor reference enables pagination:
	 * <ul>
	 *   <li><strong>Forward queries:</strong> the cursor acts as "after" — only events after this reference are returned</li>
	 *   <li><strong>Backward queries:</strong> the cursor acts as "before" — only events before this reference are returned</li>
	 * </ul>
	 * The cursor is purely a technical optimization — it does not affect which events match the query,
	 * only where the scan starts. The 'until' reference in the EventQuery is the functional boundary
	 * that determines query results.
	 *
	 * @param query the query criteria specifying which events to retrieve and in which direction
	 * @param cursor optional reference for pagination (after for forward, before for backward), null to start from the beginning/end
	 * @param limit maximum number of events to return (overrides the query's own limit)
	 * @param storedEventCursorTracker callback invoked with each raw stored event's reference before upcasting, useful for advancing cursors past events that upcast to zero enriched events
	 * @return a Stream of events matching the query criteria
	 * @see EventQuery
	 * @see Limit
	 */
	Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query, EventReference cursor, Limit limit, Consumer<EventReference> storedEventCursorTracker );

	/**
	 * Queries events from the stream with full control over pagination.
	 * <p>
	 * This is a convenience overload that delegates to
	 * {@link #query(EventQuery, EventReference, Limit, Consumer)} with a no-op cursor tracker.
	 *
	 * @param query the query criteria specifying which events to retrieve and in which direction
	 * @param cursor optional reference for pagination (after for forward, before for backward), null to start from the beginning/end
	 * @param limit maximum number of events to return (overrides the query's own limit)
	 * @return a Stream of events matching the query criteria
	 * @see EventQuery
	 * @see Limit
	 */
	default Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query, EventReference cursor, Limit limit ) {
		return query(query, cursor, limit, ref -> {});
	}

	/**
	 * Queries events from the stream starting from a specific cursor reference.
	 * <p>
	 * Convenience method for paginated queries without an explicit limit.
	 * The direction is determined by the query ({@link EventQuery#backwards()}).
	 *
	 * @param query the query criteria specifying which events to retrieve and in which direction
	 * @param cursor optional reference for pagination, null to start from the beginning/end
	 * @return a Stream of events matching the query criteria
	 */
	default Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query, EventReference cursor ) {
		return query(query, cursor, Limit.none());
	}

	/**
	 * Queries events from the stream, respecting the query's own direction and limit.
	 * <p>
	 * If the query has a backward direction (via {@link EventQuery#backwards()}), events are returned
	 * in reverse chronological order. If the query has a limit (via {@link EventQuery#limit(long)}),
	 * at most that many events are returned. Otherwise, returns all events in forward order.
	 *
	 * @param query the query criteria specifying which events to retrieve
	 * @return a Stream of events matching the query criteria
	 */
	default Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query ) {
		return query(query, null, query.limit());
	}

	/**
	 * Retrieves events by their stored event ID.
	 * <p>
	 * This method performs a direct lookup of an event by its ID and returns all events
	 * that result from deserializing and upcasting the stored event. For non-upcasted events,
	 * this returns a single-element list. For events that are upcasted into multiple sub-events,
	 * all sub-events are returned with distinct references (differing by index).
	 * Returns an empty list if no event with the given ID exists in this stream.
	 *
	 * @param eventId the unique identifier of the stored event to retrieve
	 * @return a list of events produced from the stored event, or an empty list if not found
	 */
	List<Event<DOMAIN_EVENT_TYPE>> getEventById ( EventId eventId );


	/**
	 * Subscribes to be notified when events are appended to this stream (eventually consistent).
	 * <p>
	 * This subscription provides eventual consistency guarantees - the listener may be notified
	 * slightly after the append has completed. Useful for async processing where immediate
	 * consistency is not required.
	 *
	 * @param listener the listener to receive append notifications
	 */
	void subscribe ( EventStreamEventuallyConsistentAppendListener listener );

	/**
	 * Subscribes to be notified when events are appended to this stream (strongly consistent).
	 * <p>
	 * This subscription provides strong consistency guarantees - the listener is notified
	 * synchronously as part of the append operation. The listener receives the typed domain events.
	 *
	 * @param listener the listener to receive append notifications with typed events
	 */
	void subscribe ( EventStreamConsistentAppendListener<DOMAIN_EVENT_TYPE> listener );

	/**
	 * Subscribes to be notified when bookmarks are placed in this stream (eventually consistent).
	 * <p>
	 * This subscription allows monitoring bookmark updates, useful for coordinating
	 * multiple readers or tracking processing progress.
	 *
	 * @param listener the listener to receive bookmark notifications
	 */
	void subscribe ( EventStreamEventuallyConsistentBookmarkListener listener );


	/**
	 * Places a bookmark at a specific position in the stream for a named reader.
	 * <p>
	 * Bookmarks enable readers to track their position in the event stream and resume
	 * processing from where they left off. Each reader is identified by name and can
	 * maintain only one bookmark per stream. Tags can be attached to bookmarks for
	 * additional metadata (e.g., processing status, reader state).
	 *
	 * @param reader the unique name/identifier of the reader placing the bookmark
	 * @param reference the event reference to bookmark (the last processed event)
	 * @param tags optional tags to attach to the bookmark for metadata
	 */
	void placeBookmark ( String reader, EventReference reference, Tags tags );

	/**
	 * Retrieves the bookmark for a named reader from this stream.
	 * <p>
	 * Returns the last bookmarked position for the specified reader, allowing
	 * the reader to resume processing from where it left off.
	 *
	 * @param reader the unique name/identifier of the reader
	 * @return an Optional containing the bookmarked EventReference if found, empty if no bookmark exists
	 */
	Optional<EventReference> getBookmark ( String reader );

	/**
	 * Removes the bookmark for a named reader and returns its previous value.
	 * <p>
	 * This method atomically removes the reader's bookmark and returns the last bookmarked
	 * position if one existed. This is useful when you need to both retrieve and clear a
	 * bookmark in a single operation, or when resetting a reader's progress tracking.
	 * <p>
	 * Common use cases include:
	 * <ul>
	 *   <li>Resetting a projection to reprocess all events from the beginning</li>
	 *   <li>Migrating bookmark data to a new storage mechanism</li>
	 *   <li>Cleaning up bookmarks for discontinued readers</li>
	 *   <li>Implementing bookmark expiration or rotation logic</li>
	 * </ul>
	 * <p>
	 * After this method completes, subsequent calls to {@link #getBookmark(String)} for
	 * this reader will return {@code Optional.empty()} until a new bookmark is placed.
	 * <p>
	 * Typical Usage:
	 * <pre>{@code
	 * // Reset a projection and get its last position
	 * Optional<EventReference> lastPosition = stream.removeBookmark("my-projection");
	 * if (lastPosition.isPresent()) {
	 *     System.out.println("Removed bookmark at: " + lastPosition.get());
	 * }
	 *
	 * // Now start processing from the beginning
	 * stream.query(EventQuery.matchAll()).forEach(this::processEvent);
	 * }</pre>
	 *
	 * @param reader the unique name/identifier of the reader whose bookmark should be removed
	 * @return an Optional containing the previous bookmarked EventReference if one existed, empty otherwise
	 */
	Optional<EventReference> removeBookmark ( String reader );

}
