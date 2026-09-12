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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.spi.EventStorageException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Creates the HikariCP {@link javax.sql.DataSource DataSources} a {@link PostgresEventStorage} runs on, from a
 * {@code db.properties} file.
 * <p>
 * The file holds one <em>named section</em> per DataSource — {@code db.pooled.*} for the ordinary pool and
 * {@code db.nonpooled.*} for the LISTEN/NOTIFY monitor connections, which must not sit behind a transaction
 * pooler. Keys inside a section are HikariCP configuration properties ({@code url}, {@code username},
 * {@code password}, {@code maximumPoolSize}, ...); keys under {@code datasource.} go to the JDBC driver
 * ({@code sslmode}, {@code cachePrepStmts}, ...). See {@link HikariConfigurationUtil}.
 *
 * <h2>Where the file is looked for</h2>
 * {@link #loadProperties()} takes the <strong>first</strong> of these that is present, and never looks
 * beyond a location that was explicitly configured:
 * <ol>
 *   <li>the path in the system property {@value #SYSTEM_PROPERTY}</li>
 *   <li>the path in the environment variable {@value #ENVIRONMENT_VARIABLE}</li>
 *   <li>{@code ./db.properties} in the working directory of the process</li>
 *   <li>{@code db.properties} at the root of the classpath, so {@code src/main/resources/db.properties}
 *       or {@code src/test/resources/db.properties} in a Maven project</li>
 * </ol>
 * A working-directory file wins over a packaged one, so a deployment can override what the jar carries
 * without rebuilding it — the convention Spring Boot and most application frameworks follow. The lookup
 * never walks into parent directories: a library reading credentials from wherever the process happens
 * to have been started is not a convention anyone expects, and would let a store pick up another
 * project's file, or in a container a file at {@code /}, with only a log line to say so.
 * <p>
 * Where none of that fits — configuration held by a framework, a secrets manager, several stores with
 * different sections in one process — bypass the lookup: pass the {@link Properties} or the {@link Path}
 * to {@link PostgresEventStorage.Builder#configuration(Properties)}, or build the pools yourself and pass
 * them to {@link PostgresEventStorage.Builder#dataSource(javax.sql.DataSource)}.
 * <p>
 * This factory returns {@link HikariDataSource} rather than a generic DataSource so that the storage can
 * register Micrometer metrics on the pool.
 */
public class DataSourceFactory {

	private static final Logger LOGGER = LoggerFactory.getLogger(DataSourceFactory.class);

	/** System property naming the properties file to use: {@code -Deventstore.db.config=/path/to/db.properties}. */
	public static final String SYSTEM_PROPERTY = "eventstore.db.config";

	/** Environment variable naming the properties file to use, consulted when the system property is absent. */
	public static final String ENVIRONMENT_VARIABLE = "EVENTSTORE_DB_CONFIG";

	/** The file name looked for in the working directory and on the classpath. */
	public static final String FILE_NAME = "db.properties";

	private DataSourceFactory ( ) {

	}

	/**
	 * Creates a HikariDataSource from the unnamed section ({@code db.*}) of the properties file found at
	 * the default location.
	 *
	 * @return a configured HikariDataSource instance, or null if the file holds no such section
	 * @throws EventStorageException if no properties file is found or it cannot be read
	 * @see #loadProperties()
	 */
	public static HikariDataSource fromConfiguration ( ) {
		return fromConfiguration(loadProperties(), null);
	}

	/**
	 * Creates a HikariDataSource from the unnamed section ({@code db.*}) of the provided properties.
	 *
	 * @param properties the database connection properties
	 * @return a configured HikariDataSource instance, or null if the properties hold no such section
	 */
	public static HikariDataSource fromConfiguration ( Properties properties ) {
		return fromConfiguration(properties, null);
	}

	/**
	 * Creates a HikariDataSource from a named section of the properties file found at the default location.
	 *
	 * @param datasourceConfigurationName the name of the section to use, e.g. {@code pooled}
	 * @return a configured HikariDataSource instance, or null if the file holds no such section
	 * @throws EventStorageException if no properties file is found or it cannot be read
	 * @see #loadProperties()
	 */
	public static HikariDataSource fromConfiguration ( String datasourceConfigurationName ) {
		return fromConfiguration(loadProperties(), datasourceConfigurationName);
	}

	/**
	 * Creates a HikariDataSource from a named section of the provided properties.
	 *
	 * @param dbProperties the database connection properties
	 * @param datasourceConfigurationName the name of the section to use, e.g. {@code pooled}; null for the
	 *        unnamed {@code db.*} section
	 * @return a configured HikariDataSource instance, or null if the properties hold no such section
	 */
	public static HikariDataSource fromConfiguration ( Properties dbProperties, String datasourceConfigurationName ) {
		Objects.requireNonNull(dbProperties, "dbProperties");
		HikariConfig config = HikariConfigurationUtil.createConfig(datasourceConfigurationName, dbProperties);
		if ( config != null ) {
			return new HikariDataSource(config);
		} else {
			return null;
		}
	}

	/**
	 * Loads the properties file from the default location — see the class documentation for the lookup
	 * order.
	 *
	 * @return the loaded properties
	 * @throws EventStorageException if no file is found at any of the locations, naming each one that was
	 *         tried, or if the file found cannot be read
	 */
	public static Properties loadProperties ( ) {
		return loadProperties(System::getProperty, System::getenv, Path.of("").toAbsolutePath(), contextClassLoader());
	}

	/**
	 * Loads the properties file at the given path, consulting no other location.
	 *
	 * @param file the properties file
	 * @return the loaded properties
	 * @throws EventStorageException if the file does not exist or cannot be read
	 */
	public static Properties loadProperties ( Path file ) {
		Objects.requireNonNull(file, "file");
		if ( !Files.isRegularFile(file) ) {
			throw new EventStorageException("database configuration file not found: " + file.toAbsolutePath());
		}
		return read(file);
	}

	/**
	 * The lookup with its environment made explicit, so that a test can drive every branch without
	 * setting a system property, an environment variable or a file in the real working directory.
	 */
	static Properties loadProperties ( Function<String, String> systemProperties, Function<String, String> environment,
			Path workingDirectory, ClassLoader classLoader ) {

		String configured = systemProperties.apply(SYSTEM_PROPERTY);
		if ( configured != null ) {
			LOGGER.info("reading database configuration from system property {} ({})", SYSTEM_PROPERTY, configured);
			return loadConfigured(Path.of(configured), "system property " + SYSTEM_PROPERTY);
		}

		configured = environment.apply(ENVIRONMENT_VARIABLE);
		if ( configured != null ) {
			LOGGER.info("reading database configuration from environment variable {} ({})", ENVIRONMENT_VARIABLE, configured);
			return loadConfigured(Path.of(configured), "environment variable " + ENVIRONMENT_VARIABLE);
		}

		Path inWorkingDirectory = workingDirectory.resolve(FILE_NAME);
		if ( Files.isRegularFile(inWorkingDirectory) ) {
			LOGGER.info("reading database configuration from working directory ({})", inWorkingDirectory.toAbsolutePath());
			return read(inWorkingDirectory);
		}

		URL onClasspath = classLoader == null ? null : classLoader.getResource(FILE_NAME);
		if ( onClasspath != null ) {
			LOGGER.info("reading database configuration from classpath ({})", onClasspath);
			return read(onClasspath);
		}

		List<String> tried = new ArrayList<>();
		tried.add("system property " + SYSTEM_PROPERTY + " (not set)");
		tried.add("environment variable " + ENVIRONMENT_VARIABLE + " (not set)");
		tried.add("working directory " + inWorkingDirectory.toAbsolutePath() + " (no such file)");
		tried.add("classpath " + FILE_NAME + " (no such resource)");
		throw new EventStorageException("no database configuration found; looked in: " + String.join(", ", tried)
				+ ". Create a " + FILE_NAME + " at one of those locations, point " + SYSTEM_PROPERTY + " or "
				+ ENVIRONMENT_VARIABLE + " at one, or pass the configuration or a DataSource to the builder");
	}

	private static Properties loadConfigured ( Path file, String configuredBy ) {
		if ( !Files.isRegularFile(file) ) {
			throw new EventStorageException("database configuration file " + file.toAbsolutePath() + " configured by "
					+ configuredBy + " does not exist");
		}
		return read(file);
	}

	private static Properties read ( Path file ) {
		try ( InputStream is = Files.newInputStream(file) ) {
			return read(is);
		} catch ( IOException e ) {
			throw new EventStorageException("could not read database configuration file " + file.toAbsolutePath(), e);
		}
	}

	private static Properties read ( URL resource ) {
		try ( InputStream is = resource.openStream() ) {
			return read(is);
		} catch ( IOException e ) {
			throw new EventStorageException("could not read database configuration resource " + resource, e);
		}
	}

	private static Properties read ( InputStream is ) throws IOException {
		Properties result = new Properties();
		result.load(is);
		return result;
	}

	private static ClassLoader contextClassLoader ( ) {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return loader != null ? loader : DataSourceFactory.class.getClassLoader();
	}

}
