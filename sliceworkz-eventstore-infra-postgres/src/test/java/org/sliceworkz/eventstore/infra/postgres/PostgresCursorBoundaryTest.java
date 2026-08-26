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
 * Guards the {@code (event_tx, event_position)} boundary: that it means what it should, and that it is
 * still <em>spelled</em> in the one way PostgreSQL can drive an index with.
 * <p>
 * <b>Why the spelling needs a test at all.</b> The store writes the boundary as a SQL row constructor
 * comparison, {@code (event_tx, event_position) > (?, ?)}. The lexicographic expansion —
 * {@code event_tx > ? OR (event_tx = ? AND event_position > ?)} — is exactly equivalent, reads more
 * explicitly, and is what anyone rewriting this would naturally reach for. It is also several times
 * slower, and nothing about the results would say so: measured on a 100.000-event corpus with the
 * cursor at the midpoint, the expansion still used the index for the stream columns and then dropped
 * 27.500 rows in a {@code Filter} to reach page one (3.34ms, 1295 buffers) where the row comparison
 * starts the scan at the cursor (0.165ms, 27 buffers). The cost of the expansion grows with how deep
 * the cursor sits, which is the wrong shape for the way {@code Projector} pages, and it grows
 * silently.
 * <p>
 * So the plan test below is not about a setting — there is no setting — but about a property of the
 * read path that no correctness test can notice and no reviewer can see in a diff. It asks with
 * {@code enable_seqscan} off, because what it decides is whether the predicate <em>can</em> be a start
 * condition; what the planner picks on a real store, and what that is worth, belongs to
 * {@code sliceworkz-eventstore-benchmark}.
 * <p>
 * The rest is ordinary semantics, and the case worth having is the inversion: {@code event_position}
 * comes from a sequence and {@code event_tx} is assigned independently, so an event can hold a lower
 * position and sort <em>after</em> one with a higher position. A boundary that compares positions
 * alone reads and locks against a history the store does not agree with.
 */
public class PostgresCursorBoundaryTest {

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
		public void testPagingVisitsEveryEventOnceInTheReadOrder ( ) throws Exception {
			String prefix = "cursorpaging_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				EventStreamId stream = EventStreamId.forContext("inventory").withPurpose("sku-1");
				Tags tags = Tags.of("sku", "SKU-1");
				seed(storage, stream, tags);
				// invert() adds two: one appended through the store and one inserted behind it, holding a
				// lower position and a higher tx. Paging has to cross that without losing or repeating it.
				invert(storage, dataSource, prefix, stream, tags);

				List<StoredEvent> all = read(storage, stream, null, Limit.none(), QueryDirection.FORWARD, null);
				assertEquals(SEED_APPENDS * SEED_BATCH + 2, all.size(), "the seeded stream is not the size it should be");
				assertTrue(all.getLast().reference().position() < all.get(all.size() - 2).reference().position(),
						"the inversion did not happen, so paging across it proves nothing");

				assertEquals(ids(all), walk(storage, stream),
						"paging with a cursor must visit exactly the events an unbounded read returns, in order");

				// Cursors worth trying: near the start, the midpoint, and the reference immediately before
				// the inverted event -- the last is where a boundary comparing positions alone goes wrong.
				// All interior: reading backwards from the first event matches nothing, and two reads
				// agreeing on nothing agree on nothing.
				for ( int index : new int[] { 1, all.size() / 2, all.size() - 2 } ) {
					EventReference cursor = all.get(index).reference();
					assertEquals(ids(all.subList(index + 1, all.size())),
							ids(read(storage, stream, cursor, Limit.none(), QueryDirection.FORWARD, null)),
							"forward from index %d must be everything after it".formatted(index));
					// until is the same predicate with <=, and is direction-independent by contract.
					assertEquals(ids(all.subList(0, index + 1)),
							ids(read(storage, stream, null, Limit.none(), QueryDirection.FORWARD, cursor)),
							"until index %d must be everything up to and including it".formatted(index));
					assertEquals(ids(all.subList(0, index)).reversed(),
							ids(read(storage, stream, cursor, Limit.none(), QueryDirection.BACKWARD, null)),
							"backward from index %d must be everything before it, newest first".formatted(index));
				}
			} finally {
				storage.close();
			}
		}

		@Test
		public void testTheBoundaryStillSeesAnEventThatSortsAfterALowerPosition ( ) throws Exception {
			String prefix = "cursorboundary_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				EventStreamId stream = EventStreamId.forContext("account").withPurpose("42");
				Tags tags = Tags.of("account", "42");
				EventQuery boundary = EventQuery.forEvents(EventTypesFilter.any(), tags);

				invert(storage, dataSource, prefix, stream, tags);
				List<StoredEvent> replay = read(storage, stream, null, Limit.none(), QueryDirection.FORWARD, null);
				assertEquals(2, replay.size(), "expected both events to be readable");

				// The reference a decider would hold before the inverted event became visible, and the
				// inverted event itself -- last in read order despite its lower position, which is the
				// whole point of it.
				EventReference reference = replay.get(0).reference();
				EventReference inverted = replay.get(1).reference();
				assertTrue(inverted.position() < reference.position(),
						"the inversion did not happen, so this proves nothing");
				assertTrue(inverted.happenedAfter(reference),
						"the inverted event must count as happening after the reference");

				assertThrows(OptimisticLockingException.class,
						() -> storage.append(AppendCriteria.of(boundary, reference), Optional.of(stream),
								List.of(event(stream, "MoneyWithdrawn", tags))),
						"the DCB check must see an event that sorts after a stale reference");

				assertEquals(1, storage.append(AppendCriteria.of(boundary, inverted), Optional.of(stream),
						List.of(event(stream, "MoneyWithdrawn", tags))).size(),
						"appending against the current reference must still succeed");
			} finally {
				storage.close();
			}
		}

		@Test
		public void testTheBoundaryDrivesTheIndexAsARange ( ) throws Exception {
			String prefix = "cursorplan_";
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorageImpl storage = open(prefix, dataSource);
			try {
				EventStreamId stream = EventStreamId.forContext("inventory").withPurpose("sku-1");
				Tags tags = Tags.of("sku", "SKU-1");
				seed(storage, stream, tags);
				analyze(dataSource, prefix);

				List<StoredEvent> all = read(storage, stream, null, Limit.none(), QueryDirection.FORWARD, null);
				String plan = explainPage(storage, dataSource, prefix, stream, all.get(all.size() / 2).reference());

				// Asserted in the order the mechanism has to hold, so a failure says which step broke.
				// First: the boundary is an index condition at all, rather than a filter applied to rows
				// the scan already had to fetch.
				assertTrue(indexCondition(plan).contains("event_position"), () ->
						"event_position has to appear in the Index Cond, not only in a Filter -- that is what"
								+ " makes the scan start at the cursor rather than at the head of the stream\n" + plan);

				// Second: one index scan, not two unioned. The expansion needs an arm per case; a row
				// comparison is a single condition, which is what keeps the BitmapOr away.
				assertFalse(plan.contains("BitmapOr"), () ->
						"the boundary needed a union of two index scans, which is what the row comparison"
								+ " exists to avoid\n" + plan);

				// Third: the scan supplies the order, so the LIMIT stops it early instead of a Sort having
				// to consume every matching row first. This is where the paging cost actually is.
				assertFalse(plan.contains("Sort"), () ->
						"a sort above the scan means the index did not supply the order the query asks for,"
								+ " so the LIMIT cannot be pushed into the scan and a page costs the whole"
								+ " remainder of the stream\n" + plan);
				assertTrue(plan.contains("Index Scan") && !plan.contains("Bitmap"), () ->
						"an ordered range scan is a plain Index Scan; a Bitmap Index Scan discards the order"
								+ " the index could have supplied\n" + plan);
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
		 * Adds one event holding a position lower than the event appended just before it, in a transaction
		 * that takes a higher {@code tx} — so {@code event_tx} and {@code event_position} order the stream
		 * differently, which is the case the boundary exists to get right.
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
			EventQuery query = until == null ? EventQuery.matchAll() : EventQuery.matchAll().until(until);
			return storage.query(query, Optional.of(stream), cursor, limit, direction).toList();
		}

		/** Pages the whole stream, carrying a cursor, and answers what it visited. */
		private List<String> walk ( PostgresEventStorageImpl storage, EventStreamId stream ) {
			List<String> visited = new ArrayList<>();
			EventReference cursor = null;
			while ( true ) {
				List<StoredEvent> page = read(storage, stream, cursor, Limit.to(PAGE), QueryDirection.FORWARD, null);
				if ( page.isEmpty() ) {
					return visited;
				}
				visited.addAll(ids(page));
				cursor = page.getLast().reference();
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
				EventStreamId stream, EventReference cursor ) throws SQLException {
			StringBuilder sql = new StringBuilder(
					"EXPLAIN (COSTS OFF) SELECT event_position, event_tx::text, event_id FROM %sevents"
							.formatted(prefix)
							+ " WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())");
			List<Object> parameters = new ArrayList<>();
			storage.addCursorBoundary(sql, parameters, cursor, QueryDirection.FORWARD);
			sql.append(" AND stream_context = ? AND stream_purpose = ?");
			parameters.add(stream.context());
			parameters.add(stream.purpose());
			// The read path's ORDER BY, verbatim, and the cast in it is load-bearing rather than
			// redundant: the select list projects event_tx::text, which PostgreSQL names event_tx, and
			// SQL resolves a bare name in ORDER BY to an *output* column before it looks at the table.
			// Written as "ORDER BY event_tx" this sorts the page by the text rendering of a transaction
			// id -- '9' after '10' -- on an expression no index can supply, which puts a Sort above every
			// plan. Written as an expression the name cannot be captured.
			sql.append(" ORDER BY event_tx::xid8, event_position LIMIT ").append(PAGE);

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
