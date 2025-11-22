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
 * Core EventStore API - main entry points and factory for accessing the event store.
 * <p>
 * This package contains the primary interfaces for interacting with the Sliceworkz EventStore:
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.EventStore} - The main interface for obtaining event streams</li>
 *   <li>{@link org.sliceworkz.eventstore.EventStoreFactory} - Factory for creating EventStore instances via ServiceLoader</li>
 * </ul>
 * <p>
 * <h2>Quick Start Example:</h2>
 * <pre>{@code
 * // Create EventStore with in-memory storage (for testing)
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 *
 * // Get an event stream for a specific context and purpose
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * // Append and query events
 * stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("John"), Tags.none()));
 * List<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll()).toList();
 * }</pre>
 * <p>
 * This implementation is fully compliant with the Dynamic Consistency Boundary (DCB) specification,
 * providing tag-based querying, optimistic locking, and flexible event storage backends.
 *
 * @see org.sliceworkz.eventstore.EventStore
 * @see org.sliceworkz.eventstore.EventStoreFactory
 * @see org.sliceworkz.eventstore.stream
 * @see org.sliceworkz.eventstore.events
 * @see org.sliceworkz.eventstore.query
 */
package org.sliceworkz.eventstore;
