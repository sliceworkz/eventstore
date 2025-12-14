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
/**
 * Event streams - reading, writing, and subscribing to event streams.
 * <p>
 * This package provides the stream abstraction for organizing and accessing events:
 *
 * <h2>Core Stream Interfaces:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.stream.EventStream} - Combined read/write interface for a specific stream</li>
 *   <li>{@link org.sliceworkz.eventstore.stream.EventSource} - Read-only interface for querying events</li>
 *   <li>{@link org.sliceworkz.eventstore.stream.EventSink} - Write-only interface for appending events</li>
 *   <li>{@link org.sliceworkz.eventstore.stream.EventStreamId} - Stream identifier with context and purpose</li>
 * </ul>
 *
 * <h2>Dynamic Consistency Boundary (DCB) Support:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.stream.AppendCriteria} - Defines optimistic locking rules for conditional appends</li>
 *   <li>{@link org.sliceworkz.eventstore.stream.OptimisticLockingException} - Thrown when append criteria validation fails</li>
 * </ul>
 *
 * <h2>Event Subscriptions:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.stream.EventStreamConsistentAppendListener} - Immediate notification with full events</li>
 *   <li>{@link org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener} - Delayed notification with reference only</li>
 *   <li>{@link org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentBookmarkListener} - Notification on bookmark updates</li>
 * </ul>
 *
 * <h2>Example Stream Usage:</h2>
 * <pre>{@code
 * // Get a stream for a specific customer
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("cust-123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * // Simple append without optimistic locking
 * stream.append(
 *     AppendCriteria.none(),
 *     Event.of(new CustomerRegistered("John"), Tags.of("region", "EU"))
 * );
 *
 * // Conditional append with DCB pattern
 * List<Event<CustomerEvent>> relevantEvents = stream.query(
 *     EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "cust-123"))
 * ).toList();
 *
 * EventReference lastRef = relevantEvents.getLast().reference();
 *
 * try {
 *     stream.append(
 *         AppendCriteria.of(
 *             EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "cust-123")),
 *             Optional.of(lastRef)
 *         ),
 *         Event.of(new CustomerNameChanged("Jane"), Tags.of("customer", "cust-123"))
 *     );
 * } catch (OptimisticLockingException e) {
 *     // Concurrent modification detected - retry with fresh data
 * }
 *
 * // Subscribe to new events
 * stream.subscribe((EventStreamConsistentAppendListener<CustomerEvent>) events -> {
 *     events.forEach(event -> System.out.println("New event: " + event.data()));
 * });
 * }</pre>
 *
 * @see org.sliceworkz.eventstore.stream.EventStream
 * @see org.sliceworkz.eventstore.stream.AppendCriteria
 * @see org.sliceworkz.eventstore.events
 * @see org.sliceworkz.eventstore.query
 */
package org.sliceworkz.eventstore.stream;
