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
import java.sql.PreparedStatement;
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
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Pins down the operational cost of the read barrier: what an open transaction elsewhere in the
 * database does to this store's visibility, and — the part that decides how much it matters — which
 * kinds of transaction do it.
 * <p>
 * Every read is filtered by {@code event_tx < pg_snapshot_xmin(pg_current_snapshot())}. That is
 * correct and load-bearing; {@code ConcurrentAppendVisibilityTest} in the TCK is the reason it exists,
 * and nothing here questions it. What it buys is that a reader tailing the log can never be overtaken
 * and skip an event. What it costs is that {@code pg_snapshot_xmin} is a property of the whole
 * database cluster, not of this store, so a transaction the event store knows nothing about can hold
 * the barrier down and freeze what every reader here can see.
 * <p>
 * <b>The boundary, which is the whole point of this class.</b> {@code pg_snapshot_xmin} is the oldest
 * <em>assigned transaction id</em> still running, and PostgreSQL assigns an xid lazily — at a
 * transaction's first write. So the hazard is not "any open transaction", it is "any open transaction
 * that has written something". A read-only transaction pins nothing, at any isolation level, however
 * long it runs: {@link Tests#testReadOnlyTransactionsDoNotStallReads} holds one open at READ
 * COMMITTED, REPEATABLE READ and SERIALIZABLE, and reads keep advancing throughout. That rules out
 * the alarming half of the population — {@code pg_dump}, reporting queries, an analytics replica
 * feed, an {@code idle in transaction} connection that only ever read.
 * <p>
 * A transaction that writes is a different matter, and it does not have to write anything this store
 * cares about: {@link Tests#testWritingTransactionOnAnUnrelatedTableStallsEveryRead} holds one open
 * against a table the event store has never heard of, and every event appended after it took its xid
 * becomes invisible to every query, until it commits. Nothing fails and nothing is logged — reads
 * simply stop advancing, and then catch up all at once.
 * <p>
 * {@link Tests#testAppenderCannotReadItsOwnWriteWhileAnOlderWriterIsOpen} records the two
 * consequences a caller actually meets. Read-your-own-writes does not hold. And because the
 * optimistic-locking check is a plain {@code NOT EXISTS} that deliberately carries no {@code xmin}
 * barrier, it sees the events the reader cannot: a decider re-reading its boundary gets the same
 * stale answer, appends against it, and conflicts — every time, for as long as the stall lasts. The
 * usual retry loop cannot make progress, because the fact it would have to observe to move on is the
 * one being withheld from it.
 *
 * @see PostgresLockCheckOrderingTest
 */
public class PostgresVisibilityStallTest {

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		/**
		 * The good news, and the reason this item is narrower than it looks: a read-only transaction
		 * never pins the barrier, whatever its isolation level and however long it stays open.
		 */
		@Test
		public void testReadOnlyTransactionsDoNotStallReads ( ) throws Exception {
			for ( String isolation : List.of("READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE") ) {
				withStorage("visroh_", (storage, dataSource, prefix) -> {

					EventStreamId stream = EventStreamId.forContext("account").withPurpose("1");
					storage.append(AppendCriteria.none(), Optional.of(stream), List.of(event(stream, "Before")));

					try ( Connection held = dataSource.getConnection() ) {
						held.setAutoCommit(false);
						try ( Statement stmt = held.createStatement() ) {
							stmt.execute("SET TRANSACTION ISOLATION LEVEL " + isolation);
							// read, and read this store's own table, so nothing about the access pattern
							// can be blamed: it is the absence of a write that keeps the barrier moving
							stmt.execute("SELECT count(*) FROM " + prefix + "events");
						}

						storage.append(AppendCriteria.none(), Optional.of(stream), List.of(event(stream, "During")));

						assertEquals(2, visibleCount(storage, stream),
							"a read-only transaction at " + isolation + " must not stall reads: it is assigned no "
							+ "transaction id, so it cannot hold pg_snapshot_xmin down");

						held.rollback();
					}
				});
			}
		}

		/**
		 * The hazard itself. An unrelated writer — not touching the events table, not part of this
		 * application — makes every event appended after it invisible to every reader of this store.
		 */
		@Test
		public void testWritingTransactionOnAnUnrelatedTableStallsEveryRead ( ) throws Exception {
			withStorage("visstall_", (storage, dataSource, prefix) -> {

				EventStreamId stream = EventStreamId.forContext("account").withPurpose("1");
				execute(dataSource, "CREATE TABLE IF NOT EXISTS " + prefix + "unrelated_workload (id int)");

				storage.append(AppendCriteria.none(), Optional.of(stream), List.of(event(stream, "Before")));
				assertEquals(1, visibleCount(storage, stream), "the first event must be visible before anything is held open");

				try ( Connection held = dataSource.getConnection() ) {
					held.setAutoCommit(false);
					try ( Statement stmt = held.createStatement() ) {
						// a write, to a table this store has never heard of. That is all it takes: the
						// write assigns this transaction an xid, and pg_snapshot_xmin is cluster-wide
						stmt.execute("INSERT INTO " + prefix + "unrelated_workload VALUES (1)");
					}

					storage.append(AppendCriteria.none(), Optional.of(stream), List.of(event(stream, "During")));

					assertEquals(2, rawCount(dataSource, prefix),
						"the event is committed and present in the table — this is not a write that failed");
					assertEquals(1, visibleCount(storage, stream),
						"an event appended while an unrelated writing transaction is open must be invisible to reads, "
						+ "with nothing failing and nothing logged");

					held.commit();
				}

				assertEquals(2, visibleCount(storage, stream),
					"once the blocking transaction ends, the withheld events appear — all at once");
			});
		}

		/**
		 * What a caller observes during a stall: it cannot read back what it just appended, and the
		 * optimistic-locking check — which has no barrier — conflicts on facts the caller is not
		 * allowed to see, so retrying cannot help.
		 */
		@Test
		public void testAppenderCannotReadItsOwnWriteWhileAnOlderWriterIsOpen ( ) throws Exception {
			withStorage("visryow_", (storage, dataSource, prefix) -> {

				EventStreamId stream = EventStreamId.forContext("account").withPurpose("42");
				Tags boundaryTags = Tags.of("account", "42");
				EventQuery boundary = EventQuery.forEvents(EventTypesFilter.any(), boundaryTags);
				execute(dataSource, "CREATE TABLE IF NOT EXISTS " + prefix + "unrelated_workload (id int)");

				storage.append(AppendCriteria.none(), Optional.of(stream), List.of(event(stream, "MoneyDeposited", boundaryTags)));
				EventReference reference = lastReference(storage, boundary, stream);

				try ( Connection held = dataSource.getConnection() ) {
					held.setAutoCommit(false);
					try ( Statement stmt = held.createStatement() ) {
						stmt.execute("INSERT INTO " + prefix + "unrelated_workload VALUES (1)");
					}

					// somebody else appends a fact inside the consistency boundary and commits it
					storage.append(AppendCriteria.none(), Optional.of(stream), List.of(event(stream, "MoneyWithdrawn", boundaryTags)));

					// read-your-own-writes does not hold: this is the appender's own connection pool, the
					// append returned successfully, and the event is still not readable
					List<StoredEvent> seen = query(storage, boundary, stream);
					assertEquals(1, seen.size(), "an appended event must be expected to be unreadable while an older writer is open");
					assertEquals(reference, seen.get(0).reference(),
						"re-reading the boundary yields the same reference as before — a decider learns nothing new");

					// ...but the lock check has no xmin barrier, so it sees exactly the fact the reader
					// was denied. The append conflicts against the only reference a reader can hold.
					assertThrows(OptimisticLockingException.class,
						() -> storage.append(AppendCriteria.of(boundary, reference), Optional.of(stream),
							List.of(event(stream, "MoneyWithdrawn", boundaryTags))),
						"the lock check must conflict on the committed event the reader cannot see");

					// which is why retrying cannot clear it: the loop re-reads, gets the same stale
					// reference, and conflicts again — for as long as the blocking transaction lives
					assertEquals(reference, lastReference(storage, boundary, stream),
						"a retry re-reads the same reference, so the same append conflicts again: no progress is possible");

					held.commit();
				}

				// with the blocker gone, the withheld fact surfaces and the decider can move on
				List<StoredEvent> afterCommit = query(storage, boundary, stream);
				assertEquals(2, afterCommit.size(), "both events must be readable once the blocking transaction ends");
				assertTrue(afterCommit.get(1).reference().happenedAfter(reference), "the withheld event sorts after the stale reference");
				assertEquals(1,
					storage.append(AppendCriteria.of(boundary, afterCommit.get(1).reference()), Optional.of(stream),
						List.of(event(stream, "MoneyWithdrawn", boundaryTags))).size(),
					"appending against the now-current reference must succeed");
			});
		}

		// --- helpers -------------------------------------------------------------------------------

		private interface Scenario {
			void run ( EventStorage storage, DataSource dataSource, String prefix ) throws Exception;
		}

		private void withStorage ( String prefix, Scenario scenario ) throws Exception {
			DataSource dataSource = PostgresContainer.dataSource(image);
			EventStorage storage = PostgresEventStorage.newBuilder()
				.name("unit-test")
				.prefix(prefix)
				.dataSource(dataSource)
				.initializeDatabase()
				.build();
			try {
				scenario.run(storage, dataSource, prefix);
			} finally {
				storage.close();
			}
		}

		private EventToStore event ( EventStreamId stream, String type ) {
			return event(stream, type, Tags.none());
		}

		private EventToStore event ( EventStreamId stream, String type, Tags tags ) {
			return new EventToStore(stream, new EventType(type), "{}", null, tags, null);
		}

		private List<StoredEvent> query ( EventStorage storage, EventQuery query, EventStreamId stream ) {
			return storage.query(query, Optional.of(stream), null, Limit.none(), QueryDirection.FORWARD).toList();
		}

		private int visibleCount ( EventStorage storage, EventStreamId stream ) {
			return query(storage, EventQuery.matchAll(), stream).size();
		}

		private EventReference lastReference ( EventStorage storage, EventQuery boundary, EventStreamId stream ) {
			List<StoredEvent> events = query(storage, boundary, stream);
			assertTrue(!events.isEmpty(), "expected the boundary to hold at least one event");
			return events.getLast().reference();
		}

		/** Counts what is actually committed in the table, bypassing the barrier the store reads through. */
		private int rawCount ( DataSource dataSource, String prefix ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
				  PreparedStatement stmt = connection.prepareStatement("SELECT count(*) FROM " + prefix + "events");
				  ResultSet rs = stmt.executeQuery() ) {
				assertTrue(rs.next(), "expected a count");
				return rs.getInt(1);
			}
		}

		private void execute ( DataSource dataSource, String sql ) throws SQLException {
			try ( Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement() ) {
				stmt.execute(sql);
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
