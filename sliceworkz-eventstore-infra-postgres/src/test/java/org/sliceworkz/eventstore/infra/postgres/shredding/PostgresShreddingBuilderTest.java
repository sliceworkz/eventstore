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
package org.sliceworkz.eventstore.infra.postgres.shredding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.KeyAuditQuery;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingAudit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The no-arg {@code shredding()} — keys in {@code <prefix>shredding_keys} on the store's own
 * {@code DataSource} — is honoured by {@code build()} followed by {@link EventStoreFactory}, not only
 * by {@code buildStore()}.
 * <p>
 * This is the configuration a caller could not reproduce by hand: the key store needs the
 * {@code DataSource} the builder resolves, and with one loaded from {@code db.properties} the builder
 * never hands it out. So the codec is created in {@code build()} and carried by the storage
 * ({@link EventStorage#shreddingCodec()}), where a store built through the factory finds it.
 */
public class PostgresShreddingBuilderTest {

	private static final String PREFIX = "shredbuild_";
	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");
	private static final EventStreamId STREAM = EventStreamId.forContext("contacts");

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testShreddingOnTheOwnDatabaseIsHonouredByBuildAndTheFactory ( ) {
			EventStorage storage = PostgresEventStorage.newBuilder()
					.name("shredding-build").prefix(PREFIX)
					.dataSource(PostgresContainer.dataSource(image))
					.initializeDatabase()
					.shredding()
					.build();
			assertTrue(storage.shreddingCodec().isPresent(), "the storage does not carry the codec its builder was given");

			try ( EventStore store = EventStoreFactory.get().eventStore(storage) ) {
				EventStream<ContactEvent> contacts = store.getEventStream(STREAM, ContactEvent.class);
				contacts.append(AppendCriteria.none(), Event.of(new ContactRecorded("c-1", Shreddable.of("Alice Martin", ALICE)), Tags.none()));

				ContactRecorded before = (ContactRecorded) contacts.query(EventQuery.matchAll()).findFirst().orElseThrow().data();
				assertEquals("Alice Martin", before.name().map(n -> n).orElse("[erased]"));

				// the key landed in this store's own table, which is what the no-arg shredding() promises
				ShreddingAudit audit = store.shreddingAudit().orElseThrow();
				assertEquals(1, audit.keys(KeyAuditQuery.forSubject(ALICE)).size());

				assertEquals(1, store.erase(ALICE, ErasureReason.of("GDPR art.17 request #4711")).keysShredded());

				ContactRecorded after = (ContactRecorded) contacts.query(EventQuery.matchAll()).findFirst().orElseThrow().data();
				Shreddable.Shredded<String> shredded = assertInstanceOf(Shreddable.Shredded.class, after.name());
				assertEquals(ALICE, shredded.subject());
				assertEquals(1, audit.totals().shreddedKeys());
			} finally {
				storage.close();
			}
		}

		@Test
		public void testAStorageBuiltWithoutShreddingCarriesNoCodec ( ) {
			try ( EventStorage storage = PostgresEventStorage.newBuilder()
					.name("no-shredding-build").prefix(PREFIX)
					.dataSource(PostgresContainer.dataSource(image))
					.initializeDatabase()
					.build() ) {
				assertEquals(Optional.empty(), storage.shreddingCodec());
			}
		}
	}

	@Nested
	class OnPostgres16 extends Tests {
		OnPostgres16 ( ) { super(PostgresContainer.IMAGE_PG16); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG16);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			PostgresContainer.stop(PostgresContainer.IMAGE_PG16);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG16);
		}
	}

	@Nested
	class OnPostgres17 extends Tests {
		OnPostgres17 ( ) { super(PostgresContainer.IMAGE_PG17); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG17);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			PostgresContainer.stop(PostgresContainer.IMAGE_PG17);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17);
		}
	}

	@Nested
	class OnPostgres18 extends Tests {
		OnPostgres18 ( ) { super(PostgresContainer.IMAGE_PG18); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG18);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			PostgresContainer.stop(PostgresContainer.IMAGE_PG18);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18);
		}
	}

	public sealed interface ContactEvent { }

	public record ContactRecorded ( String contactId, Shreddable<String> name ) implements ContactEvent { }

}
