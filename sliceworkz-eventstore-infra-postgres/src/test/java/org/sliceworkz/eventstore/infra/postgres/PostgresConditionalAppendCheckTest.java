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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

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
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Pins the criteria-shaped consistency check down to the mechanisms it rests on, and that neither
 * shape changes what a conditional append <em>means</em>.
 * <p>
 * <b>What the shape derivation is.</b> The store states the DCB check from the one property the
 * criteria itself carries: an expected reference present takes the ordered probe
 * ({@code ORDER BY event_tx, event_position LIMIT 1}), whose cached generic plan is the position-index
 * walk from the cursor; an expected reference absent takes the {@code NOT EXISTS} form with server
 * preparation disabled, so PostgreSQL plans it from the bound values every time and the plan cache can
 * never serve it the whole-table sequential scan it was measured serving in steady state.
 * <p>
 * <b>Why the assertions are about {@code pg_prepared_statements} rather than about a plan.</b> The
 * shape choice is a performance property, and a test that measured one would be a benchmark pretending
 * to be a test — measurement lives in {@code sliceworkz-eventstore-benchmark}, whose committed runs
 * carry the figures. What a test can decide, exactly and quickly, is which statement text ran and
 * whether it became server-prepared, which are the two mechanisms the whole design rests on. That view
 * is per <em>session</em>, so the store is given a pool of exactly one connection and the assertion
 * borrows the very connection the appends ran on.
 * <p>
 * The second half matters more than the first: each branch has to leave the consistency boundary
 * intact — conflict on a stale or wrongly-empty reference, succeed on a current one.
 */
public class PostgresConditionalAppendCheckTest {

	/**
	 * Comfortably past pgjdbc's default {@code prepareThreshold} of 5. The driver counts executions per
	 * SQL text on the connection rather than per {@code PreparedStatement} object, which is why closing
	 * and re-preparing the same statement on every append — as the backend does — still reaches the
	 * threshold.
	 */
	private static final int APPENDS = 12;

	/** The fragment only the ordered probe's SQL carries. */
	private static final String PROBE_FRAGMENT = "ORDER BY event_tx, event_position LIMIT 1";

	/** The fragment only the no-cursor form's SQL carries. */
	private static final String NOT_EXISTS_FRAGMENT = "NOT EXISTS";

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		/**
		 * A criteria carrying a cursor must run as the ordered probe, and that statement must reach the
		 * plan cache — its cached generic plan is the cursor walk, so caching it is the point. If the
		 * {@code NOT EXISTS} text shows up here instead, the derivation is not happening.
		 */
		@Test
		public void testCursorBearingCheckIsTheProbeAndReachesThePlanCache ( ) throws Exception {
			try ( Session session = openStore("checkcursor_") ) {
				appendWithCursorsThreadedForward(session.storage);
				assertTrue(countPrepared(session.pool, "checkcursor_", PROBE_FRAGMENT) > 0,
						"a cursor-bearing check must be the ordered probe, server-prepared -- its cached"
								+ " plan is the cursor walk, and caching it is the point");
				assertEquals(0, countPrepared(session.pool, "checkcursor_", NOT_EXISTS_FRAGMENT),
						"no NOT EXISTS statement may run for criteria that carry a cursor");
			}
		}

		/**
		 * A criteria without a cursor must run as {@code NOT EXISTS} and stay off the plan cache, so
		 * PostgreSQL plans every execution from the values bound to it. This is the mechanism that keeps
		 * a uniqueness check on the tag index instead of the cached sequential scan.
		 */
		@Test
		public void testNoCursorCheckStaysOffThePlanCache ( ) throws Exception {
			try ( Session session = openStore("checknocursor_") ) {
				appendAtEmptyBoundaries(session.storage);
				assertEquals(0, countPrepared(session.pool, "checknocursor_", NOT_EXISTS_FRAGMENT),
						"a no-cursor check must stay unnamed, so PostgreSQL plans every append from the"
								+ " values bound to it instead of from the plan cache");
				assertEquals(0, countPrepared(session.pool, "checknocursor_", PROBE_FRAGMENT),
						"no probe statement may run for criteria without a cursor -- the walk would have"
								+ " nowhere to start and would cover the whole stream");
			}
		}

		/**
		 * Threads a boundary reference forward through {@link #APPENDS} conditional appends, the way a
		 * decider does, then checks the boundary still means what it meant: a wrongly-empty reference
		 * against a non-empty boundary conflicts, the current reference succeeds.
		 */
		private void appendWithCursorsThreadedForward ( EventStorage storage ) {
			EventStreamId stream = EventStreamId.forContext("inventory").withPurpose("sku-1");
			Tags tags = Tags.of("sku", "SKU-1");
			EventQuery boundary = EventQuery.forEvents(EventTypesFilter.any(), tags);

			// The first append has no history and so no reference: it deliberately takes the no-cursor
			// branch, exactly as a real entity's first event does. Every one after it carries a cursor.
			EventReference reference = null;
			for ( int i = 0; i < APPENDS + 1; i++ ) {
				List<StoredEvent> written = storage.append(
						AppendCriteria.of(boundary, reference), Optional.of(stream),
						List.of(event(stream, "StockReserved", tags)));
				assertEquals(1, written.size(), "conditional append %d must succeed".formatted(i));
				reference = written.get(0).reference();
			}

			EventReference current = reference;
			assertThrows(OptimisticLockingException.class,
					() -> storage.append(AppendCriteria.of(boundary, null), Optional.of(stream),
							List.of(event(stream, "StockReserved", tags))),
					"an empty expected reference against a non-empty boundary must still conflict");
			List<StoredEvent> last = storage.append(AppendCriteria.of(boundary, current),
					Optional.of(stream), List.of(event(stream, "StockReserved", tags)));
			assertEquals(1, last.size(), "appending against the current reference must succeed");
		}

		/**
		 * Runs {@link #APPENDS} appends each expecting an <em>empty</em> boundary — the uniqueness
		 * pattern — every one against a fresh tag so every one legitimately succeeds, then checks the
		 * boundary still bites: repeating the first append's criteria must conflict, because that
		 * boundary is no longer empty.
		 */
		private void appendAtEmptyBoundaries ( EventStorage storage ) {
			EventStreamId stream = EventStreamId.forContext("inventory").withPurpose("default");
			for ( int i = 0; i < APPENDS; i++ ) {
				Tags tags = Tags.of("basket", "B-%03d".formatted(i));
				EventQuery boundary = EventQuery.forEvents(EventTypesFilter.any(), tags);
				List<StoredEvent> written = storage.append(
						AppendCriteria.of(boundary, null), Optional.of(stream),
						List.of(event(stream, "OrderPlaced", tags)));
				assertEquals(1, written.size(), "empty-boundary append %d must succeed".formatted(i));
			}

			Tags taken = Tags.of("basket", "B-000");
			assertThrows(OptimisticLockingException.class,
					() -> storage.append(
							AppendCriteria.of(EventQuery.forEvents(EventTypesFilter.any(), taken), null),
							Optional.of(stream), List.of(event(stream, "OrderPlaced", taken))),
					"the same empty-boundary criteria must conflict once its boundary holds an event");
		}

		/**
		 * A store on a pool of exactly one connection, so the session the appends ran on is the session
		 * the assertions can ask about. The monitors get a pool of their own: they hold a connection
		 * each for the storage's lifetime and would take this one and never give it back.
		 */
		private Session openStore ( String prefix ) {
			HikariDataSource writePool = PostgresContainer.singleConnectionDataSource(image);
			DataSource monitoring = PostgresContainer.dataSource(image);
			EventStorage storage = PostgresEventStorage.newBuilder()
					.name("unit-test")
					.prefix(prefix)
					.dataSource(writePool)
					.monitoringDataSource(monitoring)
					.initializeDatabase()
					.build();
			return new Session(storage, writePool);
		}

		private record Session ( EventStorage storage, HikariDataSource pool ) implements AutoCloseable {
			@Override
			public void close ( ) {
				storage.close();
				pool.close();
			}
		}

		/**
		 * How many of this session's prepared statements are the store's conditional append in the given
		 * shape. Matched on the table name plus a fragment only that shape's SQL carries: reads have
		 * neither, and an unconditional append has neither.
		 */
		private int countPrepared ( DataSource dataSource, String prefix, String fragment )
				throws SQLException {
			String sql = """
					SELECT count(*) FROM pg_prepared_statements
					WHERE statement LIKE '%%INSERT INTO %sevents%%' AND statement LIKE '%%%s%%'"""
					.formatted(prefix, fragment);
			try ( Connection connection = dataSource.getConnection();
					Statement statement = connection.createStatement();
					ResultSet rows = statement.executeQuery(sql) ) {
				assertTrue(rows.next(), "expected a count");
				return rows.getInt(1);
			}
		}

		private EventToStore event ( EventStreamId stream, String type, Tags tags ) {
			return new EventToStore(stream, new EventType(type), "{}", null, tags, null);
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
