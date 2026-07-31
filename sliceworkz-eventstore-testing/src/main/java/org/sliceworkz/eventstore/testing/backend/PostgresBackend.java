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

import java.util.Optional;

import javax.sql.DataSource;

import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.testing.EventStoreBackend;
import org.sliceworkz.eventstore.testing.StorageOptions;

/**
 * PostgreSQL storage on a Testcontainers-managed database, as a backend for the shared scenarios.
 * <p>
 * The container and its connection pool are shared for the lifetime of the JVM. Per-test isolation
 * comes from {@code initializeDatabase()}, which drops and recreates the tables for the store's
 * prefix — so a store handed to a scenario is always empty even though the database outlives it.
 * <p>
 * Requires {@code sliceworkz-eventstore-infra-postgres}, the PostgreSQL JDBC driver, HikariCP and
 * Testcontainers; all optional dependencies of the testing module. Running against PostgreSQL 17
 * additionally needs {@code com.github.f4b6a3:uuid-creator}, which the legacy storage uses to
 * generate UUIDv7 where the server cannot.
 * <p>
 * Instantiate directly for a specific image, or use {@link Postgres17Backend} /
 * {@link Postgres18Backend}, which have the no-argument constructors a {@code ServiceLoader} needs.
 */
public class PostgresBackend implements EventStoreBackend {

	private final String image;

	/**
	 * @param image the PostgreSQL image tag, e.g. {@link PostgresContainer#IMAGE_PG18}
	 */
	public PostgresBackend ( String image ) {
		this.image = image;
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
				.initializeDatabase();
		if ( options.resultLimit() != null ) {
			builder.resultLimit(options.resultLimit());
		}
		return builder.build();
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
		// the single place in the suite that needs to know the concrete storage type: EventStorage
		// has no close()/stop(), and leaving the executor running leaks a thread per test
		if ( storage instanceof PostgresEventStorageImpl postgres ) {
			postgres.stop();
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
