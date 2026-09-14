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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins that a read binding no stream column walks {@code idx_events_tx_position}, the index on the
 * global {@code (event_tx, event_position)} order — rather than scanning the table and sorting it.
 * <p>
 * Every {@code ORDER BY} the store issues is that pair, and the stream indexes all lead with
 * {@code (stream_context, stream_purpose)}: bound, they supply the order from a start condition; unbound,
 * they supply nothing, and the planner is left with a scan of the whole table feeding a top-N sort,
 * however small the {@code LIMIT}. The reads that legitimately bind no stream column — a wildcard stream
 * paged by a store-wide projection, {@code head()} of the whole store, an unscoped
 * {@code EventStoreImporter} run — then cost the size of the store per page instead of the size of the
 * page. Nothing else would notice the index going missing: the answers are correct either way, and the
 * compliance suite runs on tables far too small for a scan to hurt. So the plan is pinned, the way
 * {@code PostgresCursorBoundaryTest} pins the cursor's plan.
 * <p>
 * The third scenario is the migration story: a database created before the index existed is reported by
 * {@code VALIDATE} naming the index, and repaired by {@code ENSURE} on its next start.
 */
public class PostgresGlobalOrderIndexTest {

	/** Streams seeded, so the corpus is genuinely cross-stream. */
	private static final int STREAMS = 3;

	/** Appends per stream; each is one transaction, so the corpus spans transactions too. */
	private static final int SEED_APPENDS = 5;

	/** Events per seeding append. */
	private static final int SEED_BATCH = 40;

	/** A page smaller than the corpus, so a page really is a slice of it. */
	private static final int PAGE = 25;

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testAWildcardPageWalksTheGlobalOrderIndexFromTheCursor ( ) throws Exception {
			String prefix = "globalorder_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				seed(storage);
				analyze(dataSource, prefix);

				List<StoredEvent> all = storage.query(EventQuery.matchAll(), Optional.empty(), null, Limit.none(), QueryDirection.FORWARD).toList();
				String plan = explainWildcardPage(storage, dataSource, prefix, all.get(all.size() / 2).reference());

				assertTrue(plan.contains("Index Scan using " + prefix + "idx_events_tx_position"), () ->
						"a wildcard page has no stream column to enter a stream index by, so the global"
								+ " (event_tx, event_position) index is the only one that can supply its order\n" + plan);
				assertFalse(plan.contains("Sort"), () ->
						"a sort above the scan means no index supplied the order, so the LIMIT cannot stop the"
								+ " scan early and a page costs the whole store\n" + plan);
				assertFalse(plan.contains("Seq Scan"), plan);
				assertTrue(indexCondition(plan).contains("event_position"), () ->
						"the cursor has to be a start condition on that index, not a filter over rows already"
								+ " fetched from the head of the store\n" + plan);
			} finally {
				storage.close();
			}
		}

		@Test
		public void testTheStoreWideHeadWalksTheGlobalOrderIndexBackward ( ) throws Exception {
			String prefix = "globalhead_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				seed(storage);
				analyze(dataSource, prefix);

				String plan = explain(dataSource, "EXPLAIN (COSTS OFF) " + PostgresEventStorageImpl.headSql(prefix, Optional.empty()), List.of());

				assertTrue(plan.contains("Index Scan Backward using " + prefix + "idx_events_tx_position"), () ->
						"the head of the whole store is one backward probe on the global order index\n" + plan);
				assertFalse(plan.contains("Sort"), plan);
				assertFalse(plan.contains("Seq Scan"), plan);
			} finally {
				storage.close();
			}
		}

		@Test
		public void testADatabaseWithoutTheIndexIsReportedByValidateAndCreatedByEnsure ( ) throws Exception {
			String prefix = "globalmigr_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			open(prefix, dataSource).close();

			// a database created before the index existed
			execute(dataSource, "DROP INDEX " + prefix + "idx_events_tx_position");

			EventStorageException reported = assertThrows(EventStorageException.class, () ->
					PostgresEventStorage.newBuilder()
							.name("unit-test").prefix(prefix).dataSource(dataSource)
							.validateDatabase().build().close());
			assertTrue(reported.getMessage().contains(prefix + "idx_events_tx_position"),
					"VALIDATE must name the missing index: " + reported.getMessage());

			try ( EventStorage ensured = PostgresEventStorage.newBuilder()
					.name("unit-test").prefix(prefix).dataSource(dataSource)
					.ensureDatabase().build() ) {
				assertTrue(indexExists(dataSource, prefix + "idx_events_tx_position"),
						"ENSURE creates the index a database from before it existed is missing");
			}
		}

		// ---------------------------------------------------------------- helpers

		private PostgresEventStorageImpl open ( String prefix, DataSource dataSource ) {
			return (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
					.name("unit-test")
					.prefix(prefix)
					.dataSource(dataSource)
					.initializeDatabase()
					.build();
		}

		/** Round-robins the appends over the streams, so the streams interleave in the global order. */
		private void seed ( PostgresEventStorageImpl storage ) {
			for ( int batch = 0; batch < SEED_APPENDS; batch++ ) {
				for ( int s = 0; s < STREAMS; s++ ) {
					EventStreamId stream = EventStreamId.forContext("inventory").withPurpose("sku-" + s);
					List<EventToStore> events = new ArrayList<>();
					for ( int i = 0; i < SEED_BATCH; i++ ) {
						events.add(new EventToStore(stream, new EventType("StockReserved"), "{}", Tags.of("sku", "SKU-" + s), null));
					}
					storage.append(AppendCriteria.none(), Optional.of(stream), events);
				}
			}
		}

		private void analyze ( DataSource dataSource, String prefix ) throws SQLException {
			execute(dataSource, "ANALYZE " + prefix + "events");
		}

		/**
		 * Explains a cursor-carried page over a wildcard stream: the read path's select list, barrier,
		 * the store's own cursor predicate, and the read path's ordering and limit — with no stream
		 * predicate, which is the whole point. {@code enable_seqscan} is off so that the question asked is
		 * whether an index <em>can</em> supply the order, not whether a scan of a tiny table is cheaper.
		 */
		private String explainWildcardPage ( PostgresEventStorageImpl storage, DataSource dataSource, String prefix,
				EventReference cursor ) throws SQLException {
			StringBuilder sql = new StringBuilder(
					"EXPLAIN (COSTS OFF) SELECT event_position, event_tx::text, event_id FROM %sevents"
							.formatted(prefix)
							+ " WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())");
			List<Object> parameters = new ArrayList<>();
			storage.addCursorBoundary(sql, parameters, cursor, QueryDirection.FORWARD);
			// the read path's ORDER BY, verbatim: the cast keeps the name from resolving to the text output column
			sql.append(" ORDER BY event_tx::xid8, event_position LIMIT ").append(PAGE);
			return explain(dataSource, sql.toString(), parameters);
		}

		private String explain ( DataSource dataSource, String sql, List<Object> parameters ) throws SQLException {
			try ( Connection connection = dataSource.getConnection() ) {
				connection.setAutoCommit(false);
				try ( Statement seqscan = connection.createStatement() ) {
					seqscan.execute("SET LOCAL enable_seqscan = off");
				}
				try ( PreparedStatement explain = connection.prepareStatement(sql) ) {
					for ( int i = 0; i < parameters.size(); i++ ) {
						explain.setObject(i + 1, parameters.get(i));
					}
					StringBuilder plan = new StringBuilder();
					try ( ResultSet rows = explain.executeQuery() ) {
						while ( rows.next() ) {
							plan.append(rows.getString(1)).append('\n');
						}
					}
					connection.rollback();
					return plan.toString();
				}
			}
		}

		/** Everything on the plan's {@code Index Cond} lines, or the empty string if it has none. */
		private String indexCondition ( String plan ) {
			return plan.lines()
					.map(String::strip)
					.filter(line -> line.startsWith("Index Cond:"))
					.reduce("", String::concat);
		}

		private void execute ( DataSource dataSource, String sql ) throws SQLException {
			try ( Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement() ) {
				statement.execute(sql);
			}
		}

		private boolean indexExists ( DataSource dataSource, String indexName ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
					PreparedStatement stmt = connection.prepareStatement(
							"SELECT EXISTS (SELECT FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?)") ) {
				stmt.setString(1, indexName);
				try ( ResultSet rs = stmt.executeQuery() ) {
					return rs.next() && rs.getBoolean(1);
				}
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
}
