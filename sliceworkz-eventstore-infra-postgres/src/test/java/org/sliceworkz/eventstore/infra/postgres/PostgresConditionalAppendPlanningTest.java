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
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl.ConditionalAppendPlanning;
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
 * Pins down {@link ConditionalAppendPlanning}: that it reaches the server, and that it changes nothing
 * about what a conditional append <em>means</em>.
 * <p>
 * <b>What the setting is for.</b> The DCB check is a re-used prepared statement, so PostgreSQL holds a
 * custom plan built from the actual parameter values and a generic one built against default
 * selectivity, and from the tenth execution it adopts the generic plan if its estimate looks no worse.
 * A DCB check is the shape that misleads that comparison — it expects <em>no rows</em>, while a
 * {@code NOT EXISTS} is priced by how soon a row is expected to turn up — so the server can settle on a
 * plan that scans the whole events table for a row that is not there. {@code PER_APPEND} takes the
 * choice away by keeping the statement off the server's plan cache entirely.
 * <p>
 * <b>Why the assertion is about {@code pg_prepared_statements} and not about a plan.</b> The setting is
 * a performance property, and a test that measured one would be a benchmark pretending to be a test —
 * measurement lives in {@code sliceworkz-eventstore-benchmark}, where the {@code dcb-plan-cache}
 * profile runs both modes over one corpus. What a test can decide, exactly and quickly, is whether the
 * statement became server-prepared at all, which is the mechanism the whole setting rests on. That view
 * is per <em>session</em>, so the store is given a pool of exactly one connection and the assertion
 * borrows the very connection the appends ran on.
 * <p>
 * The second half matters more than the first: a change on the write path has to leave the consistency
 * boundary intact. Each mode therefore also has to conflict on a stale reference and succeed on a
 * current one.
 */
public class PostgresConditionalAppendPlanningTest {

	/**
	 * Comfortably past pgjdbc's default {@code prepareThreshold} of 5. The driver counts executions per
	 * SQL text on the connection rather than per {@code PreparedStatement} object, which is why closing
	 * and re-preparing the same statement on every append — as the backend does — still reaches the
	 * threshold.
	 */
	private static final int APPENDS = 12;

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testServerDefaultLetsTheStatementBecomeServerPrepared ( ) throws Exception {
			assertTrue(serverPreparedCheckStatements("planserverdefault_", ConditionalAppendPlanning.SERVER_DEFAULT) > 0,
					"with the server left to choose, the DCB check must reach its plan cache -- if it no"
							+ " longer does, PER_APPEND is a no-op and this test is what says so");
		}

		@Test
		public void testPerAppendKeepsTheStatementOffThePlanCache ( ) throws Exception {
			assertEquals(0, serverPreparedCheckStatements("planperappend_", ConditionalAppendPlanning.PER_APPEND),
					"PER_APPEND must keep the DCB check unnamed, so PostgreSQL plans every append from"
							+ " the values bound to it");
		}

		/**
		 * Runs {@link #APPENDS} conditional appends through a store in the given mode, checks that the
		 * boundary still behaves, and answers how many of the store's DCB checks the server holds as
		 * prepared statements on that session.
		 */
		private int serverPreparedCheckStatements ( String prefix, ConditionalAppendPlanning planning )
				throws Exception {
			// One connection, so the session the appends ran on is the session this can ask about. The
			// monitors get a pool of their own: they hold a connection each for the storage's lifetime
			// and would take this one and never give it back.
			try ( HikariDataSource writePool = PostgresContainer.singleConnectionDataSource(image) ) {
				DataSource monitoring = PostgresContainer.dataSource(image);
				EventStorage storage = PostgresEventStorage.newBuilder()
						.name("unit-test")
						.prefix(prefix)
						.dataSource(writePool)
						.monitoringDataSource(monitoring)
						.conditionalAppendPlanning(planning)
						.initializeDatabase()
						.build();

				try {
					EventStreamId stream = EventStreamId.forContext("inventory").withPurpose("sku-1");
					Tags tags = Tags.of("sku", "SKU-1");
					EventQuery boundary = EventQuery.forEvents(EventTypesFilter.any(), tags);

					// Thread the reference forward, the way a decider does. A conditional append that
					// re-used one reference would succeed once and conflict for the rest of the loop --
					// measuring the failure path while looking like the success path.
					EventReference reference = null;
					for ( int i = 0; i < APPENDS; i++ ) {
						List<StoredEvent> written = storage.append(
								AppendCriteria.of(boundary, reference), Optional.of(stream),
								List.of(event(stream, "StockReserved", tags)));
						assertEquals(1, written.size(), "conditional append %d must succeed".formatted(i));
						reference = written.get(0).reference();
					}

					// The boundary still means what it meant. A stale reference is a new relevant fact...
					EventReference stale = reference;
					assertThrows(OptimisticLockingException.class,
							() -> storage.append(AppendCriteria.of(boundary, null), Optional.of(stream),
									List.of(event(stream, "StockReserved", tags))),
							"an empty expected reference against a non-empty boundary must still conflict");

					// ...and the current one is not.
					List<StoredEvent> last = storage.append(AppendCriteria.of(boundary, stale),
							Optional.of(stream), List.of(event(stream, "StockReserved", tags)));
					assertEquals(1, last.size(), "appending against the current reference must succeed");

					return countPreparedChecks(writePool, prefix);
				} finally {
					storage.close();
				}
			}
		}

		/**
		 * How many of this session's prepared statements are the store's conditional append.
		 * <p>
		 * Matched on the table name plus {@code NOT EXISTS}, which no other statement the backend issues
		 * carries: reads have no {@code NOT EXISTS}, and an unconditional append has neither.
		 */
		private int countPreparedChecks ( DataSource dataSource, String prefix ) throws SQLException {
			String sql = """
					SELECT count(*) FROM pg_prepared_statements
					WHERE statement LIKE '%%INSERT INTO %sevents%%' AND statement LIKE '%%NOT EXISTS%%'"""
					.formatted(prefix);
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
