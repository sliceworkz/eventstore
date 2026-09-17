/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
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
 * Projections - building read models and views from event streams.
 * <p>
 * This package provides the projection abstraction for processing events to build read models, reports,
 * and other derived views. Projections combine event queries with event handlers to transform
 * event streams into queryable state.
 *
 * <h2>Core Components:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.projection.Projection} - An {@link org.sliceworkz.eventstore.events.EventHandler}
 *       with the {@link org.sliceworkz.eventstore.query.EventQuery} naming the events it handles</li>
 *   <li>{@link org.sliceworkz.eventstore.projection.BatchAwareProjection} - A projection told where each batch
 *       starts and ends, for one that commits its work elsewhere</li>
 *   <li>{@link org.sliceworkz.eventstore.projection.Projector} - Execution engine for running projections with metrics and resumability</li>
 * </ul>
 *
 * <h2>Basic Projection Example:</h2>
 * <pre>{@code
 * // Count customers by region. The domain event is event.data(); the region is a tag, so it is
 * // read off the metadata of the same argument
 * class CustomersByRegion implements Projection<CustomerEvent> {
 *
 *     private final Map<String, Integer> customersByRegion = new HashMap<>();
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.forEvents(EventTypesFilter.of(CustomerRegistered.class), Tags.none());
 *     }
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         event.tags().tag("region").ifPresent(region ->
 *             customersByRegion.merge(region.value(), 1, Integer::sum));
 *     }
 * }
 *
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(
 *     EventStreamId.forContext("customer").anyPurpose(),
 *     CustomerEvent.class
 * );
 *
 * CustomersByRegion projection = new CustomersByRegion();
 * ProjectorMetrics metrics = Projector.from(stream).into(projection).build().run();
 *
 * System.out.println("Handled " + metrics.eventsHandled() + " events");
 * }</pre>
 *
 * <h2>Projection with Metadata:</h2>
 * <pre>{@code
 * // Count events per day. The timestamp is an Instant, so the day it falls on depends on the zone
 * // the report is for: name it, rather than the JVM's
 * class EventsByDay implements Projection<CustomerEvent> {
 *
 *     private final Map<LocalDate, Long> eventsByDate = new HashMap<>();
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.matchAll();
 *     }
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         LocalDate date = LocalDate.ofInstant(event.timestamp(), ZoneId.of("Europe/Brussels"));
 *         eventsByDate.merge(date, 1L, Long::sum);
 *     }
 * }
 * }</pre>
 *
 * <h2>Incremental Updates:</h2>
 * <pre>{@code
 * // A projector keeps its position, so a second run handles only what arrived since the first
 * Projector<CustomerEvent> projector = Projector.from(stream).into(projection).build();
 * projector.run();
 * // ... later ...
 * ProjectorMetrics newMetrics = projector.run();
 *
 * // A fresh projector resumes from a reference kept elsewhere
 * Projector<CustomerEvent> resumed = Projector.from(stream).into(projection)
 *     .startingAfter(lastHandled)
 *     .build();
 * }</pre>
 *
 * <h2>Bounded Processing:</h2>
 * <pre>{@code
 * // Process events only up to a specific checkpoint
 * EventReference checkpoint = stream.head().orElse(null);
 * ProjectorMetrics metrics = projector.runUntil(checkpoint);
 * }</pre>
 *
 * <h2>Batch Processing:</h2>
 * <pre>{@code
 * // Page through the stream in smaller batches, or one batch at a time
 * Projector<CustomerEvent> projector = Projector.from(stream).into(projection)
 *     .inBatchesOf(100)
 *     .build();
 *
 * ProjectorMetrics batch = projector.runSingleBatch();
 * }</pre>
 *
 * @see org.sliceworkz.eventstore.projection.Projector
 * @see org.sliceworkz.eventstore.projection.Projection
 * @see org.sliceworkz.eventstore.query.EventQuery
 * @see org.sliceworkz.eventstore.events.EventHandler
 */
package org.sliceworkz.eventstore.projection;
