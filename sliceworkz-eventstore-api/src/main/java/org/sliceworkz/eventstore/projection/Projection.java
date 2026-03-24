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
package org.sliceworkz.eventstore.projection;

import org.sliceworkz.eventstore.events.EventWithMetaDataHandler;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * A projection combines an {@link EventQuery} with an {@link EventWithMetaDataHandler} to build read models from event streams.
 * <p>
 * Projections are the primary mechanism for creating materialized views of domain events. They define which events
 * are relevant (via the {@link EventQuery}) and how those events should be processed (via the {@link EventWithMetaDataHandler}).
 * The projection has access to the full {@link org.sliceworkz.eventstore.events.Event} metadata including stream, reference,
 * type, tags, and timestamp.
 * <p>
 * Projections are typically processed by a {@link Projector}, which efficiently streams matching events from an
 * {@link org.sliceworkz.eventstore.stream.EventSource} and applies them to the projection handler in batches.
 *
 * <h2>Initialization Query (Savepoint Pattern):</h2>
 * <p>
 * Projections can optionally define an {@link #initQuery()} that is executed before the main {@link #eventQuery()}.
 * This enables the <em>savepoint pattern</em>: a backward query with limit 1 finds the most recent savepoint event
 * (a domain event that summarizes all prior state), initializing the read model without replaying the entire stream.
 * The main {@code eventQuery()} then starts from that savepoint's reference, processing only subsequent events.
 * <p>
 * The savepoint is a pure domain event — no special framework support needed. It is up to the developer to decide
 * when to append savepoint events to the stream.
 * <p>
 * <strong>Note:</strong> When bookmarking is enabled on the {@link Projector}, the {@code initQuery()} is ignored
 * because bookmarked projections require every event to be processed. A warning is logged at build time if both
 * are configured.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Define a projection that builds a customer count by region
 * public class CustomerCountByRegion implements Projection<CustomerEvent> {
 *
 *     private final Map<String, Long> countByRegion = new HashMap<>();
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         // Only process CustomerRegistered events with a region tag
 *         return EventQuery.forEvents(
 *             EventTypesFilter.of(CustomerRegistered.class),
 *             Tags.of("region", "*")
 *         );
 *     }
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         if (event.data() instanceof CustomerRegistered registered) {
 *             String region = event.tags().get("region").orElse("unknown");
 *             countByRegion.merge(region, 1L, Long::sum);
 *         }
 *     }
 *
 *     public Map<String, Long> getCounts() {
 *         return Collections.unmodifiableMap(countByRegion);
 *     }
 * }
 *
 * // Process the projection
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 * CustomerCountByRegion projection = new CustomerCountByRegion();
 *
 * ProjectorMetrics metrics = Projector.from(stream)
 *     .towards(projection)
 *     .build()
 *     .run();
 *
 * System.out.println("Processed " + metrics.eventsHandled() + " events");
 * System.out.println("EU customers: " + projection.getCounts().get("EU"));
 * }</pre>
 *
 * <h2>Example Usage with initQuery (Savepoint Pattern):</h2>
 * <pre>{@code
 * // Stock keeping with savepoint optimization
 * sealed interface StockEvent {
 *     record StockAdded(String product, int quantity) implements StockEvent {}
 *     record StockPicked(String product, int quantity) implements StockEvent {}
 *     record StockCounted(String product, int counted) implements StockEvent {} // savepoint
 * }
 *
 * class StockLevelProjection implements Projection<StockEvent> {
 *     private final String product;
 *     private int level = 0;
 *
 *     @Override
 *     public EventQuery initQuery() {
 *         // Find the last stock count (savepoint) for this product
 *         return EventQuery.forEvents(
 *             EventTypesFilter.of(StockCounted.class),
 *             Tags.of("product", product)
 *         ).backwards().limit(1);
 *     }
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         // Only process movements — savepoints are handled by initQuery
 *         return EventQuery.forEvents(
 *             EventTypesFilter.of(StockAdded.class, StockPicked.class),
 *             Tags.of("product", product)
 *         );
 *     }
 *
 *     @Override
 *     public void when(Event<StockEvent> event) {
 *         switch (event.data()) {
 *             case StockCounted c  -> level = c.counted();
 *             case StockAdded a    -> level += a.quantity();
 *             case StockPicked p   -> level -= p.quantity();
 *         }
 *     }
 *
 *     public int level() { return level; }
 * }
 * }</pre>
 *
 * @param <CONSUMED_EVENT_TYPE> the type of domain events this projection processes (typically a sealed interface)
 * @see Projector
 * @see ProjectionWithoutMetaData
 * @see EventQuery
 * @see EventWithMetaDataHandler
 */
public interface Projection<CONSUMED_EVENT_TYPE> extends EventWithMetaDataHandler<CONSUMED_EVENT_TYPE> {

	/**
	 * Returns an optional initialization query that is executed before the main {@link #eventQuery()}.
	 * <p>
	 * When defined (non-null and not match-none), the {@link Projector} will execute this query first,
	 * pass the matching events to the {@link #when(org.sliceworkz.eventstore.events.Event)} handler,
	 * and use the last event's reference as the starting cursor for the main {@link #eventQuery()}.
	 * <p>
	 * This enables the <em>savepoint pattern</em>: use a backward query with limit 1 to find the most
	 * recent savepoint event that summarizes all prior state, avoiding a full replay of the event stream.
	 * <p>
	 * <strong>Note:</strong> This query is ignored when bookmarking is enabled on the {@link Projector},
	 * because bookmarked projections require processing every event. A warning is logged at build time.
	 *
	 * @return the initialization EventQuery, or {@code null} / {@link EventQuery#matchNone()} to skip (default)
	 */
	default EventQuery initQuery ( ) {
		return null;
	}

	/**
	 * Returns the event query that defines which events this projection depends on.
	 * <p>
	 * The query specifies both the event types and tags that are relevant for this projection.
	 * Only events matching this query will be passed to the {@link #when(org.sliceworkz.eventstore.events.Event)} method.
	 *
	 * @return the EventQuery defining the events this projection processes
	 */
	EventQuery eventQuery ( );

}
