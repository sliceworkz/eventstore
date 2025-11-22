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

import java.util.Collections;
import java.util.List;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EphemeralEvent;

/**
 * Interface for writing events to an event stream.
 * <p>
 * EventSink provides the capability to append events to the event store with support for
 * optimistic locking through {@link AppendCriteria}. This is a key component of the
 * Dynamic Consistency Boundary (DCB) pattern, enabling conditional appends based on
 * relevant historical facts.
 * <p>
 * When events are appended, they are transformed from {@link EphemeralEvent}s (lightweight events
 * without position information) to full {@link Event}s with assigned references, timestamps, and
 * stream associations.
 * <p>
 * EventSink is typically accessed through {@link EventStream}, which combines reading and writing capabilities.
 *
 * <h2>Append Modes:</h2>
 * <ul>
 *   <li><strong>Simple append</strong>: Use {@code AppendCriteria.none()} to append without any conditions</li>
 *   <li><strong>Conditional append (DCB)</strong>: Use {@code AppendCriteria.of(query, lastRef)} to implement
 *       optimistic locking based on relevant facts. The append will fail if new events matching the query
 *       have been added since the last reference.</li>
 * </ul>
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(
 *     EventStreamId.forContext("customer").withPurpose("123"),
 *     CustomerEvent.class
 * );
 *
 * // Simple append without conditions
 * List<Event<CustomerEvent>> appended = stream.append(
 *     AppendCriteria.none(),
 *     Event.of(new CustomerRegistered("John Doe"), Tags.of("region", "EU"))
 * );
 *
 * // Conditional append with optimistic locking (DCB pattern)
 * // First, query relevant facts
 * List<Event<CustomerEvent>> relevantEvents = stream.query(
 *     EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123"))
 * ).toList();
 *
 * // Make decision based on relevant facts
 * EventReference lastRelevantRef = relevantEvents.isEmpty()
 *     ? null
 *     : relevantEvents.getLast().reference();
 *
 * // Attempt conditional append - will fail if new relevant facts have emerged
 * try {
 *     stream.append(
 *         AppendCriteria.of(
 *             EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123")),
 *             Optional.ofNullable(lastRelevantRef)
 *         ),
 *         Event.of(new CustomerNameChanged("Jane Doe"), Tags.of("customer", "123"))
 *     );
 * } catch (OptimisticLockingException e) {
 *     // New relevant facts have emerged - retry decision
 * }
 *
 * // Batch append multiple events
 * List<Event<CustomerEvent>> batchAppended = stream.append(
 *     AppendCriteria.none(),
 *     List.of(
 *         Event.of(new CustomerAddressChanged("123 Main St"), Tags.of("customer", "123")),
 *         Event.of(new CustomerEmailChanged("john@example.com"), Tags.of("customer", "123"))
 *     )
 * );
 * }</pre>
 *
 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream (typically a sealed interface)
 * @see EventStream
 * @see EventSource
 * @see AppendCriteria
 * @see EphemeralEvent
 * @see Event
 */
public interface EventSink<DOMAIN_EVENT_TYPE> {

	/**
	 * Appends a list of events to the stream with conditional logic based on append criteria.
	 * <p>
	 * This is the primary append method. Events are provided as {@link EphemeralEvent}s and are
	 * converted to full {@link Event}s upon successful append, with assigned references, timestamps,
	 * and position information.
	 * <p>
	 * When using {@link AppendCriteria} with optimistic locking, the append will only succeed if
	 * no new events matching the criteria's query have been added since the expected last event reference.
	 * If conflicting events are detected, an {@link OptimisticLockingException}
	 * is thrown.
	 *
	 * @param appendCriteria the criteria determining whether the append should proceed (use AppendCriteria.none() for unconditional append)
	 * @param events the list of ephemeral events to append
	 * @return a list of fully-formed Events with assigned references and metadata
	 * @throws OptimisticLockingException if append criteria are violated (new relevant facts detected)
	 * @see AppendCriteria
	 */
	List<Event<DOMAIN_EVENT_TYPE>> append ( AppendCriteria appendCriteria, List<EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> events );

	/**
	 * Appends a single event to the stream with conditional logic based on append criteria.
	 * <p>
	 * Convenience method for appending a single event. Delegates to {@link #append(AppendCriteria, List)}
	 * with a single-element list.
	 *
	 * @param appendCriteria the criteria determining whether the append should proceed
	 * @param event the ephemeral event to append
	 * @return a list containing the single fully-formed Event with assigned reference and metadata
	 * @throws OptimisticLockingException if append criteria are violated
	 */
	default List<Event<DOMAIN_EVENT_TYPE>> append ( AppendCriteria appendCriteria, EphemeralEvent<? extends DOMAIN_EVENT_TYPE> event ) {
		return append(appendCriteria, Collections.singletonList(event));
	}

}
