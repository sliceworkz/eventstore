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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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

	/** PostgreSQL 15 image tag — the oldest supported major version. */
	public static final String IMAGE_PG15 = "postgres:15";

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

	/**
	 * Marker put in {@code application_name} of the pools the builder creates for itself, so a test can
	 * tell those connections apart from the ones it opened.
	 */
	public static final String SELF_BUILT_POOL_MARKER = "eventstore-selfbuilt";

	/**
	 * Points {@link org.sliceworkz.eventstore.infra.postgres.DataSourceFactory} at this container by
	 * writing a {@code db.properties} for it and setting {@code eventstore.db.config}.
	 * <p>
	 * Needed to exercise the path where the builder creates the connection pools itself — the path
	 * where nobody but the storage has a handle on them.
	 */
	public static void writeDbProperties ( String image ) {
		PostgreSQLContainer container = CONTAINERS.get(image);
		if ( container == null ) {
			throw new IllegalStateException("PostgresContainer.start(\"" + image + "\") was not called");
		}
		try {
			Path file = Files.createTempFile("eventstore-db", ".properties");
			file.toFile().deleteOnExit();
			// the ApplicationName goes in the URL: with a jdbcUrl-based HikariConfig that is what
			// actually reaches the server. A "datasource." property is still needed for
			// HikariConfigurationUtil to consider the section present at all.
			String url = container.getJdbcUrl() + (container.getJdbcUrl().contains("?") ? "&" : "?");
			Files.writeString(file, """
				db.pooled.url=%1$sApplicationName=%2$s-pooled
				db.pooled.username=%3$s
				db.pooled.password=%4$s
				db.pooled.maximumPoolSize=3
				db.pooled.datasource.ApplicationName=%2$s-pooled
				db.nonpooled.url=%1$sApplicationName=%2$s-nonpooled
				db.nonpooled.username=%3$s
				db.nonpooled.password=%4$s
				db.nonpooled.maximumPoolSize=3
				db.nonpooled.datasource.ApplicationName=%2$s-nonpooled
				""".formatted(url, SELF_BUILT_POOL_MARKER, container.getUsername(), container.getPassword()));
			System.setProperty("eventstore.db.config", file.toString());
		} catch ( IOException e ) {
			throw new IllegalStateException("could not write a db.properties for " + image, e);
		}
	}

	/**
	 * Returns the server-side connections currently held by pools the builder created for itself,
	 * identified by {@link #SELF_BUILT_POOL_MARKER}. Empty means those pools are really gone — asked of
	 * the database rather than of the pool object, since the point is that nobody holds that object.
	 *
	 * @param image the container image whose database to ask
	 * @return one entry per open backend, describing it for assertion messages
	 */
	public static List<String> backendsOfSelfBuiltPools ( String image ) {
		PostgreSQLContainer container = CONTAINERS.get(image);
		if ( container == null ) {
			throw new IllegalStateException("PostgresContainer.start(\"" + image + "\") was not called");
		}
		List<String> result = new ArrayList<>();
		try ( Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
			  PreparedStatement statement = connection.prepareStatement(
				  "select pid, application_name, state from pg_stat_activity where application_name like ?") ) {
			statement.setString(1, SELF_BUILT_POOL_MARKER + "%");
			try ( ResultSet rs = statement.executeQuery() ) {
				while ( rs.next() ) {
					result.add("%d/%s/%s".formatted(rs.getInt(1), rs.getString(2), rs.getString(3)));
				}
			}
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not inspect pg_stat_activity of " + image, e);
		}
		return result;
	}

}
