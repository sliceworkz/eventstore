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
import java.time.Duration;
import java.util.Properties;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.MeterOptions;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.infra.postgres.shredding.PostgresShreddingKeyStore;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
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
 * // Test environment: fresh schema every time, closed when the block ends
 * try ( EventStore eventStore = PostgresEventStorage.newBuilder()
 *         .initializeDatabase()
 *         .buildStore() ) {
 *     ...
 * }
 * }</pre>
 *
 * <h2>Lifecycle:</h2>
 * This storage runs two LISTEN/NOTIFY monitor threads, each holding a JDBC connection, and — unless you
 * supply a {@link DataSource} yourself — the connection pools behind them. A store that lives as long as
 * the process needs no explicit shutdown; one created per tenant, per test or per reload must be
 * {@link EventStorage#close() closed}, or its threads, connections and pools stay alive for good: the
 * running monitor threads keep the storage reachable, so garbage collection will not clean up after you.
 * <p>
 * Closing an {@link EventStore} does not close a storage you handed to it — a storage can back several
 * stores and usually outlives them. The store returned by {@link Builder#buildStore()} is the exception:
 * it created the storage and returns no other handle on it, so closing it closes both. A DataSource you
 * passed in is never closed; one this builder created is. See {@link EventStorage#close()} for the full
 * contract.
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
 * <h2>Optional uuid-creator Dependency (PostgreSQL 16-17 only)</h2>
 * From PostgreSQL 18 onwards, event ids are generated server-side via the native
 * {@code uuidv7()} function. On older versions the library generates ids in Java using
 * {@code com.github.f4b6a3:uuid-creator}, which is therefore declared as an
 * <strong>optional</strong> Maven dependency.
 * <p>
 * Applications certain they will only ever connect to PostgreSQL 18+ may simply omit
 * this dependency from their build and the smaller dependency tree carries through.
 * Applications that may connect to PostgreSQL 16-17 must declare it explicitly:
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

		/**
		 * Oldest PostgreSQL major version this library supports.
		 * <p>
		 * The schema itself needs only 13 ({@code xid8}, {@code pg_current_xact_id()}), but nothing
		 * below 16 was ever exercised — and when it was, the conditional append turned out to have been
		 * failing outright on 15 and older for want of an alias on a {@code VALUES} subquery, which
		 * PostgreSQL made optional in 16. So 16 is both the oldest version with a support life worth
		 * committing to (13 went end-of-life in November 2025, 14 follows in November 2026, 15 in
		 * November 2027) and the oldest this library has ever actually worked on.
		 * <p>
		 * An older server is <em>warned about, not rejected</em>. Nothing is known to break on 13 or 14,
		 * and turning a library upgrade into a hard startup failure for someone running a working
		 * deployment would cost them more than the unsupported configuration does. The warning names the
		 * version so it shows up in the logs of exactly the deployments that need to plan an upgrade.
		 */
		static final int OLDEST_SUPPORTED_MAJOR_VERSION = 16;

		private String prefix = "";
		private String name = "psql";
		private DataSource dataSource;
		private DataSource monitoringDataSource;
		private DatabaseInitMode databaseInitMode = DatabaseInitMode.ENSURE;
		private Duration notificationStartupTimeout = PostgresEventStorageImpl.DEFAULT_NOTIFICATION_STARTUP_TIMEOUT;
		private Limit limit = Limit.none();
		private MeterRegistry meterRegistry = Metrics.globalRegistry;
		private MeterOptions meterOptions = MeterOptions.defaults();
		private ShreddingCodec shreddingCodec;
		private boolean shreddingOnOwnDataSource;
		private PostgresEventStorageImpl.ConditionalAppendPlanning conditionalAppendPlanning =
				PostgresEventStorageImpl.ConditionalAppendPlanning.SERVER_DEFAULT;
		private PostgresEventStorageImpl.ConditionalAppendCheck conditionalAppendCheck =
				PostgresEventStorageImpl.ConditionalAppendCheck.NOT_EXISTS;

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
		 * <p>
		 * When it is set, a query that carries no limit of its own is given {@code absoluteLimit + 1} as
		 * its {@code LIMIT}, so the read stays bounded and the extra row is what reveals the violation.
		 * <p>
		 * <b>Leaving it unset means queries are unbounded, not streamed.</b> A query is read in full
		 * before its {@link java.util.stream.Stream} is returned (see
		 * {@link org.sliceworkz.eventstore.stream.EventSource}), so a query with no limit of its own,
		 * against a storage with no absolute limit, issues a {@code SELECT} with no {@code LIMIT} and
		 * materialises every matching row in heap. Not setting this is the right choice when callers
		 * bound their own queries — as {@link org.sliceworkz.eventstore.projection.Projector} does, and
		 * as a paging loop does — and a way to run out of memory when they do not.
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
		 * How long {@link #build()} waits for LISTEN/NOTIFY to be established before failing.
		 * <p>
		 * The monitors never fail by themselves — they retry with backoff, forever — so this wait needs a
		 * deadline, or an unreachable database hangs application startup silently. On expiry {@code build()}
		 * closes the storage and throws {@link EventStorageException}: an event-sourced application that is
		 * not told when events are appended does not have a working store, it has one whose read models
		 * quietly stop advancing, so there is deliberately no option to start anyway.
		 * <p>
		 * The default,
		 * {@link PostgresEventStorageImpl#DEFAULT_NOTIFICATION_STARTUP_TIMEOUT} (10 seconds), suits a
		 * database that is up. Raise it where startup legitimately races the database coming up — several
		 * services restarting at once, a cold pool — since the penalty for being too impatient is a refused
		 * boot.
		 * <p>
		 * This is most often hit where the monitoring DataSource is configured separately from the main one,
		 * which is the normal arrangement: LISTEN/NOTIFY does not survive a transaction pooler, so a
		 * deployment whose pooled DataSource works and whose direct one is firewalled reaches exactly this
		 * code path — and used to hang there.
		 * <pre>{@code
		 * EventStorage storage = PostgresEventStorage.newBuilder()
		 *     .notificationStartupTimeout(Duration.ofSeconds(30))
		 *     .build();
		 * }</pre>
		 *
		 * @param timeout how long to wait in total for both monitors; {@code null} restores the default
		 * @return this Builder for method chaining
		 * @see PostgresEventStorageImpl#isNotificationsAvailable()
		 */
		public Builder notificationStartupTimeout ( Duration timeout ) {
			this.notificationStartupTimeout = timeout == null ? PostgresEventStorageImpl.DEFAULT_NOTIFICATION_STARTUP_TIMEOUT : timeout;
			return this;
		}

		/**
		 * Chooses how PostgreSQL may plan the DCB consistency check.
		 * <p>
		 * The check is a re-used prepared statement, so the server holds a <em>custom</em> plan built from
		 * the actual parameter values and a <em>generic</em> one built against default selectivity, and
		 * from the tenth execution it adopts the generic plan if its estimate looks no worse. A DCB check
		 * is the shape that misleads that comparison: its expected result is <em>no rows</em>, while a
		 * {@code NOT EXISTS} is priced by how soon a row is expected to turn up.
		 * <p>
		 * The mistake always favours the generic plan, so there is one remedy and it is
		 * {@link PostgresEventStorageImpl.ConditionalAppendPlanning#PER_APPEND}: for a filter of several
		 * OR-ed facts, each extra fact makes the generic plan look cheaper while the custom one, built
		 * from real tag statistics, looks dearer. Past the crossing the server settles on a plan that
		 * scans the events table for a row that is not there and never reconsiders; this plans every
		 * append from its own values instead.
		 * <p>
		 * Unconditional appends and every read are unaffected. This is not a general speed-up — where it
		 * changes no plan it costs up to 2.4× on a types-only filter — so turn it on for a store whose
		 * plans have been looked at, not on the strength of this javadoc.
		 * <pre>{@code
		 * EventStorage storage = PostgresEventStorage.newBuilder()
		 *     .conditionalAppendPlanning(ConditionalAppendPlanning.PER_APPEND)
		 *     .build();
		 * }</pre>
		 *
		 * @param planning the mode; {@code null} restores the default
		 * @return this Builder for method chaining
		 */
		public Builder conditionalAppendPlanning (
				PostgresEventStorageImpl.ConditionalAppendPlanning planning ) {
			this.conditionalAppendPlanning = planning == null
					? PostgresEventStorageImpl.ConditionalAppendPlanning.SERVER_DEFAULT
					: planning;
			return this;
		}

		/**
		 * Chooses which SQL shape the DCB consistency check is stated in. <b>Experimental</b> — this
		 * setting exists to be measured and may be removed; see
		 * {@link PostgresEventStorageImpl.ConditionalAppendCheck} for the two shapes and what each
		 * costs. The meaning of a consistency boundary is identical under both.
		 *
		 * @param check the shape; {@code null} restores the default ({@code NOT_EXISTS})
		 * @return this Builder for method chaining
		 */
		public Builder conditionalAppendCheck (
				PostgresEventStorageImpl.ConditionalAppendCheck check ) {
			this.conditionalAppendCheck = check == null
					? PostgresEventStorageImpl.ConditionalAppendCheck.NOT_EXISTS
					: check;
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
		 * Configures how much detail the meters of the store returned by {@link #buildStore()} may carry.
		 * <p>
		 * Defaults to {@link MeterOptions#defaults()}, which caps the {@code purpose} tag at
		 * {@link MeterOptions#DEFAULT_MAX_PURPOSE_TAG_VALUES} distinct values. Ignored by {@link #build()},
		 * which returns a storage rather than a store — pass the options to
		 * {@link org.sliceworkz.eventstore.EventStoreFactory#eventStore(EventStorage, MeterRegistry, MeterOptions)}
		 * there instead.
		 *
		 * @param meterOptions how much detail the store's meters may carry
		 * @return this Builder instance for method chaining
		 * @see MeterOptions
		 */
		public Builder meterOptions ( MeterOptions meterOptions ) {
			this.meterOptions = meterOptions;
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
		 * <p>
		 * The returned storage is already started: its LISTEN/NOTIFY monitor threads are running and
		 * holding connections. Close it with {@link EventStorage#close()} when done — and note that if no
		 * {@link #dataSource(DataSource)} was supplied, this method also creates the connection pools, and
		 * closing the storage is then the only thing that will ever close them.
		 *
		 * @return a configured EventStorage instance backed by PostgreSQL
		 * @throws RuntimeException if database configuration cannot be loaded or schema operations fail
		 * @see #buildStore()
		 * @see EventStoreFactory#eventStore(EventStorage)
		 */
		/**
		 * Protects the {@link org.sliceworkz.eventstore.shredding.Shreddable} values in this store's
		 * events, keeping the keys in this store's own database.
		 * <p>
		 * The recommended setup for PostgreSQL. Keys go into {@code <prefix>shredding_keys} on the same
		 * {@code DataSource} as the events, so the schema machinery creates and validates the table along
		 * with the others, and a minted key and the append that seals under it commit together.
		 * <pre>{@code
		 * try ( EventStore store = PostgresEventStorage.newBuilder()
		 *         .prefix("acme_")
		 *         .shredding()
		 *         .buildStore() ) {
		 *     …
		 *     store.erase(DataSubject.of("customer", "alice-42"), ErasureReason.of("art.17 request #4711"));
		 * }
		 * }</pre>
		 * Without shredding configured, registering an event type that declares a {@code Shreddable}
		 * component fails at stream creation rather than storing personal data in the clear.
		 * <p>
		 * Note what colocation means for your threat model: an attacker with the database has both the
		 * ciphertext and the keys. What crypto-shredding still buys, and buys unconditionally, is that a
		 * <em>completed erasure</em> holds everywhere the ciphertext has already spread — old backups,
		 * write-ahead logs, replicas — which nulling a column never did. Where the keys must also be out
		 * of reach of whoever holds the database, pass a key store backed by a KMS or an HSM instead.
		 *
		 * @return this builder for method chaining
		 * @see org.sliceworkz.eventstore.infra.postgres.shredding.PostgresShreddingKeyStore
		 */
		public Builder shredding ( ) {
			this.shreddingOnOwnDataSource = true;
			return this;
		}

		/**
		 * Protects personal data with the shipped AES-256-GCM codec, holding keys in the given key store.
		 * <p>
		 * Use this to keep the encryption but put the keys somewhere else — Vault, a cloud KMS, an HSM,
		 * another database. The key store is the caller's to close, following the same rule this builder
		 * applies to a {@code DataSource}: what you pass in, you own.
		 *
		 * @param shreddingKeyStore where keys are minted, resolved and destroyed
		 * @return this builder for method chaining
		 */
		public Builder shredding ( ShreddingKeyStore shreddingKeyStore ) {
			this.shreddingCodec = AesGcmShreddingCodec.over(shreddingKeyStore);
			this.shreddingOnOwnDataSource = false;
			return this;
		}

		/**
		 * Protects personal data with a codec of your own, taking over encryption as well as key storage.
		 * <p>
		 * The seam for a codec that keeps key material inside an HSM and never lets it reach this JVM.
		 *
		 * @param shreddingCodec seals and unseals protected values
		 * @return this builder for method chaining
		 */
		public Builder shredding ( ShreddingCodec shreddingCodec ) {
			this.shreddingCodec = shreddingCodec;
			this.shreddingOnOwnDataSource = false;
			return this;
		}

		public EventStorage build ( ) {
			// a DataSource we create here belongs to the storage, and is closed by EventStorage.close();
			// one the caller passed in stays the caller's, and is never touched
			boolean createdDataSources = false;
			if ( dataSource == null ) {
				Properties dbProperties = DataSourceFactory.loadProperties();
				if ( dataSource == null ) {
					dataSource = DataSourceFactory.fromConfiguration(dbProperties, "pooled");
					monitoringDataSource = DataSourceFactory.fromConfiguration(dbProperties, "nonpooled");
					createdDataSources = true;
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

			try {
				boolean nativeUuidv7 = detectsNativeUuidv7Support(dataSource);

				PostgresEventStorageImpl result = nativeUuidv7
					? new PostgresEventStorageImpl(name, dataSource, monitoringDataSource, limit, prefix, createdDataSources, meterRegistry)
					: new PostgresLegacyEventStorageImpl(name, dataSource, monitoringDataSource, limit, prefix, createdDataSources, meterRegistry);

				result.setConditionalAppendPlanning(conditionalAppendPlanning);
				result.setConditionalAppendCheck(conditionalAppendCheck);

				switch ( databaseInitMode ) {
					case NONE       -> { }
					case VALIDATE   -> result.validateDatabase();
					case ENSURE     -> result.ensureDatabase();
					case INITIALIZE -> result.initializeDatabase();
				}
				// if we didn't fail until here, then we can start the executor threads. The wait for their
				// LISTEN is bounded: they retry forever rather than failing, so an unbounded wait here is
				// how an unreachable database used to hang startup with nothing logged
				result.start(notificationStartupTimeout);
				return result;
			} catch (RuntimeException e) {
				// version detection or schema handling failed: don't strand the pools we just created
				if ( createdDataSources ) {
					closeQuietly(dataSource);
					if ( monitoringDataSource != dataSource ) {
						closeQuietly(monitoringDataSource);
					}
				}
				throw e;
			}

		}

		private static void closeQuietly ( DataSource dataSource ) {
			if ( dataSource instanceof AutoCloseable closeable ) {
				try {
					closeable.close();
				} catch (Exception e) {
					LOGGER.warn("failed to close a DataSource after a failed build(): {}", e.getMessage(), e);
				}
			}
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
		 * <p>
		 * The returned EventStore is the only handle on the storage this creates, so it is also the only
		 * way to shut it down: {@link EventStore#close()} stops the monitor threads and closes the
		 * connection pools created here. Use try-with-resources unless the store is meant to live as long
		 * as the process.
		 *
		 * @return a fully configured EventStore backed by PostgreSQL
		 * @throws RuntimeException if database configuration cannot be loaded or schema initialization fails
		 * @see #build()
		 * @see EventStoreFactory#eventStore(EventStorage)
		 */
		public EventStore buildStore ( ) {
			// the storage is created here and never handed to the caller, so the returned store owns it:
			// closing that store is the only way this storage will ever be closed
			EventStorage eventStorage = build();
			// build() resolves the DataSource, so a key store on "this store's own database" can only be
			// created afterwards. It shares that DataSource and never closes it -- the storage does.
			ShreddingCodec codec = shreddingOnOwnDataSource
					? AesGcmShreddingCodec.over(PostgresShreddingKeyStore.on(dataSource, prefix))
					: shreddingCodec;
			return EventStore.owning(EventStoreFactory.get().eventStore(eventStorage, meterRegistry, meterOptions, codec), eventStorage);
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
				warnIfUnsupportedVersion(majorVersion);
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
		 * Logs a warning when the server is older than {@link #OLDEST_SUPPORTED_MAJOR_VERSION}.
		 * <p>
		 * Deliberately not an exception: see the constant's javadoc for why an unsupported version is
		 * reported rather than refused.
		 *
		 * @param majorVersion the server's major version, as reported by the driver
		 */
		private static void warnIfUnsupportedVersion ( int majorVersion ) {
			if ( majorVersion < OLDEST_SUPPORTED_MAJOR_VERSION ) {
				LOGGER.warn("PostgreSQL major version {} is older than the oldest supported version {} — "
					+ "this configuration is untested and unsupported; plan an upgrade",
					majorVersion, OLDEST_SUPPORTED_MAJOR_VERSION);
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
