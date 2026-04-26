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
package org.sliceworkz.eventstore.infra.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Verifies that {@link PostgresEventStorage.Builder#build()} picks the correct implementation
 * class based on the connected PostgreSQL major version, and that the chosen append code path
 * actually behaves as advertised (server-side UUIDv7 on PG18, Java-side on PG17).
 */
class PostgresEventStorageVersionDetectionTest {

	private static EventToStore sampleEvent ( ) {
		return new EventToStore(
			EventStreamId.forContext("version-detection").withPurpose("smoke"),
			EventType.ofType("Smoke"),
			"{}",
			null,
			Tags.none(),
			"idem-" + UUID.randomUUID()
		);
	}

	@Nested
	class OnPostgres17 {

		@BeforeAll
		static void start ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG17); }

		@AfterAll
		static void stop ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG17); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17); }

		@Test
		void picksLegacyImplOnPg17 ( ) {
			EventStorage storage = PostgresEventStorage.newBuilder()
				.name("version-detection-pg17")
				.prefix("ver17_")
				.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG17))
				.initializeDatabase()
				.build();
			try {
				assertInstanceOf(PostgresLegacyEventStorageImpl.class, storage,
					"PG17 should be served by PostgresLegacyEventStorageImpl");
			} finally {
				((PostgresEventStorageImpl) storage).stop();
				PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG17);
			}
		}

		@Test
		void appendReturnsValidUuidv7OnLegacyPath ( ) {
			EventStorage storage = PostgresEventStorage.newBuilder()
				.name("version-detection-pg17-append")
				.prefix("verapp17_")
				.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG17))
				.initializeDatabase()
				.build();
			try {
				List<StoredEvent> stored = storage.append(AppendCriteria.none(), Optional.empty(), List.of(sampleEvent()));
				assertEquals(1, stored.size());
				UUID id = UUID.fromString(stored.get(0).reference().id().value());
				assertEquals(7, id.version(), "expected UUIDv7");
			} finally {
				((PostgresEventStorageImpl) storage).stop();
				PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG17);
			}
		}
	}

	@Nested
	class OnPostgres18 {

		@BeforeAll
		static void start ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

		@AfterAll
		static void stop ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

		@Test
		void picksDefaultImplOnPg18 ( ) {
			EventStorage storage = PostgresEventStorage.newBuilder()
				.name("version-detection-pg18")
				.prefix("ver18_")
				.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18))
				.initializeDatabase()
				.build();
			try {
				assertNotNull(storage);
				assertEquals(PostgresEventStorageImpl.class, storage.getClass(),
					"PG18 should be served by the default PostgresEventStorageImpl, not the legacy subclass");
				assertFalse(storage instanceof PostgresLegacyEventStorageImpl,
					"PG18 must not use the legacy implementation");
			} finally {
				((PostgresEventStorageImpl) storage).stop();
				PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
			}
		}

		@Test
		void appendUsesServerSideUuidv7 ( ) {
			EventStorage storage = PostgresEventStorage.newBuilder()
				.name("version-detection-pg18-append")
				.prefix("verapp18_")
				.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18))
				.initializeDatabase()
				.build();
			try {
				List<StoredEvent> stored = storage.append(AppendCriteria.none(), Optional.empty(), List.of(sampleEvent()));
				assertEquals(1, stored.size());
				UUID id = UUID.fromString(stored.get(0).reference().id().value());
				assertEquals(7, id.version(), "PG18 uuidv7() must produce UUID version 7");
				assertTrue(id.variant() == 2, "RFC 4122 variant expected, got " + id.variant());
			} finally {
				((PostgresEventStorageImpl) storage).stop();
				PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
			}
		}
	}

}
