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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.inmem.fs.InMemoryFsEventStorage;
import org.sliceworkz.eventstore.infra.inmem.fs.shredding.InMemoryFsShreddingKeyStore;
import org.sliceworkz.eventstore.infra.inmem.shredding.InMemoryShreddingKeyStore;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;


/**
 * A builder's {@code .shredding(...)} travels with the storage, so it is honoured whether the caller
 * ends with {@code buildStore()} or with {@code build()} and {@link EventStoreFactory}.
 * <p>
 * The alternative — a codec that lives on the store alone, wired only by {@code buildStore()} — loses
 * because a caller who takes the storage from {@code build()} then gets a store that refuses the very
 * event types the builder was configured for, and on Postgres cannot even construct the key store the
 * no-arg {@code shredding()} stands for, since it needs the {@code DataSource} the builder resolved
 * and never hands out. The Postgres half is pinned in the postgres module
 * ({@code PostgresShreddingBuilderTest}); this covers the two in-memory backends and the factory's
 * precedence rule.
 */
public class StorageShreddingCodecTest {

	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");
	private static final EventStreamId STREAM = EventStreamId.forContext("contacts");

	@Test
	void theInMemoryBuildersShreddingIsHonouredByBuildAndTheFactory ( ) {
		EventStorage storage = InMemoryEventStorage.newBuilder()
				.shredding(new InMemoryShreddingKeyStore())
				.build();
		assertTrue(storage.shreddingCodec().isPresent(), "the storage does not carry the codec its builder was given");

		try ( EventStore store = EventStoreFactory.get().eventStore(storage) ) {
			assertProtectsAndErases(store);
		}
	}

	@Test
	void theFileBackedBuildersShreddingIsHonouredByBuildAndTheFactory ( @TempDir Path directory ) {
		EventStorage storage = InMemoryFsEventStorage.newBuilder()
				.directory(directory.resolve("events"))
				.shredding(new InMemoryFsShreddingKeyStore(directory.resolve("keys")))
				.build();
		assertTrue(storage.shreddingCodec().isPresent(), "the storage does not carry the codec its builder was given");

		try ( EventStore store = EventStoreFactory.get().eventStore(storage) ) {
			assertProtectsAndErases(store);
		}
	}

	@Test
	void buildStoreStillProtectsAndErases ( ) {
		try ( EventStore store = InMemoryEventStorage.newBuilder()
				.shredding(new InMemoryShreddingKeyStore())
				.buildStore() ) {
			assertProtectsAndErases(store);
		}
	}

	@Test
	void aCodecGivenToTheFactoryWinsOverTheStorages ( ) {
		EventStorage storage = InMemoryEventStorage.newBuilder()
				.shredding(new InMemoryShreddingKeyStore())
				.build();
		// a writer on the storage's own codec, a reader given a codec of its own: the reader must not
		// silently get the storage's keys instead
		try ( EventStore writer = EventStoreFactory.get().eventStore(storage);
			  EventStore reader = EventStoreFactory.get().eventStore(storage, null, ShreddingCodec.withholdingAll()) ) {
			writer.getEventStream(STREAM, ContactEvent.class)
					.append(AppendCriteria.none(), Event.of(new ContactRecorded("c-1", Shreddable.of("Alice Martin", ALICE)), Tags.none()));

			ContactRecorded read = (ContactRecorded) reader.getEventStream(STREAM, ContactEvent.class)
					.query(EventQuery.matchAll()).getFirst().data();
			assertInstanceOf(Shreddable.Withheld.class, read.name());
			assertEquals(Optional.empty(), reader.shreddingAudit(), "the withholding codec has no audit, so an audit here came from the storage's codec");
		}
	}

	@Test
	void aStorageBuiltWithoutShreddingGivesAStoreWithout ( ) {
		EventStorage storage = InMemoryEventStorage.newBuilder().build();
		assertEquals(Optional.empty(), storage.shreddingCodec());

		try ( EventStore store = EventStoreFactory.get().eventStore(storage) ) {
			// registering a type that declares a Shreddable fails, rather than storing personal data in the clear
			assertThrows(IllegalArgumentException.class, () -> store.getEventStream(STREAM, ContactEvent.class));
			assertEquals(Optional.empty(), store.shreddingAudit());
		}
	}

	private void assertProtectsAndErases ( EventStore store ) {
		EventStream<ContactEvent> contacts = store.getEventStream(STREAM, ContactEvent.class);
		contacts.append(AppendCriteria.none(), Event.of(new ContactRecorded("c-1", Shreddable.of("Alice Martin", ALICE)), Tags.none()));

		ContactRecorded before = (ContactRecorded) contacts.query(EventQuery.matchAll()).getFirst().data();
		assertEquals("Alice Martin", before.name().map(n -> n).orElse("[erased]"));

		assertEquals(1, store.eraseCategory(ALICE, ErasureReason.of("GDPR art.17 request #4711")).keysShredded());

		ContactRecorded after = (ContactRecorded) contacts.query(EventQuery.matchAll()).getFirst().data();
		Shreddable.Shredded<String> shredded = assertInstanceOf(Shreddable.Shredded.class, after.name());
		assertEquals(ALICE, shredded.subject());
		assertEquals(1, store.shreddingAudit().orElseThrow().totals().shreddedKeys());
	}

	public sealed interface ContactEvent { }

	public record ContactRecorded ( String contactId, Shreddable<String> name ) implements ContactEvent { }

}
