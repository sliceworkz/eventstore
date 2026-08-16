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
package org.sliceworkz.eventstore.infra.file;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Lease;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.file.bookmark.BookmarkLog;
import org.sliceworkz.eventstore.infra.file.index.HashToPositionMap;
import org.sliceworkz.eventstore.infra.file.index.PositionIndex;
import org.sliceworkz.eventstore.infra.file.log.EventLog;
import org.sliceworkz.eventstore.infra.file.log.EventLog.Location;
import org.sliceworkz.eventstore.infra.file.log.EventRecordCodec;
import org.sliceworkz.eventstore.infra.file.log.EventRecordCodec.Prefix;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The append-only binary log implementation of {@link EventStorage}.
 * <p>
 * Package-private: {@link FileEventStorage} is the way in, and it carries the design rationale.
 *
 * <h2>One lock, and what it buys</h2>
 * Appending, importing and querying all take the same lock. That is not caution, it is the whole
 * mechanism: the consistency-boundary check and the write that follows it are one indivisible step, so
 * two callers racing at the same boundary cannot both find it empty. The PostgreSQL backend has to
 * reach for a {@code pg_advisory_xact_lock} to get the same property, because its check is a predicate
 * over rows that do not exist yet and no row lock can cover a row that is not there.
 * <p>
 * Holding the lock for reads too is a deliberate simplification rather than a requirement. Every
 * structure here is append-only at the tail, so readers could work from a published watermark with no
 * lock at all — but that needs the growable arrays published in the right order against that watermark,
 * and getting that subtly wrong produces a reader that decodes a <em>different, valid</em> event rather
 * than failing. It is worth doing, and it is worth doing with a stress test alongside it.
 *
 * <h2>Positions are assigned at commit</h2>
 * A batch gets the next {@code n} positions and one transaction number, allocated under the lock and
 * only after the bytes are on disk. So the log has no gaps, the transaction number is a monotone
 * non-decreasing step function of the position, and there is nothing a reader has to be protected from
 * seeing. The {@code (tx, position)} tuple is still what every boundary compares, because a caller may
 * hand us a reference we never issued.
 */
class FileEventStorageImpl implements EventStorage {

	private static final Logger LOGGER = LoggerFactory.getLogger(FileEventStorageImpl.class);

	private final String name;
	private final Limit absoluteLimit;
	private final long segmentSizeBytes;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	private final ReentrantLock lock = new ReentrantLock();
	private final AtomicBoolean closed = new AtomicBoolean();

	private final StorageDirectory directory;
	private final EventLog log;
	private final BookmarkLog bookmarks;

	private final PositionIndex positions = new PositionIndex();
	private final HashToPositionMap eventIds = new HashToPositionMap(1024);
	private final HashToPositionMap idempotencyKeys = new HashToPositionMap(1024);

	private final CopyOnWriteArrayList<EventStoreListener> listeners = new CopyOnWriteArrayList<>();

	private final Map<String, Lease> leases = new HashMap<>();
	private final Map<String, Map<String, LeaseContender>> leaseContenders = new HashMap<>();

	private long txCounter;

	FileEventStorageImpl ( Path directoryPath, String name, Limit absoluteLimit, Durability durability, long segmentSizeBytes ) {
		if ( segmentSizeBytes <= 0 || segmentSizeBytes > Integer.MAX_VALUE ) {
			throw new IllegalArgumentException(
					"segment size must be positive and below 2 GiB, got " + segmentSizeBytes);
		}
		this.name = name;
		this.absoluteLimit = absoluteLimit;
		this.segmentSizeBytes = segmentSizeBytes;

		this.directory = StorageDirectory.open(directoryPath, segmentSizeBytes);
		try {
			this.log = EventLog.open(directory.eventsDirectory(), durability, segmentSizeBytes, this::index);
			this.txCounter = log.lastTx();
			this.bookmarks = BookmarkLog.open(directory.bookmarksPath(), durability);
		} catch (RuntimeException e) {
			directory.close();
			throw e;
		}

		if ( !directory.wasCleanlyClosed() ) {
			LOGGER.info(("Event storage '{}' opened {} after an unclean shutdown; the log was replayed and holds {} "
					+ "event(s)."), name, directory.path(), positions.count());
		}
	}

	/** Called for every committed record as the log is replayed, and for every record as it is written. */
	private void index ( ByteBuffer body, Location location ) {
		Prefix prefix = EventRecordCodec.decodePrefix(body);
		index(prefix, location);
	}

	private void index ( Prefix prefix, Location location ) {
		positions.add(prefix.position(), location);
		eventIds.put(HashToPositionMap.hash(prefix.reference().id().value()), prefix.position());
		if ( prefix.idempotencyKey() != null ) {
			idempotencyKeys.put(idempotencyHash(prefix.stream(), prefix.idempotencyKey()), prefix.position());
		}
	}

	@Override
	public String name ( ) {
		return name;
	}

	// ---------------------------------------------------------------------------------------------
	// reading
	// ---------------------------------------------------------------------------------------------

	@Override
	public Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream, EventReference after,
			Limit limit, QueryDirection queryDirection ) {
		checkNotClosed();
		if ( query.isMatchNone() ) {
			return Stream.empty();
		}

		lock.lock();
		try {
			List<StoredEvent> result = collect(query, stream, after, effectiveLimit(limit), queryDirection);
			if ( absoluteLimit != null && absoluteLimit.isSet() && result.size() > absoluteLimit.value() ) {
				throw new EventStorageException(
						"query returned more results than the configured absolute limit of %d".formatted(absoluteLimit.value()));
			}
			return result.stream();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Walks the log in the requested direction, collecting what matches.
	 * <p>
	 * The cursor is compared as the whole {@code (tx, position, index)} tuple rather than as a position,
	 * because the reference may not be one this store issued — {@code until} boundaries and import
	 * cursors both travel between stores. Matching itself is delegated to
	 * {@link EventQuery#matches(org.sliceworkz.eventstore.events.EventType, Tags, EventReference)}, which
	 * is the same predicate every other backend is held to and which applies the {@code until} boundary;
	 * agreeing with it by calling it is the cheapest way to be sure of agreeing with it at all.
	 * <p>
	 * Only the metadata of each candidate is decoded. The payloads sit at the end of every record and are
	 * read only for events that survive the filter, so a selective query over a large log never
	 * materialises the bodies it is going to discard.
	 */
	private List<StoredEvent> collect ( EventQuery query, Optional<EventStreamId> stream, EventReference after,
			Limit limit, QueryDirection direction ) {
		List<StoredEvent> result = new ArrayList<>();
		long count = positions.count();
		long remaining = limit != null && limit.isSet() ? limit.value() : Long.MAX_VALUE;

		for ( long i = 0; i < count && remaining > 0; i++ ) {
			long position = direction == QueryDirection.BACKWARD ? count - i : i + 1;

			Location location = positions.locationOf(position);
			ByteBuffer body = log.readBody(location);
			Prefix prefix = EventRecordCodec.decodePrefix(body);

			if ( after != null ) {
				boolean past = direction == QueryDirection.BACKWARD
						? prefix.reference().happenedBefore(after)
						: prefix.reference().happenedAfter(after);
				if ( !past ) {
					continue;
				}
			}
			if ( stream.isPresent() && !stream.get().canRead(prefix.stream()) ) {
				continue;
			}
			if ( !query.matches(prefix.type(), prefix.tags(), prefix.reference()) ) {
				continue;
			}

			result.add(EventRecordCodec.withPayloads(prefix, body));
			remaining--;
		}
		return result;
	}

	@Override
	public Optional<StoredEvent> getEventById ( EventId eventId ) {
		checkNotClosed();
		if ( eventId == null ) {
			return Optional.empty();
		}
		lock.lock();
		try {
			long position = eventIds.find(HashToPositionMap.hash(eventId.value()),
					candidate -> prefixAt(candidate).reference().id().equals(eventId));
			return position == 0 ? Optional.empty() : Optional.of(eventAt(position));
		} finally {
			lock.unlock();
		}
	}

	private Prefix prefixAt ( long position ) {
		return EventRecordCodec.decodePrefix(log.readBody(positions.locationOf(position)));
	}

	private StoredEvent eventAt ( long position ) {
		ByteBuffer body = log.readBody(positions.locationOf(position));
		return EventRecordCodec.decode(body);
	}

	// ---------------------------------------------------------------------------------------------
	// appending
	// ---------------------------------------------------------------------------------------------

	@Override
	public List<StoredEvent> append ( AppendCriteria appendCriteria, Optional<EventStreamId> stream, List<EventToStore> events ) {
		checkNotClosed();

		lock.lock();
		try {
			// "no criteria" is derived from the filter alone. An *empty* expected reference under a real
			// filter is not this case: it means "I decided on an empty stream", which is still a boundary,
			// and any matching event in the stream is a new relevant fact that has to raise.
			if ( !appendCriteria.isNone() ) {
				EventQuery lockingQuery = new EventQuery(appendCriteria.eventFilter(), EventQuery.Direction.FORWARD, Limit.none());
				List<StoredEvent> newEvents = collect(lockingQuery, stream,
						appendCriteria.expectedLastEventReference().orElse(null), Limit.to(1), QueryDirection.FORWARD);
				if ( !newEvents.isEmpty() ) {
					throw new OptimisticLockingException(appendCriteria.eventFilter(), appendCriteria.expectedLastEventReference());
				}
			}

			List<StoredEvent> stored = write(events);
			notifyAbout(stored);
			return stored;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Assigns positions, writes one batch, and only then tells the index about it.
	 * <p>
	 * The ordering is the point. If the index learned about the events first and the write then failed,
	 * this storage would answer queries with events that are not on disk and will not survive a restart —
	 * and it would do so silently. Writing first means a failure leaves the index describing exactly what
	 * the log holds.
	 */
	private List<StoredEvent> write ( List<EventToStore> events ) {
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
		long tx = txCounter + 1;
		long firstPosition = positions.count() + 1;

		List<StoredEvent> stored = new ArrayList<>(events.size());
		List<byte[]> bodies = new ArrayList<>(events.size());
		Set<Long> keysInBatch = new HashSet<>();

		for ( EventToStore event : events ) {
			// a duplicate idempotency key removes *this event* from the batch and nothing else: the call
			// still succeeds and still returns the events that were not duplicates
			if ( event.idempotencyKey() != null ) {
				long hash = idempotencyHash(event.stream(), event.idempotencyKey());
				if ( isKnownIdempotencyKey(hash, event.stream(), event.idempotencyKey()) || !keysInBatch.add(hash) ) {
					continue;
				}
			}
			StoredEvent storedEvent = event.positionAt(
					EventReference.create(firstPosition + stored.size(), tx), now);
			stored.add(storedEvent);
			bodies.add(EventRecordCodec.encode(storedEvent));
		}

		if ( stored.isEmpty() ) {
			return Collections.emptyList();
		}

		List<Location> locations = log.appendBatch(bodies, tx, firstPosition);
		txCounter = tx;
		for ( int i = 0; i < stored.size(); i++ ) {
			index(prefixOf(stored.get(i)), locations.get(i));
		}
		return stored;
	}

	private static Prefix prefixOf ( StoredEvent event ) {
		return new Prefix(event.reference(), event.stream(), event.type(), event.tags(), event.timestamp(),
				event.idempotencyKey(), 0);
	}

	private boolean isKnownIdempotencyKey ( long hash, EventStreamId stream, String key ) {
		return idempotencyKeys.find(hash, candidate -> {
			Prefix prefix = prefixAt(candidate);
			return key.equals(prefix.idempotencyKey()) && stream.equals(prefix.stream());
		}) != 0;
	}

	private static long idempotencyHash ( EventStreamId stream, String key ) {
		return HashToPositionMap.hash(String.valueOf(stream.context()), String.valueOf(stream.purpose()), key);
	}

	// ---------------------------------------------------------------------------------------------
	// importing
	// ---------------------------------------------------------------------------------------------

	@Override
	public List<StoredEvent> importEvents ( List<EventToImport> events, ImportMode mode ) {
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

		lock.lock();
		try {
			// the whole batch is validated before a single byte is written, so a rejected import leaves
			// nothing behind -- which is also what makes the batch trailer's all-or-nothing meaningful
			Set<EventId> idsInBatch = new HashSet<>();
			Set<Long> keysInBatch = new HashSet<>();
			List<EventToImport> toInsert = new ArrayList<>(events.size());

			for ( EventToImport event : events ) {
				if ( !idsInBatch.add(event.id()) ) {
					throw new IllegalArgumentException(
							"batch to import holds more than one event with id %s".formatted(event.id().value()));
				}

				verifyImportableJson(event);

				if ( knownEventId(event.id()) ) {
					if ( mode == ImportMode.SKIP_EXISTING_ID ) {
						continue;                                               // already here, and so is whatever key it carries
					}
					throw EventImportConflictException.duplicateEventId(event.id(), null);
				}

				if ( event.idempotencyKey() != null ) {
					long hash = idempotencyHash(event.stream(), event.idempotencyKey());
					if ( isKnownIdempotencyKey(hash, event.stream(), event.idempotencyKey()) || !keysInBatch.add(hash) ) {
						throw EventImportConflictException.duplicateIdempotencyKey(event.stream(), event.idempotencyKey(), null);
					}
				}

				toInsert.add(event);
			}

			if ( toInsert.isEmpty() ) {
				return Collections.emptyList();
			}

			long tx = txCounter + 1;
			long firstPosition = positions.count() + 1;
			List<StoredEvent> imported = new ArrayList<>(toInsert.size());
			List<byte[]> bodies = new ArrayList<>(toInsert.size());

			for ( EventToImport event : toInsert ) {
				// the id and the timestamp travel with the event; position and tx are always the target's
				StoredEvent storedEvent = event.positionAt(firstPosition + imported.size(), tx);
				imported.add(storedEvent);
				bodies.add(EventRecordCodec.encode(storedEvent));
			}

			List<Location> locations = log.appendBatch(bodies, tx, firstPosition);
			txCounter = tx;
			for ( int i = 0; i < imported.size(); i++ ) {
				index(prefixOf(imported.get(i)), locations.get(i));
			}

			notifyAbout(imported);
			return imported;
		} finally {
			lock.unlock();
		}
	}

	private boolean knownEventId ( EventId eventId ) {
		return eventIds.find(HashToPositionMap.hash(eventId.value()),
				candidate -> prefixAt(candidate).reference().id().equals(eventId)) != 0;
	}

	/**
	 * Rejects payloads the PostgreSQL backend would refuse on its {@code ::jsonb} cast, so an import
	 * accepted by one backend is accepted by both.
	 * <p>
	 * Only imports are checked. On the append path the payload always comes from the serializer above the
	 * SPI, so a check there would be a parse per event to re-establish something already true.
	 */
	private void verifyImportableJson ( EventToImport event ) {
		try {
			if ( jsonMapper.readTree(event.immutableData()).isMissingNode() ) {
				throw new EventStorageException(
						"event %s to import carries an empty immutable payload".formatted(event.id().value()));
			}
			if ( event.erasableData() != null && jsonMapper.readTree(event.erasableData()).isMissingNode() ) {
				throw new EventStorageException(
						"event %s to import carries an empty erasable payload".formatted(event.id().value()));
			}
		} catch (JacksonException e) {
			throw new EventStorageException(
					"event %s to import carries a payload that is not valid JSON".formatted(event.id().value()), e);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// listeners
	// ---------------------------------------------------------------------------------------------

	@Override
	public void subscribe ( EventStoreListener listener ) {
		checkNotClosed();
		listeners.addIfAbsent(listener);
	}

	@Override
	public void unsubscribe ( EventStoreListener listener ) {
		listeners.remove(listener);
	}

	/**
	 * Tells every listener about a batch: one notification per stream, carrying that stream's last
	 * reference.
	 * <p>
	 * Per stream rather than per event, because a subscriber matches a notification against a stream and
	 * would discard all but the last of a run anyway — and per stream rather than one collapsed "something
	 * happened", because a notification that names no concrete stream matches no concrete subscriber.
	 */
	private void notifyAbout ( List<StoredEvent> stored ) {
		Map<EventStreamId, AppendsToEventStoreNotification> perStream = new LinkedHashMap<>();
		for ( StoredEvent event : stored ) {
			perStream.put(event.stream(), new AppendsToEventStoreNotification(event.stream(), event.reference()));
		}
		for ( AppendsToEventStoreNotification notification : perStream.values() ) {
			for ( EventStoreListener listener : listeners ) {
				notifyQuietly(listener, notification);
			}
		}
	}

	/**
	 * Notifies one listener, containing its failure.
	 * <p>
	 * Listeners are notified inline, on the thread that appended, so a listener throwing would otherwise
	 * fail an operation that has already succeeded — inviting the caller to append the same events twice —
	 * and would rob every listener behind it of the notification.
	 */
	private void notifyQuietly ( EventStoreListener listener, AppendsToEventStoreNotification notification ) {
		try {
			listener.notify(notification);
		} catch (Exception e) {
			LOGGER.error("event store listener failed handling an append notification: {}", e.getMessage(), e);
		}
	}

	private void notifyQuietly ( EventStoreListener listener, BookmarkPlacedNotification notification ) {
		try {
			listener.notify(notification);
		} catch (Exception e) {
			LOGGER.error("event store listener failed handling a bookmark notification: {}", e.getMessage(), e);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// bookmarks
	// ---------------------------------------------------------------------------------------------

	@Override
	public Optional<EventReference> getBookmark ( String reader ) {
		checkNotClosed();
		lock.lock();
		try {
			return bookmarks.get(reader).map(Bookmark::reference);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public List<Bookmark> getBookmarks ( ) {
		checkNotClosed();
		lock.lock();
		try {
			return bookmarks.all();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void bookmark ( String reader, EventReference eventReference, Tags tags ) {
		checkNotClosed();
		lock.lock();
		try {
			// a bookmark names a place in *this* log, so a reference this store never stored -- typically
			// one from another store in a miswired setup -- is a caller error rather than a cursor. The
			// check is on the event id alone, matching the foreign key the PostgreSQL backend uses, and a
			// rejected bookmark leaves a previously placed one exactly where it was.
			if ( eventReference != null && !knownEventId(eventReference.id()) ) {
				throw new EventStorageException(
						"Cannot place bookmark for reader '%s': %s does not reference an event stored in this event storage"
								.formatted(reader, eventReference));
			}
			bookmarks.place(new Bookmark(reader, eventReference, tags == null ? Tags.none() : tags, Instant.now()));
		} finally {
			lock.unlock();
		}

		BookmarkPlacedNotification notification = new BookmarkPlacedNotification(reader, eventReference);
		listeners.forEach(listener -> notifyQuietly(listener, notification));
	}

	@Override
	public void removeBookmark ( String reader ) {
		checkNotClosed();
		lock.lock();
		try {
			bookmarks.remove(reader);
		} finally {
			lock.unlock();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// leases
	// ---------------------------------------------------------------------------------------------

	/**
	 * Leases are held in memory and are deliberately not written to disk.
	 * <p>
	 * The file-backed in-memory storage makes the same choice with the argument that a lease held by a
	 * process that no longer runs must expire rather than be resurrected. Here that argument is stronger
	 * than a judgement call: the directory is locked exclusively, so a new process can only open it once
	 * the previous holder is gone — which makes any lease that survived on disk stale by construction.
	 */
	@Override
	public LeaseResponse requestLease ( LeaseRequest request ) {
		checkNotClosed();
		if ( request == null ) {
			throw new IllegalArgumentException("lease request must not be null");
		}

		lock.lock();
		try {
			Instant now = Instant.now();

			Map<String, LeaseContender> contenders = leaseContenders.computeIfAbsent(request.leaseName(), k -> new HashMap<>());
			contenders.put(request.owner(), new LeaseContender(request.priority(), now, request.ttl()));
			contenders.values().removeIf(c -> c.heartbeatAt().plus(c.ttl().multipliedBy(10)).isBefore(now));

			Lease current = leases.get(request.leaseName());
			boolean acquirable = current == null || current.isExpiredAt(now) || current.owner().equals(request.owner());
			if ( !acquirable ) {
				return new LeaseResponse(LeaseStatus.STANDBY, current.fencingToken(), current.owner());
			}

			boolean ownershipChange = current == null || !current.owner().equals(request.owner());
			long fencingToken = current == null ? 1 : ownershipChange ? current.fencingToken() + 1 : current.fencingToken();
			Instant acquiredAt = ownershipChange ? now : current.acquiredAt();
			leases.put(request.leaseName(), new Lease(request.leaseName(), request.owner(), request.priority(),
					fencingToken, acquiredAt, now, request.ttl()));

			boolean higherPriorityContenderWaiting = contenders.entrySet().stream()
					.anyMatch(e -> !e.getKey().equals(request.owner())
							&& e.getValue().priority() > request.priority()
							&& !e.getValue().heartbeatAt().plus(e.getValue().ttl()).isBefore(now));
			LeaseStatus status = higherPriorityContenderWaiting ? LeaseStatus.LEADER_STEP_DOWN_REQUESTED : LeaseStatus.LEADER;
			return new LeaseResponse(status, fencingToken, request.owner());
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void releaseLease ( String leaseName, String owner ) {
		checkNotClosed();
		lock.lock();
		try {
			Lease current = leases.get(leaseName);
			if ( current != null && current.owner().equals(owner) ) {
				// force-expire rather than delete: the fencing token has to survive a release, or the next
				// owner mints token 1 again and a superseded leader's stamp looks current
				leases.put(leaseName, new Lease(leaseName, current.owner(), current.priority(), current.fencingToken(),
						current.acquiredAt(), Instant.EPOCH, current.ttl()));
			}
			Map<String, LeaseContender> contenders = leaseContenders.get(leaseName);
			if ( contenders != null ) {
				contenders.remove(owner);
				if ( contenders.isEmpty() ) {
					leaseContenders.remove(leaseName);
				}
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public List<Lease> getLeases ( ) {
		checkNotClosed();
		lock.lock();
		try {
			return List.copyOf(leases.values());
		} finally {
			lock.unlock();
		}
	}

	private record LeaseContender ( long priority, Instant heartbeatAt, java.time.Duration ttl ) {

	}

	// ---------------------------------------------------------------------------------------------
	// lifecycle
	// ---------------------------------------------------------------------------------------------

	/**
	 * Releases everything this storage created: the log's file handles, the bookmark log, and the
	 * directory lock.
	 * <p>
	 * Idempotent and terminal. The listeners go too — they are held strongly, so a closed storage that
	 * kept them would pin every stream ever subscribed to it and every store behind those streams.
	 * <p>
	 * There are no threads to stop, so this returns as soon as the handles are closed. That is the
	 * quietest part of the single-process design: nothing here has a bounded-shutdown problem, because
	 * there is nothing running that could fail to notice it should stop.
	 */
	@Override
	public void close ( ) {
		if ( !closed.compareAndSet(false, true) ) {
			return;
		}
		lock.lock();
		try {
			listeners.clear();
			bookmarks.close();
			directory.markCleanlyClosed(segmentSizeBytes, positions.count(), txCounter);
			log.close();
		} finally {
			directory.close();
			lock.unlock();
		}
	}

	private void checkNotClosed ( ) {
		if ( closed.get() ) {
			throw new EventStorageClosedException("event storage '%s' is closed".formatted(name));
		}
	}

	Limit effectiveLimit ( Limit softLimit ) {
		Limit result;
		if ( softLimit == null || softLimit.isNotSet() ) {
			if ( absoluteLimit != null && absoluteLimit.isSet() ) {
				result = Limit.to(absoluteLimit.value() + 1);
			} else {
				result = Limit.none();
			}
		} else if ( absoluteLimit == null || absoluteLimit.isNotSet() ) {
			result = softLimit;
		} else if ( softLimit.value() <= absoluteLimit.value() ) {
			result = softLimit;
		} else {
			throw new EventStorageException(
					"query limit exceeds the configured absolute limit of %d".formatted(absoluteLimit.value()));
		}
		return result;
	}

}
