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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Lease;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.observability.EventStoreObserver;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.spi.EventImportConflictException;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.IdempotencyKeyConflictException;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import tools.jackson.core.JacksonException;
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
 * {@link #append(AppendCriteria, EventStreamId, List)} method. This ensures that checking for new events
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
 * Package-private: {@link InMemoryEventStorage.Builder} is the only way to obtain one, and
 * {@link EventStorage} the type it is handed out as, since nothing this class does is beyond that
 * contract:
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
class InMemoryEventStorageImpl implements EventStorage {

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
	// Leases are runtime coordination state, not data: they are neither preloaded nor persisted (the
	// filesystem-backed decorator snapshots events and bookmarks, deliberately not leases — a lease
	// held by a process that no longer runs must expire, not be resurrected). Real contention happens
	// within one storage instance, so tests can elect between two contenders on any backend; in a
	// single-process deployment the only contender trivially wins.
	private Map<String,Lease> leases = new HashMap<>();
	private Map<String,Map<String,LeaseContender>> leaseContenders = new HashMap<>();
	private JsonMapper jsonMapper;
	private Limit absoluteLimit;
	private long txCounter;

	/**
	 * The highest position ever assigned in this log, seeded from the preloaded events. A position is
	 * taken from here, never derived from the log's size: a log reloaded with a gap in it (see the
	 * filesystem-backed storage, where a crash between two writes that landed out of order leaves one)
	 * would otherwise reissue the position after the gap to the next append, and two stored events
	 * would share a position.
	 */
	private long positionCounter;
	// This backend holds no threads, connections or file handles, so close() has nothing to release.
	// It still marks itself closed, so that the post-close behaviour required by EventStorage.close()
	// is the same here as on a backend that does — code that outlives its storage fails the same way
	// against every backend, in tests as in production.
	private final AtomicBoolean closed = new AtomicBoolean();
	// Never used here: the storage stores sealed envelopes as opaque JSON. Held so that a store built on
	// this storage through the factory finds the codec the builder was given (EventStorage.shreddingCodec()).
	private final ShreddingCodec shreddingCodec;
	// Reported to for this storage's own lifecycle, and answered from observer() so a store built on it
	// reports its operations to the same observer. This backend notifies in-process: it has no channels.
	private final EventStoreObserver observer;

	/**
	 * Constructs a new in-memory event storage instance without shredding, preloaded with the given
	 * events and bookmarks.
	 *
	 * @param name the unique name for this storage instance; must not be null or blank
	 * @param absoluteLimit the absolute limit on query results, or {@link Limit#none()} for no limit
	 * @param initialEvents events to preload, in any order
	 * @param initialBookmarks bookmarks to preload, by reader
	 * @throws IllegalArgumentException if name is null or blank
	 * @see InMemoryEventStorage.Builder#build()
	 */
	InMemoryEventStorageImpl ( String name, Limit absoluteLimit, List<StoredEvent> initialEvents, Map<String, Bookmark> initialBookmarks ) {
		this(name, absoluteLimit, initialEvents, initialBookmarks, null, EventStoreObserver.NOOP);
	}

	/**
	 * Constructs a new in-memory event storage instance carrying the codec that protects its events'
	 * {@link org.sliceworkz.eventstore.shredding.Shreddable} values.
	 * <p>
	 * The storage never seals or unseals anything itself; the codec is answered from
	 * {@link #shreddingCodec()} so that a store built on this storage — through
	 * {@link org.sliceworkz.eventstore.EventStoreFactory#eventStore(EventStorage)} as much as through
	 * {@link InMemoryEventStorage.Builder#buildStore()} — protects and erases personal data.
	 *
	 * @param name the unique name for this storage instance; must not be null or blank
	 * @param absoluteLimit the absolute limit on query results, or {@link Limit#none()} for no limit
	 * @param initialEvents events to preload, in order
	 * @param initialBookmarks bookmarks to preload, by reader
	 * @param shreddingCodec seals and unseals protected values, or null for a storage without shredding
	 * @param observer what this storage, and a store built on it, report to
	 * @throws IllegalArgumentException if name is null or blank
	 * @see InMemoryEventStorage.Builder#build()
	 */
	InMemoryEventStorageImpl ( String name, Limit absoluteLimit, List<StoredEvent> initialEvents, Map<String, Bookmark> initialBookmarks, ShreddingCodec shreddingCodec, EventStoreObserver observer ) {
		if ( name == null || "".equals(name.strip())) {
			throw new IllegalArgumentException("name must not be empty");
		}
		this.name = name;
		// only ever parses payloads to check they are JSON documents (verifyPersistableJson,
		// verifyImportableJson); nothing is bound to a class, so no binding features matter
		this.jsonMapper = JsonMapper.builder().build();
		this.absoluteLimit = absoluteLimit;
		this.shreddingCodec = shreddingCodec;
		this.observer = EventStoreObserver.contained(observer);
		// The log is kept in (tx, position) order -- the order every read walks, the cursor is found in,
		// and the until short-circuits and head() rely on. Appends produce it by construction, since both
		// counters advance under the same lock; a preloaded log is put in it here rather than trusted
		List<StoredEvent> ordered = new ArrayList<>(initialEvents);
		ordered.sort(Comparator
				.<StoredEvent, Long>comparing(e -> e.reference().tx())
				.thenComparing(e -> e.reference().position())
				.thenComparing(e -> e.reference().index()));
		this.eventlog.addAll(ordered);
		// a persisted bookmark is trusted for its id only: the reference kept is the loaded event's own,
		// so a bookmark file written beside a log that has since been re-imported (positions and
		// transactions reassigned, ids preserved) still names the right event. One whose event is not
		// in the log -- a file edited by hand, since nothing here prunes -- is kept as it stands rather
		// than dropped, since an absent bookmark is "replay from the beginning"
		Map<EventId, StoredEvent> loadedById = new HashMap<>();
		for ( StoredEvent event : initialEvents ) {
			loadedById.putIfAbsent(event.reference().id(), event);
		}
		initialBookmarks.forEach(( reader, bookmark ) -> {
			StoredEvent bookmarked = loadedById.get(bookmark.reference().id());
			this.bookmarks.put(reader, bookmarked == null
				? bookmark
				: new Bookmark(bookmark.reader(), bookmarked.reference(), bookmark.tags(), bookmark.updatedAt()));
		});
		this.txCounter = initialEvents.stream()
				.mapToLong(e -> e.reference().tx())
				.max()
				.orElse(0);
		this.positionCounter = initialEvents.stream()
				.mapToLong(e -> e.reference().position())
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

		// nothing further to start: an in-memory storage is ready once it is constructed
		this.observer.storageStarted(name);
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
	 *   <li>Reference-based positioning (starting after the specified reference, in the {@code (tx, position)} order)</li>
	 *   <li>Optional "until" reference from the query</li>
	 *   <li>Result limits (both soft and absolute)</li>
	 * </ul>
	 *
	 * @param filter the event filter specifying which events to retrieve
	 * @param stream the stream to read; a wildcard component reads across it, and the wildcard stream reads the whole log
	 * @param after the reference to start after; events after this position are included
	 * @param limit soft limit on the number of results; may be overridden by absolute limit
	 * @param direction the direction to traverse the event log (FORWARD or BACKWARD)
	 * @return the StoredEvent instances matching the criteria
	 * @throws EventStorageException if the result exceeds the configured absolute limit
	 * @see EventFilter
	 * @see EventReference
	 * @see Direction
	 */
	@Override
	public synchronized List<StoredEvent> query(EventFilter filter, EventStreamId stream, EventReference after, Limit limit, Direction direction ) {
		checkNotClosed();
		requireStream(stream);
		Stream<StoredEvent> on;

		switch ( direction ) {
			case BACKWARD:
				on = eventlog.reversed().stream();
				break;
			case FORWARD:
			default:
				on = eventlog.stream();
		}

		// The cursor is a boundary in the (tx, position) order, exactly as it is on Postgres (a row
		// comparison against the same tuple), and it compares stored events, so the index a reference
		// may carry plays no part. The log is in that order, so the cursor's place in it is found by
		// binary search and everything up to it is sliced off. The alternative -- skipping
		// after.position() elements -- loses because it reads a position as a list index, which holds
		// only while positions are dense and assigned in transaction order: a reference from another
		// store, a reloaded log with a gap, or a transaction and position assigned in different orders
		// then either skip the wrong events or, going backward past the end of the log, throw on a
		// negative skip.
		if ( after != null ) {
			if ( direction == Direction.FORWARD ) {
				on = on.skip(countNotAfter(after));
			} else {
				on = on.skip(eventlog.size() - countBefore(after));
			}
		}
		
		// If we only need to read until a certain event, we can cut the traversal short. "until" is a
		// matching criterion, not a traversal one: it is an inclusive upper bound over the total
		// (tx, position, index) order and means the same thing in both directions. So the events beyond
		// it are a suffix of a forward traversal and a prefix of a backward one -- hence takeWhile vs
		// dropWhile. This is purely a short-circuit; the boundary itself is enforced by filter::matches
		// below, which is where the exact comparison lives.
		if ( filter.until() != null ) {
			EventReference until = filter.until();
			if ( direction == Direction.BACKWARD ) {
				on = on.dropWhile(e->e.reference().happenedAfter(until));
			} else {
				on = on.takeWhile(e->!e.reference().happenedAfter(until));
			}
		}
		
		Stream<StoredEvent> result = on;

		// a wildcard stream reads everything, so covers is the whole of the stream scoping
		result = result.filter(e->stream.covers(e.stream()));
		
		result = result.filter(filter::matches);

		Limit effectiveLimit = effectiveLimit(limit);
		
		if ( effectiveLimit != null && effectiveLimit.isSet() ) {
			result = result.limit(effectiveLimit.value());
		}

		List<StoredEvent> returnValue = result.toList(); // a copy: the log is mutated by the next append
		
		if ( absoluteLimit != null && absoluteLimit.isSet() && returnValue.size() > absoluteLimit.value() ) {
			throw new EventStorageException("query returned more results than the configured absolute limit of %d".formatted(absoluteLimit.value()));
		}
		
		return returnValue;
	}
	
	/**
	 * How many stored events of the log sit at or before the given reference in the {@code (tx, position)}
	 * order -- which, the log being in that order, is the index of the first event after it.
	 */
	private int countNotAfter ( EventReference reference ) {
		return firstIndexWhere(e -> e.reference().storedEventHappenedAfter(reference));
	}

	/**
	 * How many stored events of the log sit strictly before the given reference in the
	 * {@code (tx, position)} order -- the index of the first event at or after it.
	 */
	private int countBefore ( EventReference reference ) {
		return firstIndexWhere(e -> !reference.storedEventHappenedAfter(e.reference()));
	}

	/**
	 * Binary search over the ordered log for the first index whose event satisfies the predicate, given
	 * that the predicate is false for a prefix of the log and true for the rest; the log's size when it
	 * holds for no event.
	 */
	private int firstIndexWhere ( Predicate<StoredEvent> predicate ) {
		int low = 0;
		int high = eventlog.size();
		while ( low < high ) {
			int mid = (low + high) >>> 1;
			if ( predicate.test(eventlog.get(mid)) ) {
				high = mid;
			} else {
				low = mid + 1;
			}
		}
		return low;
	}

	/*
	 *  Synchronized method, to allow re-querying and storing in one shot (required for optimistic locking)
	 */
	@Override
	public synchronized List<StoredEvent> append(AppendCriteria appendCriteria, EventStreamId streamId, List<EventToStore> events) {
		checkNotClosed();
		requireStream(streamId);
		
		verifyPersistableJson(events);
		rejectRepeatedIdempotencyKeys(events);
		
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
			List<StoredEvent> newEvents = query(appendCriteria.eventFilter(), streamId, appendCriteria.expectedLastEventReference().orElse(null), Limit.to(1), Direction.FORWARD);

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
	
	/**
	 * Rejects payloads the Postgres backend would refuse on its {@code ::jsonb} cast -- text that is not
	 * a JSON document, a blank string, a null -- so an append accepted here is accepted there too, and
	 * a raw-mode caller handing the SPI a payload that is not JSON finds out in a test rather than in
	 * production. Checked for the whole batch before anything is added, so a batch with one bad payload
	 * stores nothing, as a rolled-back multi-row insert stores nothing.
	 * <p>
	 * The alternative -- writing the string through the mapper and reading it back -- checks nothing:
	 * the payload is already a {@code String}, and any string serialises to a JSON string literal
	 * that reads back as itself. Whether a <em>domain</em> event round-trips through its mappings is
	 * the stream layer's concern, which surfaces it from {@code append} returning the stored events
	 * deserialized (see {@code InMemoryEventStorageImplTest.testUnparsableJsonNotAppendable}).
	 */
	private void verifyPersistableJson ( List<EventToStore> newEvents ) {
		for ( EventToStore e : newEvents ) {
			if ( e.payload() == null ) {
				throw new EventStorageException("event of type %s to append on stream %s carries no payload".formatted(e.type().name(), e.stream()));
			}
			try {
				if ( jsonMapper.readTree(e.payload()).isMissingNode() ) {
					throw new EventStorageException("event of type %s to append on stream %s carries an empty payload".formatted(e.type().name(), e.stream()));
				}
			} catch (JacksonException ex) {
				throw new EventStorageException("event of type %s to append on stream %s does not carry valid JSON".formatted(e.type().name(), e.stream()), ex);
			}
		}
	}
	
	/**
	 * Rejects a batch carrying one idempotency key on two of its events, as the Postgres backend does
	 * before its insert. A storage cannot give such a batch a meaning: the key says "appended before"
	 * of the second event while the first is being appended in the same call, and de-duplicating one
	 * against the other would store a fragment of the batch.
	 */
	private static void rejectRepeatedIdempotencyKeys ( List<EventToStore> events ) {
		Set<IdempotencyScope> scopes = new HashSet<>();
		for ( EventToStore event : events ) {
			if ( event.idempotencyKey() != null && !scopes.add(new IdempotencyScope(event.stream(), event.idempotencyKey())) ) {
				throw new IllegalArgumentException("idempotency key '%s' is carried by more than one event of the batch on stream %s".formatted(event.idempotencyKey(), event.stream()));
			}
		}
	}

	/**
	 * Adds the batch to the event log, or nothing of it. A batch every key of which was stored on its
	 * stream before is a retry of an atomically stored batch and is swallowed whole; a batch mixing
	 * stored and new keys is not a retry of anything the store holds and is refused with
	 * {@link IdempotencyKeyConflictException}, nothing stored. That is the one answer every backend can
	 * give -- Postgres writes a batch as a single multi-row insert, which its unique index rejects as a
	 * whole -- and the honest one: storing the events with new keys would leave the caller believing
	 * the colliding fact landed too, and swallowing them would lose them silently. The alternative --
	 * skipping the duplicate events and storing the rest -- looks reasonable in memory and is
	 * unavailable on Postgres, where the append pairs the returned rows with the input by position and
	 * so cannot insert a subset.
	 */
	private List<StoredEvent> addAndNotifyListeners ( List<EventToStore> events ) {
		Set<String> storedKeys = new HashSet<>();
		Set<String> newKeys = new HashSet<>();
		EventStreamId keyedStream = null;
		for ( EventToStore event : events ) {
			if ( event.idempotencyKey() != null ) {
				keyedStream = event.stream();
				boolean stored = idempotencyKeys.contains(new IdempotencyScope(event.stream(), event.idempotencyKey()));
				(stored ? storedKeys : newKeys).add(event.idempotencyKey());
			}
		}
		if ( !storedKeys.isEmpty() ) {
			if ( !newKeys.isEmpty() ) {
				throw new IdempotencyKeyConflictException(keyedStream, storedKeys, newKeys);
			}
			return Collections.emptyList();
		}

		long tx = ++txCounter;
		var addedEvents = events.stream().map(e -> addEventToEventLog(e, tx)).toList();

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
			idempotencyKeys.add(new IdempotencyScope(event.stream(), event.idempotencyKey()));
		}

		EventReference reference = EventReference.create(++positionCounter, tx);
		StoredEvent storedEvent = event.positionAt(reference, Instant.now());
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
			StoredEvent storedEvent = event.positionAt(++positionCounter, tx);
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
			if ( jsonMapper.readTree(event.payload()).isMissingNode() ) {
				throw new EventStorageException("event %s to import carries an empty payload".formatted(event.id().value()));
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

	/**
	 * The newest stored event of the stream: the log walked backwards to the first event the stream can
	 * read. Under the same monitor as {@link #query} and {@link #append}, so it is exactly what a query
	 * issued at the same instant would return last.
	 */
	@Override
	public synchronized Optional<EventReference> head ( EventStreamId stream ) {
		checkNotClosed();
		requireStream(stream);
		return eventlog.reversed().stream()
				.filter(e -> stream.covers(e.stream()))
				.findFirst()
				.map(StoredEvent::reference);
	}

	/**
	 * The stream scope of a read or a check is never absent: the whole storage is
	 * {@link EventStreamId#anyContext()}, a wildcard the scoping code handles like any other.
	 */
	private static void requireStream ( EventStreamId stream ) {
		if ( stream == null ) {
			throw new IllegalArgumentException("stream cannot be null; use EventStreamId.anyContext() for the whole storage");
		}
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
		// A bookmark is a position in this store's log, so a reference the store never stored --
		// typically one from a different store -- is a caller error. The Postgres backend rejects it
		// through the fk_bookmarks_event_id foreign key; checking here keeps the write-side contract
		// identical across backends (BookmarksTest in the TCK pins both). Matching the foreign key,
		// the check is on the event id alone, and a rejected bookmark leaves a previously placed one
		// untouched.
		StoredEvent bookmarked = eventReference == null ? null : eventsById.get(eventReference.id());
		if ( eventReference != null && bookmarked == null ) {
			throw new EventStorageException(
				"Cannot place bookmark for reader '%s': %s does not reference an event stored in this event storage"
					.formatted(reader, eventReference));
		}
		// what is kept is the store's own reference for that event, never the caller's: a bookmark names
		// an event by id, and its position and transaction are the event's to say. The Postgres backend
		// gets the same by joining the events row on every read; here the log is immutable, so resolving
		// once at placement is the same answer
		EventReference resolved = bookmarked == null ? null : bookmarked.reference();
		Tags effectiveTags = tags == null ? Tags.none() : tags;
		bookmarks.put(reader, new Bookmark(reader, resolved, effectiveTags, Instant.now()));
		BookmarkPlacedNotification notification = new BookmarkPlacedNotification(reader, resolved);
		listeners.forEach(l->notifyQuietly(l, notification));
	}

	/*
	 * Synchronized like every other state-touching operation here, which is what makes the
	 * expiry check and the takeover one atomic step: two contenders racing an expired lease
	 * cannot both acquire it.
	 */
	@Override
	public synchronized LeaseResponse requestLease ( LeaseRequest request ) {
		checkNotClosed();
		if ( request == null ) {
			throw new IllegalArgumentException("lease request must not be null");
		}
		Instant now = Instant.now();

		// register (or refresh) the caller as a live contender, and prune contenders long gone
		Map<String,LeaseContender> contenders = leaseContenders.computeIfAbsent(request.leaseName(), k -> new HashMap<>());
		contenders.put(request.owner(), new LeaseContender(request.priority(), now, request.ttl()));
		contenders.values().removeIf(c -> c.heartbeatAt().plus(c.ttl().multipliedBy(10)).isBefore(now));

		Lease current = leases.get(request.leaseName());
		boolean acquirable = current == null || current.isExpiredAt(now) || current.owner().equals(request.owner());
		if ( !acquirable ) {
			return new LeaseResponse(LeaseStatus.STANDBY, current.fencingToken(), current.owner());
		}

		// a renewal is a request by the owner of a lease that is still live. A request that finds the
		// lease expired or released is an acquisition whoever held it last: the lease was acquirable in
		// between, so the token bumps even for the same owner -- a holder paused beyond its ttl, or a
		// restarted process reusing its predecessor's owner id, must not carry on under the token its
		// earlier incarnation may still be stamping work with
		boolean renewal = current != null && current.owner().equals(request.owner()) && !current.isExpiredAt(now);
		long fencingToken = current == null ? 1 : renewal ? current.fencingToken() : current.fencingToken() + 1;
		Instant acquiredAt = renewal ? current.acquiredAt() : now;
		leases.put(request.leaseName(), new Lease(request.leaseName(), request.owner(), request.priority(), fencingToken, acquiredAt, now, request.ttl()));

		boolean higherPriorityContenderWaiting = contenders.entrySet().stream()
				.anyMatch(e -> !e.getKey().equals(request.owner())
						&& e.getValue().priority() > request.priority()
						&& !e.getValue().heartbeatAt().plus(e.getValue().ttl()).isBefore(now));
		LeaseStatus status = higherPriorityContenderWaiting ? LeaseStatus.LEADER_STEP_DOWN_REQUESTED : LeaseStatus.LEADER;
		return new LeaseResponse(status, fencingToken, request.owner());
	}

	@Override
	public synchronized void releaseLease ( String leaseName, String owner ) {
		checkNotClosed();
		Lease current = leases.get(leaseName);
		if ( current != null && current.owner().equals(owner) ) {
			// force-expire rather than delete: the fencing token must survive the release, or the
			// next owner would mint token 1 again and a superseded leader's stamp would look current
			leases.put(leaseName, new Lease(leaseName, current.owner(), current.priority(), current.fencingToken(), current.acquiredAt(), Instant.EPOCH, current.ttl()));
		}
		Map<String,LeaseContender> contenders = leaseContenders.get(leaseName);
		if ( contenders != null ) {
			contenders.remove(owner);
			if ( contenders.isEmpty() ) {
				leaseContenders.remove(leaseName);
			}
		}
	}

	@Override
	public synchronized List<Lease> getLeases ( ) {
		checkNotClosed();
		return List.copyOf(leases.values());
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
	public Optional<ShreddingCodec> shreddingCodec ( ) {
		return Optional.ofNullable(shreddingCodec);
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
		if ( closed.compareAndSet(false, true) ) {
			listeners.clear();
			observer.storageClosed(name);
		}
	}

	@Override
	public EventStoreObserver observer ( ) {
		return observer;
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

	/**
	 * A contender for a lease: its priority, and the heartbeat + ttl its liveness is judged by.
	 * Liveness uses the contender's <em>own</em> requested ttl, mirroring how the lease itself expires.
	 */
	private record LeaseContender ( long priority, Instant heartbeatAt, Duration ttl ) {
	}

}
