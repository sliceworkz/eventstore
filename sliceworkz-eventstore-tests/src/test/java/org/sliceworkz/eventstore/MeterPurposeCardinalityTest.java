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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Pins the bound on how many distinct {@code purpose} tag values a store's meters can take.
 * <p>
 * Every meter the store registers carries the stream's {@code purpose}, and purpose is documented —
 * and used throughout this library's own examples — as an entity id: {@code
 * forContext("customer").withPurpose("123")}. A Micrometer registry never evicts a meter, so used
 * that way the meters grow with every customer the process has ever seen and nothing reclaims them:
 * measured at 15 meters and ~5.5KB of heap per distinct purpose, so ~550MB and 1.5 million meters at
 * 100.000 customers. Dropping the stream handle releases none of it.
 * <p>
 * None of this fails, which is what makes it worth a test: the store keeps working, the numbers stay
 * correct, and the process simply gets heavier for as long as it runs.
 *
 * @see MeterOptions
 */
class MeterPurposeCardinalityTest {

	private static final String OVERFLOW = MeterOptions.OVERFLOW_PURPOSE_TAG_VALUE;

	sealed interface StockEvent {
		record StockAdded ( String sku, int quantity ) implements StockEvent { }
	}

	/** Every {@code purpose} tag value the registry has meters for. */
	private static Set<String> purposeTagValues ( SimpleMeterRegistry registry ) {
		return registry.getMeters().stream()
				.map(Meter::getId)
				.map(id -> id.getTag("purpose"))
				.filter(java.util.Objects::nonNull)
				.collect(Collectors.toSet());
	}

	private static EventStream<StockEvent> openStream ( EventStore eventStore, String purpose ) {
		return eventStore.getEventStream(EventStreamId.forContext("stock").withPurpose(purpose), StockEvent.class);
	}

	private static void appendOne ( EventStream<StockEvent> stream ) {
		stream.append(AppendCriteria.none(), Event.of(new StockEvent.StockAdded("SKU-1", 1), Tags.none()));
	}

	@Test
	void purposesBelowTheCapKeepTheirOwnTagValue ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get()
					.eventStore(storage, registry, MeterOptions.withMaxPurposeTagValues(5));

			IntStream.range(0, 5).forEach(i -> appendOne(openStream(eventStore, "cust-" + i)));

			assertEquals(Set.of("cust-0", "cust-1", "cust-2", "cust-3", "cust-4"), purposeTagValues(registry),
					"a store under its cap pooled purposes it had room for");
		}
	}

	@Test
	void purposesBeyondTheCapArePooledAndStopCreatingMeters ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get()
					.eventStore(storage, registry, MeterOptions.withMaxPurposeTagValues(5));

			IntStream.range(0, 5).forEach(i -> appendOne(openStream(eventStore, "cust-" + i)));
			int metersAtCap = registry.getMeters().size();

			IntStream.range(5, 500).forEach(i -> appendOne(openStream(eventStore, "cust-" + i)));

			assertEquals(6, purposeTagValues(registry).size(),
					"the five admitted purposes plus '" + OVERFLOW + "' should be all there is");
			assertTrue(purposeTagValues(registry).contains(OVERFLOW),
					"purposes beyond the cap were not pooled under '" + OVERFLOW + "'");
			assertTrue(registry.getMeters().size() <= metersAtCap * 2,
					"495 further purposes should add one tag set's worth of meters, not 495 — found "
							+ registry.getMeters().size() + " meters against " + metersAtCap + " at the cap");
		}
	}

	@Test
	void aPooledPurposeStillCountsItsEvents ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get()
					.eventStore(storage, registry, MeterOptions.withMaxPurposeTagValues(1));

			appendOne(openStream(eventStore, "admitted"));
			appendOne(openStream(eventStore, "pooled-1"));
			appendOne(openStream(eventStore, "pooled-2"));

			double pooledAppends = registry.find("sliceworkz.eventstore.append").tag("purpose", OVERFLOW).counter().count();
			assertEquals(2.0, pooledAppends,
					"appends to pooled purposes were lost rather than summed under '" + OVERFLOW + "'");
		}
	}

	@Test
	void anAdmittedPurposeKeepsItsTagValueAfterTheCapIsReached ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get()
					.eventStore(storage, registry, MeterOptions.withMaxPurposeTagValues(2));

			appendOne(openStream(eventStore, "first"));
			appendOne(openStream(eventStore, "second"));
			appendOne(openStream(eventStore, "overflowing"));

			// re-opening an admitted purpose after the cap was hit must not demote it: a dashboard built
			// on that series would otherwise lose it the moment traffic widened
			appendOne(openStream(eventStore, "first"));

			assertEquals(2.0, registry.find("sliceworkz.eventstore.append").tag("purpose", "first").counter().count(),
					"an admitted purpose was demoted to '" + OVERFLOW + "' once the cap was reached");
		}
	}

	@Test
	void theGaugeStateTheStoreHoldsIsBoundedToo ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get()
					.eventStore(storage, registry, MeterOptions.withMaxPurposeTagValues(3));

			IntStream.range(0, 200).forEach(i -> appendOne(openStream(eventStore, "cust-" + i)));

			// one gauge per tag set, and the store holds one AtomicLong per gauge. A MeterFilter cannot
			// bound that map -- it is keyed on the tags the store asks for, not on what the registry keeps
			assertEquals(4, registry.find("sliceworkz.eventstore.append.position").gauges().size(),
					"the store registered a position gauge per purpose despite the cap");
		}
	}

	@Test
	void withoutPurposeBreakdownEveryPurposeIsPooled ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get()
					.eventStore(storage, registry, MeterOptions.withoutPurposeBreakdown());

			IntStream.range(0, 50).forEach(i -> appendOne(openStream(eventStore, "cust-" + i)));

			assertEquals(Set.of(OVERFLOW), purposeTagValues(registry),
					"withoutPurposeBreakdown() still gave some purpose its own tag value");
		}
	}

	@Test
	void aStoreThatWasNeverConfiguredIsStillBounded ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			// the two-argument overload: what every existing caller uses
			EventStore eventStore = EventStoreFactory.get().eventStore(storage, registry);

			int beyondTheDefault = MeterOptions.DEFAULT_MAX_PURPOSE_TAG_VALUES + 50;
			IntStream.range(0, beyondTheDefault).forEach(i -> openStream(eventStore, "cust-" + i));

			assertEquals(MeterOptions.DEFAULT_MAX_PURPOSE_TAG_VALUES + 1, purposeTagValues(registry).size(),
					"a store built without MeterOptions did not apply the default cap");
		}
	}

	@Test
	void buildStoreAppliesTheOptionsItWasGiven ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStore eventStore = InMemoryEventStorage.newBuilder()
				.meterRegistry(registry)
				.meterOptions(MeterOptions.withMaxPurposeTagValues(2))
				.buildStore() ) {

			IntStream.range(0, 20).forEach(i -> appendOne(openStream(eventStore, "cust-" + i)));

			assertEquals(3, purposeTagValues(registry).size(),
					"buildStore() ignored the meterOptions it was given");
		}
	}

	@Test
	void theCapHoldsExactlyUnderConcurrentFirstUseOfDistinctPurposes ( ) throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			int cap = 20;
			int threads = 16;
			EventStore eventStore = EventStoreFactory.get()
					.eventStore(storage, registry, MeterOptions.withMaxPurposeTagValues(cap));

			// every thread opens the same 200 distinct purposes, all for the first time, from one signal:
			// a cap enforced with a size() check rather than a claimed slot overshoots here
			CountDownLatch start = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(threads);
			Set<Throwable> failures = ConcurrentHashMap.newKeySet();
			for ( int t = 0; t < threads; t++ ) {
				Thread.ofVirtual().start(() -> {
					try {
						start.await();
						for ( int i = 0; i < 200; i++ ) {
							openStream(eventStore, "cust-" + i);
						}
					} catch (Throwable e) {
						failures.add(e);
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertTrue(done.await(60, TimeUnit.SECONDS), "threads did not finish");
			assertTrue(failures.isEmpty(), "opening streams concurrently failed: " + failures);

			assertEquals(cap + 1, purposeTagValues(registry).size(),
					"concurrent first use of distinct purposes pushed the store past its cap");
		}
	}

	@Test
	void wildcardAndDefaultPurposesAreOrdinaryValues ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore eventStore = EventStoreFactory.get()
					.eventStore(storage, registry, MeterOptions.withMaxPurposeTagValues(5));

			eventStore.getEventStream(EventStreamId.forContext("stock"), StockEvent.class);              // "default"
			eventStore.getEventStream(EventStreamId.forContext("stock").anyPurpose(), StockEvent.class); // "" wildcard

			assertEquals(Set.of("default", ""), purposeTagValues(registry),
					"the default and wildcard purposes should be tagged as themselves");
		}
	}

	@Test
	void aNegativeCapIsRejected ( ) {
		assertThrows(IllegalArgumentException.class, () -> MeterOptions.withMaxPurposeTagValues(-1),
				"a negative cap was accepted");
		assertNotNull(MeterOptions.defaults());
		assertEquals(MeterOptions.DEFAULT_MAX_PURPOSE_TAG_VALUES, MeterOptions.defaults().maxPurposeTagValues());
		assertTrue(MeterOptions.withoutPurposeBreakdown().poolsEveryPurpose());
	}

}
