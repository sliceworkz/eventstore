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
package org.sliceworkz.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Pins {@code sliceworkz.eventstore.append.position}, the gauge reporting the highest position this
 * store has appended.
 * <p>
 * A gauge cannot be re-registered: Micrometer keeps the first registration for a given name and tags
 * and ignores every later one, logging a warning the first time. The value each stream reported into
 * was a per-stream {@code AtomicReference}, so only the very first stream ever created for a given
 * tag set was actually wired to the series — every later stream appended into a holder nothing was
 * reading. Worse, Micrometer holds gauge state <em>weakly</em>: once that first stream was collected
 * the series went to {@code NaN} and could never come back, since no later registration is accepted.
 * In the usage the documentation recommends — a stream obtained per operation and dropped — that made
 * the gauge permanently {@code NaN} almost immediately.
 * <p>
 * None of this fails loudly. The metric is simply absent or stuck, which is exactly the kind of thing
 * only a test notices.
 */
class AppendPositionGaugeTest {

	private static final String GAUGE = "sliceworkz.eventstore.append.position";

	sealed interface StockEvent {
		record StockAdded ( String sku, int quantity ) implements StockEvent { }
	}

	private static double gaugeValue ( SimpleMeterRegistry registry ) {
		Gauge gauge = registry.find(GAUGE).gauge();
		assertNotNull(gauge, "no " + GAUGE + " gauge was registered");
		return gauge.value();
	}

	private static long appendOne ( EventStream<StockEvent> stream ) {
		return stream.append(AppendCriteria.none(),
					Event.of(new StockEvent.StockAdded("SKU-1", 1), Tags.none()))
				.getFirst().reference().position();
	}

	@Test
	void anyStreamUpdatesTheGaugeNotOnlyTheFirstOneCreated ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get().eventStore(storage, registry);
			EventStreamId streamId = EventStreamId.forContext("stock");

			EventStream<StockEvent> first = eventStore.getEventStream(streamId, StockEvent.class);
			EventStream<StockEvent> second = eventStore.getEventStream(streamId, StockEvent.class);

			assertTrue(Double.isNaN(gaugeValue(registry)),
					"the gauge reported a position before anything was appended");

			// the second stream is the one that would silently report into a holder nobody reads
			long positionFromSecond = appendOne(second);
			assertEquals((double) positionFromSecond, gaugeValue(registry),
					"appending through a stream other than the first one created did not move the gauge — each stream registered its own AtomicReference and only the first registration is kept");

			long positionFromFirst = appendOne(first);
			assertEquals((double) positionFromFirst, gaugeValue(registry),
					"appending through the first stream did not move the gauge");
		}
	}

	@Test
	void exactlyOneGaugeIsRegisteredHoweverManyStreamsAreOpened ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get().eventStore(storage, registry);
			EventStreamId streamId = EventStreamId.forContext("stock");

			for ( int i = 0; i < 25; i++ ) {
				eventStore.getEventStream(streamId, StockEvent.class);
			}

			assertEquals(1, registry.find(GAUGE).gauges().size(),
					"more than one " + GAUGE + " gauge exists for a single tag set");
		}
	}

	@Test
	void theGaugeKeepsWorkingAfterTheStreamThatFirstRegisteredItIsCollected ( ) throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get().eventStore(storage, registry);
			EventStreamId streamId = EventStreamId.forContext("stock");

			EventStream<StockEvent> first = eventStore.getEventStream(streamId, StockEvent.class);
			EventStream<StockEvent> kept = eventStore.getEventStream(streamId, StockEvent.class);
			appendOne(first);

			// drop the stream that registered the gauge and give the collector every chance to take it:
			// micrometer references gauge state weakly, so a per-stream holder dies here
			first = null;
			for ( int i = 0; i < 5; i++ ) {
				System.gc();
				Thread.sleep(50);
			}

			assertTrue(!Double.isNaN(gaugeValue(registry)),
					"the gauge went NaN once the stream that registered it was collected — its state must not be owned by a single stream");

			long position = appendOne(kept);
			assertEquals((double) position, gaugeValue(registry),
					"the gauge stopped following appends after the stream that registered it was collected");
		}
	}

	@Test
	void theGaugeReportsTheHighestPositionSeenNotTheMostRecent ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get().eventStore(storage, registry);
			EventStreamId streamId = EventStreamId.forContext("stock");

			EventStream<StockEvent> stream = eventStore.getEventStream(streamId, StockEvent.class);
			long highest = 0;
			for ( int i = 0; i < 5; i++ ) {
				highest = Math.max(highest, appendOne(stream));
			}
			assertEquals((double) highest, gaugeValue(registry));
		}
	}
}
