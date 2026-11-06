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

import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * A projection combines an {@link EventQuery} with an {@link EventHandler} to build read models from event streams.
 * <p>
 * Projections are the primary mechanism for creating materialized views of domain events. They define which events
 * are relevant (via the {@link EventQuery}) and how those events should be processed (via the {@link EventHandler}).
 * The projection has access to the full {@link org.sliceworkz.eventstore.events.Event} metadata including stream, reference,
 * type, tags, and timestamp.
 * <p>
 * Projections are typically processed by a {@link Projector}, which efficiently streams matching events from an
 * {@link org.sliceworkz.eventstore.stream.EventSource} and applies them to the projection handler in batches.
 *
 * <h2>A filter, and a query only where the read needs one:</h2>
 * <p>
 * A projection says which events it depends on with {@link #eventFilter()}, and that is all most
 * projections say: the {@link Projector} reads that filter forward, which is what a projection reading
 * history in order wants. {@link #eventQuery()} is built from it and is overridden only by a projection
 * whose read is something else — the newest event backwards with a limit of one, say — and then on top
 * of {@link #eventFilter()}, so the two never describe different events. The alternative — the query as
 * the one abstract method — loses because it makes every projection wrap its filter in
 * {@code EventQuery.of(...)} for the few that read differently, and a filter is what the rest of this API
 * (an append's consistency boundary, a translator, a dispatcher) already asks for.
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
 *     public EventFilter eventFilter() {
 *         // Only process CustomerRegistered events with a region tag
 *         return EventFilter.forTypes(CustomerRegistered.class).tagged("region", "EU");
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
 *     .into(projection)
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
 *         return EventQuery.of(EventFilter.forTypes(StockCounted.class).tagged("product", product))
 *                          .backwards().limit(1);
 *     }
 *
 *     @Override
 *     public EventFilter eventFilter() {
 *         // Only process movements — savepoints are handled by initQuery
 *         return EventFilter.forTypes(StockAdded.class, StockPicked.class).tagged("product", product);
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
 * @see EventQuery
 * @see EventHandler
 */
public interface Projection<CONSUMED_EVENT_TYPE> extends EventHandler<CONSUMED_EVENT_TYPE> {

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
	 * "No initialization query" is {@code EventQuery.of(EventFilter.matchNone())}, the same spelling an absent filter has
	 * everywhere else in this API ({@link org.sliceworkz.eventstore.stream.AppendCriteria#none()} is built
	 * on {@link org.sliceworkz.eventstore.query.EventFilter#matchNone()}), and it is what this default
	 * answers. A projection written before that default may still answer {@code null}; the
	 * {@link Projector} treats the two identically.
	 *
	 * @return the initialization EventQuery, or a query over {@link org.sliceworkz.eventstore.query.EventFilter#matchNone()} (the default) to skip
	 */
	default EventQuery initQuery ( ) {
		return EventQuery.of(EventFilter.matchNone());
	}

	/**
	 * Returns the filter that defines which events this projection depends on.
	 * <p>
	 * The filter names the event types and tags that are relevant for this projection; only events
	 * matching it are passed to the {@link #when(org.sliceworkz.eventstore.events.Event)} method. It is
	 * read forward, through {@link #eventQuery()}, unless that is overridden.
	 *
	 * @return the filter defining the events this projection processes, never null
	 */
	EventFilter eventFilter ( );

	/**
	 * Returns how this projection's events are read: {@link #eventFilter()} read forward, with no limit
	 * and no read bound.
	 * <p>
	 * Override it only for a read that is something else — backwards, limited, or bounded — and build
	 * it on {@link #eventFilter()}: {@code EventQuery.of(eventFilter()).backwards().limit(1)}.
	 * <p>
	 * A {@link Projector} reads it once per run: the query storage is asked with is the query every
	 * event of that run is matched against, so an answer that changes between calls takes effect on
	 * the next run, never in the middle of one.
	 *
	 * @return the EventQuery defining how the events this projection processes are read, never null
	 * @throws IllegalStateException if {@link #eventFilter()} answers {@code null}
	 */
	default EventQuery eventQuery ( ) {
		EventFilter filter = eventFilter();
		if ( filter == null ) {
			throw new IllegalStateException("projection %s answered null to eventFilter(); a projection has to say which events it depends on (EventFilter.matchAll() or EventFilter.matchNone() to say so)".formatted(this));
		}
		return EventQuery.of(filter);
	}

}
