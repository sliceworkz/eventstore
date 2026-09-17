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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.LeaseRequest;
import org.sliceworkz.eventstore.spi.EventStorage.LeaseStatus;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The wait for an advisory lock is bounded by {@code lockTimeout}, scoped to the transaction that set it,
 * and removable.
 * <p>
 * The holder in every scenario is a plain JDBC session holding the same {@code pg_advisory_xact_lock} the
 * storage derives for the stream or the lease, in a transaction it never ends — which is what a stalled
 * appender looks like from the server's side, whatever stalled it.
 */
class PostgresLockTimeoutTest {

	/** generous enough not to be flaky, far below "forever", which is what is being tested against */
	private static final Duration MUST_RETURN_WITHIN = Duration.ofSeconds(30);

	private static final EventStreamId STREAM = EventStreamId.forContext("locktimeout").withPurpose("p");

	@BeforeAll
	static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

	@AfterAll
	static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

	/** a session holding one advisory lock in a transaction it does not end until closed */
	static final class Holder implements AutoCloseable {

		private final Connection connection;

		Holder ( DataSource dataSource, long key ) throws SQLException {
			connection = dataSource.getConnection();
			connection.setAutoCommit(false);
			try ( PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)") ) {
				lock.setLong(1, key);
				lock.execute();
			}
		}

		/** idempotent: a scenario lets go of the lock part-way and try-with-resources closes again after it */
		@Override
		public void close ( ) throws SQLException {
			if ( !connection.isClosed() ) {
				connection.rollback();
				connection.close();
			}
		}
	}

	private static PostgresEventStorageImpl storage ( DataSource main, DataSource monitoring, String prefix, Duration lockTimeout ) {
		return (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
				.name("lock-timeout-" + prefix)
				.prefix(prefix)
				.dataSource(main)
				.monitoringDataSource(monitoring)
				.databaseInitMode(DatabaseInitMode.INITIALIZE)
				.meterRegistry(new SimpleMeterRegistry())
				.lockTimeout(lockTimeout)
				.build();
	}

	private static List<EventToStore> oneEvent ( ) {
		return List.of(new EventToStore(STREAM, EventType.named("SomethingHappened"), "{}", Tags.none(), null));
	}

	/** a real consistency boundary with an empty expected reference: "I decided on an empty stream", which takes the lock */
	private static AppendCriteria emptyBoundary ( ) {
		return AppendCriteria.of(EventFilter.matchAll(), null);
	}

	private static long eventCount ( DataSource dataSource, String prefix ) throws SQLException {
		try ( Connection connection = dataSource.getConnection();
			  Statement statement = connection.createStatement();
			  ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + prefix + "events") ) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static SQLException sqlCause ( Throwable t ) {
		Throwable cause = t;
		while ( cause != null && !(cause instanceof SQLException) ) {
			cause = cause.getCause();
		}
		return (SQLException) cause;
	}

	@Test
	void aConditionalAppendWaitsAtMostTheLockTimeoutAndWritesNothing ( ) throws Exception {
		DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
		try ( PostgresEventStorageImpl storage = storage(main, main, "lt_append_", Duration.ofMillis(500));
			  Holder holder = new Holder(main, storage.appendLockKey(STREAM)) ) {

			long started = System.nanoTime();
			EventStorageException failure = assertThrows(EventStorageException.class,
					() -> storage.append(emptyBoundary(), STREAM, oneEvent()));
			long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

			assertTrue(waitedMillis >= 400, "the append should have waited for the lock, but returned after " + waitedMillis + "ms");
			assertTrue(waitedMillis < MUST_RETURN_WITHIN.toMillis(), "the append should have given up on the lock, but waited " + waitedMillis + "ms");
			assertTrue(failure.getMessage().contains("waited 500ms"), "the message should name the bound: " + failure.getMessage());
			assertTrue(failure.getMessage().contains(STREAM.toString()), "the message should name the stream: " + failure.getMessage());
			assertEquals("55P03", sqlCause(failure).getSQLState(), "the cause should carry PostgreSQL's lock_not_available SQLSTATE");
			assertEquals(0, eventCount(main, "lt_append_"), "a timed-out append must have written nothing");

			// the unconditional path takes no lock, so the stalled holder does not touch it
			StoredEvent unconditional = storage.append(AppendCriteria.none(), STREAM, oneEvent()).getFirst();
			assertEquals(1, eventCount(main, "lt_append_"));

			holder.close();

			// with the holder gone the same append goes through, at the same boundary it was refused on:
			// the refusal was about the lock, never about the boundary
			List<StoredEvent> stored = storage.append(AppendCriteria.of(EventFilter.matchAll(), unconditional.reference()), STREAM, oneEvent());
			assertEquals(1, stored.size());
			assertEquals(2, eventCount(main, "lt_append_"));
		} finally {
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}

	@Test
	void theBoundIsScopedToTheAppendsTransactionAndNeverFollowsTheConnection ( ) throws Exception {
		// one physical connection for everything the store writes, so the session the append ran on can be
		// borrowed afterwards and asked what it still carries -- the monitors get their own pool, or they
		// would take this connection and never give it back
		DataSource monitoring = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
		try ( HikariDataSource single = PostgresContainer.singleConnectionDataSource(PostgresContainer.IMAGE_PG18);
			  PostgresEventStorageImpl storage = storage(single, monitoring, "lt_scope_", Duration.ofSeconds(3)) ) {

			// more than pgjdbc's prepareThreshold (five) on this one session, so the bounded lock statement
			// is also exercised as a server-prepared statement, which is how it runs on a busy connection
			StoredEvent last = null;
			for ( int i = 0; i < 8; i++ ) {
				last = storage.append(AppendCriteria.of(EventFilter.matchAll(), last == null ? null : last.reference()),
						STREAM, oneEvent()).getFirst();
			}
			assertEquals(8, eventCount(single, "lt_scope_"));

			try ( Connection sameSession = single.getConnection();
				  Statement statement = sameSession.createStatement();
				  ResultSet rs = statement.executeQuery("SHOW lock_timeout") ) {
				rs.next();
				assertEquals("0", rs.getString(1),
					"the append's lock_timeout leaked out of its transaction into the pooled session");
			}
		} finally {
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}

	@Test
	void aZeroTimeoutWaitsTheHolderOut ( ) throws Exception {
		DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
		try ( PostgresEventStorageImpl storage = storage(main, main, "lt_zero_", Duration.ZERO) ) {
			assertEquals(Duration.ZERO, storage.lockTimeout());

			long key = storage.appendLockKey(STREAM);
			Holder holder = new Holder(main, key);
			CompletableFuture<Void> releasing = CompletableFuture.runAsync(() -> {
				try {
					Thread.sleep(1500);
					holder.close();
				} catch ( Exception e ) {
					throw new IllegalStateException(e);
				}
			});

			long started = System.nanoTime();
			List<StoredEvent> stored = storage.append(emptyBoundary(), STREAM, oneEvent());
			long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

			assertEquals(1, stored.size());
			assertTrue(waitedMillis >= 1000, "the append should have waited for the holder to let go, but returned after " + waitedMillis + "ms");
			releasing.get(MUST_RETURN_WITHIN.toSeconds(), TimeUnit.SECONDS);
		} finally {
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}

	@Test
	void aLeaseRequestWaitsAtMostTheLockTimeoutToo ( ) throws Exception {
		DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
		try ( PostgresEventStorageImpl storage = storage(main, main, "lt_lease_", Duration.ofMillis(500)) ) {
			LeaseRequest request = new LeaseRequest("leader", "me", 0, Duration.ofSeconds(5));

			try ( Holder holder = new Holder(main, storage.leaseLockKey("leader")) ) {
				EventStorageException onRequest = assertThrows(EventStorageException.class, () -> storage.requestLease(request));
				assertTrue(onRequest.getMessage().contains("waited 500ms"), onRequest.getMessage());
				assertTrue(onRequest.getMessage().contains("leader"), "the message should name the lease: " + onRequest.getMessage());
				assertEquals("55P03", sqlCause(onRequest).getSQLState());

				EventStorageException onRelease = assertThrows(EventStorageException.class, () -> storage.releaseLease("leader", "me"));
				assertEquals("55P03", sqlCause(onRelease).getSQLState());

				// a different lease takes a different lock and is not held up
				assertEquals(LeaseStatus.LEADER, storage.requestLease(new LeaseRequest("other", "me", 0, Duration.ofSeconds(5))).status());
			}

			assertEquals(LeaseStatus.LEADER, storage.requestLease(request).status(),
				"with the holder gone the lease is acquirable: the refusal was about the lock, not the lease");
		} finally {
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}

	@Test
	void theDefaultAppliesToAStoreNobodyConfigured ( ) throws Exception {
		DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
		try ( EventStorage storage = PostgresEventStorage.newBuilder()
				.name("lock-timeout-default")
				.prefix("lt_default_")
				.dataSource(main)
				.databaseInitMode(DatabaseInitMode.INITIALIZE)
				.meterRegistry(new SimpleMeterRegistry())
				.build() ) {
			PostgresEventStorageImpl impl = assertInstanceOf(PostgresEventStorageImpl.class, storage);
			assertEquals(PostgresEventStorage.Builder.DEFAULT_LOCK_TIMEOUT, impl.lockTimeout());
			assertTrue(impl.lockTimeout().compareTo(Duration.ZERO) > 0, "the default must be a bound, not 'forever'");
		} finally {
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}
}
