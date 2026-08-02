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
package org.sliceworkz.eventstore.infra.inmem;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventImportConflictException;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thread-safe in-memory implementation of the {@link EventStorage} interface.
 * <p>
 * This implementation stores all events in a simple in-memory list, providing a lightweight
 * and fast storage solution suitable for development, testing, and prototyping. All data is
 * lost when the application stops, making this unsuitable for production use.
 * <p>
 * Key characteristics:
 * <ul>
 *   <li>Thread-safe: All critical operations are synchronized to ensure consistency</li>
 *   <li>Non-persistent: Events exist only in memory and are lost on restart</li>
 *   <li>Fast: Direct memory access without I/O overhead</li>
 *   <li>Full feature support: Implements all EventStorage capabilities including subscriptions and bookmarks</li>
 *   <li>JSON validation: Validates that events can be serialized and deserialized using Jackson</li>
 * </ul>
 * <p>
 * This implementation uses a {@link LinkedList} for the event log to provide efficient append operations,
 * and a {@link HashMap} for bookmark storage. All queries are performed by streaming over the event log
 * and applying filters.
 *
 * <h2>Optimistic Locking:</h2>
 * Optimistic locking is implemented by synchronizing both the query and append operations within the
 * {@link #append(AppendCriteria, Optional, List)} method. This ensures that checking for new events
 * and appending are atomic, preventing race conditions in concurrent scenarios.
 *
 * <h2>Event Validation:</h2>
 * Before appending, all events are validated by serializing and deserializing them to JSON using Jackson.
 * This ensures that events can be properly persisted and retrieved, catching serialization issues early.
 * If an event cannot be serialized/deserialized, a {@link RuntimeException} is thrown.
 *
 * <h2>Query Limits:</h2>
 * The implementation supports an optional absolute limit on query results to protect against unbounded
 * queries. If configured via the builder's {@link InMemoryEventStorage.Builder#resultLimit(int)} method,
 * queries returning more than this limit will throw an {@link EventStorageException}.
 *
 * <h2>Example Usage:</h2>
 * This class is typically not instantiated directly. Instead, use {@link InMemoryEventStorage.Builder}:
 * <pre>{@code
 * EventStorage storage = InMemoryEventStorage.newBuilder()
 *     .resultLimit(1000)
 *     .build();
 * }</pre>
 *
 * @see EventStorage
 * @see InMemoryEventStorage
 * @see InMemoryEventStorage.Builder
 */
public class InMemoryEventStorageImpl implements EventStorage {

	private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryEventStorageImpl.class);

	private String name;
	private List<StoredEvent> eventlog = new CopyOnWriteArrayList<>();
	// Lookup index by event id, kept in step with the event log. Backs getEventById in constant time
	// rather than a linear scan, which matters for imports resolving one id per event.
	private Map<EventId,StoredEvent> eventsById = new HashMap<>();
	// Idempotency dedup is scoped to the logical event stream (context + purpose), matching the
	// Postgres backend's per-stream partial unique index, so the same key on two different streams
	// does not collide and behaviour does not depend on how storage instances are wired at runtime.
	private Set<IdempotencyScope> idempotencyKeys = new HashSet<>();
	// Strong references, released only by unsubscribe(). Held weakly, a listener whose registrant stopped
	// referencing it would vanish at the next GC and take its notifications with it, silently -- see
	// EventStorage.subscribe(). CopyOnWriteArrayList because notification is far more frequent than
	// (un)subscription, and notifying must not block appends.
	private final CopyOnWriteArrayList<EventStoreListener> listeners = new CopyOnWriteArrayList<>();
	private Map<String,Bookmark> bookmarks = new HashMap<>();
	private JsonMapper jsonMapper;
	private Limit absoluteLimit;
	private long txCounter;
	// This backend holds no threads, connections or file handles, so close() has nothing to release.
	// It still marks itself closed, so that the post-close behaviour required by EventStorage.close()
	// is the same here as on a backend that does — code that outlives its storage fails the same way
	// against every backend, in tests as in production.
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Constructs a new in-memory event storage instance with the specified name and absolute query limit.
	 * <p>
	 * This constructor is package-private and should not be called directly. Instead, use the
	 * {@link InMemoryEventStorage.Builder} to create instances.
	 * <p>
	 * The constructor initializes:
	 * <ul>
	 *   <li>An empty event log backed by a {@link CopyOnWriteArrayList}</li>
	 *   <li>An empty list of event listeners</li>
	 *   <li>An empty bookmark map</li>
	 *   <li>A Jackson {@link JsonMapper} with auto-discovered modules for event serialization validation</li>
	 * </ul>
	 *
	 * @param name the unique name for this storage instance; must not be null or blank
	 * @param absoluteLimit the absolute limit on query results, or {@link Limit#none()} for no limit
	 * @throws IllegalArgumentException if name is null or blank
	 * @see InMemoryEventStorage.Builder#build()
	 */
	public InMemoryEventStorageImpl ( String name, Limit absoluteLimit ) {
		this(name, absoluteLimit, List.of(), Map.of());
	}

	public InMemoryEventStorageImpl ( String name, Limit absoluteLimit, List<StoredEvent> initialEvents, Map<String, Bookmark> initialBookmarks ) {
		if ( name == null || "".equals(name.strip())) {
			throw new IllegalArgumentException("name must not be empty");
		}
		this.name = name;
		// Jackson 3.x: immutable mapper built via builder; modules (incl. java.time) auto-register.
		// FAIL_ON_UNKNOWN_PROPERTIES is re-enabled (Jackson 2.x default) so the round-trip
		// validation in verifyPersistableJson keeps rejecting non-round-trippable events.
		this.jsonMapper = JsonMapper.builder()
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.build();
		this.absoluteLimit = absoluteLimit;
		this.eventlog.addAll(initialEvents);
		this.bookmarks.putAll(initialBookmarks);
		this.txCounter = initialEvents.stream()
				.mapToLong(e -> e.reference().tx())
				.max()
				.orElse(0);

		// Seed the derived state from the preloaded events. Without this the idempotency keys of
		// preloaded events are unknown to the store, so a reloaded store (see the filesystem-backed
		// storage, which restores its whole event log this way) would append a duplicate for a key it
		// had already seen instead of deduplicating it.
		for ( StoredEvent event : initialEvents ) {
			EventId id = event.reference().id();
			if ( eventsById.putIfAbsent(id, event) != null ) {
				throw new IllegalArgumentException("initial events contain more than one event with id %s".formatted(id.value()));
			}
			if ( event.idempotencyKey() != null ) {
				idempotencyKeys.add(new IdempotencyScope(event.stream(), event.idempotencyKey()));
			}
		}
	}

	/**
	 * Queries the event store for events matching the specified criteria, starting after a given reference.
	 * <p>
	 * This method is synchronized to ensure thread-safe access to the event log, which is critical for
	 * optimistic locking scenarios where the query result is used to determine whether to allow an append.
	 * <p>
	 * The query processes events in the specified direction (forward or backward) and applies:
	 * <ul>
	 *   <li>Stream filtering (if a stream ID is provided)</li>
	 *   <li>Event query matching (type and tag filters)</li>
	 *   <li>Reference-based positioning (starting after the specified reference)</li>
	 *   <li>Optional "until" reference from the query</li>
	 *   <li>Result limits (both soft and absolute)</li>
	 * </ul>
	 *
	 * @param query the event query specifying which events to retrieve
	 * @param stream optional stream ID to filter events; if empty, events from all streams are considered
	 * @param after the reference to start after; events after this position are included
	 * @param limit soft limit on the number of results; may be overridden by absolute limit
	 * @param direction the direction to traverse the event log (FORWARD or BACKWARD)
	 * @return a Stream of StoredEvent instances matching the criteria
	 * @throws EventStorageException if the result exceeds the configured absolute limit
	 * @see EventQuery
	 * @see EventReference
	 * @see QueryDirection
	 */
	@Override
	public synchronized Stream<StoredEvent> query(EventQuery query, Optional<EventStreamId> stream, EventReference after, Limit limit, QueryDirection direction ) {
		checkNotClosed();
		Stream<StoredEvent> on;

		switch ( direction ) {
			case BACKWARD:
				on = eventlog.reversed().stream();
				break;
			case FORWARD:
			default:
				on = eventlog.stream();
		}

		if ( after != null ) {
			if ( direction == QueryDirection.FORWARD ) {
				on = on.skip(after.position());
			} else {
				on = on.skip(eventlog.size()-after.position()+1);
			}
		}
		
		// If we only need to read until a certain event, we can cut the traversal short. "until" is a
		// matching criterion, not a traversal one: it is an inclusive upper bound over the total
		// (tx, position, index) order and means the same thing in both directions. So the events beyond
		// it are a suffix of a forward traversal and a prefix of a backward one -- hence takeWhile vs
		// dropWhile. This is purely a short-circuit; the boundary itself is enforced by query::matches
		// below, which is where the exact comparison lives.
		if ( query.until() != null ) {
			EventReference until = query.until();
			if ( direction == QueryDirection.BACKWARD ) {
				on = on.dropWhile(e->e.reference().happenedAfter(until));
			} else {
				on = on.takeWhile(e->!e.reference().happenedAfter(until));
			}
		}
		
		Stream<StoredEvent> result = on;

		if ( stream.isPresent() ) {
			result = result.filter(e->stream.get().canRead(e.stream()));
		} else {
			// no stream specified, considering all streams present in the store
		}
		
		result = result.filter(query::matches);

		Limit effectiveLimit = effectiveLimit(limit);
		
		if ( effectiveLimit != null && effectiveLimit.isSet() ) {
			result = result.limit(effectiveLimit.value());
		}

		var returnValue = new ArrayList<>(result.toList()); // to list and back to avoid ConcurrentUpdateExceptions when writing next event in log (?)
		
		if ( absoluteLimit != null && absoluteLimit.isSet() && returnValue.size() > absoluteLimit.value() ) {
			throw new EventStorageException("query returned more results than the configured absolute limit of %d".formatted(absoluteLimit.value()));
		}
		
		return returnValue.stream();
	}
	
	/*
	 *  Synchronized method, to allow re-querying and storing in one shot (required for optimistic locking)
	 */
	@Override
	public synchronized List<StoredEvent> append(AppendCriteria appendCriteria, Optional<EventStreamId> streamId, List<EventToStore> events) {
		checkNotClosed();
		
		verifyPersistableJson(events);
		
		List<StoredEvent> result = Collections.emptyList();
		
		// if we should just append and not check
		// (an empty expectedLastEventReference is NOT this case: it means "I decided on an empty stream",
		//  which still has to be verified — see the else branch, which queries from the start of the stream)
		if ( appendCriteria.isNone() ) {
			result = addAndNotifyListeners(events);
			
		// otherwise, we'll need to be aware of any optimistic locking issues
		} else {
			
			// we query the stream with the event filter from the last event known as our reference
			// we only need to fetch max 1 event to prove a locking issue
			EventQuery lockingQuery = new EventQuery(appendCriteria.eventFilter(), EventQuery.Direction.FORWARD, Limit.none());
			Stream<StoredEvent> newEventStream = query(lockingQuery, streamId, appendCriteria.expectedLastEventReference().orElse(null), Limit.to(1), QueryDirection.FORWARD);

			List<StoredEvent> newEvents = newEventStream.toList();

			// if there are no new events in the stream ...
			if ( newEvents.isEmpty() ) {

				// we can safely append to the event log
				result = addAndNotifyListeners(events);

			} else {
				// new events means an optimistic lock !
				throw new OptimisticLockingException(appendCriteria.eventFilter(), appendCriteria.expectedLastEventReference());
			}				
		}
		
		return result;
	}
	
	private void verifyPersistableJson ( List<EventToStore> newEvents ) {
		try {
			for ( EventToStore e: newEvents ) {
				Class<?> clz = e.immutableData().getClass();
				String s = jsonMapper.writeValueAsString(e.immutableData());
				jsonMapper.readValue(s, clz);

				if ( e.erasableData() != null ) {
					clz = e.erasableData().getClass();
					s = jsonMapper.writeValueAsString(e.erasableData());
					jsonMapper.readValue(s, clz);
				}
			}
		} catch (DatabindException e) {
			throw new RuntimeException("json mapping roundtrip test failed", e);
		} catch (JacksonException e) {
			throw new RuntimeException("json mapping roundtrip test failed", e);
		}
	}
	
	private List<StoredEvent> addAndNotifyListeners ( List<EventToStore> events ) {
		long tx = ++txCounter;
		var addedEvents = events.stream().map(e -> addEventToEventLog(e, tx)).filter(e->e!=null).toList();

		notifyListenersAbout(addedEvents);

		return addedEvents;
	}

	private void notifyListenersAbout ( List<StoredEvent> storedEvents ) {
		// notify each Listener about the writes, but if multiple Events landed in one stream, only notify about the last one
		storedEvents.stream()
			    .collect(Collectors.toMap(
			        StoredEvent::stream,
			        event -> new AppendsToEventStoreNotification(event.stream(), event.reference()),
			        (existing, replacement) -> replacement // in sequence, only useful to notify about the last one
			    ))
			    .values()
			    .forEach(notification->listeners.forEach(listener->notifyQuietly(listener, notification)));
	}
	
	private StoredEvent addEventToEventLog ( EventToStore event, long tx ) {

		if ( event.idempotencyKey() != null ) {
			IdempotencyScope scope = new IdempotencyScope(event.stream(), event.idempotencyKey());
			if ( idempotencyKeys.contains(scope)) {
				return null;
			}
			idempotencyKeys.add(scope);
		}

		long position = eventlog.size() + 1;
		EventReference reference = EventReference.create(position, tx);
		StoredEvent storedEvent = event.positionAt(reference, LocalDateTime.now(ZoneOffset.UTC));
		eventlog.add(storedEvent);
		eventsById.put(reference.id(), storedEvent);
		return storedEvent;
	}

	/*
	 *  Synchronized method, so the conflict check and the insertion are one atomic step, and so a rejected
	 *  batch leaves the event log untouched.
	 */
	@Override
	public synchronized List<StoredEvent> importEvents ( List<EventToImport> events, ImportMode mode ) {
		checkNotClosed();
		if ( events == null ) {
			throw new IllegalArgumentException("events to import must not be null");
		}
		if ( mode == null ) {
			throw new IllegalArgumentException("import mode must not be null");
		}
		if ( events.isEmpty() ) {
			return Collections.emptyList();
		}

		// Validate the whole batch before touching any state, so an import either lands completely or not at all
		Set<EventId> idsInBatch = new HashSet<>();
		Set<IdempotencyScope> keysInBatch = new HashSet<>();
		List<EventToImport> toInsert = new ArrayList<>(events.size());

		for ( EventToImport event : events ) {

			if ( !idsInBatch.add(event.id()) ) {
				throw new IllegalArgumentException("batch to import holds more than one event with id %s".formatted(event.id().value()));
			}

			verifyImportableJson(event);

			if ( eventsById.containsKey(event.id()) ) {
				if ( mode == ImportMode.SKIP_EXISTING_ID ) {
					continue; // already present: skip it, and with it whatever idempotency key it carries
				}
				throw EventImportConflictException.duplicateEventId(event.id(), null);
			}

			if ( event.idempotencyKey() != null ) {
				IdempotencyScope scope = new IdempotencyScope(event.stream(), event.idempotencyKey());
				if ( idempotencyKeys.contains(scope) || !keysInBatch.add(scope) ) {
					throw EventImportConflictException.duplicateIdempotencyKey(event.stream(), event.idempotencyKey(), null);
				}
			}

			toInsert.add(event);
		}

		if ( toInsert.isEmpty() ) {
			return Collections.emptyList();
		}

		// One transaction per call, mirroring how a batch of appended events shares a transaction
		long tx = ++txCounter;
		List<StoredEvent> imported = new ArrayList<>(toInsert.size());
		for ( EventToImport event : toInsert ) {
			// position and tx are assigned here; the id and timestamp travel with the imported event
			StoredEvent storedEvent = event.positionAt(eventlog.size() + 1, tx);
			eventlog.add(storedEvent);
			eventsById.put(storedEvent.reference().id(), storedEvent);
			if ( event.idempotencyKey() != null ) {
				idempotencyKeys.add(new IdempotencyScope(event.stream(), event.idempotencyKey()));
			}
			imported.add(storedEvent);
		}

		notifyListenersAbout(imported);

		return imported;
	}

	/**
	 * Rejects payloads the Postgres backend would refuse on its {@code ::jsonb} cast, so both backends
	 * accept exactly the same imports.
	 */
	private void verifyImportableJson ( EventToImport event ) {
		try {
			if ( jsonMapper.readTree(event.immutableData()).isMissingNode() ) {
				throw new EventStorageException("event %s to import carries an empty immutable payload".formatted(event.id().value()));
			}
			if ( event.erasableData() != null && jsonMapper.readTree(event.erasableData()).isMissingNode() ) {
				throw new EventStorageException("event %s to import carries an empty erasable payload".formatted(event.id().value()));
			}
		} catch (JacksonException e) {
			throw new EventStorageException("event %s to import does not carry valid JSON".formatted(event.id().value()), e);
		}
	}

	@Override
	public synchronized Optional<StoredEvent> getEventById(EventId eventId) {
		checkNotClosed();
		return Optional.ofNullable(eventsById.get(eventId));
	}

	@Override
	public void subscribe(EventStoreListener listener) {
		checkNotClosed();
		// addIfAbsent, so re-registering the same listener does not double its notifications
		listeners.addIfAbsent(listener);
	}

	@Override
	public void unsubscribe(EventStoreListener listener) {
		// deliberately no checkNotClosed: unsubscribing from a closed storage is what an orderly
		// teardown looks like when the storage happened to be closed first, and it must not throw
		listeners.remove(listener);
	}

	@Override
	public synchronized Optional<EventReference> getBookmark(String reader) {
		checkNotClosed();
		return Optional.ofNullable(bookmarks.get(reader)).map(Bookmark::reference);
	}

	@Override
	public synchronized List<Bookmark> getBookmarks() {
		checkNotClosed();
		return List.copyOf(bookmarks.values());
	}

	@Override
	public synchronized void removeBookmark(String reader) {
		checkNotClosed();
		bookmarks.remove(reader);
	}

	@Override
	public synchronized void bookmark(String reader, EventReference eventReference, Tags tags ) {
		checkNotClosed();
		Tags effectiveTags = tags == null ? Tags.none() : tags;
		bookmarks.put(reader, new Bookmark(reader, eventReference, effectiveTags, Instant.now()));
		BookmarkPlacedNotification notification = new BookmarkPlacedNotification(reader, eventReference);
		listeners.forEach(l->notifyQuietly(l, notification));
	}

	/**
	 * Determines the effective limit to apply to a query based on both soft and absolute limits.
	 * <p>
	 * This method reconciles the soft limit (requested by the query) with the absolute limit
	 * (configured at storage level) to determine the actual limit to enforce. The logic is:
	 * <ul>
	 *   <li>If no soft limit is set, use absolute limit + 1 (to detect violations), or no limit if absolute limit is also unset</li>
	 *   <li>If no absolute limit is set, use the soft limit as-is</li>
	 *   <li>If both are set and soft limit is within absolute limit, use the soft limit</li>
	 *   <li>If soft limit exceeds absolute limit, throw an exception</li>
	 * </ul>
	 *
	 * @param softLimit the limit requested by the query, or null/Limit.none() for no soft limit
	 * @return the effective limit to apply, or Limit.none() if no limit should be enforced
	 * @throws EventStorageException if the soft limit exceeds the configured absolute limit
	 */
	Limit effectiveLimit ( Limit softLimit ) {
		Limit result;
		if ( softLimit == null || softLimit.isNotSet() ) {
			if ( absoluteLimit != null && absoluteLimit.isSet() ) {
				result = Limit.to(absoluteLimit.value()+1);
			} else {
				result = Limit.none();
			}
		} else if ( absoluteLimit == null || absoluteLimit.isNotSet() ) {
			result = softLimit;
		} else if ( softLimit.value() <= absoluteLimit.value() ){
			result = softLimit;
		} else {
			throw new EventStorageException("query limit exceeds the configured absolute limit of %d".formatted(absoluteLimit.value()));
		}
		return result;
	}

	/**
	 * Returns the unique name identifier for this in-memory event storage instance.
	 * <p>
	 * The name is automatically generated based on the object's identity hash code in the format
	 * "inmem-{hashcode}". This ensures each instance has a unique identifier for logging,
	 * metrics tagging, and debugging purposes.
	 *
	 * @return the unique name of this storage instance
	 */
	/**
	 * Notifies one listener, containing its failure.
	 * <p>
	 * Listeners are notified inline here, on the thread that appended or bookmarked, so a listener
	 * throwing would otherwise fail an operation that has already succeeded — the event is stored, and
	 * reporting it as failed invites the caller to append it twice. It would also rob every listener
	 * after it in the list of the notification. A backend delivering on a thread of its own has the
	 * same duty for a starker reason: there, one listener's throwable kills notifications for
	 * everybody.
	 */
	private void notifyQuietly ( EventStoreListener listener, AppendsToEventStoreNotification notification ) {
		try {
			listener.notify(notification);
		} catch ( Exception e ) {
			LOGGER.error("event store listener failed handling an append notification: {}", e.getMessage(), e);
		}
	}

	private void notifyQuietly ( EventStoreListener listener, BookmarkPlacedNotification notification ) {
		try {
			listener.notify(notification);
		} catch ( Exception e ) {
			LOGGER.error("event store listener failed handling a bookmark notification: {}", e.getMessage(), e);
		}
	}

	@Override
	public String name() {
		return name;
	}

	/**
	 * Marks this storage closed. There is nothing to release — the events live on the heap and go away
	 * with the instance — but the post-close contract on {@link EventStorage#close()} still applies, so
	 * that code which outlives its storage fails identically against every backend.
	 * <p>
	 * Idempotent. The events are deliberately not discarded: a closed storage rejects operations rather
	 * than quietly answering from a half-torn-down state, and dropping the log would only make
	 * diagnosing a lifecycle bug harder.
	 * <p>
	 * The listeners <em>are</em> discarded. They are held strongly, so a closed storage that kept them
	 * would pin every stream ever subscribed to it, and every event store behind those streams, for as
	 * long as anything still referenced the storage itself.
	 */
	@Override
	public void close ( ) {
		closed.set(true);
		listeners.clear();
	}

	private void checkNotClosed ( ) {
		if ( closed.get() ) {
			throw new EventStorageClosedException("event storage '%s' is closed".formatted(name));
		}
	}

	/**
	 * Composite dedup key pairing the logical event stream with an idempotency key, so idempotency
	 * is scoped per stream (context + purpose) rather than globally across the storage instance.
	 */
	private record IdempotencyScope ( EventStreamId stream, String idempotencyKey ) {
	}

}
