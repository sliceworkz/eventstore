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

	/** PostgreSQL 16 image tag — the oldest supported major version. */
	public static final String IMAGE_PG16 = "postgres:16";

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
	 * One pool at a time per image, so every store a single test builds shares it. The pool does not
	 * outlive the test — see {@link #close(String)} for why that matters.
	 *
	 * @param image the PostgreSQL image tag
	 * @return the current DataSource for that image
	 */
	public static synchronized DataSource dataSource ( String image ) {
		start(image);
		return DATASOURCES.computeIfAbsent(image, img -> {
			PostgreSQLContainer container = CONTAINERS.get(img);
			HikariConfig config = new HikariConfig();
			config.setUsername(container.getUsername());
			config.setPassword(container.getPassword());
			config.setJdbcUrl(container.getJdbcUrl());
			// the pool is rebuilt per test, so filling it eagerly would be paid on every one of them
			config.setMinimumIdle(1);
			return new HikariDataSource(config);
		});
	}

	/**
	 * Closes the connection pool for {@code image}; the next {@link #dataSource(String)} builds a new
	 * one. The container itself is left to Ryuk.
	 * <p>
	 * This runs after <em>every test</em>, not once at the end of the run. A store's LISTEN/NOTIFY
	 * monitors each hold a connection for their whole life, taken from the monitoring DataSource,
	 * which defaults to this very pool. {@code EventStorage.close()} gives those connections back
	 * before it returns, so a pool outliving the test would no longer be starved by them — but it is
	 * still dropped per test, so that a leaked store, or one a scenario forgot to close, shows up as a
	 * failure in the test that caused it rather than as connection exhaustion several tests later.
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
