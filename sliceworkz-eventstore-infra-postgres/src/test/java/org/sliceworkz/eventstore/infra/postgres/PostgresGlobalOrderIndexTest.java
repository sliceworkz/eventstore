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

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins that a read which does not bind both stream columns walks an index on the
 * {@code (event_tx, event_position)} order — rather than scanning and sorting.
 * <p>
 * Every {@code ORDER BY} the store issues is that pair, and the stream indexes all lead with
 * {@code (stream_context, stream_purpose)}: bound, they supply the order from a start condition; unbound,
 * they supply nothing, and the planner is left with a scan feeding a top-N sort, however small the
 * {@code LIMIT}. Two indexes cover the two ways a read can leave them unbound:
 * <ul>
 *   <li>{@code idx_events_tx_position}, the global order, for a read that binds no stream column — a
 *       wildcard stream paged by a store-wide projection, {@code head()} of the whole store, an unscoped
 *       {@code EventStoreImporter} run;</li>
 *   <li>{@code idx_events_context_tx_position}, the same order within a context, for a read that binds
 *       the context and leaves the purpose open — a whole-context replay or export over a per-entity
 *       layout, where every entity is its own purpose.</li>
 * </ul>
 * Without them those reads cost the size of the store per page instead of the size of the page. Nothing
 * else would notice an index going missing: the answers are correct either way, and the compliance suite
 * runs on tables far too small for a scan to hurt. So the plans are pinned, the way
 * {@code PostgresCursorBoundaryTest} pins the cursor's plan.
 * <p>
 * The context scenarios seed the context under test as a small share of the table. That is the case
 * the context index exists for: when a context is most of the table the planner may just as well walk
 * the smaller global index and filter, which is also cheap, and either plan would satisfy a looser
 * assertion — so the corpus is skewed to make the choice, and the assertion, unambiguous.
 * <p>
 * The last scenario is the migration story: a database created before the indexes existed is reported by
 * {@code VALIDATE} naming the missing one, and repaired by {@code ENSURE} on its next start.
 */
public class PostgresGlobalOrderIndexTest {

	/** The context whose reads are explained; a minority of the table, see the class comment. */
	private static final String CONTEXT = "inventory";

	/** Streams seeded in {@link #CONTEXT}, so a context read is genuinely cross-stream. */
	private static final int CONTEXT_STREAMS = 3;

	/** Streams seeded in the other context, each the same size, so {@link #CONTEXT} is a quarter of the table. */
	private static final int OTHER_STREAMS = 9;

	/** Appends per stream; each is one transaction, so the corpus spans transactions too. */
	private static final int SEED_APPENDS = 5;

	/** Events per seeding append. */
	private static final int SEED_BATCH = 40;

	/** A page smaller than a context, so a page really is a slice of it. */
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

				List<StoredEvent> all = storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), null, Limit.none(), Direction.FORWARD);
				String plan = explainPage(storage, dataSource, prefix, EventStreamId.anyContext(), all.get(all.size() / 2).reference());

				assertTrue(plan.contains("Index Scan using " + prefix + "idx_events_tx_position"), () ->
						"a wildcard page has no stream column to enter a stream index by, so the global"
								+ " (event_tx, event_position) index is the only one that can supply its order\n" + plan);
				assertOrderedWalkFromTheCursor(plan);
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

				String plan = explain(dataSource, "EXPLAIN (COSTS OFF) " + PostgresEventStorageImpl.headSql(prefix, EventStreamId.anyContext()), List.of());

				assertTrue(plan.contains("Index Scan Backward using " + prefix + "idx_events_tx_position"), () ->
						"the head of the whole store is one backward probe on the global order index\n" + plan);
				assertFalse(plan.contains("Sort"), plan);
				assertFalse(plan.contains("Seq Scan"), plan);
			} finally {
				storage.close();
			}
		}

		@Test
		public void testAContextPageWalksTheContextOrderIndexFromTheCursor ( ) throws Exception {
			String prefix = "contextorder_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				seed(storage);
				analyze(dataSource, prefix);

				EventStreamId context = EventStreamId.forContext(CONTEXT).anyPurpose();
				List<StoredEvent> all = storage.query(EventFilter.matchAll(), context, null, Limit.none(), Direction.FORWARD);
				String plan = explainPage(storage, dataSource, prefix, context, all.get(all.size() / 2).reference());

				assertTrue(plan.contains("Index Scan using " + prefix + "idx_events_context_tx_position"), () ->
						"a context page leaves the purpose open, so the stream indexes offer no start condition;"
								+ " the (stream_context, event_tx, event_position) index is entered at the context\n" + plan);
				assertTrue(indexCondition(plan).contains("stream_context"), () ->
						"the context has to be an index condition, not a filter over every other context's events\n" + plan);
				assertOrderedWalkFromTheCursor(plan);
			} finally {
				storage.close();
			}
		}

		@Test
		public void testTheHeadOfAContextWalksTheContextOrderIndexBackward ( ) throws Exception {
			String prefix = "contexthead_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				seed(storage);
				analyze(dataSource, prefix);

				String sql = "EXPLAIN (COSTS OFF) " + PostgresEventStorageImpl.headSql(prefix, EventStreamId.forContext(CONTEXT).anyPurpose());
				String plan = explain(dataSource, sql, List.of(CONTEXT));

				assertTrue(plan.contains("Index Scan Backward using " + prefix + "idx_events_context_tx_position"), () ->
						"the head of a context is one backward probe on the context order index\n" + plan);
				assertFalse(plan.contains("Sort"), plan);
				assertFalse(plan.contains("Seq Scan"), plan);
			} finally {
				storage.close();
			}
		}

		@Test
		public void testADatabaseWithoutTheIndexesIsReportedByValidateAndRepairedByEnsure ( ) throws Exception {
			String prefix = "ordermigr_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			open(prefix, dataSource).close();

			for ( String index : List.of("idx_events_tx_position", "idx_events_context_tx_position") ) {
				// a database created before the index existed
				execute(dataSource, "DROP INDEX " + prefix + index);

				EventStorageException reported = assertThrows(EventStorageException.class, () ->
						PostgresEventStorage.newBuilder()
								.name("unit-test").prefix(prefix).dataSource(dataSource)
								.validateDatabase().build().close());
				assertTrue(reported.getMessage().contains(prefix + index),
						"VALIDATE must name the missing index: " + reported.getMessage());

				try ( EventStorage ensured = PostgresEventStorage.newBuilder()
						.name("unit-test").prefix(prefix).dataSource(dataSource)
						.ensureDatabase().build() ) {
					assertTrue(indexExists(dataSource, prefix + index),
							"ENSURE creates the index a database from before it existed is missing: " + index);
				}
			}
		}

		// ---------------------------------------------------------------- helpers

		private PostgresEventStorageImpl open ( String prefix, DataSource dataSource ) {
			return (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
					.name("unit-test")
					.prefix(prefix)
					.dataSource(dataSource)
					.recreateDatabase()
					.build();
		}

		/**
		 * Round-robins the appends over every stream of both contexts, so the streams and the contexts
		 * interleave in the global order and {@link #CONTEXT} is a minority of the table.
		 */
		private void seed ( PostgresEventStorageImpl storage ) {
			List<EventStreamId> streams = new ArrayList<>();
			for ( int s = 0; s < CONTEXT_STREAMS; s++ ) {
				streams.add(EventStreamId.forContext(CONTEXT).withPurpose("sku-" + s));
			}
			for ( int s = 0; s < OTHER_STREAMS; s++ ) {
				streams.add(EventStreamId.forContext("sales").withPurpose("order-" + s));
			}
			for ( int batch = 0; batch < SEED_APPENDS; batch++ ) {
				for ( EventStreamId stream : streams ) {
					List<EventToStore> events = new ArrayList<>();
					for ( int i = 0; i < SEED_BATCH; i++ ) {
						events.add(new EventToStore(stream, new EventType("SomethingHappened"), "{}", Tags.of("purpose", stream.purpose()), null));
					}
					storage.append(AppendCriteria.none(), stream, events);
				}
			}
		}

		private void analyze ( DataSource dataSource, String prefix ) throws SQLException {
			execute(dataSource, "ANALYZE " + prefix + "events");
		}

		/** The three assertions every ordered page shares, in the order the mechanism has to hold. */
		private void assertOrderedWalkFromTheCursor ( String plan ) {
			assertTrue(indexCondition(plan).contains("event_position"), () ->
					"the cursor has to be a start condition on the index, not a filter over rows already"
							+ " fetched from the head\n" + plan);
			assertFalse(plan.contains("Sort"), () ->
					"a sort above the scan means no index supplied the order, so the LIMIT cannot stop the"
							+ " scan early and a page costs everything after the cursor\n" + plan);
			assertFalse(plan.contains("Seq Scan"), plan);
		}

		/**
		 * Explains a cursor-carried page: the read path's select list, barrier, the store's own cursor
		 * predicate, whichever stream predicates the scope binds, and the read path's ordering and limit.
		 * {@code enable_seqscan} is off so that the question asked is whether an index <em>can</em> supply
		 * the order, not whether a scan of a tiny table is cheaper.
		 */
		private String explainPage ( PostgresEventStorageImpl storage, DataSource dataSource, String prefix,
				EventStreamId stream, EventReference cursor ) throws SQLException {
			StringBuilder sql = new StringBuilder(
					"EXPLAIN (COSTS OFF) SELECT event_position, event_tx::text, event_id FROM %sevents"
							.formatted(prefix)
							+ " WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())");
			List<Object> parameters = new ArrayList<>();
			storage.addCursorBoundary(sql, parameters, cursor, Direction.FORWARD);
			if ( !stream.isAnyContext() ) {
				sql.append(" AND stream_context = ?");
				parameters.add(stream.context());
			}
			if ( !stream.isAnyPurpose() ) {
				sql.append(" AND stream_purpose = ?");
				parameters.add(stream.purpose());
			}
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
