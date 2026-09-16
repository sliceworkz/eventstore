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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.inmem.shredding.InMemoryShreddingKeyStore;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link EventStore#on(EventStorage)} is the one way application code turns a storage into a store,
 * and every argument the {@link EventStoreFactory} SPI takes is a call on its builder with the default
 * the storage builders use. This pins that each call reaches the built store, that each default is
 * the documented one, and that the store built does not own the storage. The argument checks that
 * need no implementation are pinned in the api module ({@code EventStoreTest}).
 */
public class EventStoreBuilderTest {

	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");
	private static final EventStreamId STREAM = EventStreamId.forContext("contacts");

	@Test
	void theRegistryGivenIsWhereTheMetersLand ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build();
			  EventStore store = EventStore.on(storage).meterRegistry(registry).build() ) {
			appendOne(store, "c-1");

			assertNotNull(registry.find("sliceworkz.eventstore.append").counter(), "the append was not metered in the registry the builder was given");
		}
	}

	@Test
	void theRegistryDefaultsToTheGlobalOne ( ) {
		// the global registry is a composite: a child added to it sees what is registered there
		SimpleMeterRegistry child = new SimpleMeterRegistry();
		Metrics.addRegistry(child);
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build();
			  EventStore store = EventStore.on(storage).build() ) {
			appendOne(store, "c-1");

			assertNotNull(child.find("sliceworkz.eventstore.append").counter(), "a store built with no registry did not meter into Metrics.globalRegistry");
		} finally {
			Metrics.removeRegistry(child);
		}
	}

	@Test
	void theMeterOptionsGivenBoundTheMeters ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build();
			  EventStore store = EventStore.on(storage).meterRegistry(registry).meterOptions(MeterOptions.withoutPurposeBreakdown()).build() ) {
			IntStream.range(0, 20).forEach(i -> appendOne(store, "cust-" + i));

			assertEquals(Set.of(MeterOptions.OVERFLOW_PURPOSE_TAG_VALUE), purposeTagValues(registry), "withoutPurposeBreakdown() did not reach the built store");
		}
	}

	@Test
	void theMeterOptionsDefaultToTheCappedBreakdown ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build();
			  EventStore store = EventStore.on(storage).meterRegistry(registry).build() ) {
			IntStream.range(0, 20).forEach(i -> appendOne(store, "cust-" + i));

			Set<String> purposes = purposeTagValues(registry);
			assertTrue(purposes.contains("cust-0"), "below the cap, a purpose gets its own tag value: " + purposes);
			assertTrue(purposes.stream().noneMatch(MeterOptions.OVERFLOW_PURPOSE_TAG_VALUE::equals), "twenty purposes tripped the default cap: " + purposes);
		}
	}

	@Test
	void theCodecDefaultsToTheStoragesOwn ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().shredding(new InMemoryShreddingKeyStore()).build();
			  EventStore store = EventStore.on(storage).build() ) {
			recordContact(store, "Alice Martin");

			ContactRecorded read = (ContactRecorded) contacts(store).query(EventQuery.matchAll()).getFirst().data();
			assertEquals(Optional.of("Alice Martin"), read.name().toOptional(), "the store did not read through the storage's codec");
			assertTrue(store.shreddingAudit().isPresent(), "the store has no audit, so the storage's codec never reached it");
		}
	}

	@Test
	void aStorageWithoutShreddingGivesAStoreWithout ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build();
			  EventStore store = EventStore.on(storage).build() ) {
			assertEquals(Optional.empty(), store.shreddingAudit());
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> contacts(store));
			assertTrue(e.getMessage().contains("Shreddable"), "unexpected registration failure: " + e.getMessage());
		}
	}

	@Test
	void theCodecGivenWinsOverTheStoragesOwn ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().shredding(new InMemoryShreddingKeyStore()).build();
			  EventStore writer = EventStore.on(storage).build();
			  EventStore reader = EventStore.on(storage).shredding(ShreddingCodec.withholdingAll()).build() ) {
			recordContact(writer, "Alice Martin");

			ContactRecorded read = (ContactRecorded) contacts(reader).query(EventQuery.matchAll()).getFirst().data();
			assertInstanceOf(Shreddable.Withheld.class, read.name(), "the reader read with the storage's codec rather than its own");
			assertEquals(Optional.empty(), reader.shreddingAudit(), "the withholding codec has no audit, so this one came from the storage's codec");
		}
	}

	@Test
	void theBuiltStoreDoesNotOwnTheStorage ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore store = EventStore.on(storage).build();
			appendOne(store, "c-1");
			store.close();

			assertThrows(EventStorageClosedException.class, () -> appendOne(store, "c-1"), "the closed store still serves");
			assertEquals(1, storage.query(org.sliceworkz.eventstore.query.EventFilter.matchAll(), EventStreamId.anyContext(), null,
					org.sliceworkz.eventstore.query.Limit.none(), EventStorage.QueryDirection.FORWARD).size(),
					"closing the store closed the storage it was built on");

			// and the composition for one handle on both still applies
			EventStore owning = EventStore.owning(EventStore.on(storage).build(), storage);
			owning.close();
			assertThrows(EventStorageClosedException.class, () -> storage.getBookmarks(), "closing the owning store left the storage open");
		}
	}

	@Test
	void eachBuildIsAFurtherStoreOnTheSameStorage ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			EventStore.Builder builder = EventStore.on(storage);
			EventStore first = builder.build();
			EventStore second = builder.build();
			first.close();

			appendOne(second, "c-1");
			assertEquals(1, stock(second, "c-1").query(EventQuery.matchAll()).size(), "closing one store affected another built by the same builder");
			assertNull(second.shreddingAudit().orElse(null));
			second.close();
		}
	}

	private static Set<String> purposeTagValues ( SimpleMeterRegistry registry ) {
		return registry.getMeters().stream()
				.map(Meter::getId)
				.map(id -> id.getTag("purpose"))
				.filter(java.util.Objects::nonNull)
				.collect(Collectors.toSet());
	}

	private static EventStream<StockEvent> stock ( EventStore store, String purpose ) {
		return store.getEventStream(EventStreamId.forContext("stock").withPurpose(purpose), StockEvent.class);
	}

	private static void appendOne ( EventStore store, String purpose ) {
		stock(store, purpose).append(Event.of(new StockEvent.StockAdded("SKU-1", 1), Tags.none()));
	}

	private static EventStream<ContactEvent> contacts ( EventStore store ) {
		return store.getEventStream(STREAM, ContactEvent.class);
	}

	private static void recordContact ( EventStore store, String name ) {
		contacts(store).append(Event.of(new ContactRecorded("c-1", Shreddable.of(name, ALICE)), Tags.none()));
	}

	public sealed interface StockEvent {
		record StockAdded ( String sku, int quantity ) implements StockEvent { }
	}

	public sealed interface ContactEvent { }

	public record ContactRecorded ( String contactId, Shreddable<String> name ) implements ContactEvent { }

}
