/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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

import java.util.Properties;

import javax.sql.DataSource;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;

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
 * // Using default configuration from db.properties file
 * EventStorage storage = PostgresEventStorage.newBuilder()
 *     .initializeDatabase()
 *     .build();
 * EventStore eventStore = EventStoreFactory.get().eventStore(storage);
 *
 * // Or use convenience method
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
 *     .initializeDatabase()
 *     .build();
 * EventStore eventStore = EventStoreFactory.get().eventStore(storage);
 * }</pre>
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
	 *   <li><strong>initializeDatabase</strong>: false (database schema not created automatically)</li>
	 *   <li><strong>checkDatabase</strong>: true (database schema checked upon startup, after initialization if the latter is enabled)</li>
	 *   <li><strong>resultLimit</strong>: none (no absolute limit)</li>
	 * </ul>
	 * <p>
	 * Use the builder methods to customize these settings before calling {@link #build()} or {@link #buildStore()}.
	 *
	 * @see #build()
	 * @see #buildStore()
	 */
	public static class Builder {
		
		private String prefix = "";
		private String name = "psql";
		private DataSource dataSource;
		private DataSource monitoringDataSource;
		private boolean initializeDatabase = false;
		private boolean checkDatabase = true;
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
		 * Enables automatic database schema initialization when the storage is built.
		 * <p>
		 * Equivalent to calling {@link #initializeDatabase(boolean) initializeDatabase(true)}.
		 * <p>
		 * When enabled, the builder will execute the initialization SQL script to create all
		 * required database tables, indexes, triggers, and functions. This should typically be
		 * enabled on first deployment or when setting up test environments.
		 * <p>
		 * The initialization is idempotent and safe to run multiple times. Existing tables
		 * and data will not be affected.
		 * <p>
		 * <strong>Note:</strong> Ensure the database user has sufficient privileges to create
		 * tables, functions, and triggers.
		 *
		 * @return this Builder for method chaining
		 * @see #initializeDatabase(boolean)
		 */
		public Builder initializeDatabase ( ) {
			return initializeDatabase(true);
		}
		
		/**
		 * Controls automatic database schema initialization when the storage is built.
		 * <p>
		 * When set to {@code true}, the builder will execute the initialization SQL script to
		 * create all required database tables, indexes, triggers, and functions. This should
		 * typically be enabled on first deployment or when setting up test environments.
		 * <p>
		 * The initialization is idempotent and safe to run multiple times. Existing tables
		 * and data will not be affected.
		 * <p>
		 * Tables and structures created include:
		 * <ul>
		 *   <li>{@code PREFIX_events}: Main event storage table</li>
		 *   <li>{@code PREFIX_bookmarks}: Event consumer position tracking</li>
		 *   <li>Indexes on frequently queried columns</li>
		 *   <li>Triggers for NOTIFY on event appends and bookmark updates</li>
		 * </ul>
		 * <p>
		 * <strong>Note:</strong> Ensure the database user has sufficient privileges to create
		 * tables, functions, and triggers.
		 *
		 * @param value {@code true} to initialize the database, {@code false} to skip initialization
		 * @return this Builder for method chaining
		 */
		public Builder initializeDatabase ( boolean value ) {
			this.initializeDatabase = value;
			return this;
		}

		/**
		 * Controls automatic database schema validation when the storage is built.
		 * <p>
		 * When set to {@code true} (the default), the builder will validate that all required
		 * database objects exist and are properly formed before the storage instance is returned.
		 * This provides early detection of schema issues and prevents runtime failures.
		 * <p>
		 * The validation checks include:
		 * <ul>
		 *   <li><strong>Tables:</strong> {@code PREFIX_events} and {@code PREFIX_bookmarks} exist</li>
		 *   <li><strong>Columns:</strong> All required columns exist with correct types and nullability</li>
		 *   <li><strong>Indexes:</strong> All performance-critical indexes are present</li>
		 *   <li><strong>Functions:</strong> PostgreSQL notification functions exist</li>
		 *   <li><strong>Triggers:</strong> Event and bookmark notification triggers are properly configured</li>
		 *   <li><strong>Constraints:</strong> Foreign key constraints are in place</li>
		 * </ul>
		 * <p>
		 * If any required database object is missing or malformed, an
		 * {@link org.sliceworkz.eventstore.spi.EventStorageException} is thrown with a detailed
		 * explanation of what is wrong. All validation activity is logged at DEBUG level for
		 * troubleshooting.
		 * <p>
		 * <strong>When to disable:</strong><br>
		 * Schema validation can be disabled ({@code checkDatabase(false)}) in scenarios where:
		 * <ul>
		 *   <li>You want to defer validation to a later time</li>
		 *   <li>The database user lacks permissions to query information_schema tables</li>
		 *   <li>You're using a custom schema that intentionally differs from the standard</li>
		 *   <li>You need to minimize startup time in performance-critical scenarios</li>
		 * </ul>
		 * <p>
		 * <strong>Execution order:</strong><br>
		 * If both {@link #initializeDatabase()} and {@code checkDatabase()} are enabled, the
		 * initialization runs first, followed immediately by validation to ensure the schema
		 * was created correctly.
		 * <p>
		 * <strong>Example usage:</strong>
		 * <pre>{@code
		 * // Validate existing schema (default behavior)
		 * EventStorage storage = PostgresEventStorage.newBuilder()
		 *     .checkDatabase(true)  // explicit, but this is the default
		 *     .build();
		 *
		 * // Initialize and validate in one step
		 * EventStorage storage = PostgresEventStorage.newBuilder()
		 *     .initializeDatabase()
		 *     .checkDatabase(true)  // validates the newly created schema
		 *     .build();
		 *
		 * // Skip validation (not recommended for production)
		 * EventStorage storage = PostgresEventStorage.newBuilder()
		 *     .checkDatabase(false)
		 *     .build();
		 * }</pre>
		 *
		 * @param value {@code true} to validate the database schema (default), {@code false} to skip validation
		 * @return this Builder for method chaining
		 * @throws org.sliceworkz.eventstore.spi.EventStorageException if validation is enabled and schema is invalid
		 * @see #initializeDatabase(boolean)
		 * @see PostgresEventStorageImpl#checkDatabase()
		 */
		public Builder checkDatabase ( boolean value ) {
			this.checkDatabase = value;
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
		 * If {@link #initializeDatabase()} was called, the database schema will be initialized
		 * before returning the storage instance.
		 * <p>
		 * The returned EventStorage can be passed to {@link EventStoreFactory#eventStore(EventStorage)}
		 * to create an EventStore instance.
		 *
		 * @return a configured EventStorage instance backed by PostgreSQL
		 * @throws RuntimeException if database configuration cannot be loaded or schema initialization fails
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
			
			var result = new PostgresEventStorageImpl(name, dataSource, monitoringDataSource, limit, prefix);
			if ( initializeDatabase ) {
				result.initializeDatabase();
			}
			if ( checkDatabase ) {
				result.checkDatabase();
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
		
	}

}
