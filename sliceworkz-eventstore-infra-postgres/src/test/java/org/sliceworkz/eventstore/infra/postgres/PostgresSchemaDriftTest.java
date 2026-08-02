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
 * What {@code ENSURE} repairs on a database that already exists — and what it still does not.
 * <p>
 * {@code ensure-schema.sql} used to be create-if-absent throughout, so a function body written by an
 * older release survived every mode, including the one documented as "drop and recreate from scratch"
 * ({@code drop-schema.sql} dropped only the two tables, and the {@code IF NOT EXISTS} guard then
 * declined to recreate the function). The store reported a validated schema while its notifications
 * were dead. The first half of this class pins down that this is fixed: functions are
 * {@code CREATE OR REPLACE}d, triggers are compared and recreated when their shape differs, and the
 * drop script takes the functions with it.
 * <p>
 * The second half pins down the part that is <em>not</em> fixed, so the remaining gap stays visible:
 * {@code checkDatabase()} still validates that objects exist, never what they are, so an index of the
 * wrong kind or a lost unique constraint passes validation. Closing that needs the version-marker work
 * described in {@code SCHEMA-MIGRATION.md}.
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

		/** A function body changed after creation is brought back to the shipped one by {@code ENSURE}. */
		@Test
		public void testStaleFunctionIsRepairedByEnsure ( ) throws Exception {
			String prefix = "driftensure_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();
			hijackNotifyFunction(dataSource, prefix);
			assertTrue(functionBody(dataSource, prefix).contains(HIJACKED_CHANNEL), "precondition: body was replaced");

			ensure(prefix, dataSource).close();

			assertFalse(functionBody(dataSource, prefix).contains(HIJACKED_CHANNEL),
				"ENSURE replaced the stale function body");
			assertTrue(functionBody(dataSource, prefix).contains(prefix + "event_appended"),
				"the body shipped with this release reached the database");

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- §3.2

		/**
		 * {@code INITIALIZE} means what it says: the functions go with the tables.
		 * <p>
		 * This was the sharpest form of the old bug — {@code drop-schema.sql} dropped the two tables
		 * (taking the triggers with them via {@code CASCADE}) and nothing else, so the function was
		 * still in {@code pg_proc} when {@code ensure-schema.sql} ran, where {@code IF NOT EXISTS}
		 * declined to recreate it, and the freshly created trigger was wired straight back to the old
		 * body.
		 */
		@Test
		public void testInitializeReplacesAStaleFunction ( ) throws Exception {
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

			assertFalse(functionBody(dataSource, prefix).contains(HIJACKED_CHANNEL),
				"INITIALIZE really is from scratch: the stale function body is gone");

			PostgresContainer.closeDataSource(image);
		}

		/**
		 * The repair is functional, not cosmetic: notifications flow again afterwards.
		 * <p>
		 * This is the assertion that would have caught the original bug. A count of delivered
		 * notifications is the only thing that distinguishes a store whose trigger calls the shipped
		 * function from one whose trigger calls a stale body — validation cannot see the difference,
		 * and the store logs success either way.
		 */
		@Test
		public void testNotificationsWorkAfterInitializeRepairsAStaleFunction ( ) throws Exception {
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
				waitForNotification(notifications);

				assertEquals(1, notifications.get(),
					"the trigger created by INITIALIZE calls the shipped function, which notifies '"
						+ prefix + "event_appended'");
			}

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- §3.3

		/**
		 * The remaining gap: {@code VALIDATE} still cannot see a stale function body.
		 * <p>
		 * {@code ENSURE} now repairs one, so a deployment on the default mode heals itself on the next
		 * start. A deployment pinned to {@code VALIDATE} or {@code NONE} — the split where a DBA applies
		 * DDL — does not, and nothing reports it, because {@code checkDatabase()} only asks whether the
		 * function exists. Closing this needs the version marker; see {@code SCHEMA-MIGRATION.md}.
		 */
		@Test
		public void testValidateStillDoesNotNoticeStaleFunction ( ) throws Exception {
			String prefix = "driftvalidate_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();
			hijackNotifyFunction(dataSource, prefix);

			// VALIDATE passes against the drifted database and leaves it drifted
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
		 * A trigger whose shape has drifted is repaired by {@code ENSURE}.
		 * <p>
		 * The script compares the installed trigger's {@code tgtype} and target function rather than
		 * merely checking that the name exists, so wrong timing, wrong orientation and a trigger pointing
		 * at the wrong function are all corrected. Comparing first — instead of the unconditional
		 * {@code CREATE OR REPLACE TRIGGER} available from PostgreSQL 14 — keeps the ordinary startup, in
		 * which the trigger is already correct, a catalog read that takes no lock on the events table.
		 */
		@Test
		public void testTriggerDriftIsRepairedByEnsure ( ) throws Exception {
			String prefix = "drifttrigger_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();

			execute(dataSource, "DROP TRIGGER table_insert_trigger ON " + prefix + "events");
			execute(dataSource, "CREATE TRIGGER table_insert_trigger BEFORE INSERT ON " + prefix + "events "
				+ "FOR EACH ROW EXECUTE FUNCTION " + prefix + "notify_event_appended()");
			assertEquals("BEFORE", triggerTiming(dataSource, prefix + "events", "table_insert_trigger"),
				"precondition: the trigger fires at the wrong time");

			ensure(prefix, dataSource).close();

			assertEquals("AFTER", triggerTiming(dataSource, prefix + "events", "table_insert_trigger"),
				"ENSURE put the trigger back to AFTER INSERT");

			PostgresContainer.closeDataSource(image);
		}

		/**
		 * A trigger that is already correct is left strictly alone — the point of comparing rather than
		 * rewriting.
		 * <p>
		 * This is the assertion that protects the performance property, and nothing else does:
		 * {@link #testTriggerDriftIsRepairedByEnsure()} passes just as well against an implementation
		 * that rewrites the trigger on every start, which would take an {@code ACCESS EXCLUSIVE} lock on
		 * the events table each time an instance boots. The trigger's oid changes if it was recreated.
		 * <p>
		 * It also guards the guard itself: a future change to the trigger's shape that forgets to update
		 * the expected {@code tgtype} would make every startup believe the trigger has drifted, and this
		 * test fails rather than the cost going unnoticed.
		 */
		@Test
		public void testCorrectTriggerIsNotRewrittenByEnsure ( ) throws Exception {
			String prefix = "driftnorewrite_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			ensure(prefix, dataSource).close();
			String oidAfterCreate = triggerOid(dataSource, prefix + "events", "table_insert_trigger");
			String bookmarkOidAfterCreate = triggerOid(dataSource, prefix + "bookmarks", "table_insert_or_update_trigger");

			ensure(prefix, dataSource).close();
			ensure(prefix, dataSource).close();

			assertEquals(oidAfterCreate, triggerOid(dataSource, prefix + "events", "table_insert_trigger"),
				"the events trigger was recreated even though it was already correct");
			assertEquals(bookmarkOidAfterCreate, triggerOid(dataSource, prefix + "bookmarks", "table_insert_or_update_trigger"),
				"the bookmarks trigger was recreated even though it was already correct");

			PostgresContainer.closeDataSource(image);
		}

		/**
		 * The remaining gap, and the reason this class still exists. Both of these are real,
		 * load-bearing drift that {@code ENSURE} does not repair and validation does not report,
		 * because only the <em>name</em> of the index is checked:
		 * <ul>
		 *   <li>an index of the wrong kind and on the wrong columns, under the right name;</li>
		 *   <li>the idempotency index no longer unique — silently admits duplicate keys;</li>
		 *   <li>the {@code stream_purpose} default reverted to the pre-alignment {@code ''}.</li>
		 * </ul>
		 * Indexes and columns are deliberately only ever created, never altered — rebuilding an index on
		 * a large events table at startup is not something a library should do behind the caller's back.
		 * Detecting and reporting the drift is the part that is missing, and it needs the version marker.
		 */
		@Test
		public void testIndexAndDefaultDriftAreStillNotCaught ( ) throws Exception {
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

			// ENSURE reports success and changes none of it; VALIDATE agrees
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
			}

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- §3.5

		/**
		 * Several application instances starting at once all run {@code ensure-schema.sql} — which is
		 * what the default {@link DatabaseInitMode#ENSURE} means for a scaled-out deployment rolling
		 * onto a database that does not have the schema yet. None of them may fail.
		 * <p>
		 * {@code CREATE TABLE / INDEX / EXTENSION IF NOT EXISTS} are not atomic against a concurrent
		 * creator: the existence check and the catalog insert are separate, so two transactions can both
		 * find the object absent and both try to create it. The loser hit a unique violation on a system
		 * catalog index, the whole script rolled back, and that instance failed to start — measured at
		 * 64 of 80 instances before the per-prefix advisory lock in {@code executeSqlScripts} was
		 * introduced. Several rounds, each on its own fresh prefix, because it is a race: one round
		 * passing proves very little.
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
						messages.add(root.getClass().getSimpleName() + ": "
							+ root.getMessage().replaceAll("\\s+", " ").trim());
					}
				}

				// and the schema each round leaves behind is complete
				ensure(prefix, dataSource).close();
			}

			assertEquals(0, totalFailures,
				"every instance must start; " + totalFailures + " of " + (rounds * instances)
					+ " failed across " + roundsWithFailures + " rounds: " + messages.stream().distinct().toList());

			PostgresContainer.closeDataSource(image);
		}

		// ---------------------------------------------------------------- helpers

		/** Polls for a notification rather than sleeping a fixed time, up to a generous ceiling. */
		private void waitForNotification ( AtomicInteger notifications ) throws InterruptedException {
			for ( int attempt = 0; attempt < 100 && notifications.get() == 0; attempt++ ) {
				Thread.sleep(50);
			}
		}

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

		/** The trigger's oid, which changes if and only if it was dropped and recreated. */
		private String triggerOid ( DataSource dataSource, String tableName, String triggerName ) throws SQLException {
			return queryString(dataSource,
				"SELECT t.oid::text FROM pg_trigger t JOIN pg_class c ON t.tgrelid = c.oid "
					+ "JOIN pg_namespace n ON c.relnamespace = n.oid WHERE n.nspname = current_schema() "
					+ "AND c.relname = '" + tableName + "' AND t.tgname = '" + triggerName + "' AND NOT t.tgisinternal");
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

	/**
	 * The oldest supported major version. This class exercises the most version-sensitive SQL in the
	 * codebase — {@code tgtype} comparison, {@code ::regproc} resolution, {@code CREATE OR REPLACE
	 * FUNCTION} — so it is worth running against the floor and not only against the newest releases.
	 */
	@Nested
	class OnPostgres15 extends Tests {

		OnPostgres15 ( ) { super(PostgresContainer.IMAGE_PG15); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG15);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			PostgresContainer.stop(PostgresContainer.IMAGE_PG15);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG15);
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
