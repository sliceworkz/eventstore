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
package org.sliceworkz.eventstore;

import java.util.Collections;
import java.util.Set;

import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The main entry point for interacting with an event store.
 * <p>
 * An EventStore provides access to {@link EventStream}s which allow reading and writing domain events.
 * EventStore instances are obtained via {@link EventStoreFactory} by providing an {@link org.sliceworkz.eventstore.spi.EventStorage} implementation.
 * <p>
 * This implementation is fully compliant with the Dynamic Consistency Boundary (DCB) specification,
 * supporting dynamic event tagging, flexible querying, and optimistic locking based on relevant historical facts.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create event store with in-memory storage
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 *
 * // Get an event stream for a specific context and purpose
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * // Append events and query them
 * stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("John"), Tags.none()));
 * List<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll()).toList();
 * }</pre>
 *
 * @see EventStoreFactory
 * @see EventStream
 * @see EventStreamId
 */
public interface EventStore {

	/**
	 * Retrieves an event stream with full configuration for event types and historical event types.
	 * <p>
	 * This is the primary method for obtaining an event stream. Event root classes define the sealed interfaces
	 * or base types for current domain events. Historical event root classes define types for legacy events
	 * that may need upcasting to current types.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream (context and optional purpose)
	 * @param eventRootClasses the set of root classes/interfaces for current domain events
	 * @param historicalEventRootClasses the set of root classes/interfaces for historical events requiring upcasting
	 * @return an EventStream for reading and writing domain events
	 */
	<DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses );

	/**
	 * Retrieves an event stream without specifying event root classes.
	 * <p>
	 * Use this method when working with raw events or when event types are not statically known.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId ) {
		return getEventStream(eventStreamId, Collections.emptySet(), Collections.emptySet());
	}

	/**
	 * Retrieves an event stream with current event root classes only.
	 * <p>
	 * Use this method when you only need to work with current event types and no historical upcasting is required.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream
	 * @param eventRootClasses the set of root classes/interfaces for current domain events
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses ) {
		return getEventStream(eventStreamId, eventRootClasses, Collections.emptySet());
	}

	/**
	 * Retrieves an event stream for a single event root class.
	 * <p>
	 * Convenience method for the common case of a single sealed interface or base class for domain events.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream
	 * @param eventRootClass the root class/interface for domain events (typically a sealed interface)
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Class<?> eventRootClass ) {
		return getEventStream(eventStreamId, Collections.singleton(eventRootClass), Collections.emptySet());
	}

	/**
	 * Retrieves an event stream with both current and historical event root classes.
	 * <p>
	 * Convenience method for the common case of a single current event type and a single historical event type
	 * that requires upcasting.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream
	 * @param eventRootClass the root class/interface for current domain events
	 * @param historicalEventRootClass the root class/interface for historical events requiring upcasting
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Class<?> eventRootClass, Class<?> historicalEventRootClass ) {
		return getEventStream(eventStreamId, Collections.singleton(eventRootClass), Collections.singleton(historicalEventRootClass));
	}
	
}