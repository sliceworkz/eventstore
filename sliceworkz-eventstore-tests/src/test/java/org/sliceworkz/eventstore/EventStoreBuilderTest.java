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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.inmem.fs.InMemoryFsEventStorage;
import org.sliceworkz.eventstore.observability.EventStoreObserver;
import org.sliceworkz.eventstore.observability.Observation;
import org.sliceworkz.eventstore.infra.inmem.shredding.InMemoryShreddingKeyStore;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.RecordingObserver;
import org.sliceworkz.eventstore.testing.RecordingObserver.Signal;


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
	void theObserverGivenIsWhatTheStoreReportsTo ( ) {
		RecordingObserver observer = new RecordingObserver();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build();
			  EventStore store = EventStore.on(storage).observer(observer).build() ) {
			appendOne(store, "c-1");

			assertEquals(1, observer.recordings(Observation.Append.class).size(), "the append was not reported to the observer the builder was given");
		}
	}

	@Test
	void theObserverDefaultsToTheStoragesOwn ( ) {
		RecordingObserver observer = new RecordingObserver();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().observer(observer).build();
			  EventStore store = EventStore.on(storage).build() ) {
			appendOne(store, "c-1");

			assertEquals(1, observer.recordings(Observation.Append.class).size(), "a store built with no observer did not report to the storage's own");
		}
	}

	@Test
	void anObserverGivenTakesPrecedenceOverTheStoragesOwn ( ) {
		RecordingObserver storages = new RecordingObserver();
		RecordingObserver stores = new RecordingObserver();
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().observer(storages).build();
			  EventStore store = EventStore.on(storage).observer(stores).build() ) {
			appendOne(store, "c-1");

			assertEquals(1, stores.recordings(Observation.Append.class).size());
			assertTrue(storages.recordings().isEmpty(), "the storage's observer was told about an operation of a store given its own");
		}
	}

	@Test
	void aStorageBuiltWithoutAnObserverIsObservedByNothing ( ) {
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build() ) {
			assertTrue(storage.observer() == EventStoreObserver.NOOP, "observation is opt-in: the default is NOOP");
		}
	}

	@Test
	void theInMemoryStoragesReportTheirLifecycle ( @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory ) {
		RecordingObserver observer = new RecordingObserver();
		EventStore store = InMemoryEventStorage.newBuilder().name("lifecycle").observer(observer).buildStore();
		assertEquals(java.util.List.of(new Signal.StorageStarted("lifecycle")), observer.signals(Signal.StorageStarted.class));
		appendOne(store, "c-1");
		assertEquals(1, observer.recordings(Observation.Append.class).size(), "buildStore() hands the observer to the store");
		store.close();
		store.close();
		assertEquals(java.util.List.of(new Signal.StorageClosed("lifecycle")), observer.signals(Signal.StorageClosed.class), "closed once, however often close() is called");
		assertTrue(observer.signals(Signal.ChannelChanged.class).isEmpty(), "an in-memory storage has no channels");

		RecordingObserver fsObserver = new RecordingObserver();
		try ( EventStore fs = InMemoryFsEventStorage.newBuilder().name("fs-lifecycle").directory(directory).observer(fsObserver).buildStore() ) {
			appendOne(fs, "c-1");
			assertEquals(1, fsObserver.recordings(Observation.Append.class).size());
		}
		assertEquals(java.util.List.of(new Signal.StorageStarted("fs-lifecycle"), new Signal.StorageClosed("fs-lifecycle")),
				fsObserver.signals().stream().filter(sig -> sig instanceof Signal.StorageStarted || sig instanceof Signal.StorageClosed).toList());
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
					org.sliceworkz.eventstore.query.Limit.none(), org.sliceworkz.eventstore.query.EventQuery.Direction.FORWARD).size(),
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
