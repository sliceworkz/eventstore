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

import java.util.Collections;
import java.util.List;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.Banner;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.MeterOptions;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndPayload;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndSerializedPayload;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventFilterItem;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.spi.EventStorage.AppendsToEventStoreNotification;
import org.sliceworkz.eventstore.spi.EventStorage.BookmarkPlacedNotification;
import org.sliceworkz.eventstore.spi.EventStorage.EventStoreListener;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentBookmarkListener;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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
 *   <li>Managing consistent and eventually consistent event notifications to subscribers</li>
 *   <li>Supporting both typed (Java objects) and raw (JSON) event payload modes</li>
 *   <li>Handling optimistic locking and DCB (Dynamic Consistency Boundary) compliance</li>
 * </ul>
 * <p>
 * This class is instantiated via the {@link EventStoreFactoryImpl} using Java's ServiceLoader mechanism.
 * Users should obtain EventStore instances through {@link org.sliceworkz.eventstore.EventStoreFactory#get()}.
 * <p>
 * The implementation uses virtual threads for asynchronous notification of eventually consistent subscribers,
 * ensuring efficient handling of concurrent event processing without blocking the main append operations.
 *
 * <h2>Event Payload Modes:</h2>
 * <ul>
 *   <li><b>Typed Mode:</b> Events are serialized to/from Java objects using Jackson. Requires event root classes
 *       to be registered with the stream. Supports sealed interfaces, upcasting via {@link org.sliceworkz.eventstore.events.LegacyEvent},
 *       and GDPR compliance through {@link org.sliceworkz.eventstore.events.Erasable} annotations.</li>
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
	 * The Micrometer meter registry for collecting metrics and observability data.
	 * Used to track event store operations such as event stream creation, appends, and queries.
	 */
	private final MeterRegistry meterRegistry;

	/**
	 * How much detail this store's meters may carry — in practice, how many distinct {@code purpose}
	 * tag values it will report before pooling the rest. See {@link #purposeTagValueFor}.
	 */
	private final MeterOptions meterOptions;

	/**
	 * The {@code purpose} values this store has admitted as their own tag value, and how many there
	 * are. Every purpose beyond {@link MeterOptions#maxPurposeTagValues()} is reported as
	 * {@link MeterOptions#OVERFLOW_PURPOSE_TAG_VALUE} instead — see {@link #purposeTagValueFor}.
	 * <p>
	 * The count is held separately rather than read off the set because the admission decision has to
	 * be made against a value that cannot be observed mid-update: {@code size()} on a
	 * {@code ConcurrentHashMap}-backed set is an estimate under concurrent writes, and an estimate
	 * that reads low is a cap that does not hold. Rejected purposes are deliberately not remembered —
	 * memoising them would grow a map with exactly the cardinality this is here to bound.
	 */
	private final Set<String> admittedPurposeTagValues = ConcurrentHashMap.newKeySet();
	private final AtomicInteger admittedPurposeTagValueCount = new AtomicInteger();

	/**
	 * Whether this store has already said that it hit the purpose cap. The warning is worth logging
	 * once — it means a dimension of the metrics is now pooled — and worth logging only once, since
	 * the store reaches this path on every stream it hands out afterwards.
	 */
	private final AtomicBoolean purposeCardinalityWarningLogged = new AtomicBoolean();

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
	 * reflectively, instantiating an {@code Upcast} per {@code @LegacyEvent}. Building one costs
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
	 * The highest appended event position per meter tag set, one holder shared by every stream that
	 * meters under those tags.
	 * <p>
	 * This exists because {@code sliceworkz.eventstore.append.position} is a gauge, and a gauge cannot
	 * be re-registered: Micrometer keeps the first registration for a given name and tags and ignores
	 * every later one. A per-stream holder therefore left only the very first stream's gauge live, and
	 * — since Micrometer references gauge state weakly — the series went permanently {@code NaN} as
	 * soon as that one stream was collected, which in the documented per-operation usage is almost
	 * immediately. Keeping the holder here, and registering the gauge exactly once against it, makes
	 * every stream sharing those tags report into the series that is actually being observed.
	 * <p>
	 * It is keyed on the tags <em>after</em> {@link #purposeTagValueFor} has bounded them, which is what
	 * keeps this map bounded too. That matters beyond tidiness: a caller who filters these meters away
	 * with a Micrometer {@code MeterFilter} cannot reach this map — a filter runs at registration, and
	 * this is keyed on the tags the store asked for — so with an unbounded purpose it would go on
	 * growing behind a registry holding no meters at all, at roughly 730 bytes per distinct purpose.
	 */
	private final ConcurrentHashMap<io.micrometer.core.instrument.Tags, AtomicLong> highestAppendedPositions = new ConcurrentHashMap<>();

	/**
	 * Identifies a payload serializer by the mappings it was built from, which is everything that
	 * distinguishes one from another.
	 * <p>
	 * The {@link EventStreamId} deliberately plays no part: the same stream can be opened with
	 * different event root classes, and two streams sharing a mapping can share a serde whatever their
	 * ids. The sets are copied on the way in, so a caller mutating the set it passed cannot corrupt the
	 * key of an already-cached entry.
	 */
	private record SerdeKey ( Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses ) {
		SerdeKey {
			eventRootClasses = Set.copyOf(eventRootClasses);
			historicalEventRootClasses = Set.copyOf(historicalEventRootClasses);
		}
	}

	/**
	 * How long {@link #close()} waits for each notification executor to finish before logging and
	 * moving on. The tasks are short-lived listener callbacks, so this only covers a listener that
	 * ignores interruption.
	 */
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;


	/**
	 * Constructs a new EventStoreImpl instance backed by the specified storage with observability support.
	 * <p>
	 * This constructor is invoked by {@link EventStoreFactoryImpl} and should not be called directly.
	 * The constructor initializes a single-threaded executor using virtual threads for handling
	 * eventually consistent event notifications without blocking append operations.
	 * <p>
	 * The meter registry is used to collect metrics about event store operations including:
	 * <ul>
	 *   <li>Event stream creation counts (tagged by context, purpose, and whether typed or raw)</li>
	 *   <li>Event append operations</li>
	 *   <li>Query performance</li>
	 * </ul>
	 * The {@code purpose} tag is capped at {@link MeterOptions#DEFAULT_MAX_PURPOSE_TAG_VALUES} distinct
	 * values by this constructor; use the three-argument one to change that.
	 *
	 * @param eventStorage the storage backend implementation (in-memory, PostgreSQL, etc.)
	 * @param meterRegistry the Micrometer meter registry for collecting metrics; use {@link io.micrometer.core.instrument.Metrics#globalRegistry} if unsure
	 * @throws IllegalArgumentException if eventStorage or meterRegistry is null
	 */
	protected EventStoreImpl ( EventStorage eventStorage, MeterRegistry meterRegistry ) {
		this(eventStorage, meterRegistry, MeterOptions.defaults());
	}

	/**
	 * Constructs a new EventStoreImpl with explicit control over how much detail its meters carry.
	 * <p>
	 * See {@link MeterOptions} for what the meters cost per distinct {@code purpose} and why they are
	 * capped by default. The two-argument constructor applies {@link MeterOptions#defaults()}.
	 *
	 * @param eventStorage the storage backend implementation (in-memory, PostgreSQL, etc.)
	 * @param meterRegistry the Micrometer meter registry for collecting metrics; use {@link io.micrometer.core.instrument.Metrics#globalRegistry} if unsure
	 * @param meterOptions how much detail this store's meters may carry
	 * @throws IllegalArgumentException if eventStorage, meterRegistry or meterOptions is null
	 */
	protected EventStoreImpl ( EventStorage eventStorage, MeterRegistry meterRegistry, MeterOptions meterOptions ) {
		if ( eventStorage == null ) {
			throw new IllegalArgumentException("eventStorage cannot be null");
		}
		if ( meterRegistry == null ) {
			throw new IllegalArgumentException("meterRegistry cannot be null.  Consider using Metrics.globalRegistry as a no-op fallback");
		}
		if ( meterOptions == null ) {
			throw new IllegalArgumentException("meterOptions cannot be null.  Use MeterOptions.defaults() for the default behaviour");
		}
		this.eventStorage = eventStorage;
		this.meterRegistry = meterRegistry;
		this.meterOptions = meterOptions;

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
	public <EVENT_TYPE> EventStream<EVENT_TYPE> getEventStream(EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses ) {

		if ( closed.get() ) {
			throw new EventStorageClosedException("event store on storage '%s' is closed".formatted(eventStorage.name()));
		}

		return new EventStreamImpl<EVENT_TYPE> ( eventStorage, eventStreamId, serdeFor(eventRootClasses, historicalEventRootClasses) );
	}

	/**
	 * Returns this store's serde for the given mappings, building it on first use.
	 * <p>
	 * See {@link #serdes} for why it is shared rather than built per call, and why sharing it is safe
	 * where sharing a stream would not be. A root class set that fails to register — a non-sealed
	 * interface, a duplicate event name, a {@code @LegacyEvent} without an upcaster — leaves nothing
	 * cached, so the same call fails the same way next time instead of the failure being remembered.
	 */
	private EventPayloadSerializerDeserializer serdeFor ( Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses ) {
		if ( eventRootClasses == null || eventRootClasses.isEmpty() ) {
			// no type mappings, all event payloads will be String type
			return serdes.computeIfAbsent(new SerdeKey(Set.of(), Set.of()), key -> EventPayloadSerializerDeserializer.raw());
		}
		// use typed event payloads, mapped to Java objects
		return serdes.computeIfAbsent(new SerdeKey(eventRootClasses, historicalEventRootClasses), key -> {
			EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed();
			key.eventRootClasses().forEach(serde::registerEventTypes);
			key.historicalEventRootClasses().forEach(serde::registerLegacyEventTypes);
			return serde;
		});
	}

	/**
	 * Returns the holder backing {@code sliceworkz.eventstore.append.position} for the given tags,
	 * registering the gauge against it the first time those tags are seen.
	 * <p>
	 * See {@link #highestAppendedPositions} for why the holder outlives the stream that asks for it.
	 * The gauge is registered with a strong reference so that it survives even if this map is ever
	 * given a weaker retention policy, and reads {@code NaN} until something is actually appended,
	 * which is what {@link Long#MIN_VALUE} stands in for.
	 */
	private AtomicLong highestAppendedPositionFor ( io.micrometer.core.instrument.Tags baseTags ) {
		return highestAppendedPositions.computeIfAbsent(baseTags, tags -> {
			AtomicLong holder = new AtomicLong(Long.MIN_VALUE);
			Gauge.builder("sliceworkz.eventstore.append.position", holder,
						h -> { long value = h.get(); return value == Long.MIN_VALUE ? Double.NaN : (double) value; })
				.tags(tags)
				.strongReference(true)
				.register(meterRegistry);
			return holder;
		});
	}

	/**
	 * Returns the value to put in the {@code purpose} meter tag for a stream: the purpose itself while
	 * this store is still under its cap of distinct purposes, and
	 * {@link MeterOptions#OVERFLOW_PURPOSE_TAG_VALUE} once it is not.
	 * <p>
	 * This is the one place the cap is applied, and everything tagged downstream inherits it: the
	 * per-stream counters and timers, the {@code eventtype} cross product on {@code query.event} and
	 * {@code append.event}, and {@link #highestAppendedPositions}. See {@link MeterOptions} for what an
	 * uncapped purpose costs and why the default is not "whatever the application produces".
	 * <p>
	 * Admission is first-come-first-served and permanent — a purpose that got a tag value keeps it for
	 * the life of the store — so the series a dashboard is built on do not come and go. A purpose that
	 * did not get one is <em>not</em> recorded anywhere, which is what makes this bounded: remembering
	 * the rejections would cost exactly the cardinality being avoided. The price is that the check runs
	 * per stream handle rather than once per purpose, but it is a set lookup on a path that already
	 * resolves a dozen meters.
	 */
	private String purposeTagValueFor ( String purpose ) {
		int max = meterOptions.maxPurposeTagValues();
		if ( max == 0 ) {
			return MeterOptions.OVERFLOW_PURPOSE_TAG_VALUE;
		}
		if ( admittedPurposeTagValues.contains(purpose) ) {
			return purpose;
		}
		// Claim a slot before adding, so that concurrent first-time purposes cannot both see room and
		// push the store over its cap. Losing the race to add means another thread admitted the same
		// purpose meanwhile, and the slot this thread claimed goes back.
		while ( true ) {
			int admitted = admittedPurposeTagValueCount.get();
			if ( admitted >= max ) {
				warnAboutPurposeCardinality(purpose, max);
				return MeterOptions.OVERFLOW_PURPOSE_TAG_VALUE;
			}
			if ( admittedPurposeTagValueCount.compareAndSet(admitted, admitted + 1) ) {
				break;
			}
		}
		if ( !admittedPurposeTagValues.add(purpose) ) {
			admittedPurposeTagValueCount.decrementAndGet();
		}
		return purpose;
	}

	/**
	 * Says once, on the first purpose that has to be pooled, that this store's meters are no longer
	 * broken down by purpose. Names the purpose that tripped it, so the value itself shows whether this
	 * is a purpose used as an entity id (the usual cause) or a cap set too low.
	 */
	private void warnAboutPurposeCardinality ( String purpose, int max ) {
		if ( purposeCardinalityWarningLogged.compareAndSet(false, true) ) {
			STORE_LOGGER.warn(
				"event store on storage '{}' has now seen {} distinct stream purposes, its configured maximum; "
				+ "meters for further purposes -- starting with '{}' -- are tagged purpose='{}' instead. "
				+ "This keeps the number of meters bounded: every distinct purpose costs ~15 meters and ~5.5KB of heap that no registry ever reclaims. "
				+ "If purpose is an entity id here, pass MeterOptions.withoutPurposeBreakdown(); if this is a genuinely broad but bounded set, raise MeterOptions.withMaxPurposeTagValues(int)",
				eventStorage.name(), max, purpose, MeterOptions.OVERFLOW_PURPOSE_TAG_VALUE);
		}
	}

	class EventStreamImpl<EVENT_TYPE> implements EventStream<EVENT_TYPE>, EventStoreListener {

		private final Logger LOGGER = LoggerFactory.getLogger(EventStreamImpl.class);

		private final EventStorage eventStorage;
		private final EventStreamId eventStreamId;
		private final EventPayloadSerializerDeserializer serde;

		private Counter meterAppend;
		private Counter meterAppendOptimisticLock;
		private Counter meterQuery;
		private Counter meterGetEvent;
		private Counter meterBookmarkPlace;
		private Counter meterBookmarkGet;
		private Counter meterBookmarkList;
		private Timer timerQuery;
		private Timer timerAppend;

		private final io.micrometer.core.instrument.Tags baseTags;

		/**
		 * Backs {@code sliceworkz.eventstore.append.position}. Owned by the store and shared with every
		 * other stream metering under the same tags — see {@link EventStoreImpl#highestAppendedPositions}.
		 */
		private final AtomicLong gaugeHighestEventPosition;

		private final List<EventStreamEventuallyConsistentAppendListener> eventuallyConsistentSubscribers = new CopyOnWriteArrayList<>();
		private final List<EventStreamConsistentAppendListener<EVENT_TYPE>> consistentSubscribers = new CopyOnWriteArrayList<>();
		private final List<EventStreamEventuallyConsistentBookmarkListener> bookmarkSubscribers = new CopyOnWriteArrayList<>();

		/**
		 * Whether this stream currently holds a listener registration with the storage. Flipped by
		 * {@link #subscribeToStorage()} and {@link #close()}, which are the only two places it changes.
		 */
		private final AtomicBoolean subscribedToStorage = new AtomicBoolean();

		public EventStreamImpl ( EventStorage eventStorage, EventStreamId eventStreamId, EventPayloadSerializerDeserializer serde ) {
			this.eventStorage = eventStorage;
			this.eventStreamId = eventStreamId;
			this.serde = serde;

			String tagContextValue = Optional.ofNullable(eventStreamId.context()).orElse(""); // null is not allowed
			// bounded rather than taken verbatim: purpose is documented as an entity id in half the
			// examples, and every distinct value here is a permanent set of meters -- see purposeTagValueFor
			String tagPurposeValue = purposeTagValueFor(Optional.ofNullable(eventStreamId.purpose()).orElse("")); // null is not allowed
			String tagTypedValue = String.valueOf(serde.isTyped());
			
			this.baseTags = io.micrometer.core.instrument.Tags
					.of("context", tagContextValue, "purpose", tagPurposeValue, "typed", tagTypedValue, "storage", eventStorage.name());

			// prepare counters for metering
			this.meterAppend = meterRegistry.counter("sliceworkz.eventstore.append", baseTags);
			this.meterQuery = meterRegistry.counter("sliceworkz.eventstore.query", baseTags);
			this.meterAppendOptimisticLock = meterRegistry.counter("sliceworkz.eventstore.append.optimisticlock", baseTags);
			this.meterGetEvent = meterRegistry.counter("sliceworkz.eventstore.get.event", baseTags);
			this.meterBookmarkPlace = meterRegistry.counter("sliceworkz.eventstore.bookmark.place", baseTags);
			this.meterBookmarkGet= meterRegistry.counter("sliceworkz.eventstore.bookmark.get", baseTags);
			this.meterBookmarkList = meterRegistry.counter("sliceworkz.eventstore.bookmark.list", baseTags);

			this.timerQuery = meterRegistry.timer("sliceworkz.eventstore.query.duration", baseTags);
			this.timerAppend = meterRegistry.timer("sliceworkz.eventstore.append.duration", baseTags);

			// pick up the shared holder for the highest event position, registering its gauge if this is
			// the first stream to meter under these tags
			this.gaugeHighestEventPosition = highestAppendedPositionFor(baseTags);

			// increment number of stream objects created
			meterRegistry.counter("sliceworkz.eventstore.stream.create", baseTags).increment();
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
		 * Registers this stream with the storage, on the first subscription and not before.
		 * <p>
		 * A stream that nobody subscribes to has nothing to do with a notification — {@link #notify} does
		 * no more than fan out to the three subscriber lists — so registering one would only lengthen the
		 * list the storage walks on every append, on the single thread that serves every store attached to
		 * it. Streams are handed out per operation and most of them only query and append, so that list
		 * used to grow with traffic rather than with the number of things actually listening.
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
		 * Ends this stream's subscriptions and hands its registration back to the storage.
		 * <p>
		 * Idempotent, and not terminal: the stream stays usable for querying, appending and bookmarking,
		 * and subscribing again re-registers it. See {@link org.sliceworkz.eventstore.stream.EventSource#close()}
		 * for why a stream is closable at all and why closing it is not the end of it.
		 * <p>
		 * The subscriber lists are cleared as well as the registration released, so that a listener cannot
		 * survive into a later subscription of the same stream and be notified twice.
		 */
		@Override
		public void close ( ) {
			if ( subscribedToStorage.compareAndSet(true, false) ) {
				eventStorage.unsubscribe(this);
				subscribedStreams.remove(this);
			}
			eventuallyConsistentSubscribers.clear();
			consistentSubscribers.clear();
			bookmarkSubscribers.clear();
		}

		@Override
		public void subscribe(EventStreamEventuallyConsistentAppendListener eventuallyConsistentSubscriber) {
			checkStoreNotClosed();
			this.eventuallyConsistentSubscribers.add(new OptimizingApendListenerDecorator(eventuallyConsistentSubscriber));
			subscribeToStorage();
		}

		/**
		 * {@inheritDoc}
		 * <p>
		 * Deliberately does <em>not</em> register with the storage. A consistent subscriber is notified
		 * inline by {@link #append}, on this very object, and never through a storage notification — so it
		 * needs no registration, and it cannot be orphaned by one being absent: reaching it at all means
		 * holding this stream to append through.
		 */
		@Override
		public void subscribe(EventStreamConsistentAppendListener<EVENT_TYPE> consistentSubscriber) {
			checkStoreNotClosed();
			this.consistentSubscribers.add(consistentSubscriber);
		}

		@Override
		public void subscribe(EventStreamEventuallyConsistentBookmarkListener listener) {
			checkStoreNotClosed();
			this.bookmarkSubscribers.add(listener);
			subscribeToStorage();
		}

		@Override
		public Stream<Event<EVENT_TYPE>> query(EventQuery query, EventReference cursor, Limit limit, Consumer<EventReference> storedEventCursorTracker ) {
			checkStoreNotClosed();
			meterQuery.increment(); // one query done
			QueryDirection direction = query.isBackwards() ? QueryDirection.BACKWARD : QueryDirection.FORWARD;
			EventFilter originalFilter = query.filter();

			// Time the storage fetch itself, and nothing else. The pipeline built on top of it -- peek,
			// upcasting, filtering -- is lazy, so wrapping the whole expression in timerQuery.record(...)
			// would time the construction of that pipeline rather than any work: microseconds, whatever
			// the query costs. It happens to report the fetch today only because every backend
			// materialises its whole result set before returning (EventStorage.query is eager by
			// contract), which puts the fetch inside the supplier by accident rather than by design.
			// Measuring the storage call explicitly says what the metric means and keeps it saying it if
			// a backend ever streams its result set instead.
			// Deserialisation and upcasting are deliberately outside this timer: they happen per element
			// as the caller consumes, are counted separately by sliceworkz.eventstore.query.event, and
			// are the caller's pace, not the store's.
			Stream<StoredEvent> storedEvents =
				timerQuery.record(()->eventStorage.query(includeLegacyEventTypes(query), Optional.of(eventStreamId), cursor, limit, direction));

			return storedEvents
				.peek(se -> storedEventCursorTracker.accept(se.reference()))
				.flatMap(se->enrichAfterQuery(se, direction))
				.filter(e->originalFilter.matches(e));
		}

		private Stream<Event<EVENT_TYPE>> enrichAfterQuery ( StoredEvent storedEvent, QueryDirection direction ) {
			meterRegistry.counter("sliceworkz.eventstore.query.event", baseTags.and("eventtype", storedEvent.type().name())).increment();
			return enrich(storedEvent, direction);
		}

		@SuppressWarnings("unchecked")
		private Stream<Event<EVENT_TYPE>> enrich ( StoredEvent storedEvent, QueryDirection direction ) {
			List<TypeAndPayload> results = serde.deserialize(new TypeAndSerializedPayload(storedEvent.type(), storedEvent.immutableData(), storedEvent.erasableData()));
			// For backward queries, reverse the upcasted sub-events so they appear in descending order,
			// consistent with the overall backward traversal of stored events.
			if ( direction == QueryDirection.BACKWARD ) {
				results = results.reversed();
			}
			EventReference baseRef = storedEvent.reference();
			List<TypeAndPayload> finalResults = results;
			return java.util.stream.IntStream.range(0, finalResults.size()).mapToObj(i -> {
				TypeAndPayload typeAndPayload = finalResults.get(i);
				EVENT_TYPE data = (EVENT_TYPE)typeAndPayload.eventData();
				// Each sub-event gets a unique reference via the index, distinguishing upcasted events
				// that originate from the same stored event. For single-event results, index is 0.
				EventReference ref = baseRef.withIndex(direction == QueryDirection.BACKWARD ? finalResults.size() - 1 - i : i);
				return new Event<>(storedEvent.stream(), typeAndPayload.type(), storedEvent.type(), ref, data, storedEvent.tags(), storedEvent.timestamp());
			});
		}

		private EventToStore reduce ( EphemeralEvent<? extends EVENT_TYPE> event, EventStreamId streamToAppendTo ) {
			meterRegistry.counter("sliceworkz.eventstore.append.event", baseTags.and("eventtype", event.type().name())).increment();
			Tags tags = event.tags(); 
			TypeAndSerializedPayload data = serde.serialize(event.data());
			return new EventToStore(streamToAppendTo, data.type(), data.immutablePayload(), data.erasablePayload(), tags, event.idempotencyKey());
		}

		private List<EventToStore> reduce ( List<? extends EphemeralEvent<? extends EVENT_TYPE>> events, EventStreamId streamToAppendTo ) {
			return events.stream().map(e->this.reduce(e,streamToAppendTo)).toList();
		}

		@Override
		public List<Event<EVENT_TYPE>> append(AppendCriteria appendCriteria, List<EphemeralEvent<? extends EVENT_TYPE>> events) {
			return append(appendCriteria, events, eventStreamId);
			
		}
		@Override
		public List<Event<EVENT_TYPE>> append(AppendCriteria appendCriteria, List<EphemeralEvent<? extends EVENT_TYPE>> events, EventStreamId streamToAppendTo) {
			checkStoreNotClosed();
			
			if ( !streamToAppendTo.canAppendTo(eventStreamId)) {
				throw new IllegalArgumentException("cannot append to eventstream %s using streamId %s".formatted(eventStreamId, streamToAppendTo));
			}
			
			if ( streamToAppendTo.isReadOnly() ) {
				throw new IllegalArgumentException("cannot append to non-specific eventstream %s".formatted(streamToAppendTo));
			}
			
			List<String> unAppendable = events.stream().map(e->e.type().name()).filter(t->!serde.canDeserialize(t)).toList();
			if ( !unAppendable.isEmpty() ) {
				throw new IllegalArgumentException("cannot append event type '%s' via this stream".formatted(unAppendable.getFirst()));
			}
			
			if ( events.size() == 0 ) {
				return Collections.emptyList();
			}
			
			if ( events.size() > 1 ) {
				if ( events.stream().filter(e->e.idempotencyKey()!=null).findAny().isPresent()) {
					throw new IllegalArgumentException("cannot append multiple events in combination with an idempotency key");
				}
			}

			// append events to the eventstore (with optimistic locking)
			List<Event<EVENT_TYPE>> appendedEvents;
			try {
				appendedEvents = timerAppend.record(()->eventStorage.append(appendCriteria, Optional.of(streamToAppendTo), reduce(events, streamToAppendTo)).stream().flatMap(se->enrich(se, QueryDirection.FORWARD)).toList());
				meterAppend.increment();

				// update highest event position gauge
				appendedEvents.stream()
					.map(Event::reference)
					.mapToLong(EventReference::position)
					.max()
					.ifPresent(maxPosition -> gaugeHighestEventPosition.updateAndGet(current -> Math.max(current, maxPosition)));

				// ... and dispatch events directly back to the kernel for update of consistent readmodels etc...
				LOGGER.debug("Notifying {} consistent clients of stream {} about append of {} events", consistentSubscribers.size(), streamToAppendTo, appendedEvents.size());
				consistentSubscribers.forEach(s->s.eventsAppended(appendedEvents));
			} catch (OptimisticLockingException optimisticLockingException) {
				meterAppendOptimisticLock.increment();
				throw optimisticLockingException;
			}

			return appendedEvents;
		}
		
		/**
		 * Traces back all current event types to their legacy historical ones, so a full query is done on older and newer ones
		 */
		private EventQuery includeLegacyEventTypes ( EventQuery query ) {
			if ( query.items() == null ) {
				return query; // match-all, nothing to modify
			} else {
				EventFilter newFilter = new EventFilter(query.items().stream().map(this::includeLegacyEventTypes).toList(), query.until());
				return new EventQuery(newFilter, query.direction(), query.limit());
			}
		}

		private EventFilterItem includeLegacyEventTypes ( EventFilterItem queryItem ) {
			return new EventFilterItem(includeLegacyEventTypes(queryItem.eventTypes()), queryItem.tags());
		}

		private EventTypesFilter includeLegacyEventTypes ( EventTypesFilter typesFilter ) {
			return EventTypesFilter.of(serde.determineLegacyTypes(typesFilter.eventTypes()));
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
				LOGGER.debug("Must asynchronously notify {} eventually consistent clients of stream {} about append up until at least {}", eventuallyConsistentSubscribers.size(), eventStreamId, newEventsInStore.atLeastUntil());

				// schedule for execution on different thread to notify/interrupt any waiting eventual consistent processors
				submitOrDropIfClosed(executorServiceForEventAppends, ( ) -> {
						LOGGER.debug("Notifying {} eventually consistent clients of stream {} about append up until at least {}", eventuallyConsistentSubscribers.size(), eventStreamId, newEventsInStore.atLeastUntil());
						eventuallyConsistentSubscribers.stream().forEach(s->s.eventsAppended(newEventsInStore.atLeastUntil()));
				});
			}
		}

		@Override
		public void notify(BookmarkPlacedNotification bookmarkPlaced) {
			if ( closed.get() ) {
				return; // see notify(AppendsToEventStoreNotification)
			}
			LOGGER.debug("Must asynchronously notify {} eventually consistent bookmark listeners on {} of update for {} to {}", eventuallyConsistentSubscribers.size(), eventStreamId, bookmarkPlaced.reader(), bookmarkPlaced.bookmark());

			// schedule for execution on different thread to notify/interrupt any waiting eventual consistent processors
			submitOrDropIfClosed(executorServiceForBookmarkUpdates, ( ) -> {
					LOGGER.debug("Notifying {} eventually consistent bookmark listeners on {} of update for {} to {}", eventuallyConsistentSubscribers.size(), eventStreamId, bookmarkPlaced.reader(), bookmarkPlaced.bookmark());
					bookmarkSubscribers.stream().forEach(s->s.bookmarkUpdated(bookmarkPlaced.reader(), bookmarkPlaced.bookmark()));
			});
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
			meterBookmarkPlace.increment();
			eventStorage.bookmark(reader, reference, tags);
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
			meterBookmarkGet.increment();
			return eventStorage.getBookmark(reader.toString());
		}

		@Override
		public List<Bookmark> getBookmarks() {
			checkStoreNotClosed();
			meterBookmarkList.increment();
			return eventStorage.getBookmarks();
		}

		@Override
		public List<Event<EVENT_TYPE>> getEventById(EventId eventId) {
			checkStoreNotClosed();
			meterGetEvent.increment();
			// filters out events that can not be read by this stream, then upcasts via enrich
			return eventStorage.getEventById(eventId)
				.filter(e->eventStreamId.canRead(e.stream()))
				.map(e->enrich(e, QueryDirection.FORWARD))
				.map(s->s.toList())
				.orElse(List.of());
		}

	}

}
