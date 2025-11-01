package org.sliceworkz.eventstore.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
 * EventStore implementation with pluggable storage (InMemory, Postgres, ...)
 */
public class EventStoreImpl implements EventStore {
	
	static {
		Banner.printBanner();
	}

	private EventStorage eventStorage;
	private ExecutorService executorService;
	
	protected EventStoreImpl ( EventStorage eventStorage ) {
		this.eventStorage = eventStorage;
		ThreadFactory threadFactory = Thread.ofVirtual().name("eventually-consistent-listener-notifier/" + eventStorage.name()).factory();
		this.executorService = Executors.newSingleThreadExecutor(threadFactory);
	}

	@Override
	public <EVENT_TYPE> EventStream<EVENT_TYPE> getEventStream(EventStreamId eventStreamId, Class<?> ... eventRootClasses ) {
		
		EventPayloadSerializerDeserializer serde; 
		if ( eventRootClasses != null && eventRootClasses.length > 0 ) {
			// use typed event payloads, mapped to Java objects
			serde = EventPayloadSerializerDeserializer.typed();
			Arrays.asList(eventRootClasses).forEach(serde::registerEventTypes);
		} else {
			// no type mappings, all events payload will be String type
			serde = EventPayloadSerializerDeserializer.raw();
		}
		
		return new EventStreamImpl<EVENT_TYPE> ( eventStorage, eventStreamId, serde );
	}

	class EventStreamImpl<EVENT_TYPE> implements EventStream<EVENT_TYPE>, EventStoreListener {
		
		private final Logger LOGGER = LoggerFactory.getLogger(EventStreamImpl.class);
		
		private EventStorage eventStorage;
		private EventStreamId eventStreamId;
		private EventPayloadSerializerDeserializer serde;
		
		private List<EventStreamEventuallyConsistentAppendListener> eventuallyConsistentSubscribers = new CopyOnWriteArrayList<>();
		private List<EventStreamConsistentAppendListener<EVENT_TYPE>> consistentSubscribers = new CopyOnWriteArrayList<>();
		private List<EventStreamEventuallyConsistentBookmarkListener> bookmarkSubscribers = new CopyOnWriteArrayList<>();

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
			return eventStorage.query(query,Optional.of(eventStreamId), after, limit, QueryDirection.FORWARD).map(this::enrich);
		}

		@Override
		public Stream<Event<EVENT_TYPE>> queryBackwards(EventQuery query, EventReference before, Limit limit) {
			return eventStorage.query(query,Optional.of(eventStreamId), before, limit, QueryDirection.BACKWARD).map(this::enrich);
		}
		
		@SuppressWarnings("unchecked")
		private Event<EVENT_TYPE> enrich ( StoredEvent storedEvent ) {
			assert storedEvent.reference().position() != null;
			assert storedEvent.timestamp() != null;
			
			EVENT_TYPE data = (EVENT_TYPE)storedEvent.data();
			TypeAndPayload typeAndPayload = serde.deserialize(storedEvent.type().name(), storedEvent.data());
			data = (EVENT_TYPE)typeAndPayload.eventData();
			return new Event<>(storedEvent.stream(), storedEvent.type(), storedEvent.reference(), data, storedEvent.tags(), storedEvent.timestamp());
		}
		
		private EventToStore reduce ( EphemeralEvent<? extends EVENT_TYPE> event ) {
			Tags tags = event.tags(); 
			TypeAndSerializedPayload data = serde.serialize(event.data());
			return new EventToStore(eventStreamId, data.type(), data.serializedPayload(), tags);
		}

		private List<EventToStore> reduce ( List<? extends EphemeralEvent<? extends EVENT_TYPE>> events ) {
			return events.stream().map(this::reduce).toList();
		}

		@Override
		public List<Event<EVENT_TYPE>> append(AppendCriteria appendCriteria, List<EphemeralEvent<? extends EVENT_TYPE>> events) {
			
			if ( isReadOnly() ) {
				throw new IllegalArgumentException(String.format("cannot append to non-specific eventstream %s", eventStreamId));
			}
			
			List<String> unAppendable = events.stream().map(e->e.type().name()).filter(t->!serde.canDeserialize(t)).toList();
			if ( !unAppendable.isEmpty() ) {
				throw new IllegalArgumentException(String.format("cannot append event type '%s' via this stream", unAppendable.getFirst()));
			}

			if ( appendCriteria.expectedLastEventReference() != null && appendCriteria.expectedLastEventReference().isPresent() ) {
				assert(appendCriteria.expectedLastEventReference().get().position() != null );
				// this would mean a bug where a freshly created Event that hasn't received a position in the stream is passed here.  very severe...
			}
			
			// append events to the eventstore (with optimistic locking)
			List<Event<EVENT_TYPE>> appendedEvents = eventStorage.append(appendCriteria, Optional.of(eventStreamId), reduce(events)).stream().map(this::enrich).toList();
			
			// ... and dispatch events directly back to the kernel for update of consistent readmodels etc...
			LOGGER.debug("Notifying {} consistent clients of stream {} about append of {} events", consistentSubscribers.size(), eventStreamId, appendedEvents.size());
			consistentSubscribers.forEach(s->s.eventsAppended(appendedEvents));
			
			return appendedEvents;
		}

		@Override
		public void notify(AppendsToEventStoreNotification newEventsInStore) {
			// if the events are in the logical stream we care about...
			if ( newEventsInStore.isRelevantFor(eventStreamId) ) {
				LOGGER.debug("Must asynchronously notify {} eventually consistent clients of stream {} about append up until at least {}", eventuallyConsistentSubscribers.size(), eventStreamId, newEventsInStore.atLeastUntil());
				
				// schedule for execution on different thread to notify/interrupt any waiting eventual consistent processors 
				executorService.execute( new Runnable ( ) {

					@Override
					public void run() {
						LOGGER.debug("Notifying {} eventually consistent clients of stream {} about append up until at least {}", eventuallyConsistentSubscribers.size(), eventStreamId, newEventsInStore.atLeastUntil());
						eventuallyConsistentSubscribers.stream().forEach(s->s.eventsAppended(newEventsInStore.atLeastUntil()));
					}
					
				});
			}
		}

		@Override
		public void notify(BookmarkPlacedNotification bookmarkPlaced) {
			LOGGER.debug("Must asynchronously notify {} eventually consistent bookmark listeners on {} of update for {} to {}", eventuallyConsistentSubscribers.size(), eventStreamId, bookmarkPlaced.reader(), bookmarkPlaced.bookmark());
			
			// schedule for execution on different thread to notify/interrupt any waiting eventual consistent processors 
			executorService.execute( new Runnable ( ) {

				@Override
				public void run() {
					LOGGER.debug("Notifying {} eventually consistent bookmark listeners on {} of update for {} to {}", eventuallyConsistentSubscribers.size(), eventStreamId, bookmarkPlaced.reader(), bookmarkPlaced.bookmark());
					
					bookmarkSubscribers.stream().forEach(s->s.bookmarkUpdated(bookmarkPlaced.reader(), bookmarkPlaced.bookmark()));
				}
				
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
