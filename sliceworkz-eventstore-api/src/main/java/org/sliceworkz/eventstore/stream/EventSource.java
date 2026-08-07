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

import org.sliceworkz.eventstore.events.Bookmark;
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
 * <h2>A Returned Stream Is Already In Memory:</h2>
 * Every {@code query} method here returns a {@link Stream}, but none of them is lazy in the sense the
 * type suggests. The storage below has finished reading by the time the stream is handed back: its
 * whole result set has been fetched and is being iterated from a list. Every storage backend shipped
 * with this library works that way, and no caller may assume otherwise.
 * <p>
 * What follows from that:
 * <ul>
 *   <li><b>Short-circuiting terminal operations do not save any work.</b>
 *       {@code query(q).findFirst()}, {@code .limit(10)} and {@code .takeWhile(…)} discard events that
 *       have already been read, deserialized and upcasted. Bound the read with
 *       {@link EventQuery#limit(long)} instead — that is the limit storage is given.</li>
 *   <li><b>An unbounded query holds its whole result in heap.</b> A query with no limit, run against a
 *       storage with no absolute result limit configured, reads every matching event before returning.
 *       On a large stream that is an {@link OutOfMemoryError}, not a slow stream — there is no
 *       back-pressure to arrive at.</li>
 *   <li><b>Nothing needs closing.</b> No database resource is held open behind the returned stream, so
 *       it can be abandoned half-consumed without leaking anything. (Closing an {@code EventSource} is
 *       a separate matter, and concerns subscriptions only — see {@link #close()}.)</li>
 * </ul>
 * <p>
 * So a full replay is a loop, not a single unbounded query. {@link org.sliceworkz.eventstore.projection.Projector}
 * already does this — it reads in batches of
 * {@value org.sliceworkz.eventstore.projection.Projector.Builder#DEFAULT_MAX_EVENTS_PER_QUERY} and
 * carries a cursor between them — and is the right tool for replaying a stream of unknown size. To do
 * it by hand, page with {@link #query(EventQuery, EventReference)} and a limited query, advancing the
 * cursor to the last reference of each page.
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
public interface EventSource<DOMAIN_EVENT_TYPE> extends AutoCloseable {

	/**
	 * Ends every subscription made on this source, releasing the registration it holds with the
	 * underlying storage.
	 * <p>
	 * A source that has been subscribed to is kept alive by the storage for as long as it is
	 * registered — that is what stops notifications from dying silently when the caller no longer
	 * holds the source. The flip side is that nothing else will ever release it, so a subscribed
	 * source that is never closed is retained for the lifetime of the storage. Close the sources you
	 * subscribe to, or close the {@link org.sliceworkz.eventstore.EventStore} they came from, which
	 * closes them all:
	 * <pre>{@code
	 * try ( EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class) ) {
	 *     Projector.from(stream).towards(projection).subscribe().build();
	 *     ...
	 * }   // subscriptions ended, registration released
	 * }</pre>
	 * A source that was never subscribed to holds no registration, so closing it does nothing — and
	 * not closing it costs nothing either. Streams used only to query and append, which is most of
	 * them, need no lifecycle handling at all.
	 * <p>
	 * <b>Closing is not terminal.</b> The source stays usable for querying, appending and bookmarking
	 * afterwards, and subscribing again re-registers it. The only resource a source owns is its
	 * subscriptions, so closing means "stop listening", not "throw this handle away" — poisoning a
	 * cheap handle that 150 call sites obtain freely would buy nothing. This is the one respect in
	 * which it differs from {@link org.sliceworkz.eventstore.EventStore#close()} and
	 * {@link org.sliceworkz.eventstore.spi.EventStorage#close()}, which are terminal because they do
	 * own threads and connections.
	 * <p>
	 * Idempotent. Declared without a checked exception, unlike {@link AutoCloseable#close()}, so that
	 * try-with-resources needs no catch block. The default implementation does nothing.
	 *
	 * @see #subscribe(EventStreamEventuallyConsistentAppendListener)
	 */
	@Override
	default void close ( ) {
		// no subscriptions to release
	}

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
	 * <p>
	 * <strong>Deserialization is lazy.</strong> Storage has finished reading by the time this returns, but
	 * each event's payload is converted as the returned Stream is consumed — so an
	 * {@link org.sliceworkz.eventstore.events.EventDeserializationException} for a stored event this
	 * stream's type mappings cannot read is thrown from the caller's terminal operation, not from here.
	 *
	 * @param query the query criteria specifying which events to retrieve and in which direction
	 * @param cursor optional reference for pagination (after for forward, before for backward), null to start from the beginning/end
	 * @param limit how many stored events to read (overrides the query's own limit); see {@link #query(EventQuery)} for why that is not always the number of events returned
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
	 * @param limit how many stored events to read (overrides the query's own limit); see {@link #query(EventQuery)} for why that is not always the number of events returned
	 * @return a Stream of events matching the query criteria
	 * @see EventQuery
	 * @see Limit
	 */
	default Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query, EventReference cursor, Limit limit ) {
		return query(query, cursor, limit, ref -> {});
	}

	/**
	 * Queries events from the stream starting from a specific cursor reference, respecting the query's
	 * own direction and limit.
	 * <p>
	 * Convenience method for paginated queries. The direction comes from the query
	 * ({@link EventQuery#backwards()}) and so does the limit ({@link EventQuery#limit(long)}), exactly
	 * as for {@link #query(EventQuery)} — a cursor says where to start reading, not how much to read.
	 * To override the query's own limit, pass one explicitly through
	 * {@link #query(EventQuery, EventReference, Limit)}, or {@link Limit#none()} there to read to the
	 * end of the stream.
	 * <p>
	 * This overload used to substitute {@link Limit#none()} for the query's limit, which made the
	 * natural way to page — {@code query(q.limit(500), cursor)} — an unbounded read of everything past
	 * the cursor: fetched from storage and held in heap, since a storage query materialises its whole
	 * result set before returning it. It degraded silently, the caller still receiving its first 500
	 * events, having paid for all of them.
	 *
	 * @param query the query criteria specifying which events to retrieve, in which direction, and how many
	 * @param cursor optional reference for pagination, null to start from the beginning/end
	 * @return a Stream of events matching the query criteria
	 */
	default Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query, EventReference cursor ) {
		return query(query, cursor, query.limit());
	}

	/**
	 * Queries events from the stream, respecting the query's own direction and limit.
	 * <p>
	 * If the query has a backward direction (via {@link EventQuery#backwards()}), events are returned
	 * in reverse chronological order. If the query has a limit (via {@link EventQuery#limit(long)}),
	 * that many events are read from storage. Otherwise, returns all events in forward order.
	 * <p>
	 * <b>A limit counts stored events, which is what upcasting makes visible.</b> It is what the
	 * storage query is given, so it bounds the work and the memory — that is its job. Ordinarily it is
	 * also the number of events you get back, because a stored event yields exactly one. Where an
	 * {@link org.sliceworkz.eventstore.events.Upcast @Upcast} method turns one stored event into
	 * several, or into none, the count returned is not the count read: {@code limit(1)} over a stored
	 * event that upcasts into two returns both — truncating them would hand back half of one stored
	 * event and leave a cursor pointing into its middle. Read it as "read n stored events", not as
	 * "return at most n events".
	 * <p>
	 * <b>A query with no limit reads everything before it returns.</b> The stream handed back is already
	 * in memory (see the class javadoc), so {@code query(EventQuery.matchAll())} on a large stream is an
	 * {@link OutOfMemoryError} rather than something that can be consumed a piece at a time, and
	 * {@code query(q).findFirst()} pays for the whole result set. Give the query a limit and page with
	 * {@link #query(EventQuery, EventReference)}, or use
	 * {@link org.sliceworkz.eventstore.projection.Projector}, which does that for you.
	 *
	 * @param query the query criteria specifying which events to retrieve
	 * @return a Stream of events matching the query criteria, fully realised before it is returned
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
	 * @throws org.sliceworkz.eventstore.events.EventDeserializationException if the stored event cannot be
	 *         read through this stream's type mappings. Unlike {@link #query(EventQuery)} this method is
	 *         eager, so the failure surfaces here — which makes a raw-mode stream
	 *         ({@code eventStore.getEventStream(EventStreamId.anyContext())}) the way to inspect an event
	 *         a typed stream chokes on
	 */
	List<Event<DOMAIN_EVENT_TYPE>> getEventById ( EventId eventId );


	/**
	 * Subscribes to be notified when events are appended to this stream (eventually consistent).
	 * <p>
	 * This subscription provides eventual consistency guarantees - the listener may be notified
	 * slightly after the append has completed, on a notification thread rather than the appending one.
	 * It hears about every append to the stream, whoever made it.
	 * <p>
	 * To react to <em>your own</em> append on the appending thread, there is nothing to subscribe: the
	 * typed events, with their assigned references, are the return value of
	 * {@link EventSink#append(AppendCriteria, java.util.List)}.
	 * <p>
	 * Subscribing registers this source with the underlying storage, which then keeps it alive until
	 * {@link #close()}. Close the source when the subscription is no longer wanted — see {@link #close()}.
	 *
	 * @param listener the listener to receive append notifications
	 * @see #close()
	 */
	void subscribe ( EventStreamEventuallyConsistentAppendListener listener );

	/**
	 * Subscribes to be notified when bookmarks are placed in this stream (eventually consistent).
	 * <p>
	 * This subscription allows monitoring bookmark updates, useful for coordinating
	 * multiple readers or tracking processing progress.
	 * <p>
	 * As with the append overloads, this registers the source with the storage until {@link #close()}.
	 *
	 * @param listener the listener to receive bookmark notifications
	 * @see #close()
	 */
	void subscribe ( EventStreamEventuallyConsistentBookmarkListener listener );


	/**
	 * Places a bookmark at a specific position in the stream for a named reader.
	 * <p>
	 * Bookmarks enable readers to track their position in the event stream and resume
	 * processing from where they left off. Each reader is identified by name and can
	 * maintain only one bookmark per stream. Tags can be attached to bookmarks for
	 * additional metadata (e.g., processing status, reader state).
	 * <p>
	 * The reference must name an event this storage has stored: a bookmark is a position in the
	 * store's log, and a reference the store has never seen — typically one taken from a
	 * <em>different</em> store or prefix — is a caller error, rejected with
	 * {@link org.sliceworkz.eventstore.spi.EventStorageException} rather than stored as a cursor that
	 * would poison the reader. A rejected update leaves any previously placed bookmark untouched.
	 *
	 * @param reader the unique name/identifier of the reader placing the bookmark; must not be null
	 * @param reference the event reference to bookmark (the last processed event); must reference an
	 *        event stored in this storage
	 * @param tags optional tags to attach to the bookmark for metadata
	 * @throws NullPointerException if {@code reader} is null
	 * @throws org.sliceworkz.eventstore.spi.EventStorageException if {@code reference} does not
	 *         reference an event stored in this storage
	 */
	void placeBookmark ( String reader, EventReference reference, Tags tags );

	/**
	 * Retrieves the bookmark for a named reader from this stream.
	 * <p>
	 * Returns the last bookmarked position for the specified reader, allowing
	 * the reader to resume processing from where it left off.
	 *
	 * @param reader the unique name/identifier of the reader; must not be null
	 * @return an Optional containing the bookmarked EventReference if found, empty if no bookmark exists
	 * @throws NullPointerException if {@code reader} is null
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
	 * @param reader the unique name/identifier of the reader whose bookmark should be removed; must not be null
	 * @return an Optional containing the previous bookmarked EventReference if one existed, empty otherwise
	 * @throws NullPointerException if {@code reader} is null
	 */
	Optional<EventReference> removeBookmark ( String reader );

	/**
	 * Retrieves all bookmarks currently held by the underlying event store, including the
	 * metadata (tags and last-update timestamp) supplied when each bookmark was placed.
	 * <p>
	 * Bookmarks are addressed globally by reader name, so the returned list spans the entire
	 * event store — it is not scoped to this stream. Use this for administrative inspection,
	 * monitoring reader progress, or building a UI that lists active readers.
	 * <p>
	 * The returned list is a snapshot taken at call time and is not live; subsequent
	 * {@link #placeBookmark(String, EventReference, Tags)} or {@link #removeBookmark(String)}
	 * calls do not affect a list that was already returned. Order is unspecified.
	 *
	 * @return a snapshot list of all bookmarks; empty if none exist
	 * @see Bookmark
	 * @see #getBookmark(String)
	 */
	List<Bookmark> getBookmarks ( );

}
