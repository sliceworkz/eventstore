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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.observability.EventStoreObserver;
import org.sliceworkz.eventstore.observability.HealthCheck;
import org.sliceworkz.eventstore.observability.HealthStatus;
import org.sliceworkz.eventstore.observability.Observation;
import org.sliceworkz.eventstore.observability.Outcome;
import org.sliceworkz.eventstore.observability.StorageHealth;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

import com.zaxxer.hikari.HikariPoolMXBean;

/**
 * What {@link PostgresEventStorage#health(Duration)} reports beyond {@link HealthCheck#OPEN}: the database
 * round trip, the notification channels, the visibility barrier and the connection pools.
 * <p>
 * The two properties that make it fit for a health endpoint are pinned against a database that does not
 * cooperate: a round trip that never answers costs the caller its timeout and no more, and costs the
 * database one outstanding probe however often the caller asks — and a stall, the failure that is
 * otherwise silent, is reported degraded and cleared by itself when the blocker ends.
 */
public class PostgresStorageHealthTest {

	@Test
	void aPoolWithNobodyWaitingIsUp ( ) {
		HealthCheck check = PostgresEventStorageImpl.poolCheck("connectionPool", pool(3, 7, 10, 0), 10);

		assertEquals(HealthStatus.UP, check.status());
		assertEquals(List.of("active", "idle", "total", "max", "waiting"), List.copyOf(check.details().keySet()));
		assertEquals(3, check.details().get("active"));
		assertEquals(0, check.details().get("waiting"));
	}

	@Test
	void aPoolWithCallersWaitingIsDegraded ( ) {
		HealthCheck check = PostgresEventStorageImpl.poolCheck("connectionPool", pool(10, 0, 10, 4), 10);

		assertEquals(HealthStatus.DEGRADED, check.status());
		assertTrue(check.message().contains("4 caller(s) waiting"), check.message());
	}

	private static HikariPoolMXBean pool ( int active, int idle, int total, int waiting ) {
		return new HikariPoolMXBean() {
			@Override public int getIdleConnections ( ) { return idle; }
			@Override public int getActiveConnections ( ) { return active; }
			@Override public int getTotalConnections ( ) { return total; }
			@Override public int getThreadsAwaitingConnection ( ) { return waiting; }
			@Override public void softEvictConnections ( ) { }
			@Override public void suspendPool ( ) { }
			@Override public void resumePool ( ) { }
		};
	}

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		void aStorageInWorkingOrderReportsEveryCheckUp ( ) throws Exception {
			DataSource dataSource = PostgresContainer.dataSource(image);
			try ( PostgresEventStorage storage = builder("hlthup_", dataSource).build() ) {
				StorageHealth health = storage.health();

				assertEquals(HealthStatus.UP, health.status(), health.toString());
				assertEquals(List.of(HealthCheck.OPEN, PostgresEventStorage.HEALTH_DATABASE,
						PostgresEventStorage.HEALTH_EVENT_APPENDED_NOTIFICATIONS, PostgresEventStorage.HEALTH_BOOKMARK_PLACED_NOTIFICATIONS,
						PostgresEventStorage.HEALTH_VISIBILITY, PostgresEventStorage.HEALTH_CONNECTION_POOL),
					health.checks().stream().map(HealthCheck::name).toList(),
					"one pool, shared by the monitors, so no monitoring pool of its own");
				assertTrue(health.check(PostgresEventStorage.HEALTH_DATABASE).orElseThrow().details().get("roundTripMicros") instanceof Long);
				assertTrue((Integer) health.check(PostgresEventStorage.HEALTH_CONNECTION_POOL).orElseThrow().details().get("max") > 0);
			}
		}

		@Test
		void aClosedStorageReportsOnlyThatItIsClosed ( ) {
			DataSource dataSource = PostgresContainer.dataSource(image);
			PostgresEventStorage storage = builder("hlthclosed_", dataSource).build();
			storage.close();

			StorageHealth health = storage.health();

			assertEquals(HealthStatus.DOWN, health.status());
			assertEquals(List.of(HealthCheck.OPEN), health.checks().stream().map(HealthCheck::name).toList(),
				"a closed storage asks nothing of a database it no longer holds a pool for");
		}

		@Test
		void aDatabaseThatDoesNotAnswerIsDownWithinTheTimeoutAndProbedOnce ( ) throws Exception {
			DataSource real = PostgresContainer.dataSource(image);
			GatedDataSource gated = new GatedDataSource(real);
			try ( PostgresEventStorage storage = builder("hlthhang_", gated).monitoringDataSource(real).build() ) {
				int before = gated.connectionsRequested.get();
				gated.hang();
				try {
					for ( int i = 0; i < 3; i++ ) {
						long started = System.nanoTime();
						HealthCheck database = storage.health(Duration.ofMillis(300)).check(PostgresEventStorage.HEALTH_DATABASE).orElseThrow();
						long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

						assertEquals(HealthStatus.DOWN, database.status(), database.toString());
						assertTrue(database.message().contains("has not answered"), database.message());
						assertTrue(tookMillis < 2_000, "the caller waits its timeout and no more, but took " + tookMillis + "ms");
					}
					assertEquals(1, gated.connectionsRequested.get() - before,
						"a probe still in flight is waited on again rather than joined by another");
					assertTrue(storage.health(Duration.ofMillis(300)).check(PostgresEventStorage.HEALTH_MONITORING_CONNECTION_POOL).isPresent(),
						"monitors with a pool of their own report it beside the main one");
				} finally {
					gated.open();
				}
				awaitTrue(() -> storage.health(Duration.ofSeconds(1)).check(PostgresEventStorage.HEALTH_DATABASE).orElseThrow().status() == HealthStatus.UP,
					"once the database answers again, so does the round trip");
			}
		}

		@Test
		void aDatabaseRefusingConnectionsIsDownWithItsSqlState ( ) throws Exception {
			DataSource real = PostgresContainer.dataSource(image);
			GatedDataSource gated = new GatedDataSource(real);
			try ( PostgresEventStorage storage = builder("hlthrefuse_", gated).monitoringDataSource(real).build() ) {
				gated.refuse(new SQLException("Connection refused", "08001"));

				HealthCheck database = storage.health().check(PostgresEventStorage.HEALTH_DATABASE).orElseThrow();

				assertEquals(HealthStatus.DOWN, database.status());
				assertTrue(database.message().contains("Connection refused"), database.message());
				assertEquals("08001", database.details().get("sqlState"));
				assertEquals(SQLException.class.getName(), database.details().get("error"));
			}
		}

		@Test
		void aStallIsDegradedAndReportedUntilTheBlockerEnds ( ) throws Exception {
			DataSource dataSource = PostgresContainer.dataSource(image);
			List<Boolean> stalls = new CopyOnWriteArrayList<>();
			EventStoreObserver observer = new EventStoreObserver() {
				@Override
				public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
					return NOOP.start(observation);
				}
				@Override
				public void visibilityStallChanged ( String storage, boolean stalled ) {
					stalls.add(stalled);
				}
			};
			try ( PostgresEventStorage storage = builder("hlthstall_", dataSource).observer(observer).build() ) {
				((PostgresEventStorageImpl) storage).stallThreshold(Duration.ofMillis(500));
				EventStreamId stream = EventStreamId.forContext("account").withPurpose("1");
				execute(dataSource, "CREATE TABLE IF NOT EXISTS hlthstall_unrelated_workload (id int)");

				try ( Connection held = dataSource.getConnection() ) {
					held.setAutoCommit(false);
					try ( Statement stmt = held.createStatement() ) {
						// a writing transaction anywhere in the cluster holds the barrier down
						stmt.execute("INSERT INTO hlthstall_unrelated_workload VALUES (1)");
					}
					storage.append(AppendCriteria.none(), stream, List.of(new EventToStore(stream, EventType.named("During"), "{}", Tags.none(), null)));

					awaitTrue(() -> visibility(storage).status() == HealthStatus.DEGRADED, "a notification withheld past the threshold is a degraded storage");
					HealthCheck visibility = visibility(storage);
					assertEquals(1, visibility.details().get("withheldStreams"));
					assertTrue((Long) visibility.details().get("withheldForMillis") >= 500);
					assertTrue(visibility.message().contains("pg_stat_activity"), "says where to look: " + visibility.message());
					awaitTrue(() -> stalls.equals(List.of(true)), "the stall is reported to the observer once: " + stalls);

					held.commit();
				}

				awaitTrue(() -> visibility(storage).status() == HealthStatus.UP, "a stall clears by itself when the blocker ends");
				awaitTrue(() -> stalls.equals(List.of(true, false)), "and its end is reported: " + stalls);
				assertEquals(0, visibility(storage).details().get("withheldStreams"));
			}
		}

		@Test
		void aStorageClosedWhileStalledReportsTheStallOver ( ) throws Exception {
			DataSource dataSource = PostgresContainer.dataSource(image);
			List<Boolean> stalls = new CopyOnWriteArrayList<>();
			EventStoreObserver observer = new EventStoreObserver() {
				@Override
				public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
					return NOOP.start(observation);
				}
				@Override
				public void visibilityStallChanged ( String storage, boolean stalled ) {
					stalls.add(stalled);
				}
			};
			PostgresEventStorage storage = builder("hlthstallclose_", dataSource).observer(observer).build();
			((PostgresEventStorageImpl) storage).stallThreshold(Duration.ofMillis(200));
			EventStreamId stream = EventStreamId.forContext("account").withPurpose("1");
			execute(dataSource, "CREATE TABLE IF NOT EXISTS hlthstallclose_unrelated_workload (id int)");

			try ( Connection held = dataSource.getConnection() ) {
				held.setAutoCommit(false);
				try ( Statement stmt = held.createStatement() ) {
					stmt.execute("INSERT INTO hlthstallclose_unrelated_workload VALUES (1)");
				}
				storage.append(AppendCriteria.none(), stream, List.of(new EventToStore(stream, EventType.named("During"), "{}", Tags.none(), null)));
				awaitTrue(() -> stalls.equals(List.of(true)), "fixture: the stall is reported");

				storage.close();

				assertEquals(List.of(true, false), stalls, "a gauge must not keep reporting a stall for a storage that is gone");
				held.rollback();
			}
		}

		private static HealthCheck visibility ( PostgresEventStorage storage ) {
			return storage.health().check(PostgresEventStorage.HEALTH_VISIBILITY).orElseThrow();
		}

		private static PostgresEventStorage.Builder builder ( String prefix, DataSource dataSource ) {
			return PostgresEventStorage.newBuilder()
				.name("unit-test")
				.prefix(prefix)
				.dataSource(dataSource)
				.recreateDatabase();
		}

		private static void execute ( DataSource dataSource, String sql ) throws SQLException {
			try ( Connection connection = dataSource.getConnection(); Statement stmt = connection.createStatement() ) {
				stmt.execute(sql);
			}
		}

		private static void awaitTrue ( BooleanSupplier condition, String message ) throws InterruptedException {
			long deadline = System.nanoTime() + 10_000_000_000L;
			while ( !condition.getAsBoolean() ) {
				assertTrue(System.nanoTime() < deadline, message);
				Thread.sleep(50);
			}
		}
	}

	/**
	 * A {@code DataSource} whose connections can be held back or refused, standing in for a database that
	 * has stopped answering — a pool that hands out nothing, a socket nobody replies on — or one that
	 * refuses outright.
	 */
	static final class GatedDataSource implements DataSource {

		private final DataSource delegate;
		final AtomicInteger connectionsRequested = new AtomicInteger();
		private final AtomicReference<CountDownLatch> gate = new AtomicReference<>();
		private volatile SQLException refusal;

		GatedDataSource ( DataSource delegate ) {
			this.delegate = delegate;
		}

		void hang ( ) {
			gate.set(new CountDownLatch(1));
		}

		void open ( ) {
			CountDownLatch latch = gate.getAndSet(null);
			if ( latch != null ) {
				latch.countDown();
			}
		}

		void refuse ( SQLException refusal ) {
			this.refusal = refusal;
		}

		@Override
		public Connection getConnection ( ) throws SQLException {
			connectionsRequested.incrementAndGet();
			if ( refusal != null ) {
				throw refusal;
			}
			CountDownLatch latch = gate.get();
			if ( latch != null ) {
				try {
					latch.await();
				} catch ( InterruptedException e ) {
					Thread.currentThread().interrupt();
					throw new SQLException("interrupted while held back", e);
				}
			}
			return delegate.getConnection();
		}

		@Override public Connection getConnection ( String username, String password ) throws SQLException { return getConnection(); }
		@Override public PrintWriter getLogWriter ( ) throws SQLException { return delegate.getLogWriter(); }
		@Override public void setLogWriter ( PrintWriter out ) throws SQLException { delegate.setLogWriter(out); }
		@Override public void setLoginTimeout ( int seconds ) throws SQLException { delegate.setLoginTimeout(seconds); }
		@Override public int getLoginTimeout ( ) throws SQLException { return delegate.getLoginTimeout(); }
		@Override public Logger getParentLogger ( ) throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
		@Override public <T> T unwrap ( Class<T> iface ) throws SQLException { return delegate.unwrap(iface); }
		@Override public boolean isWrapperFor ( Class<?> iface ) throws SQLException { return delegate.isWrapperFor(iface); }
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
