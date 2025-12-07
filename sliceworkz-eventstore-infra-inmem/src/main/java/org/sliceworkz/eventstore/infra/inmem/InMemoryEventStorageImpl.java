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
package org.sliceworkz.eventstore.infra.inmem;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.micrometer.core.instrument.MeterRegistry;

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

	private String name;
	private List<StoredEvent> eventlog = new LinkedList<>();
	private List<EventStoreListener> listeners = new CopyOnWriteArrayList<>();
	private Map<String,EventReference> bookmarks = new HashMap<>();
	private JsonMapper jsonMapper;
	private Limit absoluteLimit;
	private MeterRegistry meterRegistry;

	/**
	 * Constructs a new in-memory event storage instance with the specified absolute query limit and observability support.
	 * <p>
	 * This constructor is package-private and should not be called directly. Instead, use the
	 * {@link InMemoryEventStorage.Builder} to create instances.
	 * <p>
	 * The constructor initializes:
	 * <ul>
	 *   <li>A unique name based on the object's identity hash code</li>
	 *   <li>An empty event log backed by a {@link LinkedList}</li>
	 *   <li>An empty list of event listeners</li>
	 *   <li>An empty bookmark map</li>
	 *   <li>A Jackson {@link JsonMapper} with auto-discovered modules for event serialization validation</li>
	 *   <li>A Micrometer meter registry for collecting metrics about storage operations</li>
	 * </ul>
	 *
	 * @param absoluteLimit the absolute limit on query results, or {@link Limit#none()} for no limit
	 * @param meterRegistry the Micrometer meter registry for collecting observability metrics
	 * @see InMemoryEventStorage.Builder#build()
	 */
	public InMemoryEventStorageImpl ( Limit absoluteLimit, MeterRegistry meterRegistry ) {
		this.name = "inmem-%s".formatted(System.identityHashCode(this)); // unique name in case different objects are used
		this.jsonMapper = new JsonMapper();
		this.jsonMapper.findAndRegisterModules();
		this.absoluteLimit = absoluteLimit;
		this.meterRegistry = meterRegistry;
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
		return queryAfter(query, stream, after, limit, direction);
	}

	/**
	 * Queries events starting from (and including) the specified reference.
	 * <p>
	 * This is a convenience method that delegates to {@link #queryFromOrAfter(EventQuery, Optional, Limit, EventReference, boolean, QueryDirection)}
	 * with includeReference set to true.
	 *
	 * @param query the event query specifying which events to retrieve
	 * @param streamId optional stream ID to filter events
	 * @param from the reference to start from (inclusive)
	 * @param limit soft limit on the number of results
	 * @param direction the direction to traverse the event log
	 * @return a Stream of StoredEvent instances matching the criteria
	 */
	public synchronized Stream<StoredEvent> queryFrom (EventQuery query, Optional<EventStreamId> streamId, EventReference from, Limit limit, QueryDirection direction ) {
		return queryFromOrAfter(query, streamId, limit, from, true, direction);
	}

	/**
	 * Queries events starting after (excluding) the specified reference.
	 * <p>
	 * This is a convenience method that delegates to {@link #queryFromOrAfter(EventQuery, Optional, Limit, EventReference, boolean, QueryDirection)}
	 * with includeReference set to false.
	 *
	 * @param query the event query specifying which events to retrieve
	 * @param streamId optional stream ID to filter events
	 * @param after the reference to start after (exclusive)
	 * @param limit soft limit on the number of results
	 * @param direction the direction to traverse the event log
	 * @return a Stream of StoredEvent instances matching the criteria
	 */
	public synchronized Stream<StoredEvent> queryAfter (EventQuery query, Optional<EventStreamId> streamId, EventReference after, Limit limit, QueryDirection direction ) {
		return queryFromOrAfter(query, streamId, limit, after, false, direction);
	}

	/**
	 * Core query method supporting both inclusive and exclusive reference-based queries.
	 * <p>
	 * This method provides the underlying implementation for both {@link #queryFrom(EventQuery, Optional, EventReference, Limit, QueryDirection)}
	 * and {@link #queryAfter(EventQuery, Optional, EventReference, Limit, QueryDirection)} by allowing control over whether
	 * the reference event itself is included in the results.
	 * <p>
	 * The method handles:
	 * <ul>
	 *   <li>Bidirectional traversal (forward/backward) of the event log</li>
	 *   <li>Position-based skipping to start from the correct event</li>
	 *   <li>Stream filtering based on the provided stream ID</li>
	 *   <li>Event matching based on the query criteria</li>
	 *   <li>Enforcement of both soft and absolute limits</li>
	 * </ul>
	 *
	 * @param query the event query specifying which events to retrieve
	 * @param streamId optional stream ID to filter events
	 * @param limit soft limit on the number of results
	 * @param reference the reference event to position from, or null to start from the beginning
	 * @param includeReference true to include the reference event in results, false to start after it
	 * @param direction the direction to traverse the event log (FORWARD or BACKWARD)
	 * @return a Stream of StoredEvent instances matching the criteria
	 * @throws EventStorageException if the result exceeds the configured absolute limit
	 * @see #queryFrom(EventQuery, Optional, EventReference, Limit, QueryDirection)
	 * @see #queryAfter(EventQuery, Optional, EventReference, Limit, QueryDirection)
	 */
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
				Class<?> clz = e.immutableData().getClass();
				String s = jsonMapper.writeValueAsString(e.immutableData());
				jsonMapper.readValue(s, clz);

				if ( e.erasableData() != null ) {
					clz = e.erasableData().getClass();
					s = jsonMapper.writeValueAsString(e.erasableData());
					jsonMapper.readValue(s, clz);
				}
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
	public synchronized void removeBookmark(String reader) {
		bookmarks.remove(reader);
	}

	@Override
	public synchronized void bookmark(String reader, EventReference eventReference, Tags tags ) {
		bookmarks.put(reader, eventReference);
		BookmarkPlacedNotification notification = new BookmarkPlacedNotification(reader, eventReference);
		listeners.forEach(l->l.notify(notification));
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
	@Override
	public String name() {
		return name;
	}

}
