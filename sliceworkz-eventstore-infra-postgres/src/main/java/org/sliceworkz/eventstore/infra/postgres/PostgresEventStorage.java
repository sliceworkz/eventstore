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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Factory interface for creating production-ready PostgreSQL-backed event storage implementations.
 * <p>
 * PostgresEventStorage provides a production-ready {@link EventStorage} implementation that persists events
 * to a PostgreSQL database using JDBC. This implementation is fully compliant with the Dynamic Consistency
 * Boundary (DCB) specification and supports high-performance event querying, optimistic locking, and
 * real-time event notifications via PostgreSQL's LISTEN/NOTIFY mechanism.
 * <p>
 * Key features:
 * <ul>
 *   <li><strong>Production-ready</strong>: Uses HikariCP for connection pooling and optimized prepared statements</li>
 *   <li><strong>Table prefixing</strong>: Supports table name prefixes for multi-tenancy or schema isolation</li>
 *   <li><strong>Schema initialization</strong>: Can automatically create required database tables and functions</li>
 *   <li><strong>Flexible configuration</strong>: Supports custom DataSource or automatic configuration from properties file</li>
 *   <li><strong>Monitoring support</strong>: Separate DataSource for monitoring queries to avoid connection pool contention</li>
 *   <li><strong>Result limiting</strong>: Configurable absolute limits to prevent unbounded query results</li>
 * </ul>
 * <p>
 * <strong>Database Configuration:</strong><br>
 * When no custom DataSource is provided, PostgresEventStorage automatically loads configuration from a
 * {@code db.properties} file. The {@link DataSourceFactory} searches for this file in:
 * <ol>
 *   <li>System property {@code eventstore.db.config}</li>
 *   <li>Environment variable {@code EVENTSTORE_DB_CONFIG}</li>
 *   <li>Current working directory and up to 2 parent directories</li>
 * </ol>
 * <p>
 * The properties file should contain connection settings prefixed with {@code db.pooled.} for the main
 * connection pool and {@code db.nonpooled.} for monitoring connections. See {@link DataSourceFactory}
 * for details on the expected format.
 * <p>
 * <strong>Table Prefixing:</strong><br>
 * Table prefixes enable multiple isolated event stores within the same database schema. The prefix must:
 * <ul>
 *   <li>Contain only alphanumeric characters and underscores</li>
 *   <li>End with an underscore (e.g., "tenant1_", "test_")</li>
 *   <li>Be 32 characters or less</li>
 * </ul>
 * Tables created with a prefix include: {@code PREFIX_events}, {@code PREFIX_bookmarks}.
 *
 * <h2>Basic Usage Example:</h2>
 * <pre>{@code
 * // Using default configuration from db.properties file (default mode is ENSURE)
 * EventStore eventStore = PostgresEventStorage.newBuilder().buildStore();
 *
 * // Test environment: fresh schema every time
 * EventStore eventStore = PostgresEventStorage.newBuilder()
 *     .initializeDatabase()
 *     .buildStore();
 * }</pre>
 *
 * <h2>Advanced Configuration Example:</h2>
 * <pre>{@code
 * // With custom DataSource and table prefix for multi-tenancy
 * DataSource customDataSource = // ... create your DataSource
 * DataSource monitoringDataSource = // ... create separate monitoring DataSource
 *
 * EventStorage storage = PostgresEventStorage.newBuilder()
 *     .name("tenant1-store")
 *     .dataSource(customDataSource)
 *     .monitoringDataSource(monitoringDataSource)
 *     .prefix("tenant1_")
 *     .resultLimit(10000)
 *     .databaseInitMode(DatabaseInitMode.VALIDATE)
 *     .build();
 * EventStore eventStore = EventStoreFactory.get().eventStore(storage);
 * }</pre>
 *
 * <h2>Optional uuid-creator Dependency (PostgreSQL 13-17 only)</h2>
 * From PostgreSQL 18 onwards, event ids are generated server-side via the native
 * {@code uuidv7()} function. On older versions the library generates ids in Java using
 * {@code com.github.f4b6a3:uuid-creator}, which is therefore declared as an
 * <strong>optional</strong> Maven dependency.
 * <p>
 * Applications certain they will only ever connect to PostgreSQL 18+ may simply omit
 * this dependency from their build and the smaller dependency tree carries through.
 * Applications that may connect to PostgreSQL 13-17 must declare it explicitly:
 * <pre>{@code
 * <dependency>
 *     <groupId>com.github.f4b6a3</groupId>
 *     <artifactId>uuid-creator</artifactId>
 * </dependency>
 * }</pre>
 * If a legacy server is connected to but the dependency is missing, {@link Builder#build()}
 * fails fast with an {@link org.sliceworkz.eventstore.spi.EventStorageException} explaining
 * how to resolve it.
 *
 * @see EventStorage
 * @see EventStore
 * @see EventStoreFactory
 * @see DataSourceFactory
 * @see HikariConfigurationUtil
 */
public interface PostgresEventStorage {
	
	/**
	 * Creates a new builder for configuring a PostgreSQL event storage instance.
	 * <p>
	 * The builder provides a fluent API for configuring all aspects of the storage backend,
	 * including database connections, table prefixes, and initialization options.
	 *
	 * @return a new Builder instance with default settings
	 */
	public static Builder newBuilder ( ) {
		return PostgresEventStorage.Builder.newBuilder();
	}

	/**
	 * Builder for configuring and creating PostgreSQL event storage instances.
	 * <p>
	 * The Builder uses the following defaults:
	 * <ul>
	 *   <li><strong>name</strong>: "psql"</li>
	 *   <li><strong>prefix</strong>: "" (no prefix)</li>
	 *   <li><strong>dataSource</strong>: Auto-configured from db.properties if not provided</li>
	 *   <li><strong>monitoringDataSource</strong>: Defaults to same as dataSource, or separate non-pooled from db.properties</li>
	 *   <li><strong>databaseInitMode</strong>: {@link DatabaseInitMode#ENSURE} (create missing objects, validate)</li>
	 *   <li><strong>resultLimit</strong>: none (no absolute limit)</li>
	 * </ul>
	 * <p>
	 * Use the builder methods to customize these settings before calling {@link #build()} or {@link #buildStore()}.
	 *
	 * @see #build()
	 * @see #buildStore()
	 * @see DatabaseInitMode
	 */
	public static class Builder {

		private static final Logger LOGGER = LoggerFactory.getLogger(Builder.class);

		/**
		 * Major PostgreSQL version from which the native {@code uuidv7()} function is available.
		 * Servers reporting a lower major version use {@link PostgresLegacyEventStorageImpl}.
		 */
		static final int FIRST_NATIVE_UUIDV7_MAJOR_VERSION = 18;

		private String prefix = "";
		private String name = "psql";
		private DataSource dataSource;
		private DataSource monitoringDataSource;
		private DatabaseInitMode databaseInitMode = DatabaseInitMode.ENSURE;
		private Limit limit = Limit.none();
		private MeterRegistry meterRegistry = Metrics.globalRegistry;

		private Builder ( ) {

		}

		static Builder newBuilder ( ) {
			return new Builder ( );
		}

		/**
		 * Sets a descriptive name for this event storage instance.
		 * <p>
		 * The name is used for logging and monitoring purposes to distinguish between
		 * multiple event storage instances in the same application.
		 *
		 * @param name the name for this storage instance (e.g., "customer-events", "orders")
		 * @return this Builder for method chaining
		 */
		public Builder name ( String name ) {
			this.name = name;
			return this;
		}

		/**
		 * Sets the JDBC DataSource for database connections.
		 * <p>
		 * When a custom DataSource is provided, it will be used for both regular queries and
		 * monitoring queries. The monitoring DataSource will also be set to this DataSource
		 * unless explicitly overridden with {@link #monitoringDataSource(DataSource)}.
		 * <p>
		 * If no DataSource is provided, the builder will automatically create one using
		 * {@link DataSourceFactory#fromConfiguration(String)} with configuration loaded
		 * from a {@code db.properties} file.
		 * <p>
		 * The DataSource should be configured with connection pooling (e.g., HikariCP) for
		 * optimal performance.
		 *
		 * @param dataSource the JDBC DataSource to use for database connections
		 * @return this Builder for method chaining
		 * @see #monitoringDataSource(DataSource)
		 * @see DataSourceFactory
		 */
		public Builder dataSource ( DataSource dataSource ) {
			this.dataSource = dataSource;
			this.monitoringDataSource = dataSource;
			return this;
		}

		/**
		 * Sets a separate JDBC DataSource specifically for monitoring queries.
		 * <p>
		 * PostgreSQL's LISTEN/NOTIFY mechanism requires dedicated non-pooled connections.
		 * Using a separate DataSource for these monitoring operations prevents blocking
		 * of regular query operations and avoids issues with connection poolers like PgBouncer.
		 * <p>
		 * This DataSource is used for:
		 * <ul>
		 *   <li>Listening for event append notifications</li>
		 *   <li>Listening for bookmark update notifications</li>
		 * </ul>
		 * <p>
		 * If not set explicitly, defaults to the main DataSource. When using automatic configuration
		 * from {@code db.properties}, a separate non-pooled DataSource is created automatically.
		 *
		 * @param monitoringDataSource the JDBC DataSource for monitoring operations
		 * @return this Builder for method chaining
		 * @see #dataSource(DataSource)
		 */
		public Builder monitoringDataSource ( DataSource monitoringDataSource ) {
			this.monitoringDataSource = monitoringDataSource;
			return this;
		}

		/**
		 * Sets the table name prefix for database schema isolation.
		 * <p>
		 * Table prefixes enable multiple independent event stores to coexist in the same
		 * PostgreSQL database schema. All tables created by this storage instance will be
		 * prefixed with the specified value.
		 * <p>
		 * <strong>Prefix requirements:</strong>
		 * <ul>
		 *   <li>Must contain only alphanumeric characters and underscores</li>
		 *   <li>Must end with an underscore (e.g., "tenant1_", "test_")</li>
		 *   <li>Must be 32 characters or less</li>
		 *   <li>Can be empty string for no prefix</li>
		 * </ul>
		 * <p>
		 * Example table names with prefix "tenant1_": {@code tenant1_events}, {@code tenant1_bookmarks}
		 *
		 * @param prefix the table name prefix, or empty string for no prefix
		 * @return this Builder for method chaining
		 * @throws IllegalArgumentException if prefix does not meet requirements
		 */
		public Builder prefix ( String prefix ) {
			this.prefix = prefix;
			return this;
		}

		/**
		 * Sets an absolute limit on the number of results that can be returned from a query.
		 * <p>
		 * This is a safety mechanism to prevent unbounded query results that could cause
		 * out-of-memory errors or performance issues. If a query would return more than
		 * this limit, an {@link org.sliceworkz.eventstore.spi.EventStorageException} is thrown.
		 * <p>
		 * The limit applies to all queries executed through this storage instance, including
		 * event stream queries and projections. Individual queries can specify lower limits,
		 * but cannot exceed this absolute limit.
		 *
		 * @param absoluteLimit the maximum number of events that can be returned from any query
		 * @return this Builder for method chaining
		 * @see org.sliceworkz.eventstore.query.Limit
		 */
		public Builder resultLimit ( int absoluteLimit ) {
			this.limit = Limit.to(absoluteLimit);
			return this;
		}

		/**
		 * Sets the database initialization mode.
		 * <p>
		 * Controls how the database schema is handled during startup. See {@link DatabaseInitMode}
		 * for a description of each mode. The default is {@link DatabaseInitMode#ENSURE}.
		 * <p>
		 * <strong>Example usage:</strong>
		 * <pre>{@code
		 * // Default: create missing objects, validate
		 * EventStorage storage = PostgresEventStorage.newBuilder().build();
		 *
		 * // Production: trust the DBA, skip all checks
		 * EventStorage storage = PostgresEventStorage.newBuilder()
		 *     .databaseInitMode(DatabaseInitMode.NONE)
		 *     .build();
		 *
		 * // Startup validation only
		 * EventStorage storage = PostgresEventStorage.newBuilder()
		 *     .databaseInitMode(DatabaseInitMode.VALIDATE)
		 *     .build();
		 *
		 * // Test environment: fresh schema every time
		 * EventStorage storage = PostgresEventStorage.newBuilder()
		 *     .databaseInitMode(DatabaseInitMode.INITIALIZE)
		 *     .build();
		 * }</pre>
		 *
		 * @param mode the database initialization mode
		 * @return this Builder for method chaining
		 * @see DatabaseInitMode
		 */
		public Builder databaseInitMode ( DatabaseInitMode mode ) {
			this.databaseInitMode = mode;
			return this;
		}

		/**
		 * Sets the database initialization mode to {@link DatabaseInitMode#VALIDATE}.
		 * <p>
		 * Validates that all required database objects exist and are correctly defined.
		 * No objects are created or modified.
		 * <p>
		 * This is a convenience method equivalent to
		 * {@code databaseInitMode(DatabaseInitMode.VALIDATE)}.
		 *
		 * @return this Builder for method chaining
		 * @see DatabaseInitMode#VALIDATE
		 * @see #databaseInitMode(DatabaseInitMode)
		 */
		public Builder validateDatabase ( ) {
			this.databaseInitMode = DatabaseInitMode.VALIDATE;
			return this;
		}

		/**
		 * Sets the database initialization mode to {@link DatabaseInitMode#ENSURE}.
		 * <p>
		 * Creates missing database objects if they do not exist, leaving existing objects
		 * untouched, then validates the schema. This is the default mode.
		 * <p>
		 * This is a convenience method equivalent to
		 * {@code databaseInitMode(DatabaseInitMode.ENSURE)}.
		 *
		 * @return this Builder for method chaining
		 * @see DatabaseInitMode#ENSURE
		 * @see #databaseInitMode(DatabaseInitMode)
		 */
		public Builder ensureDatabase ( ) {
			this.databaseInitMode = DatabaseInitMode.ENSURE;
			return this;
		}

		/**
		 * Sets the database initialization mode to {@link DatabaseInitMode#INITIALIZE}.
		 * <p>
		 * Drops all event store objects and recreates them from scratch.
		 * <strong>Warning:</strong> This is destructive — all existing event data will be lost.
		 * <p>
		 * This is a convenience method equivalent to
		 * {@code databaseInitMode(DatabaseInitMode.INITIALIZE)}.
		 *
		 * @return this Builder for method chaining
		 * @see DatabaseInitMode#INITIALIZE
		 * @see #databaseInitMode(DatabaseInitMode)
		 */
		public Builder initializeDatabase ( ) {
			this.databaseInitMode = DatabaseInitMode.INITIALIZE;
			return this;
		}

		/**
		 * Configures the Micrometer meter registry for collecting observability metrics.
		 * <p>
		 * The meter registry is used to track event store operations including event stream creation,
		 * append operations, and query performance. Additionally, if HikariCP datasources are used,
		 * they will be configured to publish connection pool metrics to this registry.
		 * <p>
		 * If not specified, defaults to {@code Metrics.globalRegistry}.
		 *
		 * @param meterRegistry the Micrometer meter registry to use for metrics collection
		 * @return this Builder instance for method chaining
		 * @see io.micrometer.core.instrument.MeterRegistry
		 * @see io.micrometer.core.instrument.Metrics#globalRegistry
		 */
		public Builder meterRegistry ( MeterRegistry meterRegistry ) {
			this.meterRegistry = meterRegistry;
			return this;
		}


		/**
		 * Builds and returns the configured {@link EventStorage} implementation.
		 * <p>
		 * This method creates a {@link PostgresEventStorageImpl} instance with all configured
		 * settings. If no custom DataSource was provided, it will be automatically created from
		 * the {@code db.properties} file using {@link DataSourceFactory}.
		 * <p>
		 * The configured {@link DatabaseInitMode} determines how the database schema is handled:
		 * <ul>
		 *   <li>{@link DatabaseInitMode#NONE}: No schema operations</li>
		 *   <li>{@link DatabaseInitMode#VALIDATE}: Schema validation only</li>
		 *   <li>{@link DatabaseInitMode#ENSURE}: Create missing objects, then validate (default)</li>
		 *   <li>{@link DatabaseInitMode#INITIALIZE}: Drop and recreate all objects, then validate</li>
		 * </ul>
		 * <p>
		 * The returned EventStorage can be passed to {@link EventStoreFactory#eventStore(EventStorage)}
		 * to create an EventStore instance.
		 *
		 * @return a configured EventStorage instance backed by PostgreSQL
		 * @throws RuntimeException if database configuration cannot be loaded or schema operations fail
		 * @see #buildStore()
		 * @see EventStoreFactory#eventStore(EventStorage)
		 */
		public EventStorage build ( ) {
			if ( dataSource == null ) {
				Properties dbProperties = DataSourceFactory.loadProperties();
				if ( dataSource == null ) {
					dataSource = DataSourceFactory.fromConfiguration(dbProperties, "pooled");
					monitoringDataSource = DataSourceFactory.fromConfiguration(dbProperties, "nonpooled");
				}
				if ( monitoringDataSource == null ) {
					monitoringDataSource = dataSource;
				}
			}

			if ( dataSource != null && dataSource instanceof HikariDataSource hds ) {
				try {
					hds.setMetricRegistry(meterRegistry);
				} catch (IllegalStateException e) {
					// already set
				}
			}
			if ( monitoringDataSource != null && monitoringDataSource instanceof HikariDataSource hds ) {
				try {
					hds.setMetricRegistry(meterRegistry);
				} catch (IllegalStateException e) {
					// already set
				}
			}

			boolean nativeUuidv7 = detectsNativeUuidv7Support(dataSource);

			PostgresEventStorageImpl result = nativeUuidv7
				? new PostgresEventStorageImpl(name, dataSource, monitoringDataSource, limit, prefix)
				: new PostgresLegacyEventStorageImpl(name, dataSource, monitoringDataSource, limit, prefix);

			switch ( databaseInitMode ) {
				case NONE       -> { }
				case VALIDATE   -> result.validateDatabase();
				case ENSURE     -> result.ensureDatabase();
				case INITIALIZE -> result.initializeDatabase();
			}
			// if we didn't fail until here, then we can start the executor threads
			result.start();
			return result;

		}

		/**
		 * Builds and returns a fully configured {@link EventStore} instance.
		 * <p>
		 * This is a convenience method that combines {@link #build()} with
		 * {@link EventStoreFactory#eventStore(EventStorage)} to create a ready-to-use
		 * EventStore in a single call.
		 * <p>
		 * Equivalent to:
		 * <pre>{@code
		 * EventStorage storage = builder.build();
		 * EventStore eventStore = EventStoreFactory.get().eventStore(storage);
		 * }</pre>
		 *
		 * @return a fully configured EventStore backed by PostgreSQL
		 * @throws RuntimeException if database configuration cannot be loaded or schema initialization fails
		 * @see #build()
		 * @see EventStoreFactory#eventStore(EventStorage)
		 */
		public EventStore buildStore ( ) {
			return EventStoreFactory.get().eventStore(build(), meterRegistry);
		}

		/**
		 * Borrows a connection to read the server major version and decides whether the
		 * native {@code uuidv7()} function is available. Falls back to legacy on any error.
		 * Logs the chosen implementation explicitly in every branch — search the logs for
		 * {@code uuidv7} to find which impl was selected.
		 * <p>
		 * When the legacy path is selected, this also verifies that the optional
		 * {@code com.github.f4b6a3:uuid-creator} dependency is on the classpath; if not
		 * the build fails fast with an {@link EventStorageException} explaining how to add it.
		 */
		private static boolean detectsNativeUuidv7Support ( DataSource dataSource ) {
			try ( Connection connection = dataSource.getConnection() ) {
				int majorVersion = connection.getMetaData().getDatabaseMajorVersion();
				if ( majorVersion >= FIRST_NATIVE_UUIDV7_MAJOR_VERSION ) {
					LOGGER.info("PostgreSQL major version {} detected — using native server-side uuidv7() via {}",
						majorVersion, PostgresEventStorageImpl.class.getSimpleName());
					return true;
				}
				ensureLegacyUuidv7DependencyAvailable("PostgreSQL major version " + majorVersion);
				LOGGER.info("PostgreSQL major version {} detected — using Java-side uuidv7 generation via {}",
					majorVersion, PostgresLegacyEventStorageImpl.class.getSimpleName());
				return false;
			} catch (SQLException e) {
				ensureLegacyUuidv7DependencyAvailable("PostgreSQL version detection failed");
				LOGGER.warn("PostgreSQL version detection failed — falling back to Java-side uuidv7 generation via {}",
					PostgresLegacyEventStorageImpl.class.getSimpleName(), e);
				return false;
			}
		}

		/**
		 * Verifies that the optional uuid-creator dependency required by the legacy
		 * implementation is available, throwing {@link EventStorageException} with a
		 * clear remediation message otherwise.
		 */
		private static void ensureLegacyUuidv7DependencyAvailable ( String reason ) {
			try {
				Class.forName("com.github.f4b6a3.uuid.UuidCreator");
			} catch (ClassNotFoundException e) {
				throw new EventStorageException(
					"%s — Java-side uuidv7 generation is required, but the optional 'com.github.f4b6a3:uuid-creator' dependency is missing from the classpath. "
					.formatted(reason)
					+ "Either add it to your application's build (see PostgresEventStorage Javadoc for the dependency snippet), "
					+ "or upgrade the PostgreSQL server to version " + FIRST_NATIVE_UUIDV7_MAJOR_VERSION + "+ for native uuidv7() support."
				);
			}
		}

	}

}
