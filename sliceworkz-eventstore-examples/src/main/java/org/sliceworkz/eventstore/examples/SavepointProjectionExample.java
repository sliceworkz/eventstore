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
package org.sliceworkz.eventstore.examples;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.SavepointProjectionExample.StockEvent.StockAdded;
import org.sliceworkz.eventstore.examples.SavepointProjectionExample.StockEvent.StockCounted;
import org.sliceworkz.eventstore.examples.SavepointProjectionExample.StockEvent.StockPicked;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Demonstrates the savepoint pattern using {@link Projection#initQuery()}.
 * <p>
 * In this stock keeping example, {@code StockAdded} and {@code StockPicked} events represent
 * individual stock movements. A {@code StockCounted} event is a <em>savepoint</em> that summarizes
 * the stock level at a point in time — it is a pure domain event, not a framework construct.
 * <p>
 * The projection uses {@code initQuery()} to find the most recent savepoint (backward, limit 1),
 * initializing the stock level without replaying the entire stream. The main {@code eventQuery()}
 * then processes only the movements that occurred after that savepoint.
 * <p>
 * This avoids a full replay of potentially thousands of events, while keeping the projection
 * correct and self-contained. It is up to the developer to decide when to append savepoint events.
 * <p>
 * <strong>Key design decisions:</strong>
 * <ul>
 *   <li>{@code StockCounted} is only in the {@code initQuery()}, not in the {@code eventQuery()}.
 *       This means the main query never processes savepoint events, which protects against buggy
 *       historical savepoints — just append a corrected {@code StockCounted} and the next run
 *       picks it up via {@code initQuery()}.</li>
 *   <li>When no savepoint exists (e.g., first run ever), the {@code initQuery()} returns nothing
 *       and the main query replays from the beginning. This degrades gracefully.</li>
 *   <li>The savepoint pattern is an alternative to bookmarking. When using {@code initQuery()},
 *       bookmarking is typically not needed. If bookmarking is enabled, the {@code initQuery()}
 *       is ignored and a warning is logged.</li>
 * </ul>
 */
public class SavepointProjectionExample {

	public static void main ( String[] args ) {

		EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();

		EventStreamId streamId = EventStreamId.forContext("warehouse");
		EventStream<StockEvent> stream = eventstore.getEventStream(streamId, StockEvent.class);

		String product = "WIDGET-42";
		Tags tags = Tags.of("product", product);

		// Simulate stock movements
		stream.append(AppendCriteria.none(), Event.of(new StockAdded(product, 100), tags));
		stream.append(AppendCriteria.none(), Event.of(new StockPicked(product, 10), tags));
		stream.append(AppendCriteria.none(), Event.of(new StockPicked(product, 5), tags));
		stream.append(AppendCriteria.none(), Event.of(new StockAdded(product, 50), tags));
		stream.append(AppendCriteria.none(), Event.of(new StockPicked(product, 20), tags));

		// First projection run — no savepoint exists, replays everything
		StockLevelProjection projection = new StockLevelProjection(product);
		ProjectorMetrics metrics = Projector.from(stream).towards(projection).build().run();

		System.out.println("Stock level: " + projection.level());               // 115
		System.out.println("Events handled: " + metrics.eventsHandled());       // 5

		// Now append a savepoint summarizing the current state
		stream.append(AppendCriteria.none(), Event.of(new StockCounted(product, 115), tags));

		// More movements after the savepoint
		stream.append(AppendCriteria.none(), Event.of(new StockAdded(product, 30), tags));
		stream.append(AppendCriteria.none(), Event.of(new StockPicked(product, 12), tags));

		// Second projection run — initQuery finds the savepoint, only processes 2 movements after it
		StockLevelProjection projection2 = new StockLevelProjection(product);
		ProjectorMetrics metrics2 = Projector.from(stream).towards(projection2).build().run();

		System.out.println("Stock level: " + projection2.level());              // 133
		System.out.println("Events handled: " + metrics2.eventsHandled());      // 3 (1 savepoint + 2 movements)
		System.out.println("Queries done: " + metrics2.queriesDone());          // 2 (1 initQuery + 1 eventQuery)

		// Fix a bad savepoint by appending a corrected one — next run automatically uses the latest
		stream.append(AppendCriteria.none(), Event.of(new StockCounted(product, 133), tags));
		stream.append(AppendCriteria.none(), Event.of(new StockPicked(product, 3), tags));

		StockLevelProjection projection3 = new StockLevelProjection(product);
		ProjectorMetrics metrics3 = Projector.from(stream).towards(projection3).build().run();

		System.out.println("Stock level: " + projection3.level());              // 130
		System.out.println("Events handled: " + metrics3.eventsHandled());      // 2 (1 savepoint + 1 movement)
	}

	/**
	 * A projection that calculates the current stock level for a product using the savepoint pattern.
	 * <p>
	 * The {@code initQuery()} finds the most recent {@code StockCounted} savepoint, and the
	 * {@code eventQuery()} processes only {@code StockAdded} and {@code StockPicked} events after it.
	 */
	static class StockLevelProjection implements Projection<StockEvent> {

		private final String product;
		private int level = 0;

		public StockLevelProjection ( String product ) {
			this.product = product;
		}

		@Override
		public EventQuery initQuery ( ) {
			// Find the last stock count (savepoint) for this product
			return EventQuery.forEvents(
				EventTypesFilter.of(StockCounted.class),
				Tags.of("product", product)
			).backwards().limit(1);
		}

		@Override
		public EventQuery eventQuery ( ) {
			// Only process movements — savepoints are handled exclusively by initQuery
			return EventQuery.forEvents(
				EventTypesFilter.of(StockAdded.class, StockPicked.class),
				Tags.of("product", product)
			);
		}

		@Override
		public void when ( Event<StockEvent> event ) {
			switch ( event.data() ) {
				case StockCounted c  -> level = c.counted();
				case StockAdded a    -> level += a.quantity();
				case StockPicked p   -> level -= p.quantity();
			}
		}

		public int level ( ) {
			return level;
		}

	}

	sealed interface StockEvent {

		record StockAdded ( String product, int quantity ) implements StockEvent { }

		record StockPicked ( String product, int quantity ) implements StockEvent { }

		/**
		 * Savepoint event that summarizes the stock count at a certain moment in time.
		 * This is a pure domain event — no special framework support needed.
		 * It is up to the developer to decide when to append this event to the stream.
		 */
		record StockCounted ( String product, int counted ) implements StockEvent { }

	}

}
