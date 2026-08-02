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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;

/**
 * Characterisation tests for schema drift: they pin down what the schema scripts and
 * {@code checkDatabase()} do <em>today</em> on a database that already exists.
 * <p>
 * The headline is {@link Tests#testStaleFunctionSurvivesInitialize()}: {@code ensure-schema.sql} is
 * create-if-absent throughout and {@code drop-schema.sql} drops only the two tables, so a function
 * body written by an older release survives even the mode documented as "drop and recreate from
 * scratch" — and the {@code IF NOT EXISTS} guard then declines to recreate it. Validation checks
 * that objects exist, never what they are, so nothing reports the drift.
 * <p>
 * These tests assert the current (broken) behaviour deliberately. When a migration mechanism lands
 * they must be inverted, which is the point: they are the regression net for it.
 */
public class PostgresSchemaDriftTest {

	/** A function body that is recognisably not the one in {@code ensure-schema.sql}. */
	private static final String HIJACKED_CHANNEL = "hijacked_channel";

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		// ---------------------------------------------------------------- §3.1

		/** A function body changed after creation is left untouched by {@code ENSURE}, which reports success. */
		@Test
		public void testStaleFunctionSurvivesEnsure ( ) throws Exception {
			String prefix = "driftensure_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();
			hijackNotifyFunction(dataSource, prefix);
			assertTrue(functionBody(dataSource, prefix).contains(HIJACKED_CHANNEL), "precondition: body was replaced");

			// ENSURE runs again and reports success ...
			ensure(prefix, dataSource).close();

			// ... but the stale body is still there
			assertTrue(functionBody(dataSource, prefix).contains(HIJACKED_CHANNEL),
				"ENSURE left the stale function body in place");
			assertFalse(functionBody(dataSource, prefix).contains(prefix + "event_appended"),
				"the body shipped with this release never reached the database");

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- §3.2

		/**
		 * The headline: the same stale body survives {@code INITIALIZE}, the mode documented as
		 * "drop all event store objects and recreate them from scratch".
		 * <p>
		 * {@code drop-schema.sql} drops the two tables (taking the triggers with them via
		 * {@code CASCADE}) and nothing else, so the function is still in {@code pg_proc} when
		 * {@code ensure-schema.sql} runs — where {@code IF NOT EXISTS} declines to recreate it.
		 * The freshly created trigger is then wired to the old body.
		 */
		@Test
		public void testStaleFunctionSurvivesInitialize ( ) throws Exception {
			String prefix = "driftinit_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();
			hijackNotifyFunction(dataSource, prefix);

			// INITIALIZE: drop everything and recreate from scratch. Reports success.
			try ( EventStorage storage = PostgresEventStorage.newBuilder()
					.name("unit-test").prefix(prefix).dataSource(dataSource)
					.initializeDatabase().build() ) {
				// the events table really was dropped and recreated -- it is empty
				assertEquals(0, eventCount(dataSource, prefix), "INITIALIZE did drop and recreate the table");
			}

			assertTrue(functionBody(dataSource, prefix).contains(HIJACKED_CHANNEL),
				"INITIALIZE left the stale function body in place -- 'from scratch' is not from scratch");

			PostgresContainer.closeDataSource(image);
		}

		/**
		 * The drift is not cosmetic: with a stale body the store's LISTEN/NOTIFY path is dead.
		 * A listener registered on a storage that has just run {@code INITIALIZE} never hears
		 * about an insert, because the trigger notifies the old channel.
		 */
		@Test
		public void testStaleFunctionBreaksNotificationsAfterInitialize ( ) throws Exception {
			String prefix = "driftnotify_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();
			hijackNotifyFunction(dataSource, prefix);

			AtomicInteger notifications = new AtomicInteger();
			try ( EventStorage storage = PostgresEventStorage.newBuilder()
					.name("unit-test").prefix(prefix).dataSource(dataSource)
					.initializeDatabase().build() ) {

				storage.subscribe(new EventStorage.EventStoreListener() {
					@Override public void notify ( EventStorage.AppendsToEventStoreNotification n ) { notifications.incrementAndGet(); }
					@Override public void notify ( EventStorage.BookmarkPlacedNotification n ) { }
				});

				insertRawEvent(dataSource, prefix);
				// give the monitor thread more than enough time to deliver a notification
				Thread.sleep(1500);

				assertEquals(0, notifications.get(),
					"no notification arrives: the trigger created by INITIALIZE calls the stale function, "
						+ "which notifies '" + HIJACKED_CHANNEL + "' instead of '" + prefix + "event_appended'");
			}

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- §3.3

		/** Validation checks that objects exist, not what they are, so a stale body passes. */
		@Test
		public void testValidateDoesNotNoticeStaleFunction ( ) throws Exception {
			String prefix = "driftvalidate_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();
			hijackNotifyFunction(dataSource, prefix);

			// VALIDATE passes against the drifted database
			try ( EventStorage storage = PostgresEventStorage.newBuilder()
					.name("unit-test").prefix(prefix).dataSource(dataSource)
					.validateDatabase().build() ) {
				assertTrue(functionBody(dataSource, prefix).contains(HIJACKED_CHANNEL));
			}

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- §3.4

		/** Column type and nullability drift <em>is</em> caught -- this part of validation works. */
		@Test
		public void testColumnDriftIsCaught ( ) throws Exception {
			String prefix = "driftcolumn_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();
			execute(dataSource, "ALTER TABLE " + prefix + "events ALTER COLUMN stream_purpose DROP NOT NULL");

			EventStorageException e = assertThrows(EventStorageException.class, () ->
				PostgresEventStorage.newBuilder()
					.name("unit-test").prefix(prefix).dataSource(dataSource)
					.validateDatabase().build());
			assertTrue(e.getMessage().contains("stream_purpose") && e.getMessage().contains("nullability"),
				"expected a nullability complaint, got: " + e.getMessage());

			PostgresContainer.closeDataSource(image);
		}

		/**
		 * The boundary of what validation catches. Everything below is real, load-bearing drift that
		 * passes validation because only the <em>name</em> of the object is checked:
		 * <ul>
		 *   <li>an index of the wrong kind and on the wrong columns, under the right name;</li>
		 *   <li>the idempotency index no longer unique -- silently admits duplicate keys;</li>
		 *   <li>the {@code stream_purpose} default reverted to the pre-alignment {@code ''};</li>
		 *   <li>the trigger changed from {@code AFTER INSERT} to {@code BEFORE INSERT}.</li>
		 * </ul>
		 */
		@Test
		public void testIndexTriggerAndDefaultDriftAreNotCaught ( ) throws Exception {
			String prefix = "driftshape_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();

			// an index with the right name and the wrong everything else
			execute(dataSource, "DROP INDEX " + prefix + "idx_events_stream_tags");
			execute(dataSource, "CREATE INDEX " + prefix + "idx_events_stream_tags ON " + prefix + "events (event_type)");

			// the idempotency index, no longer unique
			execute(dataSource, "DROP INDEX " + prefix + "idx_events_stream_idempotency");
			execute(dataSource, "CREATE INDEX " + prefix + "idx_events_stream_idempotency ON " + prefix + "events (idempotency_key)");

			// the pre-alignment default documented in CLAUDE.md as needing a manual migration
			execute(dataSource, "ALTER TABLE " + prefix + "events ALTER COLUMN stream_purpose SET DEFAULT ''");

			// the trigger, wrong timing
			execute(dataSource, "DROP TRIGGER table_insert_trigger ON " + prefix + "events");
			execute(dataSource, "CREATE TRIGGER table_insert_trigger BEFORE INSERT ON " + prefix + "events "
				+ "FOR EACH ROW EXECUTE FUNCTION " + prefix + "notify_event_appended()");

			// ENSURE reports success and changes nothing; VALIDATE agrees
			ensure(prefix, dataSource).close();
			try ( EventStorage storage = PostgresEventStorage.newBuilder()
					.name("unit-test").prefix(prefix).dataSource(dataSource)
					.validateDatabase().build() ) {
				assertEquals("btree", indexMethod(dataSource, prefix + "idx_events_stream_tags"),
					"the GIN index is gone and validation did not notice");
				assertFalse(isUniqueIndex(dataSource, prefix + "idx_events_stream_idempotency"),
					"the unique idempotency index is gone and validation did not notice");
				assertEquals("''::text", columnDefault(dataSource, prefix + "events", "stream_purpose"),
					"the stale default survives and validation did not notice");
				assertEquals("BEFORE", triggerTiming(dataSource, prefix + "events", "table_insert_trigger"),
					"the trigger fires at the wrong time and validation did not notice");
			}

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- §3.5

		/**
		 * Several application instances starting at once all run {@code ensure-schema.sql} — which is
		 * what the default {@link DatabaseInitMode#ENSURE} means for a scaled-out deployment rolling
		 * onto a database that does not have the schema yet.
		 * <p>
		 * {@code CREATE TABLE / INDEX / EXTENSION IF NOT EXISTS} are not atomic against a concurrent
		 * creator: the existence check and the catalog insert are separate, so two transactions can
		 * both find the object absent and both try to create it. The loser hits a unique violation on
		 * a system catalog index, the whole script rolls back (it runs as one transaction), and that
		 * instance fails to start. It is a race, so it is intermittent — several rounds, each on its
		 * own fresh prefix, are used to make it show up reliably.
		 */
		@Test
		public void testConcurrentEnsureFromSeveralInstances ( ) throws Exception {
			DataSource dataSource = PostgresContainer.dataSource(image);

			int rounds = 10;
			int instances = 8;
			int roundsWithFailures = 0;
			int totalFailures = 0;
			List<String> messages = new ArrayList<>();

			for ( int round = 0; round < rounds; round++ ) {
				// a fresh prefix per round: the race only exists while the objects are still absent
				String prefix = "driftconcurrent%d_".formatted(round);
				CountDownLatch startSignal = new CountDownLatch(1);
				CountDownLatch done = new CountDownLatch(instances);
				List<Throwable> failures = new ArrayList<>();
				ExecutorService pool = Executors.newFixedThreadPool(instances);

				for ( int i = 0; i < instances; i++ ) {
					pool.execute(() -> {
						try {
							startSignal.await();
							ensure(prefix, dataSource).close();
						} catch ( Throwable t ) {
							synchronized ( failures ) { failures.add(t); }
						} finally {
							done.countDown();
						}
					});
				}

				startSignal.countDown();
				assertTrue(done.await(120, TimeUnit.SECONDS), "concurrent ENSURE did not finish");
				pool.shutdownNow();

				if ( !failures.isEmpty() ) {
					roundsWithFailures++;
					totalFailures += failures.size();
					for ( Throwable t : failures ) {
						Throwable root = t;
						while ( root.getCause() != null ) { root = root.getCause(); }
						String message = root.getMessage().replaceAll("\\s+", " ").trim();
						// every failure seen is a catalog race, not something else going wrong
						assertTrue(message.contains("already exists"),
							"expected a catalog race, got: " + message);
						messages.add(root.getClass().getSimpleName() + ": " + message);
					}
				}

				// whoever won, the script runs as one transaction, so a loser rolls back cleanly and
				// the schema left behind is complete
				ensure(prefix, dataSource).close();
			}

			System.out.println("[concurrent ENSURE on " + image + "] " + roundsWithFailures + " of " + rounds
				+ " rounds had a failing instance; " + totalFailures + " of " + (rounds * instances)
				+ " instances failed to start");
			messages.stream().distinct().forEach(m -> System.out.println("    " + m));

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- helpers

		private EventStorage ensure ( String prefix, DataSource dataSource ) {
			return PostgresEventStorage.newBuilder()
				.name("unit-test").prefix(prefix).dataSource(dataSource)
				.ensureDatabase().build();
		}

		/** Replaces the notify function with one that notifies a different channel, as a future release might. */
		private void hijackNotifyFunction ( DataSource dataSource, String prefix ) throws SQLException {
			execute(dataSource, """
				CREATE OR REPLACE FUNCTION %snotify_event_appended()
				RETURNS trigger AS $fn$
				BEGIN
				    PERFORM pg_notify('%s', NEW.event_id::text);
				    RETURN NEW;
				END;
				$fn$ LANGUAGE plpgsql;
				""".formatted(prefix, HIJACKED_CHANNEL));
		}

		private void insertRawEvent ( DataSource dataSource, String prefix ) throws SQLException {
			execute(dataSource, """
				INSERT INTO %sevents (event_id, stream_context, stream_purpose, event_type, event_data)
				VALUES ('%s', 'ctx', 'default', 'SomeEvent', '{}'::jsonb)
				""".formatted(prefix, UUID.randomUUID()));
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

		private int eventCount ( DataSource dataSource, String prefix ) throws SQLException {
			return Integer.parseInt(queryString(dataSource, "SELECT count(*) FROM " + prefix + "events"));
		}

		private String indexMethod ( DataSource dataSource, String indexName ) throws SQLException {
			return queryString(dataSource,
				"SELECT am.amname FROM pg_class c JOIN pg_am am ON c.relam = am.oid WHERE c.relname = '" + indexName + "'");
		}

		private boolean isUniqueIndex ( DataSource dataSource, String indexName ) throws SQLException {
			return "t".equals(queryString(dataSource,
				"SELECT CASE WHEN i.indisunique THEN 't' ELSE 'f' END FROM pg_index i "
					+ "JOIN pg_class c ON i.indexrelid = c.oid WHERE c.relname = '" + indexName + "'"));
		}

		private String columnDefault ( DataSource dataSource, String tableName, String columnName ) throws SQLException {
			return queryString(dataSource,
				"SELECT column_default FROM information_schema.columns WHERE table_schema = current_schema() "
					+ "AND table_name = '" + tableName + "' AND column_name = '" + columnName + "'");
		}

		private String triggerTiming ( DataSource dataSource, String tableName, String triggerName ) throws SQLException {
			return queryString(dataSource,
				"SELECT action_timing FROM information_schema.triggers WHERE trigger_schema = current_schema() "
					+ "AND event_object_table = '" + tableName + "' AND trigger_name = '" + triggerName + "'");
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
