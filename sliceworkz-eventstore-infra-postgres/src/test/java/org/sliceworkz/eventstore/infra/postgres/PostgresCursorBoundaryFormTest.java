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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl.CursorBoundaryForm;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Pins down {@link CursorBoundaryForm}: that the two spellings of the {@code (event_tx,
 * event_position)} boundary agree on every event, and that only one of them can drive the index as a
 * range.
 * <p>
 * <b>What the setting is for.</b> A cursor boundary is nearly always conjoined with
 * {@code stream_context = ? AND stream_purpose = ?}, and {@code idx_events_stream_position} is
 * {@code (stream_context, stream_purpose, event_tx, event_position)}. With the leading two columns
 * pinned by equality, a row constructor comparison over the trailing two is something a btree can turn
 * into a <em>start condition</em> — descend once to the cursor and walk the leaves in order — so a page
 * costs what the page returns. A disjunction is not a start condition, so the same predicate becomes a
 * filter over the whole stream, or a {@code BitmapOr} whose unordered result needs a sort above it.
 * <p>
 * <b>What each half of this test decides, and what it deliberately leaves alone.</b> Equivalence is the
 * half that must hold forever: SQL defines a row comparison as exactly the lexicographic expansion the
 * other form writes by hand, so any disagreement between them is a bug in this backend rather than a
 * property of PostgreSQL — and the inversion case, where {@code event_tx} and {@code event_position}
 * order events differently, is where a wrong expansion would actually show. The plan half decides the
 * one thing that cannot be reasoned out: whether PostgreSQL <em>can</em> use a row comparison over
 * index columns three and four, on {@code xid8}, on this major version. It asks that with
 * {@code enable_seqscan} off, because the question is what the predicate can drive and not what the
 * planner picks on a small table — what it picks on a real one, and what that is worth, belongs to
 * {@code sliceworkz-eventstore-benchmark}'s {@code cursor-boundary-form} profile.
 */
public class PostgresCursorBoundaryFormTest {

	/** Enough events to page through several times, in enough appends to give distinct transactions. */
	private static final int SEED_APPENDS = 10;

	/** Events per seeding append. Each append is one transaction, so this is also events per {@code tx}. */
	private static final int SEED_BATCH = 50;

	/** A page size that is smaller than the seeded stream, so paging actually pages. */
	private static final int PAGE = 20;

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testTheTwoFormsReadTheSameEvents ( ) throws Exception {
			String prefix = "cursorequiv_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				EventStreamId stream = EventStreamId.forContext("inventory").withPurpose("sku-1");
				Tags tags = Tags.of("sku", "SKU-1");
				seed(storage, stream, tags);
				invert(storage, dataSource, prefix, stream, tags);

				List<StoredEvent> all = read(storage, stream, null, Limit.none(), QueryDirection.FORWARD, null);
				assertEquals(SEED_APPENDS * SEED_BATCH + 1, all.size(), "the seeded stream is not the size it should be");

				// Cursors worth trying: the start of the stream, its midpoint, and the reference
				// immediately before the inverted event -- the last of those is the one a wrong
				// expansion gets wrong, since the inverted event holds a LOWER position than it.
				List<EventReference> cursors = List.of(
						all.get(0).reference(),
						all.get(all.size() / 2).reference(),
						all.get(all.size() - 2).reference());

				for ( EventReference cursor : cursors ) {
					// Forward and backward paging, and the until boundary, which is the same predicate
					// with <= and is direction-independent by contract.
					assertSameBothWays(storage, stream, cursor, Limit.to(PAGE), QueryDirection.FORWARD, null);
					assertSameBothWays(storage, stream, cursor, Limit.to(PAGE), QueryDirection.BACKWARD, null);
					assertSameBothWays(storage, stream, null, Limit.none(), QueryDirection.FORWARD, cursor);
					assertSameBothWays(storage, stream, null, Limit.none(), QueryDirection.BACKWARD, cursor);
				}

				// A whole walk, not just its first page: a cursor form that is wrong only at a
				// transaction boundary would still agree on page one.
				assertEquals(walk(storage, stream, CursorBoundaryForm.EXPANDED_OR),
						walk(storage, stream, CursorBoundaryForm.ROW_COMPARISON),
						"paging the whole stream must visit the same events in the same order either way");
			} finally {
				storage.close();
			}
		}

		@Test
		public void testTheRowComparisonStillEnforcesTheBoundary ( ) throws Exception {
			String prefix = "cursorboundary_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				storage.setCursorBoundaryForm(CursorBoundaryForm.ROW_COMPARISON);

				EventStreamId stream = EventStreamId.forContext("account").withPurpose("42");
				Tags tags = Tags.of("account", "42");
				EventQuery boundary = EventQuery.forEvents(EventTypesFilter.any(), tags);

				storage.append(AppendCriteria.none(), Optional.of(stream),
						List.of(event(stream, "MoneyDeposited", tags)));
				EventReference reference = read(storage, stream, null, Limit.none(), QueryDirection.FORWARD, null)
						.get(0).reference();

				// An event that every reader sorts AFTER the reference while holding a LOWER position:
				// the case a position-only comparison misses, and the case a row comparison has to get
				// right for the same reason the expansion did.
				invert(storage, dataSource, prefix, stream, tags);
				List<StoredEvent> replay = read(storage, stream, null, Limit.none(), QueryDirection.FORWARD, null);
				assertEquals(2, replay.size(), "expected both events to be readable");
				EventReference inverted = replay.get(1).reference();
				assertTrue(inverted.position() < reference.position(),
						"the inversion did not happen, so this proves nothing");

				assertThrows(OptimisticLockingException.class,
						() -> storage.append(AppendCriteria.of(boundary, reference), Optional.of(stream),
								List.of(event(stream, "MoneyWithdrawn", tags))),
						"a row comparison must still see an event that sorts after a stale reference");

				assertEquals(1, storage.append(AppendCriteria.of(boundary, inverted), Optional.of(stream),
						List.of(event(stream, "MoneyWithdrawn", tags))).size(),
						"appending against the current reference must still succeed");
			} finally {
				storage.close();
			}
		}

		@Test
		public void testOnlyTheRowComparisonDrivesTheIndexAsARange ( ) throws Exception {
			String prefix = "cursorplan_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				EventStreamId stream = EventStreamId.forContext("inventory").withPurpose("sku-1");
				Tags tags = Tags.of("sku", "SKU-1");
				seed(storage, stream, tags);
				analyze(dataSource, prefix);

				List<StoredEvent> all = read(storage, stream, null, Limit.none(), QueryDirection.FORWARD, null);
				EventReference cursor = all.get(all.size() / 2).reference();

				String expanded = explainPage(storage, dataSource, prefix, stream, cursor,
						CursorBoundaryForm.EXPANDED_OR);
				String row = explainPage(storage, dataSource, prefix, stream, cursor,
						CursorBoundaryForm.ROW_COMPARISON);
				String both = "%n-- EXPANDED_OR --%n%s%n-- ROW_COMPARISON --%n%s".formatted(expanded, row);

				assertTrue(row.contains("Index Scan"), () ->
						"the row comparison must reach the events through an index scan" + both);
				assertFalse(row.contains("Bitmap"), () ->
						"a bitmap means the boundary was not a start condition: its result has no order, so"
								+ " the LIMIT cannot be pushed into the scan" + both);
				assertFalse(row.contains("Sort"), () ->
						"a sort above the scan means the index did not supply the order the query asks for" + both);
				assertTrue(indexCondition(row).contains("event_position"), () ->
						"event_position has to appear in the Index Cond, not only in a Filter -- that is what"
								+ " makes the scan start at the cursor rather than at the head of the stream" + both);

				// If the two spellings plan identically there is nothing here to choose between, and the
				// setting has no reason to exist. Worth failing over rather than measuring for 25 minutes.
				assertNotEquals(expanded, row,
						"the two spellings produced the same plan, so the row comparison buys nothing:" + both);
			} finally {
				storage.close();
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

		/** {@link #SEED_APPENDS} appends of {@link #SEED_BATCH} events, so the stream spans transactions. */
		private void seed ( PostgresEventStorageImpl storage, EventStreamId stream, Tags tags ) {
			for ( int batch = 0; batch < SEED_APPENDS; batch++ ) {
				List<EventToStore> events = new ArrayList<>();
				for ( int i = 0; i < SEED_BATCH; i++ ) {
					events.add(event(stream, "StockReserved", tags));
				}
				storage.append(AppendCriteria.none(), Optional.of(stream), events);
			}
		}

		/**
		 * Adds one event holding a position lower than every event appended after this call, in a
		 * transaction that takes a higher {@code tx} — so {@code event_tx} and {@code event_position}
		 * order the stream differently, which is the case the boundary exists to get right.
		 */
		private void invert ( PostgresEventStorageImpl storage, DataSource dataSource, String prefix,
				EventStreamId stream, Tags tags ) throws SQLException {
			long reserved = reserveNextPosition(dataSource, prefix);
			storage.append(AppendCriteria.none(), Optional.of(stream),
					List.of(event(stream, "StockReserved", tags)));
			insertAtPosition(dataSource, prefix, reserved, stream, "StockPicked", tags);
		}

		/** Reads through the store, with the given cursor, limit, direction and {@code until}. */
		private List<StoredEvent> read ( PostgresEventStorageImpl storage, EventStreamId stream,
				EventReference cursor, Limit limit, QueryDirection direction, EventReference until ) {
			EventQuery query = until == null
					? EventQuery.matchAll()
					: EventQuery.matchAll().until(until);
			return storage.query(query, Optional.of(stream), cursor, limit, direction).toList();
		}

		/** Asserts one read returns the same events, in the same order, under both spellings. */
		private void assertSameBothWays ( PostgresEventStorageImpl storage, EventStreamId stream,
				EventReference cursor, Limit limit, QueryDirection direction, EventReference until ) {
			storage.setCursorBoundaryForm(CursorBoundaryForm.EXPANDED_OR);
			List<String> expanded = ids(read(storage, stream, cursor, limit, direction, until));
			storage.setCursorBoundaryForm(CursorBoundaryForm.ROW_COMPARISON);
			List<String> row = ids(read(storage, stream, cursor, limit, direction, until));

			assertFalse(expanded.isEmpty(),
					"the read matched nothing, so agreeing on it proves nothing (cursor=%s until=%s direction=%s)"
							.formatted(cursor, until, direction));
			assertEquals(expanded, row,
					"the two boundary spellings disagreed (cursor=%s until=%s direction=%s)"
							.formatted(cursor, until, direction));
		}

		/** Pages the whole stream with the given form, carrying a cursor, and answers what it visited. */
		private List<String> walk ( PostgresEventStorageImpl storage, EventStreamId stream, CursorBoundaryForm form ) {
			storage.setCursorBoundaryForm(form);
			List<String> visited = new ArrayList<>();
			EventReference cursor = null;
			while ( true ) {
				List<StoredEvent> page = read(storage, stream, cursor, Limit.to(PAGE), QueryDirection.FORWARD, null);
				if ( page.isEmpty() ) {
					return visited;
				}
				visited.addAll(ids(page));
				cursor = page.get(page.size() - 1).reference();
			}
		}

		private List<String> ids ( List<StoredEvent> events ) {
			return events.stream().map(stored -> stored.reference().id().toString()).toList();
		}

		/**
		 * Explains a cursor-carried page, built around the store's own boundary predicate so the plan is
		 * about the SQL the store issues rather than about a copy of it. The stream predicates follow the
		 * boundary, and the ordering and limit are the read path's, so the shape is the read path's too.
		 * <p>
		 * {@code enable_seqscan} is off for the duration: on a table this size a sequential scan may
		 * genuinely be cheapest, and that would answer a question nobody asked. What is being decided here
		 * is whether the predicate <em>can</em> be a start condition.
		 */
		private String explainPage ( PostgresEventStorageImpl storage, DataSource dataSource, String prefix,
				EventStreamId stream, EventReference cursor, CursorBoundaryForm form ) throws SQLException {
			storage.setCursorBoundaryForm(form);

			StringBuilder sql = new StringBuilder(
					"EXPLAIN (COSTS OFF) SELECT event_position, event_tx::text, event_id FROM %sevents"
							.formatted(prefix)
							+ " WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())");
			List<Object> parameters = new ArrayList<>();
			storage.addCursorBoundary(sql, parameters, cursor, QueryDirection.FORWARD);
			sql.append(" AND stream_context = ? AND stream_purpose = ?");
			parameters.add(stream.context());
			parameters.add(stream.purpose());
			sql.append(" ORDER BY event_tx, event_position LIMIT ").append(PAGE);

			try ( Connection connection = dataSource.getConnection() ) {
				connection.setAutoCommit(false);
				try ( Statement seqscan = connection.createStatement() ) {
					seqscan.execute("SET LOCAL enable_seqscan = off");
				}
				try ( PreparedStatement explain = connection.prepareStatement(sql.toString()) ) {
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

		private void analyze ( DataSource dataSource, String prefix ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
					Statement statement = connection.createStatement() ) {
				statement.execute("ANALYZE " + prefix + "events");
			}
		}

		private EventToStore event ( EventStreamId stream, String type, Tags tags ) {
			return new EventToStore(stream, new EventType(type), "{}", null, tags, null);
		}

		/**
		 * Consumes one value from the events table's position sequence without inserting anything, so a
		 * row can be given that position later, from a transaction that by then holds a higher tx.
		 */
		private long reserveNextPosition ( DataSource dataSource, String prefix ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
					PreparedStatement stmt = connection.prepareStatement(
							"SELECT nextval(pg_get_serial_sequence(?, 'event_position'))") ) {
				stmt.setString(1, prefix + "events");
				try ( ResultSet rs = stmt.executeQuery() ) {
					assertTrue(rs.next(), "expected the position sequence to yield a value");
					return rs.getLong(1);
				}
			}
		}

		/** Inserts an event at an explicitly chosen position, letting {@code event_tx} take its default. */
		private void insertAtPosition ( DataSource dataSource, String prefix, long position,
				EventStreamId stream, String type, Tags tags ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
					PreparedStatement insert = connection.prepareStatement(
							"INSERT INTO " + prefix + "events "
									+ "(event_position, event_id, stream_context, stream_purpose, event_type, event_data, event_tags) "
									+ "VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, ?)") ) {
				insert.setLong(1, position);
				insert.setString(2, UUID.randomUUID().toString());
				insert.setString(3, stream.context());
				insert.setString(4, stream.purpose());
				insert.setString(5, type);
				insert.setString(6, "{}");
				insert.setArray(7, connection.createArrayOf("text", tags.toStrings().toArray(new String[0])));
				insert.executeUpdate();
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
