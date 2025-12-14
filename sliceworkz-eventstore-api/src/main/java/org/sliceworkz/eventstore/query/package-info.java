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
 * Event querying - building and executing queries to retrieve events from the store.
 * <p>
 * This package provides a flexible query API for retrieving events based on types, tags, and time bounds.
 * Queries are central to the Dynamic Consistency Boundary (DCB) pattern, where they define which events
 * constitute "relevant facts" for business decisions.
 *
 * <h2>Query Components:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.query.EventQuery} - Complete query specification combining filters and time bounds</li>
 *   <li>{@link org.sliceworkz.eventstore.query.EventQueryItem} - Single matching rule combining event types and tags</li>
 *   <li>{@link org.sliceworkz.eventstore.query.EventTypesFilter} - Filters events by their type (class)</li>
 *   <li>{@link org.sliceworkz.eventstore.query.Limit} - Limits the number of events returned</li>
 * </ul>
 *
 * <h2>Query Patterns:</h2>
 *
 * <h3>Match All Events:</h3>
 * <pre>{@code
 * Stream<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll());
 * }</pre>
 *
 * <h3>Filter by Event Type:</h3>
 * <pre>{@code
 * EventQuery query = EventQuery.forEvents(
 *     EventTypesFilter.of(CustomerRegistered.class, CustomerNameChanged.class),
 *     Tags.none()
 * );
 * Stream<Event<CustomerEvent>> events = stream.query(query);
 * }</pre>
 *
 * <h3>Filter by Tags (DCB Pattern):</h3>
 * <pre>{@code
 * // Find all events related to a specific customer
 * EventQuery query = EventQuery.forEvents(
 *     EventTypesFilter.any(),
 *     Tags.of("customer", "cust-123")
 * );
 * Stream<Event<CustomerEvent>> events = stream.query(query);
 * }</pre>
 *
 * <h3>Combined Filters:</h3>
 * <pre>{@code
 * // Find registration and name change events for EU customers
 * EventQuery query = EventQuery.forEvents(
 *     EventTypesFilter.of(CustomerRegistered.class, CustomerNameChanged.class),
 *     Tags.of("region", "EU")
 * );
 * }</pre>
 *
 * <h3>Time-bounded Queries:</h3>
 * <pre>{@code
 * // Query events up to a specific point in time
 * EventReference checkpoint = // ... last known reference
 * EventQuery query = EventQuery.matchAll().until(checkpoint);
 * Stream<Event<CustomerEvent>> events = stream.query(query);
 * }</pre>
 *
 * <h3>Complex OR Queries:</h3>
 * <pre>{@code
 * // Events with tag1:value1 OR tag2:value2
 * EventQuery query1 = EventQuery.forEvents(EventTypesFilter.any(), Tags.of("tag1", "value1"));
 * EventQuery query2 = EventQuery.forEvents(EventTypesFilter.any(), Tags.of("tag2", "value2"));
 * EventQuery combined = query1.combineWith(query2);
 * }</pre>
 *
 * <h2>DCB Integration:</h2>
 * <p>
 * Queries define the "relevant facts" when making business decisions. The same query used to gather
 * decision inputs should be used in {@link org.sliceworkz.eventstore.stream.AppendCriteria} to ensure
 * no new relevant facts have appeared since the decision was made:
 *
 * <pre>{@code
 * // 1. Query relevant events
 * EventQuery query = EventQuery.forEvents(
 *     EventTypesFilter.any(),
 *     Tags.of("account", "acc-123")
 * );
 * List<Event<AccountEvent>> events = stream.query(query).toList();
 *
 * // 2. Make business decision based on events
 * BigDecimal balance = calculateBalance(events);
 * EventReference lastRef = events.getLast().reference();
 *
 * // 3. Append with same query to detect conflicts
 * stream.append(
 *     AppendCriteria.of(query, Optional.of(lastRef)),
 *     Event.of(new MoneyWithdrawn(amount), Tags.of("account", "acc-123"))
 * );
 * }</pre>
 *
 * @see org.sliceworkz.eventstore.query.EventQuery
 * @see org.sliceworkz.eventstore.stream.AppendCriteria
 * @see org.sliceworkz.eventstore.events.Tags
 * @see org.sliceworkz.eventstore.stream.EventSource
 */
package org.sliceworkz.eventstore.query;
