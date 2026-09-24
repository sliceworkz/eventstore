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
package org.sliceworkz.eventstore.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.Banner;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.observability.EventStoreObserver;
import org.sliceworkz.eventstore.observability.Observation;
import org.sliceworkz.eventstore.observability.Outcome;
import org.sliceworkz.eventstore.observability.StreamInfo;
import org.sliceworkz.eventstore.observability.StreamObservation;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.ErasureReport;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingAudit;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.SubjectErasureReport;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndPayload;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndSerializedPayload;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventFilterItem;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.spi.EventStorage.AppendsToEventStoreNotification;
import org.sliceworkz.eventstore.spi.EventStorage.BookmarkPlacedNotification;
import org.sliceworkz.eventstore.spi.EventStorage.EventStoreListener;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.AppendListener;
import org.sliceworkz.eventstore.stream.BookmarkListener;
import org.sliceworkz.eventstore.stream.EventPage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.stream.Subscription;

/**
 * Concrete implementation of {@link EventStore} providing event storage with pluggable backend support.
 * <p>
 * This implementation serves as the core engine for the event store, coordinating between the public API
 * and the underlying storage layer. It supports multiple storage backends (in-memory, PostgreSQL, etc.)
 * through the {@link EventStorage} abstraction.
 * <p>
 * Key responsibilities include:
 * <ul>
 *   <li>Creating and managing {@link EventStream} instances for specific stream IDs</li>
 *   <li>Coordinating event serialization/deserialization via {@link EventPayloadSerializerDeserializer}</li>
 *   <li>Managing eventually consistent event and bookmark notifications to subscribers</li>
 *   <li>Supporting both typed (Java objects) and raw (JSON) event payload modes</li>
 *   <li>Handling optimistic locking and DCB (Dynamic Consistency Boundary) compliance</li>
 * </ul>
 * <p>
 * This class is instantiated via the {@link EventStoreFactoryImpl} using Java's ServiceLoader mechanism.
 * Users should obtain EventStore instances through {@link EventStore#on(org.sliceworkz.eventstore.spi.EventStorage)}.
 * <p>
 * The implementation uses virtual threads for asynchronous notification of eventually consistent subscribers,
 * ensuring efficient handling of concurrent event processing without blocking the main append operations.
 *
 * <h2>Event Payload Modes:</h2>
 * <ul>
 *   <li><b>Typed Mode:</b> Events are serialized to/from Java objects using Jackson. Requires event root classes
 *       to be registered with the stream. Supports sealed interfaces, upcasting via {@link org.sliceworkz.eventstore.events.LegacyEvent},
 *       and GDPR compliance through {@link org.sliceworkz.eventstore.shredding.Shreddable} values.</li>
 *   <li><b>Raw Mode:</b> Events are stored and retrieved as JSON strings without type mapping. Useful for
 *       schema-less event processing or when event types are not statically known.</li>
 * </ul>
 *
 * <h2>Thread Safety:</h2>
 * This implementation is thread-safe. Multiple threads can safely obtain event streams and perform concurrent
 * append and query operations. Event notifications to subscribers are dispatched asynchronously using a dedicated
 * executor service per EventStore instance.
 *
 * @see EventStore
 * @see EventStoreFactoryImpl
 * @see EventStorage
 * @see EventPayloadSerializerDeserializer
 */
public class EventStoreImpl implements EventStore {

	private static final Logger STORE_LOGGER = LoggerFactory.getLogger(EventStoreImpl.class);

	static {
		Banner.printBanner();
	}

	/**
	 * The underlying storage backend for persisting and retrieving events.
	 */
	private final EventStorage eventStorage;

	/**
	 * Executor service using virtual threads for asynchronously notifying eventually consistent subscribers
	 * about new event appends. Named threads help with debugging and monitoring.
	 */
	private final ExecutorService executorServiceForEventAppends;

	/**
	 * Executor service using virtual threads for asynchronously notifying eventually consistent subscribers
	 * about bookmark updates. This uses a single-threaded executor to ensure bookmark notifications are
	 * processed sequentially. Named threads help with debugging and monitoring.
	 */
	private final ExecutorService executorServiceForBookmarkUpdates;

	/**
	 * What this store reports its operations to: the observer it was given, or the storage's own, wrapped
	 * so that nothing it throws reaches an operation. {@link EventStoreObserver#NOOP} for a store nobody
	 * observes, which is the default.
	 */
	private final EventStoreObserver observer;

	/**
	 * Seals and unseals the {@link org.sliceworkz.eventstore.shredding.Shreddable} values in this store's
	 * payloads, and destroys the keys behind them when a subject is erased. Null on a store configured
	 * without shredding, in which case registering an event type that declares a protected component
	 * fails rather than storing personal data in the clear, and an append carrying one the declaration
	 * did not show fails the same way.
	 * <p>
	 * Not closed by {@link #close()}: a codec is handed in by the caller and may back several stores,
	 * the same rule the library applies to a {@code DataSource}. The storage builders close the codecs
	 * they create themselves.
	 */
	private final ShreddingCodec shreddingCodec;

	/**
	 * Guards {@link #close()} so that it runs once, and marks this store as unusable afterwards.
	 */
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * The streams of this store that currently hold a listener registration with the storage — that is,
	 * the ones somebody has subscribed to. Streams nobody subscribed to never appear here.
	 * <p>
	 * The storage references those streams strongly, so they outlive the caller's variable on purpose;
	 * this set is what lets {@link #close()} hand them all back rather than leaving the storage holding
	 * streams belonging to a store that is gone. Identity-based, since {@code EventStreamImpl} defines
	 * no equality: two streams for the same id are two distinct registrations.
	 */
	private final Set<EventStreamImpl<?>> subscribedStreams = ConcurrentHashMap.newKeySet();

	/**
	 * The payload serializers this store has built so far, one per distinct pair of event root class
	 * sets. Shared by every stream opened with the same mapping.
	 * <p>
	 * A serde is by far the expensive part of {@link #getEventStream}: its constructor builds two
	 * Jackson {@code JsonMapper}s and registering the root classes walks the sealed hierarchy
	 * reflectively, instantiating an {@code Upcaster} per {@code @LegacyEvent}. Building one costs
	 * roughly 20µs and 40KB, but the mappers matter far more than that suggests — Jackson caches its
	 * per-type serializers and deserializers <em>inside the mapper</em>, so a serde built per call
	 * hands every stream a cold cache and makes the first serialize of each record type re-run bean
	 * introspection. Measured on a 24-record hierarchy, that turns a query through a freshly obtained
	 * stream into roughly four times the work of the same query through a stream that is kept.
	 * Sharing the serde is what lets those caches warm up once.
	 * <p>
	 * Only the serde is shared, never the {@link EventStreamImpl}. A stream carries subscriber lists
	 * and a subscribed flag, so handing the same instance to two callers would make one caller's
	 * {@code close()} end the other's subscriptions; a serde has no lifecycle at all. It is written
	 * once, inside {@link #serdeFor}, and only read afterwards — the two Jackson mappers are immutable
	 * and thread-safe, and the type maps are never touched again after registration — so publishing it
	 * through this map is safe.
	 * <p>
	 * Held per store rather than statically: the key references {@code Class} objects, and a static
	 * cache would pin their class loaders for the life of the JVM. Its size is bounded by the number of
	 * distinct root class sets the application opens streams with, which is a property of the code
	 * rather than of the traffic.
	 */
	private final ConcurrentHashMap<SerdeKey, EventPayloadSerializerDeserializer> serdes = new ConcurrentHashMap<>();

	/**
	 * Identifies a payload serializer by the mappings it was built from, which is everything that
	 * distinguishes one from another.
	 * <p>
	 * The {@link EventStreamId} deliberately plays no part: the same stream can be opened with
	 * different event root classes, and two streams sharing a mapping can share a serde whatever their
	 * ids. The sets are copied on the way in, so a caller mutating the set it passed cannot corrupt the
	 * key of an already-cached entry.
	 */
	private record SerdeKey ( Set<Class<?>> eventRootClasses, Set<Class<?>> legacyEventRootClasses ) {
		SerdeKey {
			eventRootClasses = Set.copyOf(eventRootClasses);
			legacyEventRootClasses = Set.copyOf(legacyEventRootClasses);
		}
	}

	/**
	 * How long {@link #close()} waits for each notification executor to finish before logging and
	 * moving on. The tasks are short-lived listener callbacks, so this only covers a listener that
	 * ignores interruption.
	 */
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;


	/**
	 * Constructs a new EventStoreImpl on the given storage.
	 * <p>
	 * Invoked by {@link EventStoreFactoryImpl}; applications build a store with
	 * {@link EventStore#on(EventStorage)}. The notification executors use virtual threads, so that
	 * eventually consistent subscribers are told about appends without blocking them.
	 *
	 * @param eventStorage the storage backend implementation (in-memory, PostgreSQL, etc.)
	 * @param observer what the store reports its operations to, or null for the storage's own
	 *                 ({@link EventStorage#observer()}), which is {@link EventStoreObserver#NOOP} for a
	 *                 storage configured without one
	 * @param shreddingCodec seals and unseals protected values, or null to use the codec the storage was
	 *                       configured with ({@link EventStorage#shreddingCodec()}), which is empty for a
	 *                       store without shredding
	 * @throws IllegalArgumentException if eventStorage is null
	 */
	protected EventStoreImpl ( EventStorage eventStorage, EventStoreObserver observer, ShreddingCodec shreddingCodec ) {
		if ( eventStorage == null ) {
			throw new IllegalArgumentException("eventStorage cannot be null");
		}
		this.eventStorage = eventStorage;
		// one given explicitly wins; otherwise the storage's own, so that a builder's .observer(...) reaches
		// a store built on the storage through EventStore.on(storage), not only one from buildStore()
		this.observer = EventStoreObserver.contained(observer != null ? observer : eventStorage.observer());
		// a codec handed in explicitly wins; otherwise the storage's own, so that a builder's .shredding(...)
		// reaches a store built on the storage through the factory, not only one from buildStore()
		this.shreddingCodec = shreddingCodec != null ? shreddingCodec : eventStorage.shreddingCodec().orElse(null);

		ThreadFactory threadFactory = Thread.ofVirtual().name("eventually-consistent-listener-notifier/" + eventStorage.name(), 0).factory();
		this.executorServiceForEventAppends = Executors.newThreadPerTaskExecutor(threadFactory);

		this.executorServiceForBookmarkUpdates = Executors.newSingleThreadExecutor(threadFactory);
	}

	/**
	 * Unregisters this store's subscribed streams from the storage and shuts down its notification
	 * executors, leaving the storage itself open.
	 * <p>
	 * Idempotent, and bounded: the executors are interrupted and awaited briefly. The storage was handed
	 * to this store rather than created by it, and may well be backing other stores, so closing it is
	 * not this store's call — see {@link EventStore#close()}. A store that must close its storage is
	 * composed with {@link EventStore#owning(EventStore, EventStorage)}, which is what the storage
	 * builders' {@code buildStore()} returns.
	 * <p>
	 * The streams are unregistered <em>first</em>: the storage holds subscribed streams strongly, so
	 * leaving them registered would keep this store — an inner-class stream references the store that
	 * made it — reachable from a storage that outlives it, and would keep the storage delivering
	 * notifications no one can act on any more.
	 * <p>
	 * Once closed, this store's streams stop working and its listeners fall silent, but nothing it does
	 * disturbs another store on the same storage.
	 */
	@Override
	public void close ( ) {
		if ( !closed.compareAndSet(false, true) ) {
			return;
		}
		subscribedStreams.forEach(EventStreamImpl::close);
		shutdown(executorServiceForEventAppends);
		shutdown(executorServiceForBookmarkUpdates);
	}

	private void shutdown ( ExecutorService executorService ) {
		executorService.shutdownNow();
		try {
			if ( !executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) ) {
				STORE_LOGGER.warn("notification threads of event store '{}' did not terminate within {}s", eventStorage.name(), SHUTDOWN_TIMEOUT_SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public ErasureReport eraseCategory ( DataSubject subject, ErasureReason reason ) {
		if ( subject == null ) {
			throw new IllegalArgumentException("subject cannot be null");
		}
		if ( reason == null ) {
			throw new IllegalArgumentException("reason cannot be null");
		}
		if ( shreddingCodec == null ) {
			throw new UnsupportedOperationException(
					"event store on storage '%s' has no ShreddingCodec configured, so it holds no keys to destroy; configure shredding on the storage builder or via EventStore.on(storage).shredding(codec)"
							.formatted(eventStorage.name()));
		}
		// Deliberately allowed on a closed store: erasure touches the key store, not the events, and
		// refusing to honour an erasure request because a store handle was closed would be a poor reason
		// to leave personal data readable.
		ErasureReport report = observed(
				new Observation.Erase(eventStorage.name(), subject.type(), subject.id(), Optional.of(subject.category()), reason),
				reporter -> {
					ErasureReport erased = shreddingCodec.shred(subject, reason);
					reporter.completed(new Outcome.Erased(erased.keysShredded(), erased.isNoop() ? List.of() : List.of(subject.category())));
					return erased;
				});

		// Logged at INFO because this is the one operation here that is irreversible, and because the
		// events record nothing about it -- the key store row and this line are the whole trail.
		STORE_LOGGER.info("erased data subject {} on storage '{}': {} key(s) shredded ({})",
				subject, eventStorage.name(), report.keysShredded(), reason);

		return report;
	}

	@Override
	public SubjectErasureReport erase ( String subjectType, String subjectId, ErasureReason reason ) {
		if ( subjectType == null || subjectType.isBlank() ) {
			throw new IllegalArgumentException("subjectType cannot be null or blank");
		}
		if ( subjectId == null || subjectId.isBlank() ) {
			throw new IllegalArgumentException("subjectId cannot be null or blank");
		}
		if ( reason == null ) {
			throw new IllegalArgumentException("reason cannot be null");
		}
		if ( shreddingCodec == null ) {
			throw new UnsupportedOperationException(
					"event store on storage '%s' has no ShreddingCodec configured, so it holds no keys to destroy; configure shredding on the storage builder or via EventStore.on(storage).shredding(codec)"
							.formatted(eventStorage.name()));
		}
		// Allowed on a closed store for the same reason eraseCategory is.
		SubjectErasureReport report = observed(
				new Observation.Erase(eventStorage.name(), subjectType, subjectId, Optional.empty(), reason),
				reporter -> {
					SubjectErasureReport erased = shreddingCodec.shredAllCategories(subjectType, subjectId, reason);
					reporter.completed(new Outcome.Erased(erased.keysShredded(), erased.categoriesErased()));
					return erased;
				});

		STORE_LOGGER.info("erased data subject {}/{} across categories {} on storage '{}': {} key(s) shredded ({})",
				subjectType, subjectId, report.categoriesErased(), eventStorage.name(), report.keysShredded(), reason);

		return report;
	}

	@Override
	public Optional<ShreddingAudit> shreddingAudit ( ) {
		// Like erase, deliberately available on a closed store: reporting on what has been erased touches
		// the key store rather than the events, and a compliance question does not stop being answerable
		// because a store handle was closed.
		return shreddingCodec == null ? Optional.empty() : shreddingCodec.audit();
	}

	@Override
	public <EVENT_TYPE> EventStream<EVENT_TYPE> getEventStream(EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> legacyEventRootClasses ) {

		if ( closed.get() ) {
			throw new EventStorageClosedException("event store on storage '%s' is closed".formatted(eventStorage.name()));
		}

		return new EventStreamImpl<EVENT_TYPE> ( eventStorage, eventStreamId, serdeFor(eventRootClasses, legacyEventRootClasses) );
	}

	/**
	 * Returns this store's serde for the given mappings, building it on first use.
	 * <p>
	 * See {@link #serdes} for why it is shared rather than built per call, and why sharing it is safe
	 * where sharing a stream would not be. A root class set that fails to register — a non-sealed
	 * interface, a duplicate event name, a {@code @LegacyEvent} without an upcaster, an upcaster naming
	 * a target this stream does not register — leaves nothing cached, so the same call fails the same
	 * way next time instead of the failure being remembered.
	 */
	private EventPayloadSerializerDeserializer serdeFor ( Set<Class<?>> eventRootClasses, Set<Class<?>> legacyEventRootClasses ) {
		if ( eventRootClasses == null || eventRootClasses.isEmpty() ) {
			// no type mappings, all event payloads will be String type
			return serdes.computeIfAbsent(new SerdeKey(Set.of(), Set.of()), key -> EventPayloadSerializerDeserializer.raw());
		}
		// use typed event payloads, mapped to Java objects
		return serdes.computeIfAbsent(new SerdeKey(eventRootClasses, legacyEventRootClasses), key -> {
			EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed(shreddingCodec);
			key.eventRootClasses().forEach(serde::registerEventTypes);
			key.legacyEventRootClasses().forEach(serde::registerLegacyEventTypes);
			// the upcasters are checked against each other only now: a target may sit in any of the roots
			return serde.validate();
		});
	}

	/**
	 * Runs one operation as an observation: started before it, reported as completed or failed, closed
	 * after it, all on the calling thread — the contract {@link EventStoreObserver} states.
	 * <p>
	 * The operation reports its own completion, since only it knows what it answered; this reports a
	 * failure for it when it throws without having completed. That is what lets an operation answer and
	 * still throw — an append at a moved boundary completes as {@link Outcome.Conflicted} and then throws
	 * the {@link OptimisticLockingException} the caller re-decides on — without that answer being reported
	 * a second time, as a failure.
	 * <p>
	 * Argument checks belong before this call, not inside it: a refused argument is not an operation that
	 * ran and failed, and reporting one would put caller bugs among the storage errors.
	 */
	private <O extends Outcome, R> R observed ( Observation<O> observation, Function<Reporter<O>, R> operation ) {
		Observation.Scope<O> scope = observer.start(observation);
		Reporter<O> reporter = new Reporter<>(scope);
		try {
			return operation.apply(reporter);
		} catch ( RuntimeException | Error e ) {
			if ( !reporter.completed ) {
				scope.failed(e);
			}
			throw e;
		} finally {
			scope.close();
		}
	}

	/**
	 * Whether anything is observing this store. The per-type counts an observation carries cost a map per
	 * operation, which a store nobody observes has no reason to build.
	 */
	private boolean observing ( ) {
		return observer != EventStoreObserver.NOOP;
	}

	/**
	 * How an operation reports what it answered, remembering that it did.
	 */
	private static final class Reporter<O extends Outcome> {

		private final Observation.Scope<O> scope;
		private boolean completed;

		private Reporter ( Observation.Scope<O> scope ) {
			this.scope = scope;
		}

		void completed ( O outcome ) {
			completed = true;
			scope.completed(outcome);
		}

	}

	private static Duration since ( long startNanos ) {
		return Duration.ofNanos(System.nanoTime() - startNanos);
	}

	private static <T> Map<EventType, Integer> countPerType ( List<T> items, Function<T, EventType> type ) {
		Map<EventType, Integer> counts = new HashMap<>();
		for ( T item : items ) {
			counts.merge(type.apply(item), 1, Integer::sum);
		}
		return counts;
	}

	class EventStreamImpl<EVENT_TYPE> implements EventStream<EVENT_TYPE>, EventStoreListener {

		private final Logger LOGGER = LoggerFactory.getLogger(EventStreamImpl.class);

		private final EventStorage eventStorage;
		private final EventStreamId eventStreamId;
		private final EventPayloadSerializerDeserializer serde;

		/**
		 * This stream as every observation of it names it. The purpose is the real one: bounding it is the
		 * concern of an observer turning it into a metrics tag, not of the store.
		 */
		private final StreamInfo info;

		/**
		 * The live subscriptions of this stream, one per {@code subscribe} call, each holding the listener it
		 * stands for (an append listener wrapped in its {@link OptimizingAppendListenerDecorator}). Read
		 * without a lock by the notification tasks, which is what the copy-on-write lists are for; written
		 * under {@link #subscriptionLock} only.
		 */
		private final List<StreamSubscription<AppendListener>> appendSubscriptions = new CopyOnWriteArrayList<>();
		private final List<StreamSubscription<BookmarkListener>> bookmarkSubscriptions = new CopyOnWriteArrayList<>();

		/**
		 * Guards the invariant that this stream is registered with the storage exactly while it has a live
		 * subscription. Every change to the subscription lists and to {@link #subscribedToStorage} is made
		 * under it, so a subscription closing as the last one and a new one arriving cannot interleave into
		 * a stream that holds a listener the storage never notifies, or a registration no listener needs.
		 */
		private final Object subscriptionLock = new Object();

		/**
		 * Whether this stream currently holds a listener registration with the storage. Flipped by
		 * {@link #subscribeToStorage()} and {@link #unsubscribeFromStorage()}, which are the only two places
		 * it changes, both under {@link #subscriptionLock}.
		 */
		private final AtomicBoolean subscribedToStorage = new AtomicBoolean();

		public EventStreamImpl ( EventStorage eventStorage, EventStreamId eventStreamId, EventPayloadSerializerDeserializer serde ) {
			this.eventStorage = eventStorage;
			this.eventStreamId = eventStreamId;
			this.serde = serde;

			this.info = new StreamInfo(eventStorage.name(), eventStreamId, serde.isTyped());
			observer.streamOpened(info);
		}
		
		/**
		 * Throws if the store this stream came from has been closed. A stream outliving its store would
		 * otherwise keep reading and writing — the storage may still be open, serving other stores —
		 * while silently receiving no notifications, which is exactly how a projection stalls unnoticed.
		 */
		private void checkStoreNotClosed ( ) {
			if ( closed.get() ) {
				throw new EventStorageClosedException("the event store this stream (%s) came from is closed".formatted(eventStreamId));
			}
		}

		@Override
		public EventStreamId id() {
			return eventStreamId;
		}

		/**
		 * Registers this stream with the storage, on the first live subscription and not before. Called
		 * under {@link #subscriptionLock}.
		 * <p>
		 * A stream that nobody subscribes to has nothing to do with a notification — {@link #notify} does
		 * no more than fan out to the subscription lists — so registering one would only lengthen the
		 * list the storage walks on every append, on the single thread that serves every store attached to
		 * it. Streams are handed out per operation and most of them only query and append, so that list
		 * would otherwise grow with traffic rather than with the number of things actually listening.
		 * <p>
		 * Deferring registration to here is also what makes the storage's strong reference safe: it holds
		 * exactly the streams somebody asked to be notified through, which are the streams that were
		 * supposed to stay alive anyway.
		 */
		private void subscribeToStorage ( ) {
			if ( !subscribedToStorage.compareAndSet(false, true) ) {
				return;
			}
			subscribedStreams.add(this);
			eventStorage.subscribe(this);
			if ( closed.get() ) {
				// the store was closed between this stream's checkStoreNotClosed and the registration
				// above, so close() has already walked its streams and will not come back for this one.
				// Undoing it here is what keeps that race from leaving a registration behind on a
				// storage that outlives the store
				close();
			}
		}

		/**
		 * Hands this stream's registration back to the storage, if it holds one. Called under
		 * {@link #subscriptionLock}, by whichever ends the last live subscription — a handle's
		 * {@link Subscription#close()} or the stream's own {@link #close()} — so that the storage holds
		 * the stream exactly while something is listening through it.
		 */
		private void unsubscribeFromStorage ( ) {
			if ( subscribedToStorage.compareAndSet(true, false) ) {
				eventStorage.unsubscribe(this);
				subscribedStreams.remove(this);
			}
		}

		/**
		 * Ends every subscription of this stream and hands its registration back to the storage.
		 * <p>
		 * Idempotent, and not terminal: the stream stays usable for querying, appending and bookmarking,
		 * and subscribing again re-registers it. See {@link org.sliceworkz.eventstore.stream.EventSource#close()}
		 * for why a stream is closable at all and why closing it is not the end of it.
		 * <p>
		 * The subscriptions are ended one by one — so every handle handed out reads inactive afterwards —
		 * and the lists are left empty, so that a listener cannot survive into a later subscription of the
		 * same stream and be notified twice. The registration is released explicitly as well, for the
		 * case where the store closed while a subscription was registering (see {@link #subscribeToStorage()}).
		 */
		@Override
		public void close ( ) {
			synchronized ( subscriptionLock ) {
				List.copyOf(appendSubscriptions).forEach(StreamSubscription::close);
				List.copyOf(bookmarkSubscriptions).forEach(StreamSubscription::close);
				unsubscribeFromStorage();
			}
		}

		@Override
		public Subscription subscribe ( AppendListener listener ) {
			if ( listener == null ) {
				throw new IllegalArgumentException("listener must not be null");
			}
			return subscribe(appendSubscriptions, new OptimizingAppendListenerDecorator(listener));
		}

		@Override
		public Subscription subscribe ( BookmarkListener listener ) {
			if ( listener == null ) {
				throw new IllegalArgumentException("listener must not be null");
			}
			return subscribe(bookmarkSubscriptions, listener);
		}

		/**
		 * Adds a subscription to one of the lists and registers this stream with the storage if it was
		 * not already. The subscription is in its list before the registration is made, so a store that
		 * closed in between finds it there and ends it, and the handle handed back reads inactive.
		 */
		private <LISTENER> Subscription subscribe ( List<StreamSubscription<LISTENER>> subscriptions, LISTENER listener ) {
			checkStoreNotClosed();
			StreamSubscription<LISTENER> subscription = new StreamSubscription<>(subscriptions, listener);
			synchronized ( subscriptionLock ) {
				subscriptions.add(subscription);
				// reported under the lock, before the registration, so that a store closing in between --
				// which ends this subscription at once -- reports it closed after it was reported opened
				observer.subscriptionOpened(info);
				subscribeToStorage();
			}
			return subscription;
		}

		/**
		 * One listener's subscription to this stream: the handle {@code subscribe} returns.
		 * <p>
		 * Closing it removes it from its list, so the next notification task does not see it, and releases
		 * the stream's registration when it was the last live subscription of either kind. Identity is the
		 * subscription object, never the listener, so the same listener subscribed twice gets two handles
		 * that each end their own subscription.
		 */
		private final class StreamSubscription<LISTENER> implements Subscription {

			private final List<StreamSubscription<LISTENER>> subscriptions;
			private final LISTENER listener;
			private volatile boolean active = true;

			private StreamSubscription ( List<StreamSubscription<LISTENER>> subscriptions, LISTENER listener ) {
				this.subscriptions = subscriptions;
				this.listener = listener;
			}

			@Override
			public void close ( ) {
				synchronized ( subscriptionLock ) {
					if ( !active ) {
						return;
					}
					active = false;
					subscriptions.remove(this);
					observer.subscriptionClosed(info);
					if ( appendSubscriptions.isEmpty() && bookmarkSubscriptions.isEmpty() ) {
						unsubscribeFromStorage();
					}
				}
			}

			@Override
			public boolean isActive ( ) {
				return active;
			}

		}

		@Override
		public List<Event<EVENT_TYPE>> query ( EventQuery query, EventReference cursor ) {
			return read(query, cursor).events();
		}

		@Override
		public EventPage<EVENT_TYPE> page ( EventQuery query, EventReference cursor ) {
			return read(query, cursor);
		}

		/**
		 * The read behind both {@link #query(EventQuery, EventReference)} and
		 * {@link #page(EventQuery, EventReference)}: one storage query, with the limit and the direction the
		 * query's own, and its result enriched in full.
		 * <p>
		 * A page is the same read as a query, with the stored events counted and the last of them kept
		 * before they are enriched: the stored list is what storage handed back, so its size and last
		 * element are known before a single payload is converted, and they stay right when the
		 * enrichment turns a stored event into several events or into none.
		 * <p>
		 * The observation spans the whole read, enrichment included, and its outcome carries the time
		 * spent inside the storage separately: the rest is deserializing, upcasting and unsealing, which
		 * on an ordinary page is most of the wait.
		 */
		private EventPage<EVENT_TYPE> read ( EventQuery query, EventReference cursor ) {
			checkStoreNotClosed();
			// widened before the observation starts: a filter naming a legacy type is refused here, as an
			// argument, not reported as a read that failed
			EventFilter storageFilter = includeLegacyEventTypes(query.filter());
			return observed(new Observation.Query(info, query.filter(), query.limit(), query.direction(), Optional.ofNullable(cursor)), reporter -> {
				long start = System.nanoTime();
				List<StoredEvent> storedEvents = eventStorage.query(storageFilter, eventStreamId, cursor, query.limit(), query.direction());
				Duration storageTime = since(start);
				List<Event<EVENT_TYPE>> events = enrichAfterQuery(storedEvents, query.filter(), query.direction());
				reporter.completed(new Outcome.Read(storageTime, storedEvents.size(),
						observing() ? countPerType(storedEvents, StoredEvent::type) : Map.of(), events.size()));
				Optional<EventReference> lastStored = storedEvents.isEmpty() ? Optional.empty() : Optional.of(storedEvents.getLast().reference());
				return new EventPage<>(events, storedEvents.size(), lastStored);
			});
		}

		/**
		 * Enriches what storage handed back: deserialized and upcast, then re-checked against the
		 * caller's own filter, since the storage query was widened to the legacy types that upcast into
		 * the ones asked for. Read in full here, so a stored event this stream cannot read fails the
		 * query or the page itself rather than whichever terminal operation a caller happens to write
		 * over the result.
		 */
		private List<Event<EVENT_TYPE>> enrichAfterQuery ( List<StoredEvent> storedEvents, EventFilter originalFilter, Direction direction ) {
			List<Event<EVENT_TYPE>> events = new ArrayList<>(storedEvents.size());
			for ( StoredEvent storedEvent : storedEvents ) {
				for ( Event<EVENT_TYPE> event : enrich(storedEvent, direction) ) {
					if ( originalFilter.matches(event) ) {
						events.add(event);
					}
				}
			}
			return Collections.unmodifiableList(events);
		}

		@SuppressWarnings("unchecked")
		private List<Event<EVENT_TYPE>> enrich ( StoredEvent storedEvent, Direction direction ) {
			List<TypeAndPayload> results;
			try {
				results = serde.deserialize(new TypeAndSerializedPayload(storedEvent.type(), storedEvent.payload()));
			} catch (EventDeserializationException e) {
				// The serde is handed a type and a JSON string, so it cannot say *which* stored event
				// failed -- and that is the one fact a caller needs to dead-letter or skip a poison event.
				// This is the only layer that knows both.
				throw e.withReference(storedEvent.reference());
			}
			// For backward queries, reverse the upcasted sub-events so they appear in descending order,
			// consistent with the overall backward traversal of stored events.
			if ( direction == Direction.BACKWARD ) {
				results = results.reversed();
			}
			EventReference baseRef = storedEvent.reference();
			List<TypeAndPayload> finalResults = results;
			return java.util.stream.IntStream.range(0, finalResults.size()).mapToObj(i -> {
				TypeAndPayload typeAndPayload = finalResults.get(i);
				EVENT_TYPE data = (EVENT_TYPE)typeAndPayload.eventData();
				// Each sub-event gets a unique reference via the index, distinguishing upcasted events
				// that originate from the same stored event. For single-event results, index is 0.
				EventReference ref = baseRef.withIndex(direction == Direction.BACKWARD ? finalResults.size() - 1 - i : i);
				return new Event<>(storedEvent.stream(), typeAndPayload.type(), storedEvent.type(), ref, data, storedEvent.tags(), storedEvent.timestamp());
			}).toList();
		}

		private EventToStore reduce ( EphemeralEvent<? extends EVENT_TYPE> event ) {
			TypeAndSerializedPayload data = serde.serialize(event.data());
			Tags tags = withShreddingKeyTags(event.tags(), data.shreddingKeys());
			return new EventToStore(eventStreamId, data.type(), data.immutablePayload(), tags, event.idempotencyKey());
		}

		/**
		 * Adds a {@code dek:} tag for every key the payload was sealed under.
		 * <p>
		 * This is what makes "every event holding data protected by this key" an ordinary tag query,
		 * served by the index the store already maintains, with no extra column. Adding tags is safe for
		 * every existing query: tag matching is containment, so an extra tag can never stop an event
		 * matching a filter that matched before.
		 * <p>
		 * The key ids are pseudonymous and random — see {@link KeyId} — so this leaves nothing
		 * re-identifiable behind once the key is destroyed.
		 */
		private Tags withShreddingKeyTags ( Tags tags, java.util.Set<KeyId> shreddingKeys ) {
			if ( shreddingKeys.isEmpty() ) {
				return tags;
			}
			return tags.merge(Tags.of(shreddingKeys.stream()
					.map(keyId -> Tag.of(KeyId.TAG_KEY, keyId.value()))
					.toArray(Tag[]::new)));
		}

		private List<EventToStore> reduce ( List<? extends EphemeralEvent<? extends EVENT_TYPE>> events ) {
			return events.stream().map(this::reduce).toList();
		}

		@Override
		public List<Event<EVENT_TYPE>> append(AppendCriteria appendCriteria, List<? extends EphemeralEvent<? extends EVENT_TYPE>> events) {
			checkStoreNotClosed();

			// A wildcard stream is a source. An event is stored in exactly one stream, and a wildcard
			// names none -- see EventSink.append for why this is not an append with a target argument.
			if ( eventStreamId.isAnyContext() || eventStreamId.isAnyPurpose() ) {
				throw new IllegalArgumentException("cannot append to non-specific eventstream %s".formatted(eventStreamId));
			}
			
			List<String> unAppendable = events.stream().map(e->e.type().name()).filter(t->!serde.canDeserialize(t)).toList();
			if ( !unAppendable.isEmpty() ) {
				throw new IllegalArgumentException("cannot append event type '%s' via this stream".formatted(unAppendable.getFirst()));
			}
			
			if ( events.size() == 0 ) {
				return Collections.emptyList();
			}
			
			// Idempotency keys are per event and must be distinct within the batch. Checked here rather
			// than left to storage because of what storage would otherwise do with a repeated key: the
			// stream-scoped unique index rejects the second row of the batch, and the append path reads
			// that violation as "this key was appended before" -- so the first ever attempt at such a
			// batch would store nothing and report a successful de-duplication. See EventSink.append.
			rejectRepeatedIdempotencyKeys(events);

			// The boundary is checked over stored type names, exactly as a query is answered: a legacy
			// event that upcasts into a type of the boundary is a new relevant fact for it, so the
			// criteria storage sees carries those legacy names too. The caller's own criteria is kept
			// for the exception, which names the boundary the caller decided on.
			AppendCriteria storageCriteria = includeLegacyEventTypes(appendCriteria);

			int idempotencyKeys = (int) events.stream().filter(e -> e.idempotencyKey() != null).count();
			Observation.Append observation = new Observation.Append(info,
					observing() ? countPerType(events, EphemeralEvent::type) : Map.of(), !appendCriteria.isNone(), idempotencyKeys);

			// append events to the eventstore (with optimistic locking)
			List<Event<EVENT_TYPE>> appendedEvents = observed(observation, reporter -> {
				List<EventToStore> eventsToStore = reduce(events);
				long start = System.nanoTime();
				List<StoredEvent> storedEvents;
				try {
					storedEvents = eventStorage.append(storageCriteria, eventStreamId, eventsToStore);
				} catch (OptimisticLockingException optimisticLockingException) {
					// the DCB answer to a stale decision: reported as what the append answered, and then
					// thrown, since the caller re-decides on it
					reporter.completed(new Outcome.Conflicted(since(start), appendCriteria.eventFilter(), appendCriteria.expectedLastEventReference()));
					throw namingTheCallersBoundary(optimisticLockingException, appendCriteria, storageCriteria);
				}
				Duration storageTime = since(start);

				// A batch is stored whole or not at all, and storage swallows it whole only as a retry: when
				// every idempotency key in it was stored before (a batch mixing stored and new keys is refused
				// with IdempotencyKeyConflictException). So an empty result for a non-empty batch is a retry,
				// and there is no partly de-duplicated answer to report.
				if ( storedEvents.isEmpty() ) {
					reporter.completed(new Outcome.Duplicated(storageTime, events.size()));
					return List.<Event<EVENT_TYPE>>of();
				}

				// Enriched before the completion is reported: an event that serializes but cannot be read back
				// fails the append here, with the event already stored, and that failure is what the caller
				// receives -- so it is what the observation reports.
				List<Event<EVENT_TYPE>> enriched = storedEvents.stream().flatMap(se->enrich(se, Direction.FORWARD).stream()).toList();
				reporter.completed(new Outcome.Appended(storageTime, storedEvents.size(),
						observing() ? countPerType(storedEvents, StoredEvent::type) : Map.of(), storedEvents.getLast().reference()));
				return enriched;
			});

			// The appended events -- typed, with their assigned references -- are handed straight back to
			// the caller, which is the whole of this store's read-your-own-writes story: code reacting to
			// an append on the appending thread is code that caller writes after this call returns.
			// Subscribers hear about it through the storage notification, on a thread of their own.
			return appendedEvents;
		}

		private static void rejectRepeatedIdempotencyKeys ( List<? extends EphemeralEvent<?>> events ) {
			Set<String> keys = new HashSet<>();
			for ( EphemeralEvent<?> event : events ) {
				if ( event.idempotencyKey() != null && !keys.add(event.idempotencyKey()) ) {
					throw new IllegalArgumentException("idempotency key '%s' is carried by more than one event of the batch".formatted(event.idempotencyKey()));
				}
			}
		}

		/**
		 * Traces back all current event types to their legacy ones, so a full query is done on older and newer ones
		 */

		/**
		 * The same trace-back for a consistency boundary: a legacy event that upcasts into a type of the
		 * boundary is a new relevant fact for it, exactly as a query for that type returns it, so the
		 * check storage runs has to count it. Without this the two would disagree on the same filter --
		 * a decision read through the query path sees the legacy event and the lock check admitting the
		 * append does not.
		 */
		private AppendCriteria includeLegacyEventTypes ( AppendCriteria criteria ) {
			if ( criteria.eventFilter().isMatchAll() ) {
				return criteria; // nothing to modify, and the caller's own criteria is what storage's exception then names
			}
			return new AppendCriteria(includeLegacyEventTypes(criteria.eventFilter()), criteria.expectedLastEventReference());
		}

		/**
		 * The same trace-back for a filter, which is what storage is asked with: a query for a current
		 * type has to fetch the legacy events that upcast into it.
		 */
		private EventFilter includeLegacyEventTypes ( EventFilter filter ) {
			if ( filter.isMatchAll() ) {
				return filter; // match-all has no items to trace back
			}
			return new EventFilter(filter.items().stream().map(this::includeLegacyEventTypes).toList(), filter.until());
		}

		/**
		 * The exception reports the boundary the caller decided on, not the stored names it was checked
		 * with: a caller comparing {@code getFilter()} against its own criteria -- the testing fixture's
		 * {@code OptimisticLockingFailure} does -- should not find legacy names it never wrote. Storage's
		 * own exception is kept as the cause. Where the trace-back changed nothing, which is every stream
		 * without legacy types, storage's exception is what the caller gets, untouched.
		 */
		private OptimisticLockingException namingTheCallersBoundary ( OptimisticLockingException fromStorage, AppendCriteria callers, AppendCriteria storages ) {
			if ( callers.eventFilter().equals(storages.eventFilter()) ) {
				return fromStorage;
			}
			OptimisticLockingException named = new OptimisticLockingException(callers.eventFilter(), callers.expectedLastEventReference());
			named.initCause(fromStorage);
			return named;
		}

		private EventFilterItem includeLegacyEventTypes ( EventFilterItem queryItem ) {
			return new EventFilterItem(includeLegacyEventTypes(queryItem.eventTypes()), queryItem.tags());
		}

		private EventTypesFilter includeLegacyEventTypes ( EventTypesFilter typesFilter ) {
			rejectLegacyEventTypes(typesFilter.eventTypes());
			return EventTypesFilter.of(serde.determineLegacyTypes(typesFilter.eventTypes()));
		}

		/**
		 * A filter on a typed stream names current types. A legacy type in it is refused, for a query
		 * and for a consistency boundary alike, because neither can be answered: storage would fetch
		 * the legacy events, the read would upcast them into their current types, and the filter,
		 * re-applied to what was read, would drop every one of them for not being the type it names --
		 * a query returning nothing, while the same filter as a boundary, checked over stored names,
		 * counts the very events the query cannot return. Mapping the name forward instead would
		 * answer a question the caller did not ask: a filter over the legacy type would return every
		 * event of the current type, the ones never stored under the legacy name included. Refused
		 * here, in the one place both paths pass through, so they cannot come to disagree again.
		 */
		private void rejectLegacyEventTypes ( Set<EventType> eventTypes ) {
			Map<EventType, Set<EventType>> legacy = serde.legacyTypesAmong(eventTypes);
			if ( legacy.isEmpty() ) {
				return;
			}
			String named = legacy.keySet().stream()
					.sorted(Comparator.comparing(EventType::name))
					.map(type -> legacy.get(type).isEmpty()
							? "'%s' (a legacy type upcasting into no current type, so no query on this stream can return it and no boundary can count it)".formatted(type.name())
							: "'%s' (a legacy type, read as %s)".formatted(type.name(), legacy.get(type).stream().map(t -> "'" + t.name() + "'").sorted().collect(Collectors.joining(", "))))
					.collect(Collectors.joining(", "));
			throw new IllegalArgumentException(
					"a query or a consistency boundary on this stream names the current event types, and it returns and counts the legacy events that upcast into them; it cannot name a legacy type: %s"
							.formatted(named));
		}

		@Override
		public void notify(AppendsToEventStoreNotification newEventsInStore) {
			// close() unregisters this stream, but a notification already being dispatched can still land
			// here afterwards -- unsubscribe only promises no *new* dispatches. Dropping it is what keeps
			// a closed store from poisoning the storage it shares: the executors are gone by then, and on
			// Postgres the caller is the LISTEN/NOTIFY monitor thread every store on the storage depends on.
			if ( closed.get() ) {
				return;
			}
			// if the events are in the logical stream we care about...
			if ( newEventsInStore.isRelevantFor(eventStreamId) ) {
				LOGGER.debug("Must asynchronously notify {} append listeners of stream {} about append up until at least {}", appendSubscriptions.size(), eventStreamId, newEventsInStore.atLeastUntil());

				// schedule for execution on different thread to notify/interrupt any waiting eventual consistent processors
				submitOrDropIfClosed(executorServiceForEventAppends, ( ) -> {
						LOGGER.debug("Notifying {} append listeners of stream {} about append up until at least {}", appendSubscriptions.size(), eventStreamId, newEventsInStore.atLeastUntil());
						// the isActive check narrows the window in which a subscription closed during this
						// task is still delivered to; it cannot close it, and the contract does not promise that
						appendSubscriptions.stream().filter(StreamSubscription::isActive).forEach(s -> notifyQuietly(s.listener, newEventsInStore.atLeastUntil()));
				});
			}
		}

		@Override
		public void notify(BookmarkPlacedNotification bookmarkPlaced) {
			if ( closed.get() ) {
				return; // see notify(AppendsToEventStoreNotification)
			}
			LOGGER.debug("Must asynchronously notify {} bookmark listeners on {} of update for {} to {}", bookmarkSubscriptions.size(), eventStreamId, bookmarkPlaced.reader(), bookmarkPlaced.bookmark());

			// schedule for execution on different thread to notify/interrupt any waiting eventual consistent processors
			submitOrDropIfClosed(executorServiceForBookmarkUpdates, ( ) -> {
					LOGGER.debug("Notifying {} bookmark listeners on {} of update for {} to {}", bookmarkSubscriptions.size(), eventStreamId, bookmarkPlaced.reader(), bookmarkPlaced.bookmark());
					bookmarkSubscriptions.stream().filter(StreamSubscription::isActive).forEach(s -> notifyQuietly(s.listener, bookmarkPlaced));
			});
		}

		/**
		 * Notifies one eventually consistent append subscriber, containing its failure.
		 * <p>
		 * Uncontained, the throwable does not merely end that subscriber's notification: it ends the whole
		 * task, so every subscriber after it in the list misses this append too, and it surfaces on the
		 * notification thread's uncaught-exception handler — a bare stack trace on {@code System.err}, at no
		 * level, under no logger name, attributed to nothing. A {@link org.sliceworkz.eventstore.projection.Projector}
		 * subscribed here is the ordinary case: its {@code eventsAppended} runs the projection, and a
		 * projection that throws throws out of here, so the projection stops advancing with nothing in the
		 * application's own logs to say why.
		 * <p>
		 * Logging it and moving on matches what the storage backends already do with their own listeners
		 * ({@code notifyQuietly} in the in-memory backends, and the Postgres LISTEN/NOTIFY monitors): a
		 * listener's failure is never anybody else's failure, and never silent.
		 */
		private void notifyQuietly ( AppendListener subscriber, EventReference atLeastUntil ) {
			try {
				subscriber.eventsAppended(atLeastUntil);
			} catch ( Exception e ) {
				// through the decorator every subscriber is wrapped in, so the name is one the caller recognises
				AppendListener subscribed =
					( subscriber instanceof OptimizingAppendListenerDecorator decorator ) ? decorator.delegate() : subscriber;
				LOGGER.error("eventually consistent append listener {} failed handling the append notification up until at least {} on stream {}: {}",
						subscribed.getClass().getName(), atLeastUntil, eventStreamId, e.getMessage(), e);
			}
		}

		/**
		 * Notifies one bookmark subscriber, containing its failure — see
		 * {@link #notifyQuietly(AppendListener, EventReference)}, which this
		 * exists for the same reasons. The bookmark executor is single-threaded, so an escaping throwable
		 * additionally costs a thread, replaced by the pool.
		 */
		private void notifyQuietly ( BookmarkListener subscriber, BookmarkPlacedNotification bookmarkPlaced ) {
			try {
				subscriber.bookmarkUpdated(bookmarkPlaced.reader(), bookmarkPlaced.bookmark());
			} catch ( Exception e ) {
				LOGGER.error("bookmark listener {} failed handling the bookmark update for reader {} to {} on stream {}: {}",
						subscriber.getClass().getName(), bookmarkPlaced.reader(), bookmarkPlaced.bookmark(), eventStreamId, e.getMessage(), e);
			}
		}

		/**
		 * Hands a notification to an executor, dropping it if this store was closed in the meantime.
		 * <p>
		 * The {@code closed} check in the callers is only an early out: it cannot close the window, since
		 * {@link #close()} can shut the executors down between that check and this submit. Whoever is
		 * notifying must not see the rejection either way — on Postgres that is the LISTEN/NOTIFY monitor
		 * thread, shared by every store on the storage, and an exception escaping it kills notifications
		 * for all of them. Dropping the notification is exactly what closing the store asked for.
		 */
		private void submitOrDropIfClosed ( ExecutorService executorService, Runnable notification ) {
			try {
				executorService.execute(notification);
			} catch ( RejectedExecutionException closedWhileNotifying ) {
				LOGGER.debug("event store closed while notifying stream {}; notification dropped", eventStreamId);
			}
		}

		@Override
		public void placeBookmark(String reader, EventReference reference, Tags tags) {
			checkStoreNotClosed();
			requireReader(reader);
			observed(new Observation.PlaceBookmark(info, reader, reference), reporter -> {
				long start = System.nanoTime();
				eventStorage.bookmark(reader, reference, tags);
				reporter.completed(new Outcome.Done(since(start)));
				return null;
			});
		}

		@Override
		public Optional<EventReference> removeBookmark(String reader) {
			checkStoreNotClosed();
			Optional<EventReference> result = getBookmark(reader);
			if ( result.isPresent() ) {
				eventStorage.removeBookmark(reader);
			}
			return result;
		}

		@Override
		public Optional<EventReference> getBookmark(String reader) {
			checkStoreNotClosed();
			requireReader(reader);
			return observed(new Observation.GetBookmark(info, reader), reporter -> {
				long start = System.nanoTime();
				Optional<EventReference> bookmark = eventStorage.getBookmark(reader);
				reporter.completed(new Outcome.Found(since(start), bookmark.isPresent()));
				return bookmark;
			});
		}

		/**
		 * Rejects a null reader name here, rather than letting one reach a backend.
		 * <p>
		 * A reader name is the whole identity of a bookmark, so a null one is a programming error in
		 * every case. What it is not is a defined one: the backends disagree about it. The in-memory
		 * stores keep bookmarks in a {@link java.util.HashMap}, which accepts a null key happily and
		 * stores a bookmark nobody can name; PostgreSQL has {@code reader TEXT PRIMARY KEY}, so a null
		 * reaches the database and comes back as a not-null violation on a place, and as a silent
		 * no-op on a remove and an empty {@code Optional} on a get, because {@code WHERE reader = NULL}
		 * matches nothing.
		 * <p>
		 * Two of those paths used to be guarded accidentally, by a {@code reader.toString()} on a value
		 * already typed {@code String}. Removing those calls (they convert nothing) would have taken the
		 * guard with them, so the check is made explicit and uniform instead — one place, same answer on
		 * every backend, and it names the parameter.
		 */
		private void requireReader ( String reader ) {
			Objects.requireNonNull(reader, "reader must not be null");
		}

		@Override
		public List<Bookmark> getBookmarks() {
			checkStoreNotClosed();
			return observed(new Observation.ListBookmarks(info), reporter -> {
				long start = System.nanoTime();
				List<Bookmark> bookmarks = eventStorage.getBookmarks();
				reporter.completed(new Outcome.Counted(since(start), bookmarks.size()));
				return bookmarks;
			});
		}

		@Override
		public Optional<List<Event<EVENT_TYPE>>> getEventById(EventId eventId) {
			checkStoreNotClosed();
			return observed(new Observation.GetEvent(info, eventId), reporter -> {
				long start = System.nanoTime();
				// an event stored in a stream this one does not read across is absent here, as it is from a
				// query; a stored event this stream holds is present whatever it upcasts into, an empty list
				// included -- the two levels are the contract, so nothing collapses them
				Optional<StoredEvent> stored = eventStorage.getEventById(eventId).filter(e->eventStreamId.covers(e.stream()));
				Duration storageTime = since(start);
				Optional<List<Event<EVENT_TYPE>>> events = stored.map(e->enrich(e, Direction.FORWARD));
				reporter.completed(new Outcome.Found(storageTime, stored.isPresent()));
				return events;
			});
		}

		@Override
		public Optional<EventReference> head ( ) {
			checkStoreNotClosed();
			// straight to the storage: the head is a stored event's reference and nothing about it goes
			// through this stream's mappings -- no legacy-type widening, no upcasting, no decryption --
			// which is what lets it be answered for a head this stream could not read. Reported as its own
			// observation rather than as a read: a head lookup is the pin of a consistency boundary, and a
			// dashboard should tell pins from reads
			return observed(new Observation.Head(info), reporter -> {
				long start = System.nanoTime();
				Optional<EventReference> head = eventStorage.head(eventStreamId);
				reporter.completed(new Outcome.HeadRead(since(start), head));
				return head;
			});
		}

		/**
		 * The store's observer and this stream: what a {@link org.sliceworkz.eventstore.projection.Projector}
		 * reading from this stream reports its batches to. Answered for an unobserved store too, with
		 * {@link EventStoreObserver#NOOP}, which costs a projector nothing.
		 */
		@Override
		public Optional<StreamObservation> observation ( ) {
			return Optional.of(new StreamObservation(observer, info));
		}

	}

}
