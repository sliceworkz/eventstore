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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.testcontainers.postgresql.PostgreSQLContainer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Manages PostgreSQL Testcontainers, keyed by image tag, so the same scenarios can run against
 * several PostgreSQL versions.
 * <p>
 * A container and its connection pool are started once per JVM per image on first use and kept for
 * the lifetime of the JVM — starting one per test class would dominate the run time, and per-test
 * isolation comes from dropping and recreating the schema instead (see {@link AbstractPostgresBackend}).
 * Containers are reaped by Testcontainers' Ryuk on shutdown; {@link #close(String)} closes the pool.
 */
public final class PostgresContainer {

	/** PostgreSQL 17 image tag. */
	public static final String IMAGE_PG17 = "postgres:17";

	/** PostgreSQL 18 image tag. */
	public static final String IMAGE_PG18 = "postgres:18";

	private static final Map<String, PostgreSQLContainer> CONTAINERS = new ConcurrentHashMap<>();
	private static final Map<String, HikariDataSource> DATASOURCES = new ConcurrentHashMap<>();

	private PostgresContainer ( ) {
	}

	/**
	 * Starts the container for {@code image} if it is not already running. Idempotent.
	 *
	 * @param image the PostgreSQL image tag
	 */
	public static synchronized void start ( String image ) {
		CONTAINERS.computeIfAbsent(image, img -> {
			@SuppressWarnings("resource")
			PostgreSQLContainer container = new PostgreSQLContainer(img)
				.withDatabaseName("integration-tests-db")
				.withUsername("sa")
				.withPassword("pwd");
			container.start();
			return container;
		});
	}

	/**
	 * The pooled DataSource for {@code image}, starting the container on first use.
	 * <p>
	 * One pool per image, shared by every store built against it. Callers must not close it; use
	 * {@link #close(String)} when the whole run against that image is over.
	 *
	 * @param image the PostgreSQL image tag
	 * @return the shared DataSource
	 */
	public static synchronized DataSource dataSource ( String image ) {
		start(image);
		return DATASOURCES.computeIfAbsent(image, img -> {
			PostgreSQLContainer container = CONTAINERS.get(img);
			HikariConfig config = new HikariConfig();
			config.setUsername(container.getUsername());
			config.setPassword(container.getPassword());
			config.setJdbcUrl(container.getJdbcUrl());
			return new HikariDataSource(config);
		});
	}

	/**
	 * Closes the connection pool for {@code image}. The container itself is left to Ryuk.
	 *
	 * @param image the PostgreSQL image tag
	 */
	public static synchronized void close ( String image ) {
		HikariDataSource dataSource = DATASOURCES.remove(image);
		if ( dataSource != null && !dataSource.isClosed() ) {
			dataSource.close();
		}
	}

}
