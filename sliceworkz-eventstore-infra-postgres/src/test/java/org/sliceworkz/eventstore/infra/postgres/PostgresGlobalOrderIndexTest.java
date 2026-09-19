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
import java.util.Set;

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
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins that every read walks the index on the {@code (event_tx, event_position)} order that belongs to
 * its scope — never a scan and a sort, and never the order index of a wider scope.
 * <p>
 * Every {@code ORDER BY} the store issues is that order, and three B-trees carry it: the stream indexes,
 * which lead with {@code (stream_context, stream_purpose)}, and two for the reads that leave those
 * unbound:
 * <ul>
 *   <li>{@code idx_events_global_order}, the global order, for a read that binds no stream column — a
 *       wildcard stream paged by a store-wide projection, {@code head()} of the whole store, an unscoped
 *       {@code EventStoreImporter} run;</li>
 *   <li>{@code idx_events_context_order}, the same order within a context, for a read that binds
 *       the context and leaves the purpose open — a whole-context replay or export over a per-entity
 *       layout, where every entity is its own purpose.</li>
 * </ul>
 * Without them those reads cost the size of the store per page instead of the size of the page. Nothing
 * else would notice an index going missing: the answers are correct either way, and the compliance suite
 * runs on tables far too small for a scan to hurt. So the plans are pinned, the way
 * {@code PostgresCursorBoundaryTest} pins the cursor's plan.
 * <p>
 * The reverse matters as much, and is the other half of this class: a read of one stream must not walk
 * the context or the global order and filter, which is what the planner prefers when the stream is a
 * large share of the table — and what costs a walk over every event other streams wrote since, when
 * the stream has been quiet. Each order index is keyed on its own spelling of the position
 * ({@code OrderScope}), so a statement can walk only its own. The scenarios for that seed exactly the
 * shape that invites the wider index: one stream holding most of the table, written first, and other
 * streams' events after it.
 * <p>
 * The wider-scope scenarios seed the context under test as a small share of the table, so the plans
 * they assert on are the ones the index exists for. The last scenarios are the migration story: a
 * database carrying the order indexes as they were first created, on the bare column, is reported by
 * {@code VALIDATE} with the migration and repaired by {@code ENSURE}, and so is one missing them.
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

	/** The stream that is most of the table and has been quiet since; see {@code seedQuietLargeStream}. */
	private static final EventStreamId QUIET_STREAM = EventStreamId.forContext("ledger").withPurpose("default");

	/** Events in {@link #QUIET_STREAM}, written before everything else. */
	private static final int QUIET_STREAM_EVENTS = 6_000;

	/** Events of other contexts, written after {@link #QUIET_STREAM} went quiet. */
	private static final int LATER_EVENTS = 4_000;

	/** Chunks each seeding runs in, one transaction each. */
	private static final int SEED_CHUNKS = 10;

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

				assertTrue(plan.contains("Index Scan using " + prefix + "idx_events_global_order"), () ->
						"a wildcard page has no stream column to enter a stream index by, so the global"
								+ " order index is the only one that can supply its order\n" + plan);
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

				assertTrue(plan.contains("Index Scan Backward using " + prefix + "idx_events_global_order"), () ->
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

				assertTrue(plan.contains("Index Scan using " + prefix + "idx_events_context_order"), () ->
						"a context page leaves the purpose open, so the stream indexes offer no start condition;"
								+ " the context order index is entered at the context\n" + plan);
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

				assertTrue(plan.contains("Index Scan Backward using " + prefix + "idx_events_context_order"), () ->
						"the head of a context is one backward probe on the context order index\n" + plan);
				assertFalse(plan.contains("Sort"), plan);
				assertFalse(plan.contains("Seq Scan"), plan);
			} finally {
				storage.close();
			}
		}

		@Test
		public void testTheHeadOfAQuietLargeStreamWalksTheStreamIndexNotAWiderOrder ( ) throws Exception {
			String prefix = "quiethead_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				seedQuietLargeStream(dataSource, prefix);
				analyze(dataSource, prefix);

				String sql = "EXPLAIN (COSTS OFF) " + PostgresEventStorageImpl.headSql(prefix, QUIET_STREAM);
				String plan = explain(dataSource, sql, List.of(QUIET_STREAM.context(), QUIET_STREAM.purpose()));

				assertTrue(plan.contains("Index Scan Backward using " + prefix + "idx_events_stream_position"), () ->
						"the head of a stream is one backward probe on the stream's own index; walked off the"
								+ " context or the global order, it filters out every event written since it went quiet\n" + plan);
				assertStreamColumnsAreIndexConditions(plan);
				assertFalse(plan.contains("Sort"), plan);
			} finally {
				storage.close();
			}
		}

		@Test
		public void testANewestFirstPageOfAQuietLargeStreamWalksTheStreamIndex ( ) throws Exception {
			String prefix = "quietpage_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				seedQuietLargeStream(dataSource, prefix);
				analyze(dataSource, prefix);

				List<StoredEvent> all = storage.query(EventFilter.matchAll(), QUIET_STREAM, null, Limit.none(), Direction.FORWARD);
				String plan = explainPage(storage, dataSource, prefix, QUIET_STREAM, all.get(all.size() / 2).reference(), Direction.BACKWARD);

				assertTrue(plan.contains("Index Scan Backward using " + prefix + "idx_events_stream_position"), () ->
						"a stream read walks the stream's own index, whatever share of the table the stream is\n" + plan);
				assertStreamColumnsAreIndexConditions(plan);
				assertOrderedWalkFromTheCursor(plan);
			} finally {
				storage.close();
			}
		}

		@Test
		public void testTheHeadOfAQuietLargeContextWalksTheContextIndexNotTheGlobalOrder ( ) throws Exception {
			String prefix = "quietctx_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				seedQuietLargeStream(dataSource, prefix);
				analyze(dataSource, prefix);

				EventStreamId context = EventStreamId.forContext(QUIET_STREAM.context()).anyPurpose();
				String sql = "EXPLAIN (COSTS OFF) " + PostgresEventStorageImpl.headSql(prefix, context);
				String plan = explain(dataSource, sql, List.of(context.context()));

				assertTrue(plan.contains("Index Scan Backward using " + prefix + "idx_events_context_order"), () ->
						"the head of a context is one backward probe on the context order index; walked off the"
								+ " global order, it filters out every event other contexts wrote since\n" + plan);
				assertTrue(indexCondition(plan).contains("stream_context"), plan);
				assertFalse(plan.contains("Sort"), plan);
			} finally {
				storage.close();
			}
		}

		/**
		 * The consistency check of a conditional append runs as a server-prepared statement, and once
		 * PostgreSQL adopts its generic plan every append runs that plan. So the plan asserted on is the
		 * generic one, prepared and explained under {@code plan_cache_mode = force_generic_plan}, for the
		 * store's own check: a type and a tag, and the stream's head as the
		 * expected reference -- the recommended pin, which on a quiet stream is far back in the global
		 * order. Walked off the global order, the probe filters out every event written anywhere since.
		 */
		@Test
		public void testTheConsistencyCheckOfAQuietLargeStreamNeverWalksAWiderOrder ( ) throws Exception {
			String prefix = "quietcheck_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				seedQuietLargeStream(dataSource, prefix);
				analyze(dataSource, prefix);

				EventReference head = storage.head(QUIET_STREAM).orElseThrow();
				AppendCriteria criteria = AppendCriteria.of(
						EventFilter.forEvents(EventTypesFilter.of(Set.of(new EventType("SomethingHappened"))), Tags.of("acct", "idle")),
						head);
				StringBuilder check = new StringBuilder("SELECT 1 FROM ( VALUES (1) ) AS new_events ");
				storage.addConsistencyCheck(check, new ArrayList<>(), criteria, QUIET_STREAM);
				String plan = explainGeneric(dataSource, check.toString());

				assertTrue(plan.contains(prefix + "idx_events_stream_position"), () ->
						"the check is answered off the stream's own index\n" + plan);
				String condition = indexCondition(plan);
				assertTrue(condition.contains("stream_context") && condition.contains("stream_purpose")
						&& condition.contains("ROW(event_tx, event_position)"), () ->
						"the stream and the expected reference both enter that index, so the probe starts at the"
								+ " reference and sees only the stream's events after it\n" + plan);
				assertFalse(plan.contains(prefix + "idx_events_global_order"), () ->
						"the check of one stream must not walk the global order: from a quiet stream's head that"
								+ " is every event written anywhere since\n" + plan);
				assertFalse(plan.contains(prefix + "idx_events_context_order"), () ->
						"the check of one stream must not walk its context's order either\n" + plan);
			} finally {
				storage.close();
			}
		}

		@Test
		public void testADatabaseWithTheBareColumnOrderIndexesIsReportedByValidateAndMigratedByEnsure ( ) throws Exception {
			String prefix = "ordermigr_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			open(prefix, dataSource).close();

			// the order indexes as a database created before the per-scope spelling carries them
			execute(dataSource, "DROP INDEX " + prefix + "idx_events_global_order");
			execute(dataSource, "DROP INDEX " + prefix + "idx_events_context_order");
			execute(dataSource, "CREATE INDEX " + prefix + "idx_events_tx_position ON " + prefix + "events (event_tx, event_position)");
			execute(dataSource, "CREATE INDEX " + prefix + "idx_events_context_tx_position ON " + prefix + "events (stream_context, event_tx, event_position)");

			EventStorageException reported = assertThrows(EventStorageException.class, () ->
					PostgresEventStorage.newBuilder()
							.name("unit-test").prefix(prefix).dataSource(dataSource)
							.validateDatabase().build().close());
			assertTrue(reported.getMessage().contains(prefix + "idx_events_tx_position"),
					"VALIDATE must name the index to replace: " + reported.getMessage());
			assertTrue(reported.getMessage().contains(PostgresEventStorageImpl.ORDER_INDEXES_MIGRATION.formatted(prefix)),
					"VALIDATE must name the migration: " + reported.getMessage());

			try ( EventStorage ensured = PostgresEventStorage.newBuilder()
					.name("unit-test").prefix(prefix).dataSource(dataSource)
					.ensureDatabase().build() ) {
				assertTrue(indexExists(dataSource, prefix + "idx_events_global_order"));
				assertTrue(indexExists(dataSource, prefix + "idx_events_context_order"));
				assertFalse(indexExists(dataSource, prefix + "idx_events_tx_position"),
						"ENSURE drops the bare-column order index, whose presence is what a stream read walks");
				assertFalse(indexExists(dataSource, prefix + "idx_events_context_tx_position"),
						"ENSURE drops the bare-column context order index, for the same reason");
			}
		}

		@Test
		public void testTheMigrationItNamesAppliesToAnExistingDatabase ( ) throws Exception {
			String prefix = "ordermigrsql_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			open(prefix, dataSource).close();
			execute(dataSource, "DROP INDEX " + prefix + "idx_events_global_order");
			execute(dataSource, "DROP INDEX " + prefix + "idx_events_context_order");
			execute(dataSource, "CREATE INDEX " + prefix + "idx_events_tx_position ON " + prefix + "events (event_tx, event_position)");
			execute(dataSource, "CREATE INDEX " + prefix + "idx_events_context_tx_position ON " + prefix + "events (stream_context, event_tx, event_position)");

			// CONCURRENTLY refuses a transaction block, so the migration is run one statement at a time
			// on autocommit -- as a DBA pasting it into psql runs it
			for ( String statement : PostgresEventStorageImpl.ORDER_INDEXES_MIGRATION.formatted(prefix).split(";") ) {
				if ( !statement.isBlank() ) {
					execute(dataSource, statement);
				}
			}

			PostgresEventStorage.newBuilder()
					.name("unit-test").prefix(prefix).dataSource(dataSource)
					.validateDatabase().build().close();
		}

		@Test
		public void testADatabaseWithoutTheIndexesIsReportedByValidateAndRepairedByEnsure ( ) throws Exception {
			String prefix = "ordermissing_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			open(prefix, dataSource).close();

			for ( String index : List.of("idx_events_global_order", "idx_events_context_order") ) {
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

		/**
		 * The shape that invites a stream read onto a wider order index: {@link #QUIET_STREAM} holds most
		 * of the table, so the smaller global index looks like a cheap walk with a filter, and it is
		 * written first, so its newest event -- and its context's -- sits behind every event written
		 * after it. Those are written one row per stream in turn across two other contexts, each a
		 * single stream as {@link #QUIET_STREAM} is -- the stream-per-context layout -- one context
		 * sorting before {@link #QUIET_STREAM}'s and one after, which is what a table written by several
		 * contexts looks like: the stream columns do not follow the table's physical order. Both halves
		 * matter to the plan, and a corpus without them hides it. The planner prices a walk of the
		 * stream index by how well its leading column follows the heap, and contexts written in
		 * alphabetical order make it look as cheap as it is; and it estimates the stream's share of the
		 * table from the purpose as well as the context, so neighbours with a purpose per entity make
		 * the stream look small enough that the consistency check's generic plan keeps to the stream
		 * index. Seeded in SQL, one transaction per chunk so the transaction ids advance as appends
		 * would make them.
		 */
		private void seedQuietLargeStream ( DataSource dataSource, String prefix ) throws SQLException {
			for ( int chunk = 0; chunk < SEED_CHUNKS; chunk++ ) {
				insert(dataSource, prefix, "'" + QUIET_STREAM.context() + "'", "'" + QUIET_STREAM.purpose() + "'", QUIET_STREAM_EVENTS / SEED_CHUNKS);
			}
			for ( int chunk = 0; chunk < SEED_CHUNKS; chunk++ ) {
				insert(dataSource, prefix, "(ARRAY['accounts', 'sales'])[1 + i % 2]", "'default'", LATER_EVENTS / SEED_CHUNKS);
			}
		}

		private void insert ( DataSource dataSource, String prefix, String context, String purpose, int count ) throws SQLException {
			execute(dataSource, ("INSERT INTO %sevents (event_id, stream_context, stream_purpose, event_type, event_data, event_tags)"
					+ " SELECT gen_random_uuid(), %s, %s, 'SomethingHappened', '{}', '{}' FROM generate_series(1, %d) i")
					.formatted(prefix, context, purpose, count));
		}

		private void analyze ( DataSource dataSource, String prefix ) throws SQLException {
			execute(dataSource, "ANALYZE " + prefix + "events");
		}

		/** Both stream columns enter the index; neither is a filter over rows another stream owns. */
		private void assertStreamColumnsAreIndexConditions ( String plan ) {
			String condition = indexCondition(plan);
			assertTrue(condition.contains("stream_context") && condition.contains("stream_purpose"), () ->
					"both stream columns have to be index conditions, not a filter over other streams' events\n" + plan);
			assertFalse(plan.contains("Filter:"), () ->
					"nothing of another stream is walked, so nothing is filtered out\n" + plan);
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
		 * predicate, whichever stream predicates the scope binds, and the read path's ordering and limit,
		 * in the scope's spelling of the position. {@code enable_seqscan} is off so that the question
		 * asked is whether an index <em>can</em> supply the order, not whether a scan of a tiny table is
		 * cheaper.
		 */
		private String explainPage ( PostgresEventStorageImpl storage, DataSource dataSource, String prefix,
				EventStreamId stream, EventReference cursor ) throws SQLException {
			return explainPage(storage, dataSource, prefix, stream, cursor, Direction.FORWARD);
		}

		private String explainPage ( PostgresEventStorageImpl storage, DataSource dataSource, String prefix,
				EventStreamId stream, EventReference cursor, Direction direction ) throws SQLException {
			PostgresEventStorageImpl.OrderScope scope = PostgresEventStorageImpl.OrderScope.of(stream);
			StringBuilder sql = new StringBuilder(
					"EXPLAIN (COSTS OFF) SELECT event_position, event_tx::text, event_id FROM %sevents"
							.formatted(prefix)
							+ " WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())");
			List<Object> parameters = new ArrayList<>();
			storage.addCursorBoundary(sql, parameters, cursor, direction, scope);
			if ( !stream.isAnyContext() ) {
				sql.append(" AND stream_context = ?");
				parameters.add(stream.context());
			}
			if ( !stream.isAnyPurpose() ) {
				sql.append(" AND stream_purpose = ?");
				parameters.add(stream.purpose());
			}
			// the read path's ORDER BY: the cast keeps the name from resolving to the text output column
			String descending = direction == Direction.BACKWARD ? " DESC" : "";
			sql.append(" ORDER BY event_tx::xid8").append(descending).append(", ").append(scope.position()).append(descending)
					.append(" LIMIT ").append(PAGE);
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

		/**
		 * The generic plan of a statement written with JDBC {@code ?} placeholders: the plan the plan
		 * cache runs once it has adopted one. Each {@code ?} becomes {@code $1}, {@code $2}, ... for a
		 * {@code PREPARE}, and the statement is explained under {@code force_generic_plan}, where the
		 * values handed to {@code EXECUTE} play no part in the plan -- so nulls do. The statement must
		 * carry no other {@code ?}, which the check does not.
		 */
		private String explainGeneric ( DataSource dataSource, String sql ) throws SQLException {
			StringBuilder numbered = new StringBuilder();
			int parameters = 0;
			for ( char c : sql.toCharArray() ) {
				if ( c == '?' ) {
					numbered.append('$').append(++parameters);
				} else {
					numbered.append(c);
				}
			}
			try ( Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement() ) {
				statement.execute("PREPARE generic_check AS " + numbered);
				try {
					statement.execute("SET plan_cache_mode = force_generic_plan");
					statement.execute("SET enable_seqscan = off");
					StringBuilder plan = new StringBuilder();
					try ( ResultSet rows = statement.executeQuery("EXPLAIN (COSTS OFF) EXECUTE generic_check("
							+ String.join(", ", java.util.Collections.nCopies(parameters, "NULL")) + ")") ) {
						while ( rows.next() ) {
							plan.append(rows.getString(1)).append('\n');
						}
					}
					return plan.toString();
				} finally {
					statement.execute("DEALLOCATE generic_check");
					statement.execute("RESET plan_cache_mode");
					statement.execute("RESET enable_seqscan");
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
