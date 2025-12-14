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
 * Projections - building read models and views from event streams.
 * <p>
 * This package provides the projection abstraction for processing events to build read models, reports,
 * and other derived views. Projections combine event queries with event handlers to transform
 * event streams into queryable state.
 *
 * <h2>Core Components:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.projection.Projection} - Combines query with metadata-aware handler</li>
 *   <li>{@link org.sliceworkz.eventstore.projection.ProjectionWithoutMetaData} - Simpler projection processing only event data</li>
 *   <li>{@link org.sliceworkz.eventstore.projection.Projector} - Execution engine for running projections with metrics and resumability</li>
 * </ul>
 *
 * <h2>Basic Projection Example:</h2>
 * <pre>{@code
 * // Simple projection counting customers by region
 * Map<String, Integer> customersByRegion = new HashMap<>();
 *
 * ProjectionWithoutMetaData<CustomerEvent> projection = new ProjectionWithoutMetaData<>(
 *     EventQuery.forEvents(
 *         EventTypesFilter.of(CustomerRegistered.class),
 *         Tags.none()
 *     ),
 *     event -> {
 *         if (event instanceof CustomerRegistered registered) {
 *             String region = // ... extract region from event
 *             customersByRegion.merge(region, 1, Integer::sum);
 *         }
 *     }
 * );
 *
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(
 *     EventStreamId.forContext("customer").anyPurpose(),
 *     CustomerEvent.class
 * );
 *
 * Projector<CustomerEvent> projector = Projector.from(stream, projection);
 * Projector.ProjectorMetrics metrics = projector.run();
 *
 * System.out.println("Processed " + metrics.eventsProcessed() + " events");
 * System.out.println("Customers by region: " + customersByRegion);
 * }</pre>
 *
 * <h2>Projection with Metadata:</h2>
 * <pre>{@code
 * // Track when events occurred using timestamps
 * Map<LocalDate, Long> eventsByDate = new HashMap<>();
 *
 * Projection<CustomerEvent> projection = new Projection<>(
 *     EventQuery.matchAll(),
 *     event -> {
 *         LocalDate date = event.timestamp().toLocalDate();
 *         eventsByDate.merge(date, 1L, Long::sum);
 *     }
 * );
 * }</pre>
 *
 * <h2>Incremental Updates:</h2>
 * <pre>{@code
 * // Build initial projection
 * Projector<CustomerEvent> projector = Projector.from(stream, projection);
 * Projector.ProjectorMetrics metrics = projector.run();
 * EventReference lastProcessed = metrics.lastProcessedEventReference();
 *
 * // Later, process only new events
 * Projector<CustomerEvent> incrementalProjector = Projector.from(stream, projection)
 *     .skipUntil(lastProcessed);
 * Projector.ProjectorMetrics newMetrics = incrementalProjector.run();
 * }</pre>
 *
 * <h2>Bounded Processing:</h2>
 * <pre>{@code
 * // Process events only up to a specific checkpoint
 * EventReference checkpoint = // ... some reference point
 * Projector.ProjectorMetrics metrics = projector.runUntil(checkpoint);
 * }</pre>
 *
 * <h2>Batch Processing:</h2>
 * <pre>{@code
 * // Process in smaller batches for better memory usage
 * Projector<CustomerEvent> batchProjector = Projector.from(stream, projection)
 *     .batchSize(1000);
 *
 * while (true) {
 *     Projector.ProjectorMetrics metrics = batchProjector.run();
 *     if (metrics.eventsProcessed() == 0) break;
 *
 *     // Save checkpoint
 *     bookmark.set(metrics.lastProcessedEventReference());
 * }
 * }</pre>
 *
 * @see org.sliceworkz.eventstore.projection.Projector
 * @see org.sliceworkz.eventstore.projection.Projection
 * @see org.sliceworkz.eventstore.query.EventQuery
 * @see org.sliceworkz.eventstore.events.EventHandler
 */
package org.sliceworkz.eventstore.projection;
