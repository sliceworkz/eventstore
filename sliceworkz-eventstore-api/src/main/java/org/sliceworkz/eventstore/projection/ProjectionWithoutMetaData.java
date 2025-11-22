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

import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.events.EventWithMetaDataHandler;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * A projection that processes only the event data without accessing event metadata.
 * <p>
 * This interface extends both {@link Projection} and {@link EventHandler}, providing a convenient way to create
 * projections that only need the domain event data itself, not the surrounding metadata (stream, reference, tags, timestamp).
 * The {@link EventHandler} automatically unwraps the event data from the {@link org.sliceworkz.eventstore.events.Event}
 * wrapper, so implementations only need to handle the raw domain event.
 * <p>
 * Use this when your projection logic only depends on the event data itself. If you need access to tags, timestamps,
 * or event references, use the full {@link Projection} interface instead.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Define a projection that calculates total order value
 * public class TotalOrderValue implements ProjectionWithoutMetaData<OrderEvent> {
 *
 *     private BigDecimal total = BigDecimal.ZERO;
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         // Only process OrderPlaced events
 *         return EventQuery.forEvents(
 *             EventTypesFilter.of(OrderPlaced.class),
 *             Tags.none()
 *         );
 *     }
 *
 *     @Override
 *     public void when(OrderEvent event) {
 *         // Handle only the event data, no metadata needed
 *         if (event instanceof OrderPlaced placed) {
 *             total = total.add(placed.amount());
 *         }
 *     }
 *
 *     public BigDecimal getTotal() {
 *         return total;
 *     }
 * }
 *
 * // Process the projection
 * EventStream<OrderEvent> stream = eventStore.getEventStream(streamId, OrderEvent.class);
 * TotalOrderValue projection = new TotalOrderValue();
 *
 * Projector.from(stream)
 *     .towards(projection)
 *     .build()
 *     .run();
 *
 * System.out.println("Total: " + projection.getTotal());
 * }</pre>
 *
 * <h2>Comparison with Projection:</h2>
 * <pre>{@code
 * // ProjectionWithoutMetaData - receives only event data
 * void when(CustomerEvent event) {
 *     if (event instanceof CustomerRegistered registered) {
 *         // Work with event data directly
 *     }
 * }
 *
 * // Projection - receives full event with metadata
 * void when(Event<CustomerEvent> event) {
 *     if (event.data() instanceof CustomerRegistered registered) {
 *         String region = event.tags().get("region").orElse("unknown");
 *         // Can access tags, timestamp, reference, etc.
 *     }
 * }
 * }</pre>
 *
 * @param <CONSUMED_EVENT_TYPE> the type of domain events this projection processes (typically a sealed interface)
 * @see Projection
 * @see EventHandler
 * @see EventQuery
 * @see Projector
 */
public interface ProjectionWithoutMetaData<CONSUMED_EVENT_TYPE> extends Projection<CONSUMED_EVENT_TYPE>, EventHandler<CONSUMED_EVENT_TYPE> {

}
