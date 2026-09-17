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
package org.sliceworkz.eventstore.infra.postgres.shredding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore.ActiveKey;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore.KeyResolution;
import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * The key store honours the bound on its cache: past it, the least recently used key costs a query
 * again, and a key inside the working set does not. Proven by counting the connections the store takes,
 * since a cache hit takes none.
 */
public class PostgresShreddingKeyStoreCacheTest {

	private static final String PREFIX = "kscache_";

	/**
	 * A data source that counts what is asked of it and hands everything on.
	 */
	private static final class CountingDataSource implements DataSource {

		private final DataSource delegate;
		private final AtomicInteger connections = new AtomicInteger();

		CountingDataSource ( DataSource delegate ) {
			this.delegate = delegate;
		}

		int connectionsTaken ( ) {
			return connections.get();
		}

		@Override
		public Connection getConnection ( ) throws SQLException {
			connections.incrementAndGet();
			return delegate.getConnection();
		}

		@Override
		public Connection getConnection ( String username, String password ) throws SQLException {
			connections.incrementAndGet();
			return delegate.getConnection(username, password);
		}

		@Override
		public PrintWriter getLogWriter ( ) throws SQLException {
			return delegate.getLogWriter();
		}

		@Override
		public void setLogWriter ( PrintWriter out ) throws SQLException {
			delegate.setLogWriter(out);
		}

		@Override
		public void setLoginTimeout ( int seconds ) throws SQLException {
			delegate.setLoginTimeout(seconds);
		}

		@Override
		public int getLoginTimeout ( ) throws SQLException {
			return delegate.getLoginTimeout();
		}

		@Override
		public Logger getParentLogger ( ) throws SQLFeatureNotSupportedException {
			return delegate.getParentLogger();
		}

		@Override
		public <T> T unwrap ( Class<T> iface ) throws SQLException {
			return delegate.unwrap(iface);
		}

		@Override
		public boolean isWrapperFor ( Class<?> iface ) throws SQLException {
			return delegate.isWrapperFor(iface);
		}
	}

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testTheBoundEvictsTheLeastRecentlyUsedKeyAndKeepsTheWorkingSet ( ) {
			// one pool, asked for once: PostgresContainer.dataSource closes the previous pool of the image
			// on every call. The schema takes it plain, so the storage's monitors -- which hold
			// connections of their own -- are not counted; the key store takes it counted
			DataSource pool = PostgresContainer.dataSource(image);
			CountingDataSource dataSource = new CountingDataSource(pool);

			try ( EventStorage schema = PostgresEventStorage.newBuilder()
					.name("kscache-schema").prefix(PREFIX).dataSource(pool).recreateDatabase().build();
				  PostgresShreddingKeyStore keys = new PostgresShreddingKeyStore(dataSource, PREFIX, Duration.ofHours(1), 2) ) {

				assertEquals(2, keys.maxCachedKeys());
				assertEquals(Duration.ofHours(1), keys.cacheTtl());

				// three keys through a cache that holds two: minting caches each, so the first is out
				ActiveKey alice = keys.keyFor(DataSubject.of("customer", "alice"));
				ActiveKey bob = keys.keyFor(DataSubject.of("customer", "bob"));
				ActiveKey carol = keys.keyFor(DataSubject.of("customer", "carol"));

				int before = dataSource.connectionsTaken();
				assertResolvesTo(keys, carol);
				assertResolvesTo(keys, bob);
				assertEquals(before, dataSource.connectionsTaken(), "the two most recently used keys are answered from the cache");

				assertResolvesTo(keys, alice);
				assertEquals(before + 1, dataSource.connectionsTaken(), "the key the bound evicted costs one query");

				// alice is back in and carol, least recently used of the two that were held, is out
				before = dataSource.connectionsTaken();
				assertResolvesTo(keys, alice);
				assertResolvesTo(keys, bob);
				assertEquals(before, dataSource.connectionsTaken());
				assertResolvesTo(keys, carol);
				assertEquals(before + 1, dataSource.connectionsTaken(), "the least recently used key was evicted, not the oldest");
			}
		}

		@Test
		public void testTheDefaultBoundIsPositiveAndTheArgumentsAreValidated ( ) {
			DataSource dataSource = PostgresContainer.dataSource(image);

			try ( PostgresShreddingKeyStore keys = PostgresShreddingKeyStore.on(dataSource, PREFIX) ) {
				assertEquals(PostgresShreddingKeyStore.DEFAULT_MAX_CACHED_KEYS, keys.maxCachedKeys());
				assertEquals(PostgresShreddingKeyStore.DEFAULT_CACHE_TTL, keys.cacheTtl());
				assertTrue(keys.maxCachedKeys() > 0, "the default must be a bound, not 'unlimited'");
			}

			assertThrows(IllegalArgumentException.class, () -> new PostgresShreddingKeyStore(dataSource, PREFIX, Duration.ofHours(1), 0));
			assertThrows(IllegalArgumentException.class, () -> new PostgresShreddingKeyStore(dataSource, PREFIX, Duration.ofHours(1), -1));
			assertThrows(IllegalArgumentException.class, () -> new PostgresShreddingKeyStore(dataSource, PREFIX, null, 10));
			assertThrows(IllegalArgumentException.class, () -> new PostgresShreddingKeyStore(dataSource, PREFIX, Duration.ofSeconds(-1), 10));
		}

		private static void assertResolvesTo ( PostgresShreddingKeyStore keys, ActiveKey expected ) {
			KeyResolution resolution = keys.resolveKey(expected.id());
			KeyResolution.Resolved resolved = assertInstanceOf(KeyResolution.Resolved.class, resolution);
			assertEquals(expected.key(), resolved.key());
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
