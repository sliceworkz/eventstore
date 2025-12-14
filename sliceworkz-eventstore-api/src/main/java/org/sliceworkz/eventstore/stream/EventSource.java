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

import java.util.Optional;
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
 * <h2>Query Modes:</h2>
 * <ul>
 *   <li><strong>Forward queries</strong>: Read events from earliest to latest (default)</li>
 *   <li><strong>Backward queries</strong>: Read events from latest to earliest</li>
 *   <li><strong>Pagination</strong>: Use 'after'/'before' references and limits for efficient navigation</li>
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
 * Stream<Event<CustomerEvent>> recent = stream.queryBackwards(
 *     EventQuery.matchAll(),
 *     Limit.of(10)
 * );
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
	 * Queries events from the stream in forward order (earliest to latest) with full control over pagination.
	 * <p>
	 * This is the primary query method providing complete control over event retrieval.
	 * The 'after' parameter enables pagination and is purely a technical optimization - it does not affect
	 * which events match the query, only where the scan starts. The 'until' reference in the EventQuery
	 * is the functional boundary that determines query results.
	 *
	 * @param query the query criteria specifying which events to retrieve
	 * @param after optional reference to start reading after (for pagination), null to start from the beginning
	 * @param limit optional limit on the number of events to return
	 * @return a Stream of events matching the query criteria
	 * @see EventQuery
	 * @see Limit
	 */
	Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query, EventReference after, Limit limit );

	/**
	 * Queries events from the stream in forward order starting after a specific event reference.
	 * <p>
	 * Convenience method for paginated queries without a limit. Returns all matching events
	 * after the specified reference.
	 *
	 * @param query the query criteria specifying which events to retrieve
	 * @param after optional reference to start reading after, null to start from the beginning
	 * @return a Stream of events matching the query criteria
	 */
	default Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query, EventReference after ) {
		return query(query, after, Limit.none());
	}

	/**
	 * Queries events from the stream in forward order starting from the beginning.
	 * <p>
	 * Convenience method for querying without pagination. Returns all events matching
	 * the query criteria from the start of the stream.
	 *
	 * @param query the query criteria specifying which events to retrieve
	 * @return a Stream of events matching the query criteria
	 */
	default Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query ) {
		return query(query, null, Limit.none());
	}

	/**
	 * Queries events from the stream in backward order (latest to earliest) with a limit.
	 * <p>
	 * Convenience method for retrieving the most recent events matching a query.
	 * Useful for displaying recent activity or processing events in reverse chronological order.
	 *
	 * @param query the query criteria specifying which events to retrieve
	 * @param limit optional limit on the number of events to return
	 * @return a Stream of events matching the query criteria in reverse order
	 */
	default Stream<Event<DOMAIN_EVENT_TYPE>> queryBackwards ( EventQuery query, Limit limit ) {
		return queryBackwards(query, null, limit);
	}

	/**
	 * Queries events from the stream in backward order ending before a specific event reference.
	 * <p>
	 * Convenience method for backward pagination without a limit. Returns all matching events
	 * before the specified reference in reverse chronological order.
	 *
	 * @param query the query criteria specifying which events to retrieve
	 * @param before optional reference to end reading before, null to start from the end
	 * @return a Stream of events matching the query criteria in reverse order
	 */
	default Stream<Event<DOMAIN_EVENT_TYPE>> queryBackwards ( EventQuery query, EventReference before ) {
		return queryBackwards(query, before, Limit.none());
	}

	/**
	 * Queries events from the stream in backward order (latest to earliest) with full control over pagination.
	 * <p>
	 * Primary method for backward queries. Events are returned in reverse chronological order,
	 * optionally limited and starting before a specific event reference.
	 *
	 * @param query the query criteria specifying which events to retrieve
	 * @param before optional reference to end reading before (for pagination), null to start from the end
	 * @param limit optional limit on the number of events to return
	 * @return a Stream of events matching the query criteria in reverse order
	 */
	Stream<Event<DOMAIN_EVENT_TYPE>> queryBackwards ( EventQuery query, EventReference before, Limit limit );

	/**
	 * Retrieves the event reference for a specific event ID.
	 * <p>
	 * This method is useful when you have an event ID and need to obtain its reference
	 * (which includes position information) without retrieving the full event.
	 *
	 * @param id the event ID to look up
	 * @return an Optional containing the EventReference if found, empty otherwise
	 */
	Optional<EventReference> queryReference ( EventId id );

	/**
	 * Retrieves a specific event by its unique event ID.
	 * <p>
	 * This method performs a direct lookup of an event by its ID, returning the full
	 * event with all metadata and data if it exists in this stream.
	 *
	 * @param eventId the unique identifier of the event to retrieve
	 * @return an Optional containing the Event if found, empty otherwise
	 */
	Optional<Event<DOMAIN_EVENT_TYPE>> getEventById ( EventId eventId );


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
