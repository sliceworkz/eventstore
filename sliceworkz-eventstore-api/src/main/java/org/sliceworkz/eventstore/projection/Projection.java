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
 * @param <CONSUMED_EVENT_TYPE> the type of domain events this projection processes (typically a sealed interface)
 * @see Projector
 * @see ProjectionWithoutMetaData
 * @see EventQuery
 * @see EventWithMetaDataHandler
 */
public interface Projection<CONSUMED_EVENT_TYPE> extends EventWithMetaDataHandler<CONSUMED_EVENT_TYPE> {

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
