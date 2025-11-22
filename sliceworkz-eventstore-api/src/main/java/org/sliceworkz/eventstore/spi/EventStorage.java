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
package org.sliceworkz.eventstore.spi;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Service Provider Interface (SPI) for event storage backend implementations.
 * <p>
 * This interface defines the contract that all event storage backends must implement to provide
 * persistence capabilities for the EventStore. Implementations are responsible for storing, retrieving,
 * and managing events, bookmarks, and subscriptions. The EventStore delegates all storage operations
 * to an EventStorage implementation through this interface.
 * <p>
 * This SPI is fully compliant with the Dynamic Consistency Boundary (DCB) specification, supporting:
 * <ul>
 *   <li>Event querying by types and tags</li>
 *   <li>Optimistic locking based on append criteria</li>
 *   <li>Event stream subscriptions</li>
 *   <li>Reader bookmarks for tracking event processing positions</li>
 * </ul>
 *
 * <h2>Implementation Responsibilities:</h2>
 * Implementations must:
 * <ul>
 *   <li>Store events with their associated metadata (stream, type, tags, timestamps)</li>
 *   <li>Support efficient querying by event types, tags, and stream identifiers</li>
 *   <li>Enforce optimistic locking constraints defined by {@link AppendCriteria}</li>
 *   <li>Maintain event references (unique IDs and positions) for ordering and retrieval</li>
 *   <li>Manage reader bookmarks for tracking event processing progress</li>
 *   <li>Notify listeners when new events are appended or bookmarks are placed</li>
 *   <li>Handle both forward and backward queries with proper ordering</li>
 * </ul>
 *
 * <h2>Thread Safety:</h2>
 * Implementations should be thread-safe and support concurrent reads and writes.
 * The optimistic locking mechanism handles conflicts when multiple threads attempt
 * to append events with overlapping consistency boundaries.
 *
 * <h2>Example Implementation Pattern:</h2>
 * <pre>{@code
 * public class CustomEventStorage implements EventStorage {
 *
 *     private final String name;
 *     private final List<EventStoreListener> listeners = new CopyOnWriteArrayList<>();
 *
 *     @Override
 *     public String name() {
 *         return name;
 *     }
 *
 *     @Override
 *     public Stream<StoredEvent> query(EventQuery query, Optional<EventStreamId> stream,
 *                                      EventReference from, Limit limit, QueryDirection direction) {
 *         // 1. Filter events by stream (if specified)
 *         // 2. Apply event type filters from query
 *         // 3. Apply tag filters from query
 *         // 4. Filter events after 'from' reference
 *         // 5. Apply limit and direction
 *         // 6. Return stream of StoredEvent records
 *     }
 *
 *     @Override
 *     public List<StoredEvent> append(AppendCriteria criteria, Optional<EventStreamId> stream,
 *                                     List<EventToStore> events) {
 *         // 1. Check optimistic locking via criteria
 *         // 2. Assign references and timestamps to events
 *         // 3. Persist events to storage
 *         // 4. Notify listeners of new events
 *         // 5. Return list of StoredEvent records
 *         // Throw OptimisticLockingException if criteria violated
 *     }
 *
 *     @Override
 *     public void subscribe(EventStoreListener listener) {
 *         listeners.add(listener);
 *     }
 *
 *     // Implement remaining methods...
 * }
 * }</pre>
 *
 * <h2>Available Implementations:</h2>
 * <ul>
 *   <li>{@code InMemoryEventStorage} - In-memory storage for development and testing</li>
 *   <li>{@code PostgresEventStorage} - PostgreSQL-backed storage for production use</li>
 * </ul>
 *
 * @see org.sliceworkz.eventstore.EventStore
 * @see org.sliceworkz.eventstore.EventStoreFactory
 * @see StoredEvent
 * @see EventToStore
 * @see AppendCriteria
 * @see EventQuery
 * @see org.sliceworkz.eventstore.stream.OptimisticLockingException
 */
public interface EventStorage {

	/**
	 * Returns the name of this event storage implementation.
	 * <p>
	 * The name is used for identification and logging purposes. It should be unique
	 * within an application if multiple storage instances are used.
	 *
	 * @return the name of this storage implementation
	 */
	String name ( );

	/**
	 * Queries events from storage based on specified criteria with directional control.
	 * <p>
	 * This method retrieves events matching the provided query, optionally filtered by stream,
	 * starting from a specific reference point, with configurable limit and direction.
	 * The query supports filtering by event types and tags as defined in the {@link EventQuery}.
	 * <p>
	 * Query Parameters:
	 * <ul>
	 *   <li><b>query</b> - Defines which events to retrieve based on types and tags</li>
	 *   <li><b>stream</b> - Optional stream filter; if present, only events from matching streams are returned</li>
	 *   <li><b>from</b> - Starting reference point; events after this reference are returned</li>
	 *   <li><b>limit</b> - Maximum number of events to return (or unlimited)</li>
	 *   <li><b>queryDirection</b> - Direction of traversal (FORWARD or BACKWARD)</li>
	 * </ul>
	 * <p>
	 * Query Direction:
	 * <ul>
	 *   <li><b>FORWARD</b> - Events are returned in chronological order (oldest to newest)</li>
	 *   <li><b>BACKWARD</b> - Events are returned in reverse chronological order (newest to oldest)</li>
	 * </ul>
	 *
	 * @param query the event query defining type and tag filters
	 * @param stream optional stream identifier to filter events by stream
	 * @param from the reference point to start querying from (events after this reference)
	 * @param limit maximum number of events to return
	 * @param queryDirection the direction of query traversal (FORWARD or BACKWARD)
	 * @return a stream of stored events matching the query criteria
	 * @throws EventStorageException if an error occurs during query execution
	 * @see EventQuery
	 * @see QueryDirection
	 * @see StoredEvent
	 */
	Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream, EventReference from, Limit limit, QueryDirection queryDirection );

	/**
	 * Queries events from storage in forward (chronological) direction.
	 * <p>
	 * This is a convenience method that delegates to {@link #query(EventQuery, Optional, EventReference, Limit, QueryDirection)}
	 * with {@link QueryDirection#FORWARD}. Events are returned in chronological order from oldest to newest.
	 *
	 * @param query the event query defining type and tag filters
	 * @param stream optional stream identifier to filter events by stream
	 * @param after the reference point to start querying from (events after this reference)
	 * @param limit maximum number of events to return
	 * @return a stream of stored events matching the query criteria in chronological order
	 * @throws EventStorageException if an error occurs during query execution
	 * @see #query(EventQuery, Optional, EventReference, Limit, QueryDirection)
	 */
	default Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream, EventReference after, Limit limit ) {
		return query ( query, stream, after, limit, QueryDirection.FORWARD);
	}

	/**
	 * Appends new events to storage with optimistic locking based on append criteria.
	 * <p>
	 * This method is the core write operation for the event store. It persists new events while
	 * enforcing optimistic locking constraints defined by the {@link AppendCriteria}. The criteria
	 * specifies an event query and an expected last event reference. If new events matching the query
	 * have been appended after the expected reference, the append fails with an {@link org.sliceworkz.eventstore.stream.OptimisticLockingException}.
	 * <p>
	 * Optimistic Locking Process:
	 * <ol>
	 *   <li>Query storage for events matching the criteria's query</li>
	 *   <li>Check if the last matching event's reference equals the expected reference</li>
	 *   <li>If references match (or no criteria), append events and assign references/timestamps</li>
	 *   <li>If references don't match, throw {@link org.sliceworkz.eventstore.stream.OptimisticLockingException}</li>
	 *   <li>Notify all subscribed listeners of new events via {@link AppendsToEventStoreNotification}</li>
	 * </ol>
	 * <p>
	 * Event Processing - Each {@link EventToStore} is converted to a {@link StoredEvent} by:
	 * <ul>
	 *   <li>Assigning a unique {@link EventReference} (ID and position)</li>
	 *   <li>Recording the current timestamp</li>
	 *   <li>Persisting the event data (both immutable and erasable portions)</li>
	 * </ul>
	 *
	 * @param appendCriteria criteria defining optimistic locking constraints (or none for simple append)
	 * @param stream optional stream identifier to append events to a specific stream
	 * @param events list of events to append (must not be empty)
	 * @return list of stored events with assigned references and timestamps
	 * @throws org.sliceworkz.eventstore.stream.OptimisticLockingException if append criteria are violated
	 * @throws EventStorageException if an error occurs during append operation
	 * @see AppendCriteria
	 * @see EventToStore
	 * @see StoredEvent
	 * @see org.sliceworkz.eventstore.stream.OptimisticLockingException
	 */
	List<StoredEvent> append ( AppendCriteria appendCriteria, Optional<EventStreamId> stream, List<EventToStore> events );

	/**
	 * Retrieves a specific event by its unique identifier.
	 * <p>
	 * This method provides direct access to an event using its {@link EventId}.
	 * Returns empty if no event with the specified ID exists.
	 *
	 * @param eventId the unique identifier of the event to retrieve
	 * @return an Optional containing the event if found, or empty if not found
	 * @throws EventStorageException if an error occurs during retrieval
	 * @see EventId
	 * @see StoredEvent
	 */
	Optional<StoredEvent> getEventById ( EventId eventId );

	/**
	 * Registers a listener to receive notifications about storage events.
	 * <p>
	 * Listeners are notified synchronously when:
	 * <ul>
	 *   <li>New events are appended via {@link AppendsToEventStoreNotification}</li>
	 *   <li>Bookmarks are placed via {@link BookmarkPlacedNotification}</li>
	 * </ul>
	 * <p>
	 * Implementations should ensure listeners are called in a thread-safe manner.
	 * Listeners should perform minimal work and delegate to async processing where possible.
	 *
	 * @param listener the listener to register for storage notifications
	 * @see EventStoreListener
	 * @see AppendsToEventStoreNotification
	 * @see BookmarkPlacedNotification
	 */
	void subscribe ( EventStoreListener listener );

	/**
	 * Defines the direction of event query traversal.
	 * <p>
	 * This enum controls how events are ordered when retrieved from storage.
	 * The direction affects the order of results but not which events are matched.
	 *
	 * @see #query(EventQuery, Optional, EventReference, Limit, QueryDirection)
	 */
	enum QueryDirection {
		/**
		 * Events are returned in chronological order (oldest to newest).
		 * This is the default direction for most query operations.
		 */
		FORWARD,

		/**
		 * Events are returned in reverse chronological order (newest to oldest).
		 * Useful for retrieving recent events or working backwards through history.
		 */
		BACKWARD
	}

	/**
	 * Retrieves the most recent bookmark for a specific reader.
	 * <p>
	 * Bookmarks allow readers (such as projections or event processors) to track their
	 * position in the event stream. A reader can store a bookmark after processing events,
	 * then resume from that position later. This is essential for building reliable event
	 * processors that can recover from failures or restarts.
	 *
	 * @param reader the unique identifier of the reader (e.g., projection name, processor ID)
	 * @return an Optional containing the last bookmarked reference, or empty if no bookmark exists
	 * @throws EventStorageException if an error occurs during bookmark retrieval
	 * @see #bookmark(String, EventReference, Tags)
	 * @see EventReference
	 */
	Optional<EventReference> getBookmark ( String reader );

	/**
	 * Records a bookmark for a reader at a specific event reference with associated tags.
	 * <p>
	 * This method stores a reader's current position in the event stream. The bookmark
	 * indicates that the reader has processed all events up to and including the specified
	 * reference. Tags can be used to store additional metadata about the bookmark (e.g.,
	 * processing state, version information).
	 * <p>
	 * After successfully storing a bookmark, implementations must notify all subscribed
	 * listeners via {@link BookmarkPlacedNotification}.
	 * <p>
	 * Typical Usage:
	 * <pre>{@code
	 * // Process events and bookmark progress
	 * Stream<Event> events = eventStream.query(EventQuery.matchAll());
	 * events.forEach(event -> {
	 *     processEvent(event);
	 *     storage.bookmark("my-projection", event.reference(), Tags.of("status", "processed"));
	 * });
	 * }</pre>
	 *
	 * @param reader the unique identifier of the reader
	 * @param eventReference the reference of the last processed event
	 * @param tags additional metadata tags for the bookmark
	 * @throws EventStorageException if an error occurs during bookmark storage
	 * @see #getBookmark(String)
	 * @see BookmarkPlacedNotification
	 * @see EventReference
	 * @see Tags
	 */
	void bookmark ( String reader, EventReference eventReference, Tags tags );


	/**
	 * Notification sent when new events are appended to the event store.
	 * <p>
	 * This notification informs listeners (such as event streams and projections) that new events
	 * are available for processing. Listeners can use this to trigger asynchronous event processing
	 * or to update read models.
	 * <p>
	 * The notification includes the stream where events were appended and a reference indicating
	 * at least up to which point new events exist. Consumers should query for events after their
	 * last known position.
	 *
	 * @param stream the event stream where new events were appended
	 * @param atLeastUntil reference indicating new events exist at least up to this point
	 * @see EventStoreListener#notify(AppendsToEventStoreNotification)
	 * @see #append(AppendCriteria, Optional, List)
	 */
	record AppendsToEventStoreNotification ( EventStreamId stream, EventReference atLeastUntil ) {

		/**
		 * Checks if this notification is relevant for a given event stream criteria.
		 * <p>
		 * This method determines whether an event stream identified by the criteria should
		 * be notified about events appended to the stream in this notification. The check
		 * uses the stream's read compatibility logic.
		 *
		 * @param eventStreamCriteria the event stream criteria to check relevance against
		 * @return true if the notification is relevant for the criteria, false otherwise
		 * @see EventStreamId#canRead(EventStreamId)
		 */
		public boolean isRelevantFor ( EventStreamId eventStreamCriteria ) {
			return eventStreamCriteria.canRead(stream);
		}

	}

	/**
	 * Notification sent when a bookmark is placed by a reader.
	 * <p>
	 * This notification informs listeners that a reader (such as a projection or event processor)
	 * has updated its bookmark position. This can be used for monitoring progress, detecting stalls,
	 * or coordinating between multiple readers.
	 *
	 * @param reader the unique identifier of the reader that placed the bookmark
	 * @param bookmark the event reference where the bookmark was placed
	 * @see EventStoreListener#notify(BookmarkPlacedNotification)
	 * @see #bookmark(String, EventReference, Tags)
	 */
	record BookmarkPlacedNotification ( String reader, EventReference bookmark ) {

	}

	/**
	 * Listener interface for receiving storage-level notifications.
	 * <p>
	 * Implementations of this interface can be registered via {@link #subscribe(EventStoreListener)}
	 * to receive notifications when:
	 * <ul>
	 *   <li>New events are appended to storage</li>
	 *   <li>Bookmarks are placed by readers</li>
	 * </ul>
	 * <p>
	 * <b>Important:</b> All listeners are notified <b>synchronously</b> within the same thread
	 * that triggered the event (e.g., during an append operation). This allows for synchronous
	 * actions to be integrated, but listeners should perform minimal work and delegate to
	 * asynchronous processing as soon as possible to avoid blocking storage operations.
	 *
	 * <h2>Thread Safety:</h2>
	 * Listener implementations must be thread-safe as they may be called concurrently
	 * from multiple threads performing storage operations.
	 *
	 * <h2>Best Practices:</h2>
	 * <pre>{@code
	 * public class AsyncEventProcessor implements EventStoreListener {
	 *     private final ExecutorService executor = Executors.newCachedThreadPool();
	 *
	 *     @Override
	 *     public void notify(AppendsToEventStoreNotification notification) {
	 *         // Minimal work in sync context, then go async
	 *         executor.submit(() -> processNewEvents(notification));
	 *     }
	 *
	 *     @Override
	 *     public void notify(BookmarkPlacedNotification notification) {
	 *         // Handle bookmark updates
	 *         executor.submit(() -> updateMonitoring(notification));
	 *     }
	 * }
	 * }</pre>
	 *
	 * @see #subscribe(EventStoreListener)
	 * @see AppendsToEventStoreNotification
	 * @see BookmarkPlacedNotification
	 */
	interface EventStoreListener {
		/**
		 * Called when new events are appended to the event store.
		 * <p>
		 * This method is invoked synchronously during the append operation.
		 * Implementations should delegate to asynchronous processing to avoid
		 * blocking the append operation.
		 *
		 * @param newEventsInStore notification containing details about the appended events
		 * @see AppendsToEventStoreNotification
		 */
		void notify ( AppendsToEventStoreNotification newEventsInStore );

		/**
		 * Called when a bookmark is placed by a reader.
		 * <p>
		 * This method is invoked synchronously during the bookmark operation.
		 * Implementations should delegate to asynchronous processing to avoid
		 * blocking the bookmark operation.
		 *
		 * @param bookmarkPlaced notification containing details about the placed bookmark
		 * @see BookmarkPlacedNotification
		 */
		void notify ( BookmarkPlacedNotification bookmarkPlaced );
	}

	/**
	 * Represents an event before it is stored in the event storage.
	 * <p>
	 * This record contains all the information needed to persist an event, but without
	 * the storage-assigned metadata (reference and timestamp). When an event is appended
	 * to storage, each {@code EventToStore} is converted to a {@link StoredEvent} with
	 * an assigned {@link EventReference} and timestamp.
	 * <p>
	 * Event data is separated into two categories:
	 * <ul>
	 *   <li><b>Immutable data:</b> Core event information that must never be deleted (GDPR-compliant)</li>
	 *   <li><b>Erasable data:</b> Sensitive information that may need to be erased (e.g., personal data)</li>
	 * </ul>
	 *
	 * @param stream the event stream this event belongs to
	 * @param type the event type identifying the kind of event
	 * @param immutableData serialized event data that must be retained permanently
	 * @param erasableData serialized event data that may be erased for privacy compliance
	 * @param tags key-value pairs for dynamic event retrieval and consistency boundaries
	 * @see StoredEvent
	 * @see #append(AppendCriteria, Optional, List)
	 */
	public record EventToStore ( EventStreamId stream, EventType type, String immutableData, String erasableData, Tags tags ) {

		/**
		 * Converts this event to a stored event by assigning a reference and timestamp.
		 * <p>
		 * This method is typically called by storage implementations during the append operation
		 * to create the final persisted representation of the event.
		 *
		 * @param reference the unique reference assigned to this event
		 * @param timestamp the timestamp when this event was stored
		 * @return a StoredEvent with all metadata assigned
		 * @see StoredEvent
		 */
		public StoredEvent positionAt ( EventReference reference, LocalDateTime timestamp) {
			return new StoredEvent(stream, type, reference, immutableData, erasableData, tags, timestamp);
		}
	}

	/**
	 * Represents a fully persisted event with all storage-assigned metadata.
	 * <p>
	 * This record contains the complete event information including the storage-assigned
	 * {@link EventReference} and timestamp. StoredEvents are returned by query and append
	 * operations, providing the definitive record of events in the event store.
	 * <p>
	 * The reference consists of:
	 * <ul>
	 *   <li><b>Event ID:</b> Unique identifier for the event</li>
	 *   <li><b>Position:</b> Sequential position within the event stream</li>
	 * </ul>
	 * <p>
	 * Event data is separated into immutable and erasable portions to support privacy
	 * regulations like GDPR while maintaining event sourcing integrity.
	 *
	 * @param stream the event stream this event belongs to
	 * @param type the event type identifying the kind of event
	 * @param reference the unique reference (ID and position) of this event
	 * @param immutableData serialized event data that must be retained permanently
	 * @param erasableData serialized event data that may be erased for privacy compliance
	 * @param tags key-value pairs for dynamic event retrieval and consistency boundaries
	 * @param timestamp the moment this event was stored
	 * @see EventToStore
	 * @see EventReference
	 * @see #query(EventQuery, Optional, EventReference, Limit, QueryDirection)
	 */
	public record StoredEvent ( EventStreamId stream, EventType type, EventReference reference, String immutableData, String erasableData, Tags tags, LocalDateTime timestamp ) {

	}

}
