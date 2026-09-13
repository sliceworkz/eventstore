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
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A store restored <em>logically</em> into a cluster whose transaction counter is younger than the
 * data must refuse to start, because started it fails silently twice over.
 * <p>
 * {@code pg_dump} copies {@code event_tx} as plain data, so a restore into a fresh cluster keeps the
 * old cluster's transaction ids — typically in the billions, since {@code xid8} carries the epoch —
 * while the new cluster hands out ids from a few hundred. Every read sits behind
 * {@code event_tx < pg_snapshot_xmin(...)}, so the restored history reads as absent while
 * {@code SELECT count(*)} shows every row; and the first append gets a low id and sorts before all of
 * it in the {@code (event_tx, event_position)} order, so a bookmark at the old head is ahead of every
 * new event and a lock check whose reference is in history sees nothing after it. Nothing fails and
 * nothing is logged, and the counter catching up with the data is years away.
 * <p>
 * The signature is unambiguous: a stored transaction id at or above the next one the cluster will
 * assign, which no append can produce. These scenarios plant such a row — the same thing a restore
 * does — and pin down that {@code build()} fails under every init mode that keeps the data, that the
 * failure names the remedies, that a store already appended to after the restore is still reported
 * (the newest-position row is then an ordinary low-tx event, so a check reading only that row would
 * pass), that the failed storage is closed rather than left with its monitors running, that an
 * ordinary store is untouched by the check, and that the check walks the stream index rather than
 * scanning the table — since it runs on every start.
 */
public class PostgresRestoredIntoYoungerClusterTest {

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testAStoreRestoredIntoAYoungerClusterRefusesToStart ( ) throws Exception {
			String prefix = "restored_";
			DataSource dataSource = prepare(prefix);
			plantRestoredEvent(dataSource, prefix, "account", "1");

			for ( DatabaseInitMode mode : List.of(DatabaseInitMode.NONE, DatabaseInitMode.VALIDATE, DatabaseInitMode.ENSURE) ) {
				EventStorageException e = assertThrows(EventStorageException.class, () -> PostgresEventStorage.newBuilder()
						.name("restored-" + mode)
						.prefix(prefix)
						.dataSource(dataSource)
						.databaseInitMode(mode)
						.build(),
					"under " + mode + " the restored history must fail the start rather than read as an empty store");

				assertTrue(e.getMessage().contains("pg_resetwal"), "the error should name the counter remedy: " + e.getMessage());
				assertTrue(e.getMessage().contains("EventStoreImporter"), "the error should name the import remedy: " + e.getMessage());
				assertTrue(e.getMessage().contains("physical backup"), "the error should say what would have avoided it: " + e.getMessage());
				assertTrue(e.getMessage().contains("1 stream"), "the error should say how much of the store is affected: " + e.getMessage());
			}
		}

		/**
		 * The case that decides how the check has to be written. Once something has been appended to the
		 * restored store, the row at the newest position is an ordinary event with a low transaction id;
		 * only the stream heads by <em>transaction id</em> still show the restored history above the
		 * counter — and that is exactly the state to keep reporting, since it is now the ordering that
		 * is broken and not only the visibility.
		 */
		@Test
		public void testAnAppendBelowTheRestoredHistoryDoesNotHideItFromTheCheck ( ) throws Exception {
			String prefix = "restoredapp_";
			DataSource dataSource = prepare(prefix);
			plantRestoredEvent(dataSource, prefix, "account", "1");
			// what an application appends after the restore: an ordinary row, default transaction id,
			// newest position
			execute(dataSource, "INSERT INTO " + prefix + "events (event_id, stream_context, stream_purpose, event_type, event_data, event_tags)"
					+ " VALUES (gen_random_uuid(), 'account', '1', 'Deposited', '{}', '{}')");
			assertEquals("Deposited", newestByPosition(dataSource, prefix), "the planted row must not be the newest by position, or this proves nothing");

			EventStorageException e = assertThrows(EventStorageException.class, () -> PostgresEventStorage.newBuilder()
					.name("restored-appended")
					.prefix(prefix)
					.dataSource(dataSource)
					.databaseInitMode(DatabaseInitMode.NONE)
					.build());
			assertTrue(e.getMessage().contains("pg_resetwal"), e.getMessage());
		}

		/** A storage that failed to start is closed: nothing keeps listening behind a handle nobody got. */
		@Test
		public void testAFailedStartLeavesAClosedStorage ( ) throws Exception {
			String prefix = "restoredcl_";
			DataSource dataSource = prepare(prefix);
			plantRestoredEvent(dataSource, prefix, "order", "7");

			PostgresEventStorageImpl storage = new PostgresEventStorageImpl(
				"restored-closed", dataSource, dataSource, Limit.none(), prefix, false, new SimpleMeterRegistry());
			assertThrows(EventStorageException.class, storage::start);
			assertThrows(IllegalStateException.class, storage::start,
				"a storage whose startup failed must be closed, and a closed storage is terminal");
			assertFalse(storage.isNotificationsAvailable(), "no monitor may be left listening behind a start that failed");
		}

		/** The ordinary case, which is every start there is: many streams, nothing above the counter, no effect. */
		@Test
		public void testAnOrdinaryStoreStartsUnderEveryInitMode ( ) throws Exception {
			String prefix = "ordinary_";
			DataSource dataSource = prepare(prefix);
			execute(dataSource, "INSERT INTO " + prefix + "events (event_id, stream_context, stream_purpose, event_type, event_data, event_tags)"
					+ " SELECT gen_random_uuid(), 'account', (i % 40)::text, 'Opened', '{}', '{}' FROM generate_series(1, 400) i");

			for ( DatabaseInitMode mode : List.of(DatabaseInitMode.NONE, DatabaseInitMode.VALIDATE, DatabaseInitMode.ENSURE) ) {
				try ( EventStorage storage = PostgresEventStorage.newBuilder()
						.name("ordinary-" + mode)
						.prefix(prefix)
						.dataSource(dataSource)
						.databaseInitMode(mode)
						.build() ) {
					assertTrue(storage.head(Optional.of(EventStreamId.forContext("account").withPurpose("3"))).isPresent());
				}
			}
		}

		/**
		 * The check runs on every start, so it has to be a handful of index probes whatever the store
		 * holds: the stream enumeration and each head off {@code idx_events_stream_position}, never a
		 * scan of the table and never a sort. With sequential scans disabled the planner shows whether
		 * the index <em>can</em> serve every step, which is the property that keeps it bounded.
		 */
		@Test
		public void testTheCheckWalksTheStreamIndexAndNeverScansTheTable ( ) throws Exception {
			String prefix = "guardplan_";
			DataSource dataSource = prepare(prefix);
			execute(dataSource, "INSERT INTO " + prefix + "events (event_id, stream_context, stream_purpose, event_type, event_data, event_tags)"
					+ " SELECT gen_random_uuid(), 'account', (i % 50)::text, 'Opened', '{}', '{}' FROM generate_series(1, 2000) i");
			execute(dataSource, "ANALYZE " + prefix + "events");

			String plan = explain(dataSource, PostgresEventStorageImpl.clusterAheadOfHistorySql(prefix));

			assertFalse(plan.contains("Seq Scan"), "the check must not scan the events table:\n" + plan);
			assertFalse(plan.contains("Sort"), "every step must come off the index in order:\n" + plan);
			assertTrue(plan.contains("using " + prefix + "idx_events_stream_position"), "the check must walk the stream position index:\n" + plan);
			assertTrue(plan.contains("Backward"), "each head is the index walked backwards from the end of its stream:\n" + plan);
		}

		/** A fresh, empty store for the prefix; the storage that created it is closed again. */
		private DataSource prepare ( String prefix ) {
			DataSource dataSource = PostgresContainer.dataSource(image);
			try ( EventStorage storage = PostgresEventStorage.newBuilder()
					.name("prepare")
					.prefix(prefix)
					.dataSource(dataSource)
					.initializeDatabase()
					.build() ) {
				storage.append(AppendCriteria.none(), Optional.of(EventStreamId.forContext("account").withPurpose("1")),
					List.of(new EventToStore(EventStreamId.forContext("account").withPurpose("1"), new EventType("Opened"), "{}", Tags.none(), null)));
			}
			return dataSource;
		}

		/**
		 * What a logical restore leaves behind: a row whose transaction id the cluster has not reached.
		 * A billion above the next id to be assigned, which is what an old cluster's epoch looks like
		 * from a fresh one.
		 */
		private void plantRestoredEvent ( DataSource dataSource, String prefix, String context, String purpose ) throws SQLException {
			execute(dataSource, "INSERT INTO " + prefix + "events (event_id, stream_context, stream_purpose, event_type, event_data, event_tags, event_tx)"
					+ " VALUES (gen_random_uuid(), '" + context + "', '" + purpose + "', 'Restored', '{}', '{}',"
					+ " (pg_snapshot_xmax(pg_current_snapshot())::text::numeric + 1000000000)::text::xid8)");
		}

		private String newestByPosition ( DataSource dataSource, String prefix ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
					Statement statement = connection.createStatement();
					ResultSet rs = statement.executeQuery("SELECT event_type FROM " + prefix + "events ORDER BY event_position DESC LIMIT 1") ) {
				rs.next();
				return rs.getString(1);
			}
		}

		private String explain ( DataSource dataSource, String sql ) throws SQLException {
			try ( Connection connection = dataSource.getConnection() ) {
				connection.setAutoCommit(false);
				try ( Statement statement = connection.createStatement() ) {
					statement.execute("SET LOCAL enable_seqscan = off");
					StringBuilder plan = new StringBuilder();
					try ( ResultSet rows = statement.executeQuery("EXPLAIN (COSTS OFF) " + sql) ) {
						while ( rows.next() ) {
							plan.append(rows.getString(1)).append('\n');
						}
					}
					connection.rollback();
					return plan.toString();
				}
			}
		}

		private void execute ( DataSource dataSource, String sql ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
					Statement statement = connection.createStatement() ) {
				statement.execute(sql);
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
