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
package org.sliceworkz.eventstore.testing.backend;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.shredding.PostgresShreddingKeyStore;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.testing.EventStoreBackend;
import org.sliceworkz.eventstore.testing.StorageOptions;

/**
 * PostgreSQL storage on a Testcontainers-managed database, as a backend for the shared scenarios.
 * <p>
 * The container is shared for the lifetime of the JVM; the connection pool is not, and is dropped
 * after each test — see {@link PostgresContainer#close(String)} for why. Per-test isolation comes
 * from {@code initializeDatabase()}, which drops and recreates the tables for the store's prefix, so
 * a store handed to a scenario is always empty even though the database outlives it.
 * <p>
 * Requires {@code sliceworkz-eventstore-infra-postgres}, the PostgreSQL JDBC driver, HikariCP and
 * Testcontainers; all optional dependencies of the testing module. Running against PostgreSQL 17
 * additionally needs {@code com.github.f4b6a3:uuid-creator}, which the legacy storage uses to
 * generate UUIDv7 where the server cannot.
 * <p>
 * Abstract on purpose: every usable Postgres backend names the version it pins, so nothing in a test
 * report or a service file can be read as "PostgreSQL, some version". Use {@link Postgres17Backend}
 * or {@link Postgres18Backend}, which also have the no-argument constructors a {@code ServiceLoader}
 * needs, or {@link #forImage(String)} for a version this module ships no class for.
 */
public abstract class AbstractPostgresBackend implements EventStoreBackend {

	private final String image;

	/** Stores handed out and not yet destroyed; a test may hold more than one. */
	private final Set<EventStorage> liveStorages = ConcurrentHashMap.newKeySet();

	/**
	 * @param image the PostgreSQL image tag, e.g. {@link PostgresContainer#IMAGE_PG18}
	 */
	protected AbstractPostgresBackend ( String image ) {
		this.image = image;
	}

	/**
	 * A backend for a PostgreSQL image this module ships no dedicated class for.
	 * <p>
	 * The image tag becomes the backend name, so the version still shows up in every test report.
	 * A {@code ServiceLoader} cannot use this — register {@link Postgres17Backend} /
	 * {@link Postgres18Backend}, or a named subclass of your own, for that.
	 *
	 * @param image the PostgreSQL image tag, e.g. {@code "postgres:15"}
	 * @return a backend pinned to that image
	 */
	public static AbstractPostgresBackend forImage ( String image ) {
		return new AbstractPostgresBackend(image) { };
	}

	@Override
	public String name ( ) {
		return image;
	}

	@Override
	public EventStorage createEventStorage ( StorageOptions options ) {
		PostgresEventStorage.Builder builder = PostgresEventStorage.newBuilder()
				.name(options.discriminator())
				.prefix(prefix(options))
				.dataSource(PostgresContainer.dataSource(image))
				// drops and recreates this prefix's tables: what makes each test start empty
				.initializeDatabase()
				// build() already fails if LISTEN/NOTIFY is not established; the longer deadline is for a
				// container that has just been started and a pool that is rebuilt per test, so a slow
				// first connection shows up as a slow test rather than a failed one
				.notificationStartupTimeout(Duration.ofSeconds(30));
		if ( options.resultLimit() != null ) {
			builder.resultLimit(options.resultLimit());
		}
		EventStorage storage = builder.build();
		liveStorages.add(storage);
		return storage;
	}

	/**
	 * A key store in the same database as the events, so the TCK exercises the SQL table, the schema
	 * that creates it and the validation that checks it.
	 * <p>
	 * The prefix is deliberately left unset here rather than derived: this backend hands every test an
	 * unprefixed store unless the scenario asks otherwise, and the key store must sit beside the events
	 * of the store it protects.
	 *
	 * @param storage the storage the keys will protect events in
	 * @return a key store on the same database
	 */
	@Override
	public ShreddingKeyStore shreddingKeyStore ( EventStorage storage ) {
		return PostgresShreddingKeyStore.on(PostgresContainer.dataSource(image), prefix(StorageOptions.defaults()));
	}

	/**
	 * The table prefix isolating this store.
	 * <p>
	 * An explicit prefix wins. Otherwise a non-default discriminator becomes one, so a scenario
	 * asking for two stores gets two independent sets of tables in the one database. The stock
	 * store keeps the unprefixed table names.
	 *
	 * @param options what the scenario asked for
	 * @return the prefix to build the store with
	 */
	protected String prefix ( StorageOptions options ) {
		if ( options.prefix() != null ) {
			return options.prefix();
		}
		return StorageOptions.defaults().discriminator().equals(options.discriminator())
				? ""
				: options.discriminator() + "_";
	}

	@Override
	public void destroyEventStorage ( EventStorage storage ) {
		// close() returns once the store's LISTEN/NOTIFY monitors have stopped and handed their
		// connections back, so the pool below is free the moment this returns
		storage.close();
		liveStorages.remove(storage);

		// Drop the pool once the test's last store is gone. It is this backend's pool, not the
		// store's, so no store will ever close it. Waiting for the last one matters because a
		// scenario may hold two at once (an import needs a source and a target) and they share it;
		// closing it under a live store would leave that store's monitors retrying against a dead
		// pool, which they cannot tell from a database outage.
		if ( liveStorages.isEmpty() ) {
			PostgresContainer.close(image);
		}
	}

	@Override
	public void close ( ) {
		PostgresContainer.close(image);
	}

	@Override
	public Optional<DataSource> dataSource ( EventStorage storage ) {
		return Optional.of(PostgresContainer.dataSource(image));
	}

	@Override
	public boolean supports ( Capability capability ) {
		return true;
	}

}
