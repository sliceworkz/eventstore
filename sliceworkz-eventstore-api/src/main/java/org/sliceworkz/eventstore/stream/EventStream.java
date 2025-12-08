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
package org.sliceworkz.eventstore.stream;

/**
 * A type-safe interface for reading from and writing to an event stream in the event store.
 * <p>
 * EventStream combines the capabilities of {@link EventSource} (reading events) and {@link EventSink} (writing events),
 * providing a unified interface for interacting with a specific event stream. Each stream is identified by an
 * {@link EventStreamId} and is parameterized by the domain event type, ensuring type safety throughout event operations.
 * <p>
 * Event streams are the primary way to organize and access events in the event store. They provide:
 * <ul>
 *   <li>Type-safe reading and writing of domain events</li>
 *   <li>Querying capabilities via {@link org.sliceworkz.eventstore.query.EventQuery}</li>
 *   <li>Optimistic locking support through {@link AppendCriteria}</li>
 *   <li>Event subscription and bookmarking for event-driven processing</li>
 * </ul>
 * <p>
 * EventStream instances are obtained via {@link org.sliceworkz.eventstore.EventStore#getEventStream(EventStreamId, Class)}.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Get an event stream for a specific customer
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * // Append events to the stream
 * stream.append(
 *     AppendCriteria.none(),
 *     Event.of(new CustomerRegistered("John Doe"), Tags.of("region", "EU"))
 * );
 *
 * // Query all events from the stream
 * List<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll()).toList();
 *
 * // Query with filters
 * Stream<Event<CustomerEvent>> euCustomers = stream.query(
 *     EventQuery.forEvents(EventTypesFilter.any(), Tags.of("region", "EU"))
 * );
 *
 * // Conditional append with optimistic locking (DCB pattern)
 * List<Event<CustomerEvent>> relevantEvents = stream.query(
 *     EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123"))
 * ).toList();
 * EventReference lastRef = relevantEvents.getLast().reference();
 *
 * stream.append(
 *     AppendCriteria.of(
 *         EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123")),
 *         Optional.of(lastRef)
 *     ),
 *     Event.of(new CustomerNameChanged("Jane Doe"), Tags.of("customer", "123"))
 * );
 * }</pre>
 *
 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream (typically a sealed interface)
 * @see EventSource
 * @see EventSink
 * @see EventStreamId
 * @see org.sliceworkz.eventstore.EventStore#getEventStream(EventStreamId)
 */
public interface EventStream<DOMAIN_EVENT_TYPE> extends EventSource<DOMAIN_EVENT_TYPE>, EventSink<DOMAIN_EVENT_TYPE> {

	/**
	 * Returns the identifier of this event stream.
	 * <p>
	 * The stream ID contains the context and purpose that uniquely identify this stream
	 * within the event store.
	 *
	 * @return the EventStreamId for this stream
	 */
	EventStreamId id ( );
	
	EventStream<DOMAIN_EVENT_TYPE> withPurpose ( String purpose );

}
