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
package org.sliceworkz.eventstore.impl;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.Banner;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndPayload;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndSerializedPayload;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventQueryItem;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
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
	
	static {
		Banner.printBanner();
	}

	/**
	 * The underlying storage backend for persisting and retrieving events.
	 */
	private final EventStorage eventStorage;

	/**
	 * Executor service using virtual threads for asynchronously notifying eventually consistent subscribers.
	 * Named threads help with debugging and monitoring.
	 */
	private final ExecutorService executorService;

	/**
	 * Constructs a new EventStoreImpl instance backed by the specified storage.
	 * <p>
	 * This constructor is invoked by {@link EventStoreFactoryImpl} and should not be called directly.
	 * The constructor initializes a single-threaded executor using virtual threads for handling
	 * eventually consistent event notifications without blocking append operations.
	 *
	 * @param eventStorage the storage backend implementation (in-memory, PostgreSQL, etc.)
	 */
	protected EventStoreImpl ( EventStorage eventStorage ) {
		this.eventStorage = eventStorage;
		ThreadFactory threadFactory = Thread.ofVirtual().name("eventually-consistent-listener-notifier/" + eventStorage.name()).factory();
		this.executorService = Executors.newSingleThreadExecutor(threadFactory);
	}

	@Override
	public <EVENT_TYPE> EventStream<EVENT_TYPE> getEventStream(EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses ) {
		
		EventPayloadSerializerDeserializer serde; 
		if ( eventRootClasses != null && eventRootClasses.size() > 0 ) {
			// use typed event payloads, mapped to Java objects
			serde = EventPayloadSerializerDeserializer.typed();
			eventRootClasses.forEach(serde::registerEventTypes);
			historicalEventRootClasses.forEach(serde::registerLegacyEventTypes);
		} else {
			// no type mappings, all events payload will be String type
			serde = EventPayloadSerializerDeserializer.raw();
		}
		
		return new EventStreamImpl<EVENT_TYPE> ( eventStorage, eventStreamId, serde );
	}

	class EventStreamImpl<EVENT_TYPE> implements EventStream<EVENT_TYPE>, EventStoreListener {
		
		private final Logger LOGGER = LoggerFactory.getLogger(EventStreamImpl.class);
		
		private final EventStorage eventStorage;
		private final EventStreamId eventStreamId;
		private final EventPayloadSerializerDeserializer serde;
		
		private final List<EventStreamEventuallyConsistentAppendListener> eventuallyConsistentSubscribers = new CopyOnWriteArrayList<>();
		private final List<EventStreamConsistentAppendListener<EVENT_TYPE>> consistentSubscribers = new CopyOnWriteArrayList<>();
		private final List<EventStreamEventuallyConsistentBookmarkListener> bookmarkSubscribers = new CopyOnWriteArrayList<>();

		public EventStreamImpl ( EventStorage eventStorage, EventStreamId eventStreamId, EventPayloadSerializerDeserializer serde ) {
			this.eventStorage = eventStorage;
			this.eventStreamId = eventStreamId;
			this.eventStorage.subscribe(this);
			this.serde = serde;
		}
		
		public boolean isReadOnly ( ) {
			return !canAppend();
		}
		
		public boolean canAppend ( ) {
			return !eventStreamId.isAnyContext() && !eventStreamId.isAnyPurpose();
		}

		@Override
		public EventStreamId id() {
			return eventStreamId;
		}

		@Override
		public void subscribe(EventStreamEventuallyConsistentAppendListener eventuallyConsistentSubscriber) {
			this.eventuallyConsistentSubscribers.add(eventuallyConsistentSubscriber);
		}

		@Override
		public void subscribe(EventStreamConsistentAppendListener<EVENT_TYPE> consistentSubscriber) {
			this.consistentSubscribers.add(consistentSubscriber);
		}

		@Override
		public void subscribe(EventStreamEventuallyConsistentBookmarkListener listener) {
			this.bookmarkSubscribers.add(listener);
		}

		@Override
		public Stream<Event<EVENT_TYPE>> query(EventQuery query, EventReference after, Limit limit ) {
			return eventStorage.query(includeLegacyEventTypes(query),Optional.of(eventStreamId), after, limit, QueryDirection.FORWARD).map(this::enrich);
		}

		@Override
		public Stream<Event<EVENT_TYPE>> queryBackwards(EventQuery query, EventReference before, Limit limit) {
			return eventStorage.query(includeLegacyEventTypes(query),Optional.of(eventStreamId), before, limit, QueryDirection.BACKWARD).map(this::enrich);
		}
		
		@SuppressWarnings("unchecked")
		private Event<EVENT_TYPE> enrich ( StoredEvent storedEvent ) {
			TypeAndPayload typeAndPayload = serde.deserialize(new TypeAndSerializedPayload(storedEvent.type(), storedEvent.immutableData(), storedEvent.erasableData()));
			EVENT_TYPE data = (EVENT_TYPE)typeAndPayload.eventData();
			return new Event<>(storedEvent.stream(), typeAndPayload.type(), storedEvent.type(), storedEvent.reference(), data, storedEvent.tags(), storedEvent.timestamp());
		}
		
		private EventToStore reduce ( EphemeralEvent<? extends EVENT_TYPE> event ) {
			Tags tags = event.tags(); 
			TypeAndSerializedPayload data = serde.serialize(event.data());
			return new EventToStore(eventStreamId, data.type(), data.immutablePayload(), data.erasablePayload(), tags);
		}

		private List<EventToStore> reduce ( List<? extends EphemeralEvent<? extends EVENT_TYPE>> events ) {
			return events.stream().map(this::reduce).toList();
		}

		@Override
		public List<Event<EVENT_TYPE>> append(AppendCriteria appendCriteria, List<EphemeralEvent<? extends EVENT_TYPE>> events) {
			
			if ( isReadOnly() ) {
				throw new IllegalArgumentException("cannot append to non-specific eventstream %s".formatted(eventStreamId));
			}
			
			List<String> unAppendable = events.stream().map(e->e.type().name()).filter(t->!serde.canDeserialize(t)).toList();
			if ( !unAppendable.isEmpty() ) {
				throw new IllegalArgumentException("cannot append event type '%s' via this stream".formatted(unAppendable.getFirst()));
			}

			// append events to the eventstore (with optimistic locking)
			List<Event<EVENT_TYPE>> appendedEvents = eventStorage.append(appendCriteria, Optional.of(eventStreamId), reduce(events)).stream().map(this::enrich).toList();
			
			// ... and dispatch events directly back to the kernel for update of consistent readmodels etc...
			LOGGER.debug("Notifying {} consistent clients of stream {} about append of {} events", consistentSubscribers.size(), eventStreamId, appendedEvents.size());
			consistentSubscribers.forEach(s->s.eventsAppended(appendedEvents));
			
			return appendedEvents;
		}
		
		/**
		 * Traces back all current event types to their legacy historical ones, so a full query is done on older and newer ones
		 */
		private EventQuery includeLegacyEventTypes ( EventQuery query ) {
			if ( query.items() == null ) {
				return new EventQuery(null, query.until());
			} else {
				return new EventQuery(query.items().stream().map(this::includeLegacyEventTypes).toList(), query.until());
			}
		}

		private EventQueryItem includeLegacyEventTypes ( EventQueryItem queryItem ) {
			return new EventQueryItem(includeLegacyEventTypes(queryItem.eventTypes()), queryItem.tags());
		}

		private EventTypesFilter includeLegacyEventTypes ( EventTypesFilter typesFilter ) {
			return EventTypesFilter.of(serde.determineLegacyTypes(typesFilter.eventTypes()));
		}

		@Override
		public void notify(AppendsToEventStoreNotification newEventsInStore) {
			// if the events are in the logical stream we care about...
			if ( newEventsInStore.isRelevantFor(eventStreamId) ) {
				LOGGER.debug("Must asynchronously notify {} eventually consistent clients of stream {} about append up until at least {}", eventuallyConsistentSubscribers.size(), eventStreamId, newEventsInStore.atLeastUntil());
				
				// schedule for execution on different thread to notify/interrupt any waiting eventual consistent processors 
				executorService.execute( ( ) -> {
						LOGGER.debug("Notifying {} eventually consistent clients of stream {} about append up until at least {}", eventuallyConsistentSubscribers.size(), eventStreamId, newEventsInStore.atLeastUntil());
						eventuallyConsistentSubscribers.stream().forEach(s->s.eventsAppended(newEventsInStore.atLeastUntil()));
				});
			}
		}

		@Override
		public void notify(BookmarkPlacedNotification bookmarkPlaced) {
			LOGGER.debug("Must asynchronously notify {} eventually consistent bookmark listeners on {} of update for {} to {}", eventuallyConsistentSubscribers.size(), eventStreamId, bookmarkPlaced.reader(), bookmarkPlaced.bookmark());
			
			// schedule for execution on different thread to notify/interrupt any waiting eventual consistent processors 
			executorService.execute( ( ) -> {
					LOGGER.debug("Notifying {} eventually consistent bookmark listeners on {} of update for {} to {}", eventuallyConsistentSubscribers.size(), eventStreamId, bookmarkPlaced.reader(), bookmarkPlaced.bookmark());
					bookmarkSubscribers.stream().forEach(s->s.bookmarkUpdated(bookmarkPlaced.reader(), bookmarkPlaced.bookmark()));
			});
		}

		@Override
		public void placeBookmark(String reader, EventReference reference, Tags tags) {
			eventStorage.bookmark(reader, reference, tags);
		}

		@Override
		public Optional<EventReference> getBookmark(String reader) {
			return eventStorage.getBookmark(reader.toString());
		}

		@Override
		public Optional<EventReference> queryReference(EventId id) {
			return eventStorage.getEventById(id).map(this::enrich).map(Event::reference);
		}
		
		@Override
		public Optional<Event<EVENT_TYPE>> getEventById(EventId eventId) {
			// filters out events that can not be read by this stream
			return eventStorage.getEventById(eventId).filter(e->eventStreamId.canRead(e.stream())).map(this::enrich);
					
		}

	}

}
