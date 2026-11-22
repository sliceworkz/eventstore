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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.IdempotencyKeyConflictException;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The experimental single-round-trip conditional append ({@link AppendPipelining#SINGLE_ROUND_TRIP})
 * against the shipped one ({@link AppendPipelining#OFF}).
 * <p>
 * What the mode must not change is the order the server runs the lock, the {@code INSERT} and the
 * commit in, and the transaction they share: the {@code INSERT} must run holding the lock, under the
 * lock's timeout, with a snapshot taken after the lock was granted, and the lock must be released by
 * the commit. The first scenario pins that property of the driver and the protocol on the statement
 * shape the mode sends; the others pin the answers an append gives, in both modes: conflicts seen
 * behind the lock, concurrent deciders, the lock timeout and its scope, idempotency, random filters
 * against the boundary contract, and the timing hook.
 */
public class PostgresAppendPipeliningTest {

	private static final EventStreamId STREAM = EventStreamId.forContext("bank").withPurpose("accounts");

	@Test
	void theSwitchIsOffUnlessSelectedAndRefusesAnUnknownValue ( ) {
		assertEquals(AppendPipelining.OFF, AppendPipelining.parse(null));
		assertEquals(AppendPipelining.OFF, AppendPipelining.parse(" "));
		assertEquals(AppendPipelining.OFF, AppendPipelining.parse("off"));
		assertEquals(AppendPipelining.SINGLE_ROUND_TRIP, AppendPipelining.parse("single-round-trip"));
		assertEquals(AppendPipelining.SINGLE_ROUND_TRIP, AppendPipelining.parse(" Single_Round_Trip "));
		assertThrows(IllegalArgumentException.class, () -> AppendPipelining.parse("pipelined"));
	}

	abstract static class Tests {

		final String image;

		final List<AutoCloseable> closing = new ArrayList<>();

		final ConcurrentLinkedQueue<AppendPipelining.Timing> timings = new ConcurrentLinkedQueue<>();

		Tests ( String image ) {
			this.image = image;
		}

		@AfterEach
		void close ( ) throws Exception {
			for ( int i = closing.size() - 1; i >= 0; i-- ) {
				closing.get(i).close();
			}
			closing.clear();
		}

		HikariDataSource pool ( int size ) {
			HikariConfig config = new HikariConfig();
			config.setJdbcUrl(PostgresContainer.jdbcUrl(image, "integration-tests-db"));
			config.setUsername("sa");
			config.setPassword("pwd");
			config.setMaximumPoolSize(size);
			HikariDataSource pool = new HikariDataSource(config);
			closing.add(pool);
			return pool;
		}

		/** a fresh, empty store on the given pool, in the given mode, timing every conditional append */
		PostgresEventStorageImpl storage ( String prefix, DataSource pool, AppendPipelining mode ) {
			PostgresEventStorageImpl storage = (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
					.name("pipelining-" + mode)
					.prefix(prefix)
					.dataSource(pool)
					.monitoringDataSource(PostgresContainer.dataSource(image))
					.recreateDatabase()
					.build();
			closing.add(storage);
			timings.clear();
			return storage.appendPipelining(mode).appendTimingRecorder(timings::add);
		}

		/**
		 * The property the mode rests on, asked of the server directly with the statement shape the mode
		 * sends: in one pipelined batch with {@code autoCommit=false}, the statement after the lock runs
		 * in the lock's transaction, holding the lock, under the lock's timeout — and the {@code COMMIT}
		 * at the end of the batch releases it, leaving no setting behind on the session.
		 */
		@Test
		void inOnePipelinedBatchTheStatementAfterTheLockRunsHoldingItAndTheCommitReleasesIt ( ) throws SQLException {
			HikariDataSource pool = pool(1);
			try ( Connection connection = pool.getConnection() ) {
				connection.setAutoCommit(false);
				String batch = "SET LOCAL lock_timeout = '1234ms'; SELECT pg_advisory_xact_lock(?); "
						+ "SELECT (SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid() AND granted) AS held, "
						+ "current_setting('lock_timeout') AS timeout; COMMIT";
				try ( PreparedStatement stmt = connection.prepareStatement(batch) ) {
					stmt.setLong(1, 424242L);
					stmt.execute();
					stmt.getMoreResults();
					stmt.getMoreResults();
					try ( ResultSet rs = stmt.getResultSet() ) {
						assertTrue(rs.next());
						assertEquals(1, rs.getInt("held"), "the statement after the lock must run holding it");
						assertEquals("1234ms", rs.getString("timeout"), "the lock's timeout must be in force for the statement after it");
					}
					while ( stmt.getMoreResults() || stmt.getUpdateCount() != -1 ) { }
				}
				try ( PreparedStatement after = connection.prepareStatement(
						"SELECT (SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid()) AS held, current_setting('lock_timeout') AS timeout");
						ResultSet rs = after.executeQuery() ) {
					assertTrue(rs.next());
					assertEquals(0, rs.getInt("held"), "the COMMIT in the batch must release the lock");
					assertEquals("0", rs.getString("timeout"), "SET LOCAL must not outlive the batch's transaction");
				}
				connection.rollback();
			}
		}

		/**
		 * A relevant event committed while the append waits for the lock is seen by its check: the
		 * {@code INSERT}'s snapshot is taken after the lock is granted, also when the two travel in one
		 * batch. Deterministic: the test holds the lock, starts the append, waits for PostgreSQL to report
		 * it waiting, writes the event and releases the lock in one commit.
		 */
		@Test
		void aRelevantEventCommittedWhileTheAppendWaitsForTheLockIsAConflictInBothModes ( ) throws Exception {
			for ( AppendPipelining mode : AppendPipelining.values() ) {
				HikariDataSource pool = pool(6);
				PostgresEventStorageImpl storage = storage("pipeliningwait_", pool, mode);
				storage.append(AppendCriteria.none(), STREAM, List.of(event("Opened", "a-1")));
				EventReference n = storage.head(STREAM).orElseThrow();
				try ( Connection holder = pool.getConnection();
						ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor() ) {
					holder.setAutoCommit(false);
					try ( PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)") ) {
						lock.setLong(1, storage.appendLockKey(STREAM));
						lock.execute();
					}
					Future<Boolean> append = executor.submit(() -> {
						try {
							storage.append(AppendCriteria.of(EventFilter.forTags("account", "a-1"), n), STREAM, List.of(event("Withdrawn", "a-1")));
							return true;
						} catch ( OptimisticLockingException e ) {
							return false;
						}
					});
					awaitWaitingOnAnAdvisoryLock(pool);
					try ( PreparedStatement insert = holder.prepareStatement(
							"INSERT INTO pipeliningwait_events (event_id, stream_context, stream_purpose, event_type, event_data, event_tags) "
							+ "VALUES (gen_random_uuid(), 'bank', 'accounts', 'Deposited', '{}'::jsonb, ARRAY['account:a-1'])") ) {
						insert.execute();
					}
					holder.commit();
					assertFalse(append.get(30, TimeUnit.SECONDS), mode + ": the event committed before the lock was granted must be seen");
				}
				assertEquals(2, count(storage, EventFilter.forTags("account", "a-1")), mode + ": nothing of the rejected append may be stored");
				storage.close();
			}
		}

		/** eight deciders on one boundary and one reference, ten rounds: exactly one is admitted per round, in both modes */
		@Test
		void concurrentDecidersOnOneBoundaryAdmitExactlyOneInBothModes ( ) throws Exception {
			for ( AppendPipelining mode : AppendPipelining.values() ) {
				PostgresEventStorageImpl storage = storage("pipeliningrace_", pool(12), mode);
				EventFilter boundary = EventFilter.forTags("account", "a-1");
				for ( int round = 0; round < 10; round++ ) {
					storage.append(AppendCriteria.none(), STREAM, List.of(event("Noise", "other-" + round)));
					EventReference n = storage.head(STREAM).orElseThrow();
					CountDownLatch start = new CountDownLatch(1);
					List<Future<Boolean>> deciders = new ArrayList<>();
					try ( ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor() ) {
						for ( int d = 0; d < 8; d++ ) {
							deciders.add(executor.submit(() -> {
								start.await();
								try {
									storage.append(AppendCriteria.of(boundary, n), STREAM, List.of(event("Withdrawn", "a-1")));
									return true;
								} catch ( OptimisticLockingException e ) {
									return false;
								}
							}));
						}
						start.countDown();
						int admitted = 0;
						for ( Future<Boolean> decider : deciders ) {
							admitted += decider.get() ? 1 : 0;
						}
						assertEquals(1, admitted, "%s, round %d: exactly one decider on one boundary may be admitted".formatted(mode, round));
					}
				}
				assertEquals(10, count(storage, boundary), mode + ": one admitted append per round, and nothing of the others");
				storage.close();
			}
		}

		/**
		 * A conflict commits an empty transaction in the pipelined mode: the lock must be released and the
		 * connection left clean for the next append on a pool of one.
		 */
		@Test
		void aConflictReleasesTheLockAndLeavesTheConnectionClean ( ) {
			PostgresEventStorageImpl storage = storage("pipeliningconflict_", pool(1), AppendPipelining.SINGLE_ROUND_TRIP);
			EventFilter boundary = EventFilter.forTags("account", "a-1");
			storage.append(AppendCriteria.none(), STREAM, List.of(event("Opened", "a-1")));
			EventReference n = storage.head(STREAM).orElseThrow();
			storage.append(AppendCriteria.of(boundary, n), STREAM, List.of(event("Withdrawn", "a-1")));
			for ( int i = 0; i < 3; i++ ) {
				assertThrows(OptimisticLockingException.class,
						() -> storage.append(AppendCriteria.of(boundary, n), STREAM, List.of(event("Withdrawn", "a-1"))));
			}
			EventReference current = storage.head(STREAM).orElseThrow();
			assertEquals(1, storage.append(AppendCriteria.of(boundary, current), STREAM, List.of(event("Withdrawn", "a-1"))).size());
			assertEquals(3, count(storage, boundary));
		}

		/**
		 * The lock's timeout holds in the pipelined mode, fails the append as the shipped mode does with
		 * nothing written, and is scoped to the append's transaction.
		 */
		@Test
		void theLockTimeoutBoundsThePipelinedAppendAndDoesNotOutliveIt ( ) throws Exception {
			HikariDataSource pool = pool(1);
			HikariDataSource other = pool(2);
			PostgresEventStorageImpl storage = storage("pipeliningtimeout_", pool, AppendPipelining.SINGLE_ROUND_TRIP);
			storage.lockTimeout(Duration.ofMillis(300));
			storage.append(AppendCriteria.none(), STREAM, List.of(event("Opened", "a-1")));
			EventReference n = storage.head(STREAM).orElseThrow();
			try ( Connection holder = other.getConnection() ) {
				holder.setAutoCommit(false);
				try ( PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)") ) {
					lock.setLong(1, storage.appendLockKey(STREAM));
					lock.execute();
				}
				long started = System.nanoTime();
				EventStorageException failure = assertThrows(EventStorageException.class,
						() -> storage.append(AppendCriteria.of(EventFilter.forTags("account", "a-1"), n), STREAM, List.of(event("Withdrawn", "a-1"))));
				long waitedMillis = (System.nanoTime() - started) / 1_000_000;
				assertInstanceOf(SQLException.class, failure.getCause());
				assertEquals("55P03", ((SQLException) failure.getCause()).getSQLState());
				assertTrue(waitedMillis >= 250 && waitedMillis < 5000, "waited " + waitedMillis + "ms against a 300ms bound");
				holder.rollback();
			}
			assertEquals(1, count(storage, EventFilter.forTags("account", "a-1")), "nothing of the timed-out append may be stored");
			try ( Connection session = pool.getConnection();
					PreparedStatement show = session.prepareStatement("SHOW lock_timeout");
					ResultSet rs = show.executeQuery() ) {
				assertTrue(rs.next());
				assertEquals("0", rs.getString(1), "the bound must not follow the pooled connection");
			}
		}

		/** a retried batch is swallowed, a batch mixing stored and new keys is refused, nothing partial stored: both modes */
		@Test
		void idempotencyAnswersAreUnchangedInBothModes ( ) {
			for ( AppendPipelining mode : AppendPipelining.values() ) {
				PostgresEventStorageImpl storage = storage("pipeliningidem_", pool(2), mode);
				EventFilter boundary = EventFilter.forTags("account", "a-1");
				storage.append(AppendCriteria.none(), STREAM, List.of(event("Opened", "a-1")));
				EventReference n = storage.head(STREAM).orElseThrow();
				List<EventToStore> batch = List.of(keyed(event("Withdrawn", "a-1"), "cmd-1/1"), keyed(event("Withdrawn", "a-1"), "cmd-1/2"));
				assertEquals(2, storage.append(AppendCriteria.of(boundary, n), STREAM, batch).size(), mode.toString());

				EventReference head = storage.head(STREAM).orElseThrow();
				assertEquals(0, storage.append(AppendCriteria.of(boundary, head), STREAM, batch).size(), mode + ": a retry is swallowed");
				assertThrows(IdempotencyKeyConflictException.class,
						() -> storage.append(AppendCriteria.of(boundary, head), STREAM,
								List.of(keyed(event("Withdrawn", "a-1"), "cmd-1/1"), keyed(event("Withdrawn", "a-1"), "cmd-2/1"))),
						mode + ": a batch mixing stored and new keys is refused");
				assertThrows(OptimisticLockingException.class,
						() -> storage.append(AppendCriteria.of(boundary, n), STREAM, batch),
						mode + ": the lock check runs first, so a stale retry conflicts");
				assertEquals(3, count(storage, boundary), mode + ": nothing partial stored");
				storage.close();
			}
		}

		/** random histories, cursors and OR-ed filters with item bounds against the boundary contract, both modes */
		@Test
		void randomFiltersAnswerExactlyAsTheBoundaryContractSaysInBothModes ( ) {
			for ( AppendPipelining mode : AppendPipelining.values() ) {
				PostgresEventStorageImpl storage = storage("pipeliningrandom_", pool(4), mode);
				Random random = new Random(20261123L);
				int conflicts = 0;
				int admitted = 0;
				for ( int trial = 0; trial < 150; trial++ ) {
					EventStreamId stream = EventStreamId.forContext("random").withPurpose("t" + trial);
					List<EventReference> history = new ArrayList<>();
					int before = random.nextInt(12);
					for ( int i = 0; i < before; i++ ) {
						history.addAll(references(storage.append(AppendCriteria.none(), stream, List.of(randomEvent(random, stream)))));
					}
					EventReference n = history.isEmpty() || random.nextInt(5) == 0 ? null : history.get(random.nextInt(history.size()));
					int after = random.nextInt(12);
					for ( int i = 0; i < after; i++ ) {
						history.addAll(references(storage.append(AppendCriteria.none(), stream, List.of(randomEvent(random, stream)))));
					}
					AppendCriteria criteria = AppendCriteria.of(randomFilter(random, history), n);
					boolean expectConflict = !criteria.isNone()
							&& !storage.query(criteria.eventFilter(), stream, n, null, Limit.none(), Direction.FORWARD).isEmpty();
					boolean conflicted;
					try {
						storage.append(criteria, stream, List.of(randomEvent(random, stream)));
						conflicted = false;
					} catch ( OptimisticLockingException e ) {
						conflicted = true;
					}
					assertEquals(expectConflict, conflicted, "trial %d, %s: criteria %s".formatted(trial, mode, criteria));
					if ( conflicted ) {
						conflicts++;
					} else {
						admitted++;
					}
				}
				assertTrue(conflicts > 10 && admitted > 10, "%s: %d conflicts, %d admitted".formatted(mode, conflicts, admitted));
				storage.close();
			}
		}

		@Test
		void everyConditionalAppendIsTimedAndAnUnconditionalOneIsNot ( ) {
			PostgresEventStorageImpl storage = storage("pipeliningtiming_", pool(2), AppendPipelining.SINGLE_ROUND_TRIP);
			EventFilter boundary = EventFilter.forTags("account", "a-1");
			storage.append(AppendCriteria.none(), STREAM, List.of(event("Opened", "a-1")));
			assertTrue(timings.isEmpty(), "an unconditional append takes no lock and is not timed");
			EventReference n = storage.head(STREAM).orElseThrow();
			storage.append(AppendCriteria.of(boundary, n), STREAM, List.of(event("Withdrawn", "a-1")));
			assertThrows(OptimisticLockingException.class,
					() -> storage.append(AppendCriteria.of(boundary, n), STREAM, List.of(event("Withdrawn", "a-1"))));
			assertEquals(List.of(false, true), timings.stream().map(AppendPipelining.Timing::conflicted).toList());
		}

		@Test
		void aStoreBuiltWithoutTheSystemPropertyRunsTheShippedAppend ( ) {
			PostgresEventStorageImpl storage = (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
					.name("pipelining-default")
					.prefix("pipeliningdefault_")
					.dataSource(pool(2))
					.monitoringDataSource(PostgresContainer.dataSource(image))
					.recreateDatabase()
					.build();
			closing.add(storage);
			assertEquals(AppendPipelining.OFF, storage.appendPipelining());
		}

		void awaitWaitingOnAnAdvisoryLock ( DataSource dataSource ) throws Exception {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			while ( System.nanoTime() < deadline ) {
				try ( Connection connection = dataSource.getConnection();
						PreparedStatement stmt = connection.prepareStatement("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted");
						ResultSet rs = stmt.executeQuery() ) {
					rs.next();
					if ( rs.getInt(1) > 0 ) {
						return;
					}
				}
				Thread.sleep(10);
			}
			throw new AssertionError("the append never waited on the stream's lock");
		}
	}

	// --------------------------------------------------------------------------------------------------
	// events and filters
	// --------------------------------------------------------------------------------------------------

	static EventToStore event ( String type, String account ) {
		return new EventToStore(STREAM, EventType.named(type), "{}", Tags.of("account", account), null);
	}

	static EventToStore keyed ( EventToStore event, String key ) {
		return new EventToStore(event.stream(), event.type(), event.payload(), event.tags(), key);
	}

	static int count ( PostgresEventStorageImpl storage, EventFilter filter ) {
		return storage.query(filter, STREAM, null, null, Limit.none(), Direction.FORWARD).size();
	}

	static List<EventReference> references ( List<StoredEvent> events ) {
		return events.stream().map(StoredEvent::reference).toList();
	}

	static final String[] TYPES = { "A", "B", "C" };

	static final String[] TAG_VALUES = { "1", "2", "3" };

	static EventToStore randomEvent ( Random random, EventStreamId stream ) {
		List<Tag> tags = new ArrayList<>();
		for ( String value : TAG_VALUES ) {
			if ( random.nextInt(3) == 0 ) {
				tags.add(Tag.of("k", value));
			}
		}
		return new EventToStore(stream, EventType.named(TYPES[random.nextInt(TYPES.length)]), "{}", Tags.of(tags.toArray(new Tag[0])), null);
	}

	/** one to three items, each with zero to two types, zero to two tags, and an optional bound from the history */
	static EventFilter randomFilter ( Random random, List<EventReference> history ) {
		if ( random.nextInt(15) == 0 ) {
			return EventFilter.matchAll();
		}
		EventFilter filter = null;
		int count = 1 + random.nextInt(3);
		for ( int i = 0; i < count; i++ ) {
			List<EventType> types = new ArrayList<>();
			for ( int t = random.nextInt(3); t > 0; t-- ) {
				types.add(EventType.named(TYPES[random.nextInt(TYPES.length)]));
			}
			List<Tag> tags = new ArrayList<>();
			for ( int t = random.nextInt(3); t > 0; t-- ) {
				tags.add(Tag.of("k", TAG_VALUES[random.nextInt(TAG_VALUES.length)]));
			}
			EventFilter item = types.isEmpty()
					? (tags.isEmpty() ? EventFilter.matchAll() : EventFilter.forTags(tags.toArray(new Tag[0])))
					: EventFilter.forTypes(types.toArray(new EventType[0])).tagged(tags.toArray(new Tag[0]));
			if ( !history.isEmpty() ) {
				switch ( random.nextInt(4) ) {
					case 0 -> item = item.until(history.get(random.nextInt(history.size())));
					case 1 -> item = item.after(history.get(random.nextInt(history.size())));
					default -> { }
				}
			}
			filter = filter == null ? item : filter.or(item);
		}
		return filter;
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
