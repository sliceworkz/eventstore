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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * Running this version against an <em>existing</em> eventstore upgrades it in place.
 * <p>
 * The question this answers is the one an operator asks before upgrading: is this a change that only
 * works for stores created from now on, or does an existing database come along? It is the latter, for
 * the objects {@code ENSURE} is able to bring up to date — and this test says exactly which those are,
 * by starting from a database shaped the way an older release created it
 * ({@code legacy-ensure-schema.sql}) with events already in it.
 * <p>
 * What it pins down, in one run:
 * <ul>
 *   <li>existing events survive — nothing is dropped or rewritten;</li>
 *   <li>a function body from the older release is replaced by the current one;</li>
 *   <li>objects the older release never had (the combined stream+tags GIN index, the per-stream
 *       partial unique idempotency index) are created;</li>
 *   <li>the upgraded store's notifications actually work, end to end;</li>
 *   <li>and what is <em>not</em> upgraded: the pre-alignment {@code stream_purpose} default and the
 *       old table-wide {@code UNIQUE} on {@code idempotency_key} both survive, because they need
 *       {@code ALTER TABLE}. Those remain the manual migrations documented in {@code CLAUDE.md}.</li>
 * </ul>
 */
public class PostgresSchemaUpgradeTest {

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testEnsureUpgradesAnExistingEventstoreInPlace ( ) throws Exception {
			String prefix = "upgrade_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			// a database as an older release left it, with an event already in it
			applyLegacySchema(dataSource, prefix);
			UUID existingEvent = insertRawEvent(dataSource, prefix);

			assertFalse(indexExists(dataSource, prefix + "idx_events_stream_tags"),
				"precondition: the older release had no combined stream+tags index");
			assertFalse(indexExists(dataSource, prefix + "idx_events_stream_idempotency"),
				"precondition: the older release had no per-stream idempotency index");
			assertFalse(functionBody(dataSource, prefix).contains("eventTx"),
				"precondition: the older release's notify function predates eventTx");

			// upgrade: start the current version against it, on the default mode
			AtomicInteger notifications = new AtomicInteger();
			try ( EventStorage storage = PostgresEventStorage.newBuilder()
					.name("unit-test").prefix(prefix).dataSource(dataSource)
					.ensureDatabase().build() ) {

				// 1. the events that were there are still there
				assertEquals(1, eventCount(dataSource, prefix), "existing events survived the upgrade");
				assertEquals(1, eventCountById(dataSource, prefix, existingEvent), "the same event, untouched");

				// 2. the stale function body was replaced by the current one
				assertTrue(functionBody(dataSource, prefix).contains("eventTx"),
					"the notify function was brought up to the current release");

				// 3. objects the older release never had were created
				assertTrue(indexExists(dataSource, prefix + "idx_events_stream_tags"),
					"the combined stream+tags index was added");
				assertTrue(indexExists(dataSource, prefix + "idx_events_stream_idempotency"),
					"the per-stream idempotency index was added");

				// 4. and the upgraded store's notifications actually work
				storage.subscribe(new EventStorage.EventStoreListener() {
					@Override public void notify ( EventStorage.AppendsToEventStoreNotification n ) { notifications.incrementAndGet(); }
					@Override public void notify ( EventStorage.BookmarkPlacedNotification n ) { }
				});
				insertRawEvent(dataSource, prefix);
				for ( int attempt = 0; attempt < 100 && notifications.get() == 0; attempt++ ) {
					Thread.sleep(50);
				}
				assertEquals(1, notifications.get(), "the upgraded store notifies on append");
			}

			// 5. what the upgrade does NOT do -- these still need ALTER TABLE by hand
			assertEquals("''::text", columnDefault(dataSource, prefix + "events", "stream_purpose"),
				"the pre-alignment stream_purpose default survives: still a manual migration");
			assertTrue(hasTableWideIdempotencyConstraint(dataSource, prefix),
				"the old table-wide UNIQUE on idempotency_key survives: still a manual migration");

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- helpers

		/** Creates the schema the way an older release of this library would have. */
		private void applyLegacySchema ( DataSource dataSource, String prefix ) throws Exception {
			String sql = new String(
				getClass().getClassLoader().getResourceAsStream("legacy-ensure-schema.sql").readAllBytes(),
				StandardCharsets.UTF_8).replaceAll("PREFIX_", prefix);
			execute(dataSource, sql);
		}

		private UUID insertRawEvent ( DataSource dataSource, String prefix ) throws SQLException {
			UUID id = UUID.randomUUID();
			execute(dataSource, """
				INSERT INTO %sevents (event_id, stream_context, stream_purpose, event_type, event_data)
				VALUES ('%s', 'ctx', 'default', 'SomeEvent', '{}'::jsonb)
				""".formatted(prefix, id));
			return id;
		}

		private void execute ( DataSource dataSource, String sql ) throws SQLException {
			try ( Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement() ) {
				statement.execute(sql);
			}
		}

		private String functionBody ( DataSource dataSource, String prefix ) throws SQLException {
			return queryString(dataSource,
				"SELECT prosrc FROM pg_proc p JOIN pg_namespace n ON p.pronamespace = n.oid "
					+ "WHERE n.nspname = current_schema() AND p.proname = '" + prefix + "notify_event_appended'");
		}

		private boolean indexExists ( DataSource dataSource, String indexName ) throws SQLException {
			return queryString(dataSource,
				"SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND indexname = '"
					+ indexName + "'") != null;
		}

		/** The pre-rework table-wide UNIQUE constraint, which the upgrade deliberately leaves alone. */
		private boolean hasTableWideIdempotencyConstraint ( DataSource dataSource, String prefix ) throws SQLException {
			return queryString(dataSource,
				"SELECT conname FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid "
					+ "WHERE t.relname = '" + prefix + "events' AND c.contype = 'u' "
					+ "AND pg_get_constraintdef(c.oid) = 'UNIQUE (idempotency_key)'") != null;
		}

		private int eventCount ( DataSource dataSource, String prefix ) throws SQLException {
			return Integer.parseInt(queryString(dataSource, "SELECT count(*) FROM " + prefix + "events"));
		}

		private int eventCountById ( DataSource dataSource, String prefix, UUID eventId ) throws SQLException {
			return Integer.parseInt(queryString(dataSource,
				"SELECT count(*) FROM " + prefix + "events WHERE event_id = '" + eventId + "'"));
		}

		private String columnDefault ( DataSource dataSource, String tableName, String columnName ) throws SQLException {
			return queryString(dataSource,
				"SELECT column_default FROM information_schema.columns WHERE table_schema = current_schema() "
					+ "AND table_name = '" + tableName + "' AND column_name = '" + columnName + "'");
		}

		private String queryString ( DataSource dataSource, String sql ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
				  Statement statement = connection.createStatement();
				  ResultSet rs = statement.executeQuery(sql) ) {
				return rs.next() ? rs.getString(1) : null;
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
