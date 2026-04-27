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
package org.sliceworkz.eventstore.infra.postgres.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.testcontainers.postgresql.PostgreSQLContainer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Test helper that manages PostgreSQL Testcontainers, parameterised by image tag so the same
 * shared test scenarios can run against multiple PostgreSQL versions (e.g. PG17 and PG18).
 * <p>
 * Containers are started once per JVM per image — the first {@code start(image)} call boots
 * the container, subsequent calls are no-ops. {@code stop} and {@code cleanup} are intentionally
 * no-ops (containers stay alive for the duration of the JVM and are reaped by Testcontainers'
 * Ryuk on shutdown). This halves CI time vs. starting/stopping a container per test class.
 */
public class PostgresContainer {

	public static final String IMAGE_PG17 = "postgres:17";
	public static final String IMAGE_PG18 = "postgres:18";

	private static final Map<String, PostgreSQLContainer> CONTAINERS = new ConcurrentHashMap<>();
	private static final Map<String, HikariDataSource> DATASOURCES = new ConcurrentHashMap<>();

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

	public static void stop ( String image ) {
		// no-op: container kept alive for JVM lifetime; reaped by Testcontainers' Ryuk on shutdown
	}

	public static void cleanup ( String image ) {
		// no-op: see stop(...)
	}

	public static DataSource dataSource ( String image ) {
		PostgreSQLContainer container = CONTAINERS.get(image);
		if ( container == null ) {
			throw new IllegalStateException("PostgresContainer.start(\"" + image + "\") was not called");
		}
		HikariConfig config = new HikariConfig();
		config.setUsername(container.getUsername());
		config.setPassword(container.getPassword());
		config.setJdbcUrl(container.getJdbcUrl());
		HikariDataSource dataSource = new HikariDataSource(config);
		HikariDataSource previous = DATASOURCES.put(image, dataSource);
		if ( previous != null && !previous.isClosed() ) {
			previous.close();
		}
		return dataSource;
	}

	public static void closeDataSource ( String image ) {
		HikariDataSource dataSource = DATASOURCES.remove(image);
		if ( dataSource != null && !dataSource.isClosed() ) {
			dataSource.close();
		}
	}

}
