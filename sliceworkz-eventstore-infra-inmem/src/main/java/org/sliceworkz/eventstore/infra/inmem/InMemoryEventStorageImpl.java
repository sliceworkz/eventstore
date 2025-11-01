package org.sliceworkz.eventstore.infra.inmem;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class InMemoryEventStorageImpl implements EventStorage {

	private String name;
	private List<StoredEvent> eventlog = new LinkedList<>();
	private List<EventStoreListener> listeners = new ArrayList<>();
	private Map<String,EventReference> bookmarks = new HashMap<>();
	private JsonMapper jsonMapper;
	
	public InMemoryEventStorageImpl ( ) {
		this.name = String.format("inmem-%s", System.identityHashCode(this)); // unique name in case different objects are used
		this.jsonMapper = new JsonMapper();
		this.jsonMapper.findAndRegisterModules();
	}
	
	@Override
	public synchronized Stream<StoredEvent> query(EventQuery query, Optional<EventStreamId> stream, EventReference after, Limit limit, QueryDirection direction ) {
		return queryAfter(query, stream, after, limit, direction);
	}

	public synchronized Stream<StoredEvent> queryFrom (EventQuery query, Optional<EventStreamId> streamId, EventReference from, Limit limit, QueryDirection direction ) {
		return queryFromOrAfter(query, streamId, limit, from, true, direction);
	}
	
	public synchronized Stream<StoredEvent> queryAfter (EventQuery query, Optional<EventStreamId> streamId, EventReference after, Limit limit, QueryDirection direction ) {
		return queryFromOrAfter(query, streamId, limit, after, false, direction);
	}
	
	public synchronized Stream<StoredEvent> queryFromOrAfter (EventQuery query, Optional<EventStreamId> streamId, Limit limit, EventReference reference, boolean includeReference, QueryDirection direction ) {
		Stream<StoredEvent> on;
		
		switch ( direction ) {
			case BACKWARD:
				on = eventlog.reversed().stream();
				break;
			case FORWARD:
			default:
				on = eventlog.stream(); 
		}
		
		if ( reference != null ) {
			if ( reference.position() != null ) {
				if ( direction == QueryDirection.FORWARD ) {
					on = on.skip(reference.position()-(includeReference?1:0));  // skip until the position in the stream, including the referenced/current one as first or not
				} else {
					on = on.skip(eventlog.size()-reference.position()+(includeReference?0:1));  // skip until the position in the stream, including the referenced/current one as first or not
				}
			} else {
				on = Stream.empty(); // no position: use empty stream, so reference event was not found and optimistic locking exception will be thrown
			}
		}
		
		// if we only need to read until a certain event, we stop after we have reached it
		if ( query.until() != null ) {
			on = on.takeWhile(e->e.reference().position()<=query.until().position());
		}
		
		Stream<StoredEvent> result = on;

		if ( streamId.isPresent() ) {
			result = result.filter(e->streamId.get().canRead(e.stream()));
		} else {
			// no streamId specified, considering all streams present in the store
		}
		
		result = result.filter(query::matches);

		if ( limit != null && limit.isSet() ) {
			result = result.limit(limit.value());
		}

		var returnValue = new ArrayList<>(result.toList()); // to list and back to avoid ConcurrentUpdateExceptions when writing next event in log (?)
		return returnValue.stream();
	}
	
	/*
	 *  Synchronized method, to allow re-querying and storing in one shot (required for optimistic locking)
	 */
	@Override
	public synchronized List<StoredEvent> append(AppendCriteria appendCriteria, Optional<EventStreamId> streamId, List<EventToStore> events) {
		
		verifyPersistableJson(events);
		
		List<StoredEvent> result = Collections.emptyList();
		
		// if we should just append and not check, or no reference was present to a last event id (empty stream)
		if ( appendCriteria.isNone() || appendCriteria.expectedLastEventReference() == null ) {
			result = addAndNotifyListeners(events);
			
		// otherwise, we'll need to be aware of any optimistic locking issues
		} else {
			
			// we query the stream with the same filter from the last event known as our reference
			// we only need to fetch max 1 event to prove a locking issue
			Stream<StoredEvent> newEventStream = queryAfter(appendCriteria.eventQuery(), streamId, appendCriteria.expectedLastEventReference().orElse(null), Limit.to(1), QueryDirection.FORWARD);
			
			List<StoredEvent> newEvents = newEventStream.toList();
			
			// if there are no new events in the stream ...
			if ( newEvents.isEmpty() ) {
				
				// we can safely append to the event log
				result = addAndNotifyListeners(events);
				
			} else {
				// new events means an optimistic lock !
				throw new OptimisticLockingException(appendCriteria.eventQuery(), appendCriteria.expectedLastEventReference());
			}				
		}
		
		return result;
	}
	
	private void verifyPersistableJson ( List<EventToStore> newEvents ) {
		try {
			for ( EventToStore e: newEvents ) {
				Class<?> clz = e.data().getClass();
				String s = jsonMapper.writeValueAsString(e.data());
				jsonMapper.readValue(s, clz);
			}
		} catch (JsonMappingException e) {
			throw new RuntimeException("json mapping roundtrip test failed", e);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("json mapping roundtrip test failed", e);
		}
	}
	
	private List<StoredEvent> addAndNotifyListeners ( List<EventToStore> events ) {
		var addedEvents = events.stream().map(this::addEventToEventLog).toList();
		
		// notify each Listener about the appends, but if multiple Events were appended, only notify about the last one
		addedEvents.stream()
			    .collect(Collectors.toMap(
			        StoredEvent::stream,
			        event -> new AppendsToEventStoreNotification(event.stream(), event.reference()),
			        (existing, replacement) -> replacement // in sequence, only useful to notify about the last one
			    ))
			    .values()
			    .forEach(notification->listeners.forEach(listener->listener.notify(notification)));
		
		return addedEvents;
	}
	
	private StoredEvent addEventToEventLog ( EventToStore event ) {
		EventReference reference = EventReference.create(eventlog.size()+1);
		StoredEvent storedEvent = event.positionAt(reference, LocalDateTime.now());
		eventlog.add(storedEvent);
		return storedEvent;
	}

	@Override
	public Optional<StoredEvent> getEventById(EventId eventId) {
		return eventlog.stream().filter(e->e.reference().id().equals(eventId)).findFirst();
	}

	@Override
	public void subscribe(EventStoreListener listener) {
		listeners.add(listener);
	}

	@Override
	public synchronized Optional<EventReference> getBookmark(String reader) {
		return Optional.ofNullable(bookmarks.get(reader));
	}

	@Override
	public synchronized void bookmark(String reader, EventReference eventReference, Tags tags ) {
		bookmarks.put(reader, eventReference);
		BookmarkPlacedNotification notification = new BookmarkPlacedNotification(reader, eventReference);
		listeners.forEach(l->l.notify(notification));
	}

	@Override
	public String name() {
		return name;
	}

}
