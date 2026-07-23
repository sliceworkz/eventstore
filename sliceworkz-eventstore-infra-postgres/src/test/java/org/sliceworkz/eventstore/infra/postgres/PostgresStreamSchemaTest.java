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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Verifies the physical stream schema stays aligned with the API's stream identity:
 * <ul>
 *   <li>a row inserted relying on the SQL {@code stream_purpose} default lands with the same
 *       purpose value that {@link EventStreamId#forContext(String)} produces, so it is reachable
 *       through a context-scoped read (guards against the DDL {@code ''} vs Java {@code "default"}
 *       mismatch);</li>
 *   <li>schema initialization creates the combined stream+tags GIN index (and the {@code btree_gin}
 *       extension it depends on).</li>
 * </ul>
 * Uses a distinct prefix per test so a single shared container can be reused.
 */
public class PostgresStreamSchemaTest {

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testSqlDefaultPurposeMatchesContextScopedRead ( ) throws Exception {
			String prefix = "purposedefault_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			PostgresEventStorageImpl storage = (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
				.name("unit-test")
				.prefix(prefix)
				.dataSource(dataSource)
				.initializeDatabase()
				.build();

			try {
				String context = "customer";
				UUID eventId = UUID.randomUUID();

				// Insert a row WITHOUT stream_purpose, relying on the column default. The library
				// itself always binds purpose explicitly, so this exercises the raw-SQL default path.
				try ( Connection connection = dataSource.getConnection();
					  PreparedStatement insert = connection.prepareStatement(
						  "INSERT INTO " + prefix + "events (event_id, stream_context, event_type, event_data) "
						  + "VALUES (?::uuid, ?, ?, ?::jsonb)") ) {
					insert.setString(1, eventId.toString());
					insert.setString(2, context);
					insert.setString(3, "SomethingHappened");
					insert.setString(4, "{}");
					insert.executeUpdate();
				}

				String storedPurpose;
				try ( Connection connection = dataSource.getConnection();
					  PreparedStatement select = connection.prepareStatement(
						  "SELECT stream_purpose FROM " + prefix + "events WHERE event_id = ?::uuid") ) {
					select.setString(1, eventId.toString());
					try ( ResultSet rs = select.executeQuery() ) {
						assertTrue(rs.next(), "expected the inserted event to be present");
						storedPurpose = rs.getString("stream_purpose");
					}
				}

				EventStreamId contextStream = EventStreamId.forContext(context);

				// The SQL default must equal the Java default purpose...
				assertEquals(contextStream.purpose(), storedPurpose,
					"SQL default stream_purpose must match EventStreamId.forContext(...) purpose");

				// ...so a context-scoped read (which matches on equality) can actually see the row.
				assertTrue(contextStream.canRead(new EventStreamId(context, storedPurpose)),
					"a context-scoped stream id must be able to read a row written via the SQL default purpose");
			} finally {
				storage.stop();
				PostgresContainer.closeDataSource(image);
			}
		}

		@Test
		public void testStreamTagsIndexAndExtensionCreated ( ) throws Exception {
			String prefix = "streamtagsidx_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			PostgresEventStorageImpl storage = (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
				.name("unit-test")
				.prefix(prefix)
				.dataSource(dataSource)
				.initializeDatabase()
				.build();

			try ( Connection connection = dataSource.getConnection() ) {
				assertTrue(indexExists(connection, prefix + "idx_events_stream_tags"),
					"combined stream+tags GIN index must be created by schema initialization");
				assertTrue(extensionExists(connection, "btree_gin"),
					"btree_gin extension must be present to support the combined stream+tags index");
			} finally {
				storage.stop();
				PostgresContainer.closeDataSource(image);
			}
		}

		private boolean indexExists ( Connection connection, String indexName ) throws Exception {
			try ( PreparedStatement stmt = connection.prepareStatement(
					"SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?)") ) {
				stmt.setString(1, indexName);
				try ( ResultSet rs = stmt.executeQuery() ) {
					return rs.next() && rs.getBoolean(1);
				}
			}
		}

		private boolean extensionExists ( Connection connection, String extensionName ) throws Exception {
			try ( PreparedStatement stmt = connection.prepareStatement(
					"SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = ?)") ) {
				stmt.setString(1, extensionName);
				try ( ResultSet rs = stmt.executeQuery() ) {
					return rs.next() && rs.getBoolean(1);
				}
			}
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

}
