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

import java.nio.file.Path;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The parts of the lifecycle contract that only a backend holding real resources can be asked about.
 * <p>
 * The backend-agnostic half — idempotent, terminal, operations throwing afterwards — is asserted for
 * every storage by {@code EventStorageLifecycleTest} in the TCK. What is left here is what makes this
 * backend expensive: two LISTEN/NOTIFY threads holding a JDBC connection each, and connection pools
 * that either belong to the caller or were built by the builder and belong to the storage.
 */
class PostgresLifecycleTest {

	/** what a store looks like to a scenario: one context, typed events */
	public record Ping ( String message ) { }

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@AfterEach
		void closeBorrowedPool ( ) {
			// after the storage using it has been closed by the test itself: that order is the one the
			// contract prescribes, since a storage left running against a closed pool cannot tell that
			// from a database outage and retries
			PostgresContainer.closeDataSource(image);
		}

		private PostgresEventStorage storageOn ( DataSource dataSource ) {
			return PostgresEventStorage.newBuilder()
					.name("lifecycle-test")
					.dataSource(dataSource)
					.recreateDatabase()
					.build();
		}

		@Test
		void testCloseReleasesTheMonitorConnectionsBeforeItReturns ( ) {
			HikariDataSource dataSource = (HikariDataSource) PostgresContainer.dataSource(image);
			EventStorage storage = storageOn(dataSource);

			assertEquals(2, dataSource.getHikariPoolMXBean().getActiveConnections(),
				"the two LISTEN/NOTIFY monitors should each hold a connection while the storage is open");

			storage.close();

			// the contract says close() blocks until background activity has ceased: no polling here,
			// the connections must already be back the moment close() returns
			assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections(),
				"close() must not return while the monitor threads still hold their connections");
		}

		@Test
		void testCloseLeavesACallerSuppliedDataSourceAlone ( ) {
			HikariDataSource dataSource = (HikariDataSource) PostgresContainer.dataSource(image);
			EventStorage storage = storageOn(dataSource);

			storage.close();

			assertFalse(dataSource.isClosed(), "a DataSource supplied by the caller must never be closed by the storage");
			assertTrue(usable(dataSource), "a DataSource supplied by the caller must still hand out connections after close()");
		}

		@Test
		void testCloseLogsNothingWhenTheMonitorsStopOnTheirOwn ( ) {
			HikariDataSource dataSource = (HikariDataSource) PostgresContainer.dataSource(image);
			EventStorage storage = storageOn(dataSource);

			storage.close();

			// the monitors were left to notice the stop and unwind: had they been interrupted instead,
			// their connections would have been broken under the driver and evicted by the pool
			assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
			assertTrue(usable(dataSource), "the pool must still be healthy, not emptied by evicted connections");
		}

		@Test
		void testTheHandleTheBuilderReturnsAnswersWhetherNotificationsAreUp ( ) {
			// the type build() returns, not a cast: a health endpoint keeps this handle and asks it
			PostgresEventStorage storage = storageOn(PostgresContainer.dataSource(image));

			assertTrue(storage.isNotificationsAvailable(),
				"build() waits for both monitors to register, so a storage it hands back has its notifications up");

			storage.close();

			assertFalse(storage.isNotificationsAvailable(),
				"a closed storage listens on nothing, and must say so rather than report the last state it had");
			assertThrows(EventStorageClosedException.class, storage::getBookmarks);
		}

		private boolean usable ( DataSource dataSource ) {
			try ( var connection = dataSource.getConnection() ) {
				return connection.isValid(2);
			} catch ( Exception e ) {
				return false;
			}
		}

	}

	@Nested
	class OnPostgres17 extends Tests {

		OnPostgres17 ( ) { super(PostgresContainer.IMAGE_PG17); }

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG17); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG17); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17); }
	}

	@Nested
	class OnPostgres18 extends Tests {

		OnPostgres18 ( ) { super(PostgresContainer.IMAGE_PG18); }

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }
	}

	/**
	 * The pools the builder makes for itself, which no caller ever gets a handle on: if closing the
	 * EventStore does not close them, nothing will.
	 */
	@Nested
	class OnBuilderCreatedDataSource {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

		@Test
		void testBuildStoreCallerCanShutEverythingDownThroughTheEventStore ( ) {
			PostgresContainer.writeDbProperties(PostgresContainer.IMAGE_PG18);

			EventStore eventStore = PostgresEventStorage.newBuilder()
					.name("lifecycle-owned")
					.prefix("owned_")
					.recreateDatabase()
					.buildStore();

			EventStream<Ping> stream = eventStore.getEventStream(EventStreamId.forContext("lifecycle"), Ping.class);
			stream.append(AppendCriteria.none(), Event.of(new Ping("owned pool"), Tags.none()));
			assertEquals(1, stream.query(EventQuery.matchAll()).size());

			assertFalse(PostgresContainer.backendsOfSelfBuiltPools(PostgresContainer.IMAGE_PG18).isEmpty(),
				"the builder should have opened its own pool");

			eventStore.close();

			// the pools are closed by the time close() returns; the server's view of a disconnect can
			// trail it by a moment, so allow for that rather than asserting on the very same instant
			assertTrue(awaitNoSelfBuiltBackends(),
				"closing the EventStore must close the pools the builder created — there is no other handle on them, still open: "
					+ PostgresContainer.backendsOfSelfBuiltPools(PostgresContainer.IMAGE_PG18));
			assertThrows(EventStorageClosedException.class, () -> stream.query(EventQuery.matchAll()).size());
		}

		/**
		 * A builder is reusable: each {@code build()} resolves its own pools rather than remembering the
		 * ones it created. Otherwise the second build would find the first one's pool on the builder,
		 * take it for a caller-supplied one it must not own, and start on a pool the first storage's
		 * close() has already shut.
		 */
		@Test
		void testABuilderBuildsAgainAfterTheStorageItBuiltWasClosed ( ) {
			Path dbProperties = PostgresContainer.writeDbProperties(PostgresContainer.IMAGE_PG18);
			PostgresEventStorage.Builder builder = PostgresEventStorage.newBuilder()
					.name("lifecycle-rebuilt")
					.prefix("rebuilt_")
					.configuration(dbProperties)
					.recreateDatabase();

			EventStorage first = builder.build();
			first.close();

			EventStorage second = builder.build();
			try {
				EventStream<Ping> stream = EventStoreFactory.get().eventStore(second)
						.getEventStream(EventStreamId.forContext("lifecycle"), Ping.class);
				stream.append(AppendCriteria.none(), Event.of(new Ping("second build"), Tags.none()));
				assertEquals(1, stream.query(EventQuery.matchAll()).size(),
					"the second build must run on pools of its own, not on the ones the first storage closed");
			} finally {
				second.close();
			}
			assertTrue(awaitNoSelfBuiltBackends(),
				"closing the second storage must close the pools its build created, still open: "
					+ PostgresContainer.backendsOfSelfBuiltPools(PostgresContainer.IMAGE_PG18));
		}

		/**
		 * A build that fails closes the pools it made; the next build on the same builder must make new
		 * ones rather than start on the closed ones.
		 */
		@Test
		void testABuilderBuildsAgainAfterAFailedBuild ( ) {
			Path dbProperties = PostgresContainer.writeDbProperties(PostgresContainer.IMAGE_PG18);
			PostgresEventStorage.Builder builder = PostgresEventStorage.newBuilder()
					.name("lifecycle-retried")
					.prefix("retried_" + System.nanoTime() + "_")
					.configuration(dbProperties)
					// nothing exists under a fresh prefix, so validating it is a failed build
					.databaseInitMode(DatabaseInitMode.VALIDATE);

			assertThrows(EventStorageException.class, builder::build);
			assertTrue(awaitNoSelfBuiltBackends(), "a failed build must not strand the pools it created");

			EventStorage storage = builder.recreateDatabase().build();
			try {
				EventStream<Ping> stream = EventStoreFactory.get().eventStore(storage)
						.getEventStream(EventStreamId.forContext("lifecycle"), Ping.class);
				stream.append(AppendCriteria.none(), Event.of(new Ping("after a failed build"), Tags.none()));
				assertEquals(1, stream.query(EventQuery.matchAll()).size(),
					"the build after a failed one must run on fresh pools, not on the ones the failure closed");
			} finally {
				storage.close();
			}
			assertTrue(awaitNoSelfBuiltBackends());
		}

		/**
		 * Two storages from one builder each own their pools: closing one leaves the other running.
		 */
		@Test
		void testTwoStoragesFromOneBuilderDoNotSharePools ( ) {
			Path dbProperties = PostgresContainer.writeDbProperties(PostgresContainer.IMAGE_PG18);
			PostgresEventStorage.Builder builder = PostgresEventStorage.newBuilder()
					.name("lifecycle-twins")
					.prefix("twins_")
					.configuration(dbProperties)
					.recreateDatabase();

			EventStorage first = builder.build();
			EventStorage second = builder.build();
			try {
				first.close();

				assertFalse(PostgresContainer.backendsOfSelfBuiltPools(PostgresContainer.IMAGE_PG18).isEmpty(),
					"the second storage's pools must survive the first storage closing its own");
				EventStream<Ping> stream = EventStoreFactory.get().eventStore(second)
						.getEventStream(EventStreamId.forContext("lifecycle"), Ping.class);
				stream.append(AppendCriteria.none(), Event.of(new Ping("still open"), Tags.none()));
				assertEquals(1, stream.query(EventQuery.matchAll()).size(),
					"closing the first storage must not shut the pools the second one runs on");
			} finally {
				second.close();
			}
			assertTrue(awaitNoSelfBuiltBackends(),
				"closing both storages must close both sets of pools, still open: "
					+ PostgresContainer.backendsOfSelfBuiltPools(PostgresContainer.IMAGE_PG18));
		}

		private boolean awaitNoSelfBuiltBackends ( ) {
			long deadline = System.currentTimeMillis() + 5_000;
			while ( System.currentTimeMillis() < deadline ) {
				List<String> open = PostgresContainer.backendsOfSelfBuiltPools(PostgresContainer.IMAGE_PG18);
				if ( open.isEmpty() ) {
					return true;
				}
				try {
					Thread.sleep(100);
				} catch ( InterruptedException e ) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return PostgresContainer.backendsOfSelfBuiltPools(PostgresContainer.IMAGE_PG18).isEmpty();
		}

	}

}
