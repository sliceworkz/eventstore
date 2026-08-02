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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.PrintWriter;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorageException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * What happens at startup when the database the LISTEN/NOTIFY monitors need is not there.
 * <p>
 * The monitors are the one part of this backend that never fails: on a {@code SQLException} they log,
 * back off and try again, for as long as the storage lives. That is the right behaviour for a running
 * store — an outage should not permanently cost it its notifications — but it means anything waiting on
 * them to succeed is waiting on something that has no failure mode. {@code start()} used to wait on
 * exactly that, without a deadline, so a database that was unreachable at boot hung
 * {@code Builder.build()} forever: no exception, no timeout, nothing logged above DEBUG, and — since
 * {@code build()} never returned — no handle to close either.
 * <p>
 * The wait is bounded now, and expiry is fatal: an event-sourced application that is not told when
 * events are appended has read models that quietly stop advancing, which is not a state to run in.
 * These scenarios pin down that startup always terminates, that it terminates with an error rather than
 * a working-looking store, and that a <em>running</em> store which loses its notifications says so and
 * gets them back.
 */
class PostgresNotificationStartupTest {

	/** generous enough not to be flaky, far below "forever", which is what is being tested against */
	private static final Duration MUST_RETURN_WITHIN = Duration.ofSeconds(30);

	/**
	 * A DataSource that can be switched between "the database is gone" and the real thing, which is how a
	 * monitor's view of an outage is reproduced without taking a container down and back up.
	 */
	static class SwitchableDataSource implements DataSource {

		private final DataSource delegate;
		private final AtomicBoolean down;

		SwitchableDataSource ( DataSource delegate, boolean initiallyDown ) {
			this.delegate = delegate;
			this.down = new AtomicBoolean(initiallyDown);
		}

		void comeBack ( ) { down.set(false); }
		void goDown ( )   { down.set(true); }

		@Override public Connection getConnection ( ) throws SQLException {
			if ( down.get() ) {
				throw new SQLException("simulated database outage");
			}
			return delegate.getConnection();
		}
		@Override public Connection getConnection ( String username, String password ) throws SQLException { return getConnection(); }
		@Override public PrintWriter getLogWriter ( ) { return null; }
		@Override public void setLogWriter ( PrintWriter out ) { /* not used by the monitors */ }
		@Override public void setLoginTimeout ( int seconds ) { /* not used by the monitors */ }
		@Override public int getLoginTimeout ( ) { return 0; }
		@Override public Logger getParentLogger ( ) throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
		@Override public <T> T unwrap ( Class<T> iface ) throws SQLException { return delegate.unwrap(iface); }
		@Override public boolean isWrapperFor ( Class<?> iface ) throws SQLException { return delegate.isWrapperFor(iface); }
	}

	/** a pool pointed at a port nothing is listening on: every {@code getConnection()} fails, quickly */
	private static HikariDataSource unreachablePool ( String poolName ) throws Exception {
		int closedPort;
		try ( ServerSocket probe = new ServerSocket(0) ) {
			closedPort = probe.getLocalPort();
		}
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + closedPort + "/nothing-here");
		config.setUsername("nobody");
		config.setPassword("nothing");
		config.setPoolName(poolName);
		config.setConnectionTimeout(250);
		config.setInitializationFailTimeout(-1);   // the pool itself must not probe at construction
		return new HikariDataSource(config);
	}

	private static double gauge ( MeterRegistry registry, String channel ) {
		return registry.get("sliceworkz.eventstore.notifications.up").tag("channel", channel).gauge().value();
	}

	/**
	 * The half that needs no database at all: a closed port is a perfectly good unreachable database, and
	 * makes these deterministic and free.
	 */
	@Nested
	class WithAnUnreachableDatabase {

		@Test
		void testBuildFailsRatherThanHangingWhenTheDatabaseIsUnreachable ( ) throws Exception {
			try ( HikariDataSource unreachable = unreachablePool("unreachable-none") ) {

				Throwable cause = failsWithinTheDeadline(() -> PostgresEventStorage.newBuilder()
						.name("startup-none")
						.dataSource(unreachable)
						// the reachable path: with ENSURE or VALIDATE the schema work fails first and this
						// is never reached, so NONE -- which is what a production deployment trusting its
						// DBA is told to use -- is where the hang lived
						.databaseInitMode(DatabaseInitMode.NONE)
						.notificationStartupTimeout(Duration.ofSeconds(1))
						.build());

				assertInstanceOf(EventStorageException.class, cause);
				assertTrue(cause.getMessage().contains("LISTEN/NOTIFY"),
					"the message should say what is actually wrong: " + cause.getMessage());
			}
		}

		@Test
		void testTheDefaultTimeoutIsBoundedToo ( ) throws Exception {
			// the bound is the whole point, so a default that quietly went back to waiting forever has to
			// fail something. No timeout configured here on purpose
			try ( HikariDataSource unreachable = unreachablePool("unreachable-default") ) {

				Throwable cause = failsWithinTheDeadline(() -> PostgresEventStorage.newBuilder()
						.name("startup-default")
						.dataSource(unreachable)
						.databaseInitMode(DatabaseInitMode.NONE)
						.build());

				assertInstanceOf(EventStorageException.class, cause);
			}
		}

		@Test
		void testAFailedStartupLeavesNoMonitorsRunningBehindIt ( ) throws Exception {
			try ( HikariDataSource unreachable = unreachablePool("unreachable-leak") ) {

				PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl(
					"startup-leak", unreachable, unreachable, Limit.none(), "", false, new SimpleMeterRegistry());

				assertThrows(EventStorageException.class, () -> storage.start(Duration.ofSeconds(1)));

				// the two monitor threads started by that call would otherwise go on retrying forever
				// behind a storage the caller never received: start() closes it rather than just throwing
				assertThrows(IllegalStateException.class, () -> storage.start(Duration.ofSeconds(1)),
					"a storage whose startup failed must be closed, and a closed storage is terminal");
			}
		}

		@Test
		void testCloseReleasesACallerStillWaitingInStart ( ) throws Exception {
			try ( HikariDataSource unreachable = unreachablePool("unreachable-close") ) {

				PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl(
					"startup-close", unreachable, unreachable, Limit.none(), "", false, new SimpleMeterRegistry());

				// a deliberately long deadline: this is the case where only close() can release the caller
				AtomicReference<Throwable> outcome = new AtomicReference<>();
				CompletableFuture<Void> starting = CompletableFuture.runAsync(() -> {
					try {
						storage.start(Duration.ofMinutes(10));
					} catch ( Throwable t ) {
						outcome.set(t);
					}
				});
				assertThrows(TimeoutException.class, () -> starting.get(1, TimeUnit.SECONDS),
					"start() should still be waiting for the monitors at this point");

				storage.close();

				// close() stops the monitors, so nothing will ever count those latches down: if close()
				// does not release the waiter itself, this thread is parked for ten minutes
				try {
					starting.get(MUST_RETURN_WITHIN.toSeconds(), TimeUnit.SECONDS);
				} catch ( TimeoutException e ) {
					fail("close() left a caller blocked inside start()");
				}
				assertInstanceOf(EventStorageException.class, outcome.get(),
					"the released caller must still learn that notifications were never established");
			}
		}

		@Test
		void testInterruptDuringStartupThrowsRatherThanReturningAnUnstartedStorage ( ) throws Exception {
			try ( HikariDataSource unreachable = unreachablePool("unreachable-interrupt") ) {

				PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl(
					"startup-interrupt", unreachable, unreachable, Limit.none(), "", false, new SimpleMeterRegistry());

				AtomicBoolean returnedNormally = new AtomicBoolean();
				AtomicBoolean threw = new AtomicBoolean();
				AtomicBoolean interruptFlagPreserved = new AtomicBoolean();

				Thread starter = new Thread(() -> {
					try {
						storage.start(Duration.ofMinutes(10));
						returnedNormally.set(true);
					} catch ( EventStorageException e ) {
						threw.set(true);
					}
					interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
				});
				starter.start();
				Thread.sleep(500);
				starter.interrupt();
				starter.join(MUST_RETURN_WITHIN.toMillis());

				assertFalse(starter.isAlive(), "the interrupted starter thread should have finished");
				assertFalse(returnedNormally.get(),
					"an interrupt must not be swallowed into a silent 'started successfully': the monitors "
					+ "are not listening and nothing would ever say so");
				assertTrue(threw.get(), "an interrupt during startup should surface as an EventStorageException");
				assertTrue(interruptFlagPreserved.get(), "the interrupt flag must be restored");
			}
		}

		@Test
		void testTheGaugeSaysNotificationsAreDown ( ) throws Exception {
			try ( HikariDataSource unreachable = unreachablePool("unreachable-gauge") ) {
				MeterRegistry registry = new SimpleMeterRegistry();

				PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl(
					"startup-gauge", unreachable, unreachable, Limit.none(), "", false, registry);

				// registered by the constructor, before anything has been started: a gauge that only appears
				// once notifications work cannot be alerted on
				assertEquals(0d, gauge(registry, "event_appended"));
				assertEquals(0d, gauge(registry, "bookmark_placed"));

				assertThrows(EventStorageException.class, () -> storage.start(Duration.ofSeconds(1)));

				assertEquals(0d, gauge(registry, "event_appended"),
					"the gauge must read 0 while the monitor cannot reach the database");
				assertEquals(0d, gauge(registry, "bookmark_placed"));
			}
		}
	}

	/**
	 * The half that needs a real database, because the point is that everything <em>except</em> the
	 * monitors works. This is the realistic misconfiguration: the two DataSources are separate precisely
	 * because LISTEN/NOTIFY does not survive a transaction pooler, so a deployment where the pooled one is
	 * reachable and the direct one is firewalled is an ordinary mistake — and it used to produce a silent
	 * hang instead of an error.
	 */
	@Nested
	class WithARealDatabase {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

		@Test
		void testStartupFailsEvenThoughTheSchemaWorkSucceeded ( ) throws Exception {
			DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
			try ( HikariDataSource unreachableMonitoring = unreachablePool("split-monitoring") ) {

				Throwable cause = failsWithinTheDeadline(() -> PostgresEventStorage.newBuilder()
						.name("split-datasource")
						.prefix("split_")
						.dataSource(main)
						.monitoringDataSource(unreachableMonitoring)
						.databaseInitMode(DatabaseInitMode.INITIALIZE)
						.notificationStartupTimeout(Duration.ofSeconds(1))
						.build());

				assertInstanceOf(EventStorageException.class, cause);
				// the schema work ran against the reachable main DataSource and succeeded, which is why
				// nothing failed earlier and why this used to be a hang rather than an error
				assertTrue(tableExists(main, "split_events"),
					"the schema work should have completed before the monitors were even started");
			} finally {
				PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
			}
		}

		@Test
		void testStartupWaitsOutADatabaseThatIsSlowToArrive ( ) throws Exception {
			DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
			SwitchableDataSource monitoring = new SwitchableDataSource(main, true);
			MeterRegistry registry = new SimpleMeterRegistry();
			try {
				PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl(
					"slow-arrival", main, monitoring, Limit.none(), "slow_", false, registry);
				storage.initializeDatabase();

				CompletableFuture<Void> starting = CompletableFuture.runAsync(
					() -> storage.start(MUST_RETURN_WITHIN));
				assertThrows(TimeoutException.class, () -> starting.get(1, TimeUnit.SECONDS),
					"start() should still be retrying while the monitoring datasource is down");

				monitoring.comeBack();

				// the retry loop is what makes a generous deadline worth having: an application racing its
				// database up does not need to fail, it needs to wait
				starting.get(MUST_RETURN_WITHIN.toSeconds(), TimeUnit.SECONDS);
				assertTrue(storage.isNotificationsAvailable());
				assertEquals(1d, gauge(registry, "event_appended"));
				assertEquals(1d, gauge(registry, "bookmark_placed"));

				storage.close();
			} finally {
				PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
			}
		}

		@Test
		void testARunningStoreThatLosesItsNotificationsSaysSoAndGetsThemBack ( ) throws Exception {
			DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
			SwitchableDataSource monitoring = new SwitchableDataSource(main, false);
			MeterRegistry registry = new SimpleMeterRegistry();
			try {
				PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl(
					"losing-notifications", main, monitoring, Limit.none(), "losing_", false, registry);
				storage.initializeDatabase();
				storage.start(MUST_RETURN_WITHIN);
				assertTrue(storage.isNotificationsAvailable());

				// a store whose notifications die an hour in leaves exactly the same silence as one that
				// never had them, so the gauge has to fall for both
				monitoring.goDown();
				terminateListeningBackends(main);

				awaitTrue(() -> gauge(registry, "event_appended") == 0d && gauge(registry, "bookmark_placed") == 0d,
					"the gauge should have dropped to 0 once the monitors lost their connections");
				assertFalse(storage.isNotificationsAvailable());

				monitoring.comeBack();

				awaitTrue(storage::isNotificationsAvailable, "notifications never came back after the outage ended");
				assertEquals(1d, gauge(registry, "event_appended"));

				storage.close();
			} finally {
				PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
			}
		}

		@Test
		void testCloseDuringAnOutageStaysWithinItsDocumentedBound ( ) throws Exception {
			DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
			SwitchableDataSource monitoring = new SwitchableDataSource(main, false);
			try {
				PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl(
					"closing-in-outage", main, monitoring, Limit.none(), "outage_", false, new SimpleMeterRegistry());
				storage.initializeDatabase();
				storage.start(MUST_RETURN_WITHIN);

				monitoring.goDown();
				terminateListeningBackends(main);
				// let the backoff grow past its 1s starting point, so the monitors are asleep well beyond
				// the graceful window and close() has to fall back to interrupting them
				Thread.sleep(4_000);

				long startedAt = System.nanoTime();
				storage.close();
				long tookMillis = (System.nanoTime() - startedAt) / 1_000_000;

				// the contract on EventStorage.close() is "blocks, bounded" -- an outage must not turn that
				// into the monitors' 30s backoff ceiling
				assertTrue(tookMillis < 10_000, "close() during an outage took " + tookMillis + "ms");
			} finally {
				PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
			}
		}

		/**
		 * Kills the server-side connections that are sitting on a {@code LISTEN}, which is how a monitor
		 * that already holds a healthy connection is made to notice an outage — switching the DataSource
		 * alone would not, since it only affects the next {@code getConnection()}.
		 */
		private void terminateListeningBackends ( DataSource dataSource ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
				  Statement statement = connection.createStatement() ) {
				statement.execute("""
					select pg_terminate_backend(pid) from pg_stat_activity
					where datname = current_database() and pid <> pg_backend_pid() and query like 'LISTEN %'
					""");
			}
		}

		private boolean tableExists ( DataSource dataSource, String table ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
				  Statement statement = connection.createStatement();
				  ResultSet rs = statement.executeQuery(
					  "select to_regclass('" + table + "') is not null") ) {
				return rs.next() && rs.getBoolean(1);
			}
		}
	}

	/** polls until {@code condition} holds, failing rather than looping forever */
	private static void awaitTrue ( java.util.function.BooleanSupplier condition, String message ) throws Exception {
		long deadline = System.nanoTime() + MUST_RETURN_WITHIN.toNanos();
		while ( System.nanoTime() < deadline ) {
			if ( condition.getAsBoolean() ) {
				return;
			}
			Thread.sleep(100);
		}
		fail(message);
	}

	/**
	 * Runs {@code work} on another thread, expecting it to fail, and fails the test if it has not returned
	 * at all in time — rather than hanging the build the way the code under test used to hang an
	 * application.
	 *
	 * @return the exception {@code work} threw
	 */
	private static Throwable failsWithinTheDeadline ( java.util.function.Supplier<?> work ) throws Exception {
		CompletableFuture<?> future = CompletableFuture.supplyAsync(work);
		try {
			Object result = future.get(MUST_RETURN_WITHIN.toSeconds(), TimeUnit.SECONDS);
			fail("startup should have failed, but returned " + result);
			return null;
		} catch ( TimeoutException e ) {
			future.cancel(true);
			fail("startup did not return within " + MUST_RETURN_WITHIN);
			return null;
		} catch ( ExecutionException e ) {
			return e.getCause();
		}
	}

}
