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
package org.sliceworkz.eventstore.spi;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.Lease;
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
 *   <li>Release everything they created when {@link #close()} is called — see the contract there</li>
 * </ul>
 *
 * <h2>Lifecycle:</h2>
 * An EventStorage is usable from the moment its builder returns it, and stays usable until it is
 * {@link #close() closed}. Backends without background resources need do nothing: {@code close()}
 * defaults to a no-op. Backends that start threads, hold connections or open files must implement it
 * and honour the contract documented on {@link #close()}.
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
 *                                      EventReference after, Limit limit, QueryDirection direction) {
 *         // 1. Filter events by stream (if specified)
 *         // 2. Apply event type filters from query
 *         // 3. Apply tag filters from query
 *         // 4. Filter events after 'after' reference
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
 *         listeners.addIfAbsent(listener);
 *     }
 *
 *     @Override
 *     public void unsubscribe(EventStoreListener listener) {
 *         listeners.remove(listener);
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
public interface EventStorage extends AutoCloseable {

	/**
	 * Releases everything this storage created, and stops all background activity it started.
	 * <p>
	 * The default implementation does nothing, which is correct for a backend that holds no resources
	 * beyond the objects it was handed. A backend that starts threads, checks out connections or opens
	 * files must override it and honour the following contract, which callers and the
	 * {@link org.sliceworkz.eventstore.EventStore} rely on:
	 * <ol>
	 *   <li><b>Idempotent.</b> The second and later calls do nothing and never throw.</li>
	 *   <li><b>Blocking and bounded.</b> {@code close()} returns only once background activity has
	 *       actually ceased: no thread it started may still be running, no connection it checked out
	 *       may still be held. It must return within an implementation-defined bound; if that bound
	 *       expires it logs and returns rather than throwing or hanging.</li>
	 *   <li><b>Ownership.</b> It releases only what the implementation created. A DataSource,
	 *       connection pool, thread pool or file handle supplied by the caller is left untouched —
	 *       closing that is the caller's business. Conversely, a caller who supplies such a resource
	 *       must close this storage <em>before</em> closing the resource.</li>
	 *   <li><b>Terminal.</b> A closed storage cannot be reopened.</li>
	 *   <li><b>Operations throw afterwards.</b> {@link #query}, {@link #append}, {@link #importEvents},
	 *       {@link #getEventById}, {@link #subscribe}, {@link #bookmark}, {@link #getBookmark},
	 *       {@link #getBookmarks}, {@link #removeBookmark}, {@link #requestLease},
	 *       {@link #releaseLease} and {@link #getLeases} throw
	 *       {@link EventStorageClosedException} once the storage is closed. Continuing to serve reads
	 *       and writes while notifications are dead is not an acceptable alternative: it strands
	 *       projections silently. {@link #name()} keeps working, so logging and diagnostics do not
	 *       break.</li>
	 *   <li><b>No more notifications.</b> No {@link AppendsToEventStoreNotification} or
	 *       {@link BookmarkPlacedNotification} is delivered to any listener after {@code close()}
	 *       returns.</li>
	 * </ol>
	 * <p>
	 * Declared without a checked exception, unlike {@link AutoCloseable#close()}, so that
	 * try-with-resources stays pleasant:
	 * <pre>{@code
	 * try ( EventStorage storage = PostgresEventStorage.newBuilder().build() ) {
	 *     ...
	 * }
	 * }</pre>
	 *
	 * @see org.sliceworkz.eventstore.EventStore#close()
	 */
	@Override
	default void close ( ) {
		// no resources to release
	}

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
	 *   <li><b>after</b> - Starting reference point; events after this reference are returned</li>
	 *   <li><b>limit</b> - Maximum number of events to return (or unlimited)</li>
	 *   <li><b>queryDirection</b> - Direction of traversal (FORWARD or BACKWARD)</li>
	 * </ul>
	 * <p>
	 * Query Direction:
	 * <ul>
	 *   <li><b>FORWARD</b> - Events are returned in chronological order (oldest to newest)</li>
	 *   <li><b>BACKWARD</b> - Events are returned in reverse chronological order (newest to oldest)</li>
	 * </ul>
	 * <p>
	 * The Until Boundary:
	 * <p>
	 * {@link EventQuery#until()} is a matching criterion, not a traversal one. It is the <em>inclusive
	 * upper bound</em> over the total {@code (tx, position, index)} order that
	 * {@link org.sliceworkz.eventstore.query.EventFilter#matches(StoredEvent)} implements, and it selects
	 * the same events in both directions — direction decides only the order they come back in. An
	 * implementation must not read it as "traverse until you reach it", which turns it into a lower bound
	 * when going backward and returns the events on the far side of it. Nor may it compare positions
	 * alone where positions and transactions can be assigned in different orders: the boundary has to be
	 * compared the same way the cursor is.
	 * <p>
	 * A backend may implement the boundary as a superset — dropping only what it can cheaply prove is
	 * past it — because the exact filter is re-applied above this SPI after upcasting. It must never
	 * exclude an event the filter would keep. Note that a limit is applied <em>after</em> the boundary:
	 * spending it on events beyond the boundary that are discarded later returns too few events, or none.
	 * <p>
	 * The Returned Stream:
	 * <p>
	 * The return type is a {@link Stream}, but laziness is not part of this contract, and callers do not
	 * get to assume it. Every in-tree backend reads its whole result set before returning and hands back
	 * a stream over a list; {@link org.sliceworkz.eventstore.stream.EventSource} documents that to
	 * callers as the behaviour to expect. So {@code limit} is what bounds the work and the memory of a
	 * query — a caller that passes {@link Limit#none()} is asking to have everything matching read into
	 * heap, and gets it.
	 * <p>
	 * An implementation <em>may</em> stream its result set lazily, but then it owns two obligations this
	 * SPI does not otherwise impose. First, nothing above it closes the returned stream — neither
	 * {@code EventSource} nor {@link org.sliceworkz.eventstore.projection.Projector} does, and user code
	 * receives a bare {@code Stream} it has never been told to close — so any resource held open behind
	 * it (a connection, a cursor) leaks on every query, including one abandoned half-consumed. Second, a
	 * cursor held open for the caller's whole traversal is a long-running transaction, which on
	 * PostgreSQL holds down {@code pg_snapshot_xmin} and thereby stalls what every reader of that
	 * database can see. Neither is a reason not to do it; both have to be solved deliberately rather
	 * than discovered.
	 *
	 * @param query the event query defining type and tag filters
	 * @param stream optional stream identifier to filter events by stream
	 * @param after the reference point to start querying after (exclusive - events after this reference)
	 * @param limit maximum number of events to read; {@link Limit#none()} reads everything matching
	 * @param queryDirection the direction of query traversal (FORWARD or BACKWARD)
	 * @return a stream of stored events matching the query criteria, which callers must not assume is lazy
	 * @throws EventStorageException if an error occurs during query execution
	 * @see EventQuery
	 * @see QueryDirection
	 * @see StoredEvent
	 */
	Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream, EventReference after, Limit limit, QueryDirection queryDirection );

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
	 * Imports events into storage, preserving their identity, timestamp and idempotency key.
	 * <p>
	 * This is the write path used to move events between storage backends. It is deliberately <em>not</em>
	 * {@link #append(AppendCriteria, Optional, List)}: an import performs no optimistic locking, accepts a
	 * caller-supplied {@link EventId} and timestamp, and may span multiple streams in a single call.
	 * <p>
	 * What is preserved and what is not:
	 * <ul>
	 *   <li><b>Preserved</b> — event id, timestamp, idempotency key, type, tags, immutable and erasable payloads</li>
	 *   <li><b>Reassigned</b> — position and transaction, which are always allocated by this storage. An import
	 *       reproduces the source <em>order</em>, never the source ordering numbers. Events are inserted in
	 *       list order.</li>
	 * </ul>
	 * <p>
	 * Conflict handling depends on {@code mode}. In either mode an idempotency key already used by a different
	 * event on the same stream is fatal — skipping it would silently discard an event the target has never seen.
	 * <p>
	 * <b>Atomicity:</b> a single call is all-or-nothing. A caller importing more events than fit in one call
	 * gets atomicity per call only; a failure part-way through a sequence of calls leaves the earlier calls
	 * committed. {@link ImportMode#SKIP_EXISTING_ID} is the supported way to resume such an import.
	 * <p>
	 * <b>Concurrency:</b> implementations check the target and insert without holding a lock across both steps.
	 * Two imports running concurrently against one storage can produce spurious conflicts; run one at a time.
	 * <p>
	 * Implementations must reject the batch with {@link IllegalArgumentException} if it contains two events
	 * sharing an {@link EventId}, and with {@link EventStorageException} if a payload is not valid JSON.
	 * Listeners are notified of imported events exactly as they are for appended ones.
	 *
	 * @param events the events to import, in the order they should be inserted (must not be null)
	 * @param mode how to treat an event whose id already exists in this storage (must not be null)
	 * @return the imported events with their preserved ids and newly assigned references; under
	 *         {@link ImportMode#SKIP_EXISTING_ID} this excludes skipped events, so the caller can derive
	 *         what was skipped by difference
	 * @throws EventImportConflictException if the import conflicts with what the target already holds
	 * @throws EventStorageException if an error occurs during the import
	 * @throws UnsupportedOperationException if this storage implementation does not support importing
	 * @see EventToImport
	 * @see ImportMode
	 */
	default List<StoredEvent> importEvents ( List<EventToImport> events, ImportMode mode ) {
		throw new UnsupportedOperationException("%s does not support importing events".formatted(name()));
	}

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
	 * <p>
	 * <b>The registration is a strong reference, and it is permanent until
	 * {@link #unsubscribe(EventStoreListener)} is called.</b> Implementations must not hold listeners
	 * weakly: a listener that quietly disappears when the caller stops referencing it turns "my
	 * projection stopped updating" into a garbage-collection-timing bug with no error and no log. Who
	 * registered a listener is responsible for unregistering it — which for the streams this library
	 * hands out means {@code EventStream.close()}, or closing the store they came from.
	 * <p>
	 * Registering the same listener twice must be harmless and must not double the notifications it
	 * receives, so that a caller need not track whether it has already subscribed.
	 *
	 * @param listener the listener to register for storage notifications
	 * @see #unsubscribe(EventStoreListener)
	 * @see EventStoreListener
	 * @see AppendsToEventStoreNotification
	 * @see BookmarkPlacedNotification
	 */
	void subscribe ( EventStoreListener listener );

	/**
	 * Unregisters a listener previously passed to {@link #subscribe(EventStoreListener)}, so that it
	 * receives no further notifications and this storage stops referencing it.
	 * <p>
	 * Idempotent and forgiving: unregistering a listener that was never registered, or unregistering
	 * one twice, does nothing and does not throw. Listeners are matched by identity unless the listener
	 * type defines equality.
	 * <p>
	 * A notification already in flight on another thread may still reach the listener after this
	 * returns; the guarantee is that no <em>new</em> notification will be dispatched to it.
	 * <p>
	 * The default implementation does nothing, so that an {@link EventStorage} written before this
	 * method existed still compiles and runs. Such a backend leaks every listener ever registered with
	 * it, which the compliance scenarios in {@code org.sliceworkz.eventstore.testing.tck} detect — a
	 * backend that keeps a listener list must override this.
	 *
	 * @param listener the listener to unregister; ignored if null or not registered
	 * @see #subscribe(EventStoreListener)
	 */
	default void unsubscribe ( EventStoreListener listener ) {
		// backends predating this method keep their listeners forever; the TCK reports it
	}

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
	 * Controls how {@link #importEvents(List, ImportMode)} treats an event whose identifier already
	 * exists in the target storage.
	 * <p>
	 * Neither mode affects idempotency key conflicts: a key already used by a different event on the same
	 * stream is always fatal.
	 *
	 * @see #importEvents(List, ImportMode)
	 */
	enum ImportMode {

		/**
		 * Abort the batch if any event's identifier already exists in the target.
		 * <p>
		 * The strict default. Appropriate when the target is known to be free of the events being imported,
		 * and the safer choice when an unexpected overlap should stop the operation rather than be absorbed.
		 * Note that a conflict rolls back only the batch that hit it: batches already committed remain.
		 */
		FAIL_ON_EXISTING_ID,

		/**
		 * Skip any event whose identifier already exists in the target, and import the rest.
		 * <p>
		 * The resume mode: re-running an interrupted import passes over what already landed and continues
		 * with what did not. Matching is on identifier alone — the payload already in the target is neither
		 * read nor compared, so this mode assumes an id identifies the same event. It offers no protection
		 * against a target whose events were altered after being imported, and it is meaningless if the
		 * import mints new identifiers, since nothing stable remains to match on.
		 */
		SKIP_EXISTING_ID
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
	 * Implementations must reject a reference that does not name an event stored in this storage,
	 * throwing {@link EventStorageException} and leaving any previously stored bookmark for the
	 * reader untouched. The check is on the event id alone — the position and transaction carried by
	 * the reference are not cross-validated. The TCK's {@code BookmarksTest} pins this contract.
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
	 * Removes a previously placed bookmark for a specific reader.
	 * <p>
	 * This method permanently deletes the stored position for the given reader, effectively
	 * resetting its progress tracking. After removal, subsequent calls to {@link #getBookmark(String)}
	 * for this reader will return {@code Optional.empty()}.
	 * <p>
	 * Common use cases include:
	 * <ul>
	 *   <li>Resetting a projection to reprocess all events from the beginning</li>
	 *   <li>Cleaning up bookmarks for discontinued readers or projections</li>
	 *   <li>Handling errors that require complete reprocessing</li>
	 *   <li>Removing bookmarks as part of administrative maintenance</li>
	 * </ul>
	 * <p>
	 * If no bookmark exists for the specified reader, this method should complete successfully
	 * without error (idempotent behavior).
	 * <p>
	 * Typical Usage:
	 * <pre>{@code
	 * // Reset a projection to start from the beginning
	 * storage.removeBookmark("customer-summary-projection");
	 *
	 * // Next query will start from the beginning
	 * Optional<EventReference> bookmark = storage.getBookmark("customer-summary-projection");
	 * // bookmark.isEmpty() == true
	 * }</pre>
	 *
	 * @param reader the unique identifier of the reader whose bookmark should be removed
	 * @throws EventStorageException if an error occurs during bookmark removal
	 * @see #bookmark(String, EventReference, Tags)
	 * @see #getBookmark(String)
	 */
	void removeBookmark ( String reader );

	/**
	 * Retrieves all bookmarks currently held by this storage, including the metadata
	 * (tags and last-update timestamp) supplied when each bookmark was placed.
	 * <p>
	 * Bookmarks are addressed globally by reader name, so the returned list spans the entire
	 * storage — it is not scoped to any particular event stream. Use this for administrative
	 * inspection, monitoring reader progress, or building a UI that lists active readers.
	 * <p>
	 * The returned list is a snapshot taken at call time and is not live; subsequent
	 * {@link #bookmark(String, EventReference, Tags)} or {@link #removeBookmark(String)} calls do
	 * not affect a list that was already returned. Order is unspecified.
	 *
	 * @return a snapshot list of all bookmarks; empty if none exist
	 * @throws EventStorageException if an error occurs while reading bookmarks
	 * @see Bookmark
	 * @see #getBookmark(String)
	 */
	List<Bookmark> getBookmarks ( );

	/**
	 * Requests — or renews — a named lease for the given owner, registering the owner as a live
	 * contender either way. This is the single operation behind leader election: every contender
	 * calls it periodically (well within {@link LeaseRequest#ttl()}), and the returned
	 * {@link LeaseStatus} tells it what it is right now.
	 * <p>
	 * Semantics, which every implementation must honour identically:
	 * <ul>
	 *   <li><b>Acquisition.</b> If the lease does not exist, or exists but has expired — its last
	 *       heartbeat is older than the time-to-live it was requested with, measured on the
	 *       <b>storage's clock</b> — the caller becomes the owner. The fencing token is one higher
	 *       than the previous owner's (starting at 1), and the response is {@link LeaseStatus#LEADER}.</li>
	 *   <li><b>Renewal.</b> If the caller already owns the lease, its heartbeat and priority are
	 *       refreshed and the fencing token is unchanged. The response is {@link LeaseStatus#LEADER} —
	 *       unless a <em>live</em> contender with a <b>strictly higher</b> priority exists, in which
	 *       case it is {@link LeaseStatus#LEADER_STEP_DOWN_REQUESTED}: the caller still holds the
	 *       lease and remains the only legitimate processor, but is asked to finish its current work
	 *       and {@link #releaseLease(String, String) release}. The storage never revokes a live lease
	 *       itself; a leader that cannot step down safely may keep renewing. Contenders with an equal
	 *       or lower priority never trigger a step-down request.</li>
	 *   <li><b>Standby.</b> If another owner holds a live lease, the caller is recorded as a live
	 *       contender at its requested priority and gets {@link LeaseStatus#STANDBY}. A contender is
	 *       considered live while its own last request is younger than the time-to-live it passed.</li>
	 * </ul>
	 * <p>
	 * The single-writer guarantee this gives a caller: between two calls that both returned
	 * {@code LEADER} (or {@code LEADER_STEP_DOWN_REQUESTED}) no other owner has held the lease —
	 * provided the caller stops acting as leader the moment it can no longer <em>confirm</em> a
	 * renewal within the ttl it requested. A caller whose renewal call fails or hangs must demote
	 * itself on its own clock rather than assume it is still the leader; expiry on the storage clock
	 * plus self-demotion on the caller clock is what keeps two leaders from overlapping (modulo a
	 * caller paused beyond its ttl, which no lease can prevent — the fencing token exists so such a
	 * zombie's writes can be recognised).
	 * <p>
	 * Expiry never depends on any contender's clock, so contenders' clocks need not agree with each
	 * other or with the storage. Callers only measure durations (their own polling interval and the
	 * time since their last confirmed renewal), never compare instants across machines.
	 *
	 * @param request the lease name, owner identity, priority and time-to-live (must not be null)
	 * @return the caller's resulting status, never null
	 * @throws EventStorageException if an error occurs while reading or writing the lease
	 * @throws UnsupportedOperationException if this storage implementation does not support leases
	 * @see #releaseLease(String, String)
	 * @see #getLeases()
	 */
	default LeaseResponse requestLease ( LeaseRequest request ) {
		throw new UnsupportedOperationException("%s does not support leases".formatted(name()));
	}

	/**
	 * Releases a lease held by the given owner, so the next {@link #requestLease(LeaseRequest)} by
	 * any contender acquires it immediately instead of waiting out the time-to-live. The owner's
	 * contender registration is withdrawn as well.
	 * <p>
	 * A release makes the lease immediately expired; it does not erase it. The record — most
	 * importantly its fencing token — survives, so the next acquisition still increments over it:
	 * fencing tokens stay strictly monotonic per lease across releases, never resetting.
	 * <p>
	 * Idempotent and forgiving: releasing a lease the owner does not hold — because it expired and
	 * was taken over, or was never acquired — does nothing and does not throw. A release never
	 * touches a lease currently held by a <em>different</em> owner.
	 *
	 * @param leaseName the name of the lease to release
	 * @param owner the owner releasing it; only a lease held by exactly this owner is released
	 * @throws EventStorageException if an error occurs while releasing the lease
	 * @throws UnsupportedOperationException if this storage implementation does not support leases
	 * @see #requestLease(LeaseRequest)
	 */
	default void releaseLease ( String leaseName, String owner ) {
		throw new UnsupportedOperationException("%s does not support leases".formatted(name()));
	}

	/**
	 * Retrieves all leases currently recorded by this storage, including expired ones that have not
	 * been taken over yet (an expired lease keeps its last owner and heartbeat until someone else
	 * acquires it — liveness is judged against {@link Lease#heartbeatAt()}, not against presence in
	 * this list).
	 * <p>
	 * Like bookmarks, leases are addressed globally by name, so the returned list spans the entire
	 * storage. The list is a snapshot taken at call time; order is unspecified.
	 *
	 * @return a snapshot list of all leases; empty if none exist
	 * @throws EventStorageException if an error occurs while reading leases
	 * @throws UnsupportedOperationException if this storage implementation does not support leases
	 * @see Lease
	 */
	default List<Lease> getLeases ( ) {
		throw new UnsupportedOperationException("%s does not support leases".formatted(name()));
	}

	/**
	 * A request to acquire or renew a lease — the input to {@link #requestLease(LeaseRequest)}.
	 * <p>
	 * The time-to-live is the window within which the owner must renew: a lease whose last
	 * heartbeat is older than this, on the storage's clock, is expired and acquirable by anyone.
	 * The caller must therefore call {@link #requestLease(LeaseRequest)} well within every ttl —
	 * a third of it is a sensible interval, leaving two failed attempts before leadership lapses.
	 *
	 * @param leaseName the globally unique name of the lease (must not be null or blank)
	 * @param owner the identity of the requester; must be stable for the requester's lifetime and
	 *              unique among contenders (must not be null or blank)
	 * @param priority the requester's priority; a live contender with a strictly higher priority
	 *                 makes the storage ask the current owner to step down
	 * @param ttl the time-to-live of the granted or renewed lease (must be positive)
	 */
	record LeaseRequest ( String leaseName, String owner, long priority, Duration ttl ) {

		public LeaseRequest {
			Objects.requireNonNull(leaseName, "leaseName must not be null");
			Objects.requireNonNull(owner, "owner must not be null");
			Objects.requireNonNull(ttl, "ttl must not be null");
			if ( leaseName.isBlank() ) {
				throw new IllegalArgumentException("leaseName must not be blank");
			}
			if ( owner.isBlank() ) {
				throw new IllegalArgumentException("owner must not be blank");
			}
			if ( ttl.isNegative() || ttl.isZero() ) {
				throw new IllegalArgumentException("ttl must be positive, but was " + ttl);
			}
		}

	}

	/**
	 * The outcome of a {@link #requestLease(LeaseRequest)} call.
	 *
	 * @param status what the caller is after this call — see {@link LeaseStatus}
	 * @param fencingToken the lease's current fencing token: the caller's own token when it is the
	 *                     leader, the current holder's when it is standing by. Strictly increases on
	 *                     every ownership change, stable across renewals
	 * @param currentOwner the owner holding the lease after this call (the caller itself when leader)
	 */
	record LeaseResponse ( LeaseStatus status, long fencingToken, String currentOwner ) {

		public LeaseResponse {
			Objects.requireNonNull(status, "status must not be null");
			Objects.requireNonNull(currentOwner, "currentOwner must not be null");
		}

	}

	/**
	 * What a contender is, as answered by {@link #requestLease(LeaseRequest)}.
	 *
	 * @see LeaseResponse
	 */
	enum LeaseStatus {

		/**
		 * The caller holds the lease: it is the single legitimate processor until the ttl it
		 * requested runs out, and renewing within the ttl extends that indefinitely.
		 */
		LEADER,

		/**
		 * The caller still holds the lease — everything {@link #LEADER} means still applies — but a
		 * live contender with a strictly higher priority is waiting. The caller should finish its
		 * current unit of work and {@link #releaseLease(String, String) release}, so the
		 * higher-priority contender acquires on its next request instead of waiting out the ttl.
		 * The storage never enforces this; a caller that keeps renewing keeps the lease.
		 */
		LEADER_STEP_DOWN_REQUESTED,

		/**
		 * Another owner holds a live lease. The caller has been recorded as a live contender at its
		 * requested priority and should simply keep requesting: it acquires when the lease expires,
		 * is released, or — if its priority is strictly higher — when the owner honours the
		 * step-down request.
		 */
		STANDBY

	}

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
	 *   <li><b>Erasable data:</b> the second document of the superseded immutable/erasable split; written only
 *       by versions before personal data moved into the payload as encrypted
 *       {@link org.sliceworkz.eventstore.shredding.Shreddable} values, and read for those events still</li>
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
	public record EventToStore ( EventStreamId stream, EventType type, String immutableData, String erasableData, Tags tags, String idempotencyKey ) {

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
			return new StoredEvent(stream, type, reference, immutableData, erasableData, tags, timestamp, idempotencyKey);
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
	 * @param timestamp the moment this event was stored, always in UTC
	 * @param idempotencyKey the idempotency key the event was appended with, or {@code null} if none;
	 *                       scoped to the event stream (context and purpose)
	 * @see EventToStore
	 * @see EventReference
	 * @see #query(EventQuery, Optional, EventReference, Limit, QueryDirection)
	 */
	public record StoredEvent ( EventStreamId stream, EventType type, EventReference reference, String immutableData, String erasableData, Tags tags, LocalDateTime timestamp, String idempotencyKey ) {

		/**
		 * Convenience constructor for stored events without an idempotency key.
		 * <p>
		 * Delegates to the canonical constructor with a {@code null} idempotency key. Retained so that
		 * callers that never dealt with idempotency keys keep compiling unchanged.
		 *
		 * @param stream the event stream this event belongs to
		 * @param type the event type identifying the kind of event
		 * @param reference the unique reference (ID and position) of this event
		 * @param immutableData serialized event data that must be retained permanently
		 * @param erasableData serialized event data that may be erased for privacy compliance
		 * @param tags key-value pairs for dynamic event retrieval and consistency boundaries
		 * @param timestamp the moment this event was stored, always in UTC
		 */
		public StoredEvent ( EventStreamId stream, EventType type, EventReference reference, String immutableData, String erasableData, Tags tags, LocalDateTime timestamp ) {
			this(stream, type, reference, immutableData, erasableData, tags, timestamp, null);
		}

	}

}
