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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.MeterRegistry;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventFilterItem;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventImportConflictException;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL-backed implementation of the {@link EventStorage} interface.
 * <p>
 * This is the internal implementation class used by {@link PostgresEventStorage}. It provides
 * a production-ready event storage backend with the following characteristics:
 * <ul>
 *   <li><strong>JDBC-based persistence</strong>: Events are stored in PostgreSQL tables with optimized indexing</li>
 *   <li><strong>Optimistic locking</strong>: DCB-compliant concurrency control via conditional inserts</li>
 *   <li><strong>Real-time notifications</strong>: PostgreSQL LISTEN/NOTIFY for event-driven architectures</li>
 *   <li><strong>Virtual thread support</strong>: Uses Java 21+ virtual threads for efficient monitoring</li>
 *   <li><strong>Connection pooling</strong>: Leverages HikariCP for high-performance connection management</li>
 *   <li><strong>Table prefixing</strong>: Supports multi-tenancy via configurable table name prefixes</li>
 * </ul>
 * <p>
 * This implementation is thread-safe and designed for high-concurrency environments. Multiple
 * event streams can be accessed concurrently without coordination.
 * <p>
 * <strong>Internal Architecture:</strong><br>
 * The implementation uses two background virtual threads for monitoring PostgreSQL notifications:
 * <ul>
 *   <li>{@code NewEventsAppendedMonitor}: Listens for event append notifications on the
 *       {@code PREFIX_event_appended} channel</li>
 *   <li>{@code BookmarkPlacedMonitor}: Listens for bookmark update notifications on the
 *       {@code PREFIX_bookmark_placed} channel</li>
 * </ul>
 * These monitors enable eventually-consistent event processing without polling.
 * <p>
 * <strong>Database Schema:</strong><br>
 * The implementation expects the following tables (where PREFIX_ is the configured prefix):
 * <ul>
 *   <li>{@code PREFIX_events}: Main event storage with columns for stream context, purpose, type,
 *       timestamp, data, and tags</li>
 *   <li>{@code PREFIX_bookmarks}: Consumer position tracking with reader name and event reference</li>
 * </ul>
 * <p>
 * <strong>Performance Characteristics:</strong>
 * <ul>
 *   <li>Event appends: O(1) with optimistic locking check, single roundtrip</li>
 *   <li>Event queries: O(log n) for indexed columns (position, stream, type), O(n) for tag filters</li>
 *   <li>Bookmark operations: O(1) upsert with unique constraint</li>
 * </ul>
 * <p>
 * This class is not intended to be instantiated directly. Use {@link PostgresEventStorage.Builder}
 * to create instances.
 *
 * @see PostgresEventStorage
 * @see EventStorage
 * @see org.sliceworkz.eventstore.EventStore
 */
public class PostgresEventStorageImpl implements EventStorage {

	private static final Logger LOGGER = LoggerFactory.getLogger(PostgresEventStorageImpl.class);

	private final String name;
	private final String prefix;
	private final DataSource dataSource;
	private final DataSource monitoringDataSource;
	private final Limit absoluteLimit;

	/**
	 * Whether the DataSources were created by {@link PostgresEventStorage.Builder} rather than supplied
	 * by the caller, and must therefore be closed by {@link #close()}. A caller-supplied pool is never
	 * touched — closing someone else's pool is worse than leaking our own.
	 */
	private final boolean ownsDataSources;

	// Strong references, released only by unsubscribe(). Held weakly, a listener whose registrant stopped
	// referencing it would vanish at the next GC and take its notifications with it, silently -- see
	// EventStorage.subscribe(). CopyOnWriteArrayList because the monitor threads walk this on every
	// notification and must never block on a subscription.
	private final CopyOnWriteArrayList<EventStoreListener> listeners = new CopyOnWriteArrayList<>();
	private final ExecutorService executorService;
	private final AtomicBoolean stopped = new AtomicBoolean();

	/**
	 * Whether each monitor currently holds a live {@code LISTEN}. Flipped on by the monitor once the
	 * statement has succeeded and off again the moment its connection fails, so it tracks the state of
	 * notification delivery over the whole life of the storage and not just at startup — a database that
	 * goes away an hour after boot leaves exactly the same silence as one that was never there.
	 */
	private final AtomicBoolean eventMonitorListening = new AtomicBoolean();
	private final AtomicBoolean bookmarkMonitorListening = new AtomicBoolean();

	/**
	 * The latches {@link #start()} is waiting on, so that {@link #close()} can release a caller blocked in
	 * there. Without this a {@code start()} that timed out generously — or one racing a close — would stay
	 * parked after the monitors it is waiting for have already been told to stop and will never count
	 * anything down.
	 */
	private volatile CountDownLatch eventMonitorReady;
	private volatile CountDownLatch bookmarkMonitorReady;

	private final MeterRegistry meterRegistry;

	private static final JsonMapper JSONMAPPER = JsonMapper.builder().build();

	/**
	 * How long a single {@code getNotifications} call blocks before returning empty-handed.
	 * <p>
	 * This is a polling slice, not a deadline: the monitors loop on it, so it costs one parked socket
	 * read per interval and nothing else, and notification delivery is unaffected — a notification
	 * returns the call immediately. It is short because it doubles as how quickly a monitor notices it
	 * has been stopped: the monitor then finishes on its own, UNLISTENs, and hands its connection back
	 * to the pool intact. Interrupting it instead would be faster, but it closes the socket underneath
	 * the driver, so the pool discards the connection and logs a stack trace about it on every shutdown.
	 */
	public static final int WAIT_FOR_NOTIFICATIONS_TIMEOUT = 100;

	/**
	 * How long {@link #close()} waits for the monitors to finish by themselves before resorting to
	 * interrupting them. Comfortably more than {@link #WAIT_FOR_NOTIFICATIONS_TIMEOUT} plus an UNLISTEN
	 * round trip, so the tidy path is the one that normally happens.
	 */
	private static final long GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS = 2_000;

	/**
	 * How long {@link #close()} waits for the monitor threads to finish after interrupting them, before
	 * giving up and logging. Only reached when a monitor is wedged somewhere it did not notice being
	 * stopped — inside a listener callback that ignores interruption, for instance.
	 */
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

	private static final long INITIAL_RETRY_DELAY_MS = 1_000;
	private static final long MAX_RETRY_DELAY_MS = 30_000;

	/**
	 * How long {@link #start()} waits for the monitors to register their {@code LISTEN} before deciding
	 * they are not going to, when no other timeout has been configured.
	 * <p>
	 * Expiry fails the startup, so this errs generous: a database that is up answers in milliseconds, and
	 * the cost of being too impatient with one that is merely slow — a cold pool, a connection storm on a
	 * simultaneous restart — is an application that refuses to boot. It still has to be a deadline, since
	 * the monitors themselves have none.
	 */
	public static final Duration DEFAULT_NOTIFICATION_STARTUP_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * The slice {@link #start()} waits in, so that it notices a concurrent {@link #close()} promptly even
	 * when the configured timeout is long. The latches are also counted down by {@code close()}; this is
	 * the belt to that pair of braces.
	 */
	private static final long START_POLL_SLICE_MILLIS = 100;

	private static final int MAX_PREFIX_LENGTH = 32;

	/**
	 * Constructs a new PostgreSQL-backed event storage instance with observability support.
	 * <p>
	 * This constructor is package-private and should not be called directly. Use
	 * {@link PostgresEventStorage.Builder} to create instances.
	 * <p>
	 * The constructor initializes:
	 * <ul>
	 *   <li>Virtual thread executors for PostgreSQL LISTEN/NOTIFY monitoring</li>
	 *   <li>Background monitors for event append and bookmark notifications</li>
	 * </ul>
	 *
	 * @param name the logical name for this storage instance (used in logging and monitoring)
	 * @param dataSource the main JDBC DataSource for event operations
	 * @param monitoringDataSource the JDBC DataSource for LISTEN/NOTIFY operations
	 * @param absoluteLimit the absolute limit on query results, or {@link Limit#none()} for no limit
	 * @param prefix the table name prefix (validated, or empty string for no prefix)
	 * @see PostgresEventStorage.Builder#build()
	 */
	public PostgresEventStorageImpl ( String name, DataSource dataSource, DataSource monitoringDataSource, Limit absoluteLimit, String prefix ) {
		this(name, dataSource, monitoringDataSource, absoluteLimit, prefix, false);
	}

	/**
	 * Constructs a new PostgreSQL-backed event storage instance, stating who owns the DataSources.
	 * <p>
	 * This constructor is used by {@link PostgresEventStorage.Builder} and should not be called
	 * directly. It exists so that {@link #close()} can close a pool the builder created without ever
	 * closing one the caller supplied.
	 *
	 * @param name the logical name for this storage instance (used in logging and monitoring)
	 * @param dataSource the main JDBC DataSource for event operations
	 * @param monitoringDataSource the JDBC DataSource for LISTEN/NOTIFY operations
	 * @param absoluteLimit the absolute limit on query results, or {@link Limit#none()} for no limit
	 * @param prefix the table name prefix (validated, or empty string for no prefix)
	 * @param ownsDataSources {@code true} if the DataSources were created for this storage and should
	 *                        be closed by {@link #close()}; {@code false} if they belong to the caller
	 * @see PostgresEventStorage.Builder#build()
	 */
	public PostgresEventStorageImpl ( String name, DataSource dataSource, DataSource monitoringDataSource, Limit absoluteLimit, String prefix, boolean ownsDataSources ) {
		this(name, dataSource, monitoringDataSource, absoluteLimit, prefix, ownsDataSources, Metrics.globalRegistry);
	}

	/**
	 * Constructs a new PostgreSQL-backed event storage instance, stating who owns the DataSources and
	 * where the notification-availability meters go.
	 * <p>
	 * This constructor is used by {@link PostgresEventStorage.Builder} and should not be called directly.
	 *
	 * @param name the logical name for this storage instance (used in logging and monitoring)
	 * @param dataSource the main JDBC DataSource for event operations
	 * @param monitoringDataSource the JDBC DataSource for LISTEN/NOTIFY operations
	 * @param absoluteLimit the absolute limit on query results, or {@link Limit#none()} for no limit
	 * @param prefix the table name prefix (validated, or empty string for no prefix)
	 * @param ownsDataSources {@code true} if the DataSources were created for this storage and should
	 *                        be closed by {@link #close()}; {@code false} if they belong to the caller
	 * @param meterRegistry where to register the {@code sliceworkz.eventstore.notifications.*} meters
	 * @see PostgresEventStorage.Builder#build()
	 */
	public PostgresEventStorageImpl ( String name, DataSource dataSource, DataSource monitoringDataSource, Limit absoluteLimit, String prefix, boolean ownsDataSources, MeterRegistry meterRegistry ) {
		this.prefix = validatePrefix(prefix);
		this.name = name;
		this.dataSource = dataSource;
		this.monitoringDataSource = monitoringDataSource;
		this.absoluteLimit = absoluteLimit;
		this.ownsDataSources = ownsDataSources;
		this.meterRegistry = meterRegistry == null ? Metrics.globalRegistry : meterRegistry;

		this.executorService = Executors.newVirtualThreadPerTaskExecutor();

		registerNotificationMeters();
	}

	/**
	 * Publishes notification availability as a gauge, one series per channel, so that a storage whose
	 * monitors are down is visible without holding a reference to it and downcasting.
	 * <p>
	 * Registered in the constructor rather than in {@link #start()}, so the series exists — reading 0 —
	 * from the moment the storage does. A gauge that only appears once notifications work is no use for
	 * alerting on notifications not working.
	 */
	private void registerNotificationMeters ( ) {
		io.micrometer.core.instrument.Tags baseTags = io.micrometer.core.instrument.Tags.of("storage", name == null ? "" : name);
		meterRegistry.gauge("sliceworkz.eventstore.notifications.up", baseTags.and("channel", "event_appended"),
			eventMonitorListening, up -> up.get() ? 1d : 0d);
		meterRegistry.gauge("sliceworkz.eventstore.notifications.up", baseTags.and("channel", "bookmark_placed"),
			bookmarkMonitorListening, up -> up.get() ? 1d : 0d);
	}

	/**
	 * Whether both LISTEN/NOTIFY monitors currently hold a live registration, i.e. whether appends and
	 * bookmark placements are reaching subscribers.
	 * <p>
	 * {@code false} means the storage is <em>degraded</em>, not broken: queries, appends and bookmarks all
	 * go through the main {@code DataSource} and keep working, but nothing wakes a subscriber, so
	 * projections only advance when run explicitly. The monitors retry with backoff, so this comes back to
	 * {@code true} on its own once the database is reachable again.
	 * <p>
	 * Intended for health endpoints. The same state is published as the
	 * {@code sliceworkz.eventstore.notifications.up} gauge, which needs no downcast from
	 * {@link EventStorage}.
	 *
	 * @return {@code true} if both monitors are listening
	 */
	public boolean isNotificationsAvailable ( ) {
		return eventMonitorListening.get() && bookmarkMonitorListening.get();
	}
	
	static String validatePrefix(String prefix) {
		if (prefix == null) {
			throw new IllegalArgumentException("Prefix cannot be null");
		}
		
		// Empty is OK, otherwise more complex rules apply to keep SQL sane and to avoid SQL injection
		if ( ! prefix.isEmpty() ) {
			
			if (!prefix.matches("^[a-zA-Z0-9_]+_$")) {
				throw new IllegalArgumentException("Invalid prefix: '" + prefix + "'. "
						+ "Prefix must contain only alphanumeric characters and underscores, "
						+ "and must end with an underscore (e.g., 'tenant1_')");
			}
	
			if (prefix.length() > MAX_PREFIX_LENGTH) {
				throw new IllegalArgumentException("Prefix too long (max {} characters): {}".formatted(MAX_PREFIX_LENGTH, prefix));
			}
			
		}
		
		return prefix;
	}
	
	/**
	 * Validates that all required database objects exist and are correctly defined.
	 * <p>
	 * This is equivalent to using {@link DatabaseInitMode#VALIDATE}. No objects are created
	 * or modified. Throws {@link EventStorageException} if any required object is missing
	 * or malformed.
	 *
	 * @return this instance for method chaining
	 * @throws EventStorageException if the schema is invalid
	 */
	public PostgresEventStorageImpl validateDatabase ( ) {
		checkDatabase();
		return this;
	}

	/**
	 * Creates missing database objects if they do not exist, leaving existing objects untouched,
	 * then validates the schema.
	 * <p>
	 * This is equivalent to using {@link DatabaseInitMode#ENSURE}. It is safe to run repeatedly.
	 * If an existing object has an incompatible definition, the subsequent validation will
	 * detect and report it.
	 *
	 * @return this instance for method chaining
	 * @throws EventStorageException if schema creation or validation fails
	 */
	public PostgresEventStorageImpl ensureDatabase ( ) {
		LOGGER.info("Ensuring database schema for prefix '{}'", prefix);
		executeSqlScripts("ensure-schema.sql");
		checkDatabase();
		return this;
	}

	/**
	 * Drops all event store objects and recreates them from scratch, then validates the schema.
	 * <p>
	 * This is equivalent to using {@link DatabaseInitMode#INITIALIZE}.
	 * <p>
	 * <strong>Warning:</strong> This is destructive — all existing event data will be lost.
	 *
	 * @return this instance for method chaining
	 * @throws EventStorageException if schema initialization or validation fails
	 */
	public PostgresEventStorageImpl initializeDatabase ( ) {
		LOGGER.info("Initializing database schema for prefix '{}' (drop and recreate)", prefix);
		executeSqlScripts("drop-schema.sql", "ensure-schema.sql");
		checkDatabase();
		return this;
	}

	/**
	 * Runs the given schema scripts as one transaction, holding {@link #schemaLockKey the schema
	 * advisory lock} for its duration.
	 * <p>
	 * <b>Why the lock.</b> {@code CREATE TABLE / INDEX / EXTENSION IF NOT EXISTS} is not atomic against a
	 * concurrent creator: the existence check and the catalog insert are separate steps, so several
	 * instances starting together on a database that does not have the schema yet all find an object
	 * absent and all try to create it. The losers fail on a system catalog's unique index
	 * ({@code pg_type_typname_nsp_index}, {@code pg_class_relname_nsp_index}) and, since the script is
	 * one transaction, roll back entirely and fail to start. Measured before this lock existed: 64 of 80
	 * instances failed, on PostgreSQL 17 and 18 alike. {@code CREATE OR REPLACE FUNCTION} racing itself
	 * has the same problem, reported as {@code tuple concurrently updated}.
	 * <p>
	 * <b>Why all scripts in one transaction.</b> {@code INITIALIZE} runs drop-then-ensure. Split across
	 * two transactions it releases the lock in between, so a second instance can drop the schema the
	 * first has just recreated, and the first's {@link #checkDatabase()} then fails against a database
	 * that is momentarily empty. One transaction makes the whole drop-and-recreate indivisible.
	 *
	 * @param scriptNames classpath resources to run, in order
	 */
	private void executeSqlScripts ( String... scriptNames ) {
		String running = scriptNames.length == 0 ? "" : scriptNames[0];
		try ( Connection writeConnection = dataSource.getConnection() ) {
			writeConnection.setAutoCommit(false);
			try {
				try ( PreparedStatement lock = writeConnection.prepareStatement(ACQUIRE_SCHEMA_LOCK) ) {
					lock.setLong(1, schemaLockKey());
					lock.execute();
				}
				for ( String scriptName : scriptNames ) {
					running = scriptName;
					try ( Statement statement = writeConnection.createStatement() ) {
						statement.execute(readSqlScript(scriptName));
					}
				}
				writeConnection.commit();
			} catch (IOException | SQLException e) {
				try {
					writeConnection.rollback();
				} catch (SQLException rollbackEx) {
					e.addSuppressed(rollbackEx);
				}
				throw e;
			}
		} catch (IOException | SQLException e) {
			throw new EventStorageException("Failed to execute database script: %s".formatted(running), e);
		}
	}

	/** Reads a schema script from the classpath and applies the table prefix to it. */
	private String readSqlScript ( String scriptName ) throws IOException {
		try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(scriptName)) {
			if (inputStream == null) {
				throw new EventStorageException("Could not find %s in classpath".formatted(scriptName));
			}
			return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("PREFIX_", prefix);
		}
	}

	PostgresEventStorageImpl checkDatabase ( ) {
		LOGGER.info("Starting database schema validation for prefix '{}'", prefix);

		try ( Connection readConnection = dataSource.getConnection() ) {
			// Check events table
			checkEventsTable(readConnection);

			// Check bookmarks table
			checkBookmarksTable(readConnection);

			// Check functions
			checkFunction(readConnection, prefix + "notify_event_appended");
			checkFunction(readConnection, prefix + "notify_bookmark_placed");

			// Check triggers
			// STATEMENT for events: one notification per stream per statement, not one per row.
			// ROW for bookmarks: a bookmark upsert is always a single row, so the two are the same there.
			checkTrigger(readConnection, prefix + "events", "table_insert_trigger", "STATEMENT");
			checkTrigger(readConnection, prefix + "bookmarks", "table_insert_or_update_trigger", "ROW");

			// Check indexes
			checkIndex(readConnection, prefix + "idx_events_position_brin");
			checkIndex(readConnection, prefix + "idx_events_stream_type_position");
			checkIndex(readConnection, prefix + "idx_events_tags");
			checkIndex(readConnection, prefix + "idx_events_stream_tags");
			checkIndex(readConnection, prefix + "idx_events_stream_position");
			checkIndex(readConnection, idempotencyIndexName());
			checkIndex(readConnection, prefix + "idx_bookmarks_event_id");

			LOGGER.info("Database schema validation completed successfully for prefix '{}'", prefix);

		} catch (SQLException e) {
			throw new EventStorageException("Failed to validate database schema", e);
		}
		return this;
	}

	private void checkEventsTable(Connection connection) throws SQLException {
		String tableName = prefix + "events";
		LOGGER.debug("Checking table: {}", tableName);

		if (!tableExists(connection, tableName)) {
			throw new EventStorageException("Required table '%s' does not exist".formatted(tableName));
		}

		// Check required columns with their types
		checkColumn(connection, tableName, "event_position", "bigserial", false);
		checkColumn(connection, tableName, "event_tx", "xid8", false);
		checkColumn(connection, tableName, "event_id", "uuid", false);
		checkColumn(connection, tableName, "idempotency_key", "text", true);
		checkColumn(connection, tableName, "stream_context", "text", false);
		checkColumn(connection, tableName, "stream_purpose", "text", false);
		checkColumn(connection, tableName, "event_type", "text", false);
		checkColumn(connection, tableName, "event_timestamp", "timestamp with time zone", true);
		checkColumn(connection, tableName, "event_data", "jsonb", false);
		checkColumn(connection, tableName, "event_erasable_data", "jsonb", true);
		checkColumn(connection, tableName, "event_tags", "ARRAY", true);

		LOGGER.debug("Table {} validated successfully", tableName);
	}

	private void checkBookmarksTable(Connection connection) throws SQLException {
		String tableName = prefix + "bookmarks";
		LOGGER.debug("Checking table: {}", tableName);

		if (!tableExists(connection, tableName)) {
			throw new EventStorageException("Required table '%s' does not exist".formatted(tableName));
		}

		// Check required columns with their types
		checkColumn(connection, tableName, "reader", "text", false);
		checkColumn(connection, tableName, "event_position", "bigint", false);
		checkColumn(connection, tableName, "event_id", "uuid", false);
		checkColumn(connection, tableName, "event_tx", "xid8", false);
		checkColumn(connection, tableName, "updated_at", "timestamp with time zone", true);
		checkColumn(connection, tableName, "updated_tags", "ARRAY", true);

		// Check foreign key constraint
		checkForeignKey(connection, tableName, "fk_bookmarks_event_id");

		LOGGER.debug("Table {} validated successfully", tableName);
	}

	private boolean tableExists(Connection connection, String tableName) throws SQLException {
		String sql = """
			SELECT EXISTS (
				SELECT FROM information_schema.tables
				WHERE table_schema = current_schema()
				AND table_name = ?
			)
		""";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, tableName);
			try (ResultSet rs = stmt.executeQuery()) {
				return rs.next() && rs.getBoolean(1);
			}
		}
	}

	private void checkColumn(Connection connection, String tableName, String columnName, String expectedType, boolean nullable) throws SQLException {
		LOGGER.debug("Checking column: {}.{} (expected type: {}, nullable: {})", tableName, columnName, expectedType, nullable);

		String sql = """
			SELECT data_type, is_nullable, udt_name
			FROM information_schema.columns
			WHERE table_schema = current_schema()
			AND table_name = ?
			AND column_name = ?
		""";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, tableName);
			stmt.setString(2, columnName);
			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next()) {
					throw new EventStorageException(
						"Required column '%s.%s' does not exist".formatted(tableName, columnName)
					);
				}

				String dataType = rs.getString("data_type");
				String udtName = rs.getString("udt_name");
				String isNullable = rs.getString("is_nullable");

				// Handle special cases for type checking
				boolean typeMatches = false;
				if (expectedType.equalsIgnoreCase("bigserial")) {
					// bigserial is stored as bigint in information_schema
					typeMatches = dataType.equalsIgnoreCase("bigint");
				} else if (expectedType.equalsIgnoreCase("ARRAY")) {
					typeMatches = dataType.equalsIgnoreCase("ARRAY");
				} else {
					typeMatches = dataType.equalsIgnoreCase(expectedType) ||
								 udtName.equalsIgnoreCase(expectedType.replace(" ", ""));
				}

				if (!typeMatches) {
					throw new EventStorageException(
						"Column '%s.%s' has incorrect type: expected '%s', found '%s' (udt: '%s')"
							.formatted(tableName, columnName, expectedType, dataType, udtName)
					);
				}

				boolean actuallyNullable = "YES".equalsIgnoreCase(isNullable);
				if (nullable != actuallyNullable) {
					throw new EventStorageException(
						"Column '%s.%s' has incorrect nullability: expected %s, found %s"
							.formatted(tableName, columnName,
								nullable ? "nullable" : "not null",
								actuallyNullable ? "nullable" : "not null")
					);
				}
			}
		}
	}

	private void checkForeignKey(Connection connection, String tableName, String constraintName) throws SQLException {
		LOGGER.debug("Checking foreign key constraint: {} on table {}", constraintName, tableName);

		String sql = """
			SELECT EXISTS (
				SELECT FROM information_schema.table_constraints
				WHERE table_schema = current_schema()
				AND table_name = ?
				AND constraint_name = ?
				AND constraint_type = 'FOREIGN KEY'
			)
		""";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, tableName);
			stmt.setString(2, constraintName);
			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next() || !rs.getBoolean(1)) {
					throw new EventStorageException(
						"Required foreign key constraint '%s' does not exist on table '%s'"
							.formatted(constraintName, tableName)
					);
				}
			}
		}
	}

	private void checkFunction(Connection connection, String functionName) throws SQLException {
		LOGGER.debug("Checking function: {}", functionName);

		String sql = """
			SELECT EXISTS (
				SELECT FROM pg_proc p
				JOIN pg_namespace n ON p.pronamespace = n.oid
				WHERE n.nspname = current_schema()
				AND p.proname = ?
			)
		""";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, functionName);
			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next() || !rs.getBoolean(1)) {
					throw new EventStorageException(
						"Required function '%s' does not exist".formatted(functionName)
					);
				}
			}
		}
	}

	/**
	 * Validates that a trigger exists <em>and</em> fires at the expected granularity.
	 * <p>
	 * Checking the name alone is not enough. The append notification trigger changed from
	 * {@code FOR EACH ROW} to {@code FOR EACH STATEMENT} without changing its name, and a database
	 * carrying the old row-level trigger is not merely slower — paired with a refreshed function body
	 * it is silently broken, and paired with a stale one it re-amplifies every write. Neither shows up
	 * in a name check, so the orientation is validated explicitly and an un-migrated database fails
	 * here, loudly, instead of at runtime with notifications that no subscriber matches.
	 *
	 * @param expectedOrientation {@code STATEMENT} or {@code ROW}, as reported by
	 *        {@code information_schema.triggers.action_orientation}
	 */
	private void checkTrigger(Connection connection, String tableName, String triggerName, String expectedOrientation) throws SQLException {
		LOGGER.debug("Checking trigger: {} on table {} (expecting {} level)", triggerName, tableName, expectedOrientation);

		String sql = """
			SELECT action_orientation
			FROM information_schema.triggers
			WHERE trigger_schema = current_schema()
			AND event_object_table = ?
			AND trigger_name = ?
			LIMIT 1
		""";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, tableName);
			stmt.setString(2, triggerName);
			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next()) {
					throw new EventStorageException(
						"Required trigger '%s' does not exist on table '%s'"
							.formatted(triggerName, tableName)
					);
				}
				String actualOrientation = rs.getString(1);
				if (!expectedOrientation.equalsIgnoreCase(actualOrientation)) {
					throw new EventStorageException(
						("Trigger '%s' on table '%s' fires FOR EACH %s, but this version requires FOR EACH %s. "
						+ "The database predates the statement-level notification trigger; re-run schema creation "
						+ "(the ensure-schema script is idempotent and migrates it in place) so notifications are "
						+ "emitted once per stream per statement.")
							.formatted(triggerName, tableName, actualOrientation, expectedOrientation)
					);
				}
			}
		}
	}

	private void checkIndex(Connection connection, String indexName) throws SQLException {
		LOGGER.debug("Checking index: {}", indexName);

		String sql = """
			SELECT EXISTS (
				SELECT FROM pg_indexes
				WHERE schemaname = current_schema()
				AND indexname = ?
			)
		""";

		try (PreparedStatement stmt = connection.prepareStatement(sql)) {
			stmt.setString(1, indexName);
			try (ResultSet rs = stmt.executeQuery()) {
				if (!rs.next() || !rs.getBoolean(1)) {
					throw new EventStorageException(
						"Required index '%s' does not exist".formatted(indexName)
					);
				}
			}
		}
	}
	
	/**
	 * Starts the LISTEN/NOTIFY monitor threads, waiting up to
	 * {@link #DEFAULT_NOTIFICATION_STARTUP_TIMEOUT} for both to register their listener.
	 *
	 * @throws IllegalStateException if this storage has been stopped
	 * @throws EventStorageException if LISTEN/NOTIFY is not established in time
	 * @see #start(Duration)
	 */
	public void start ( ) {
		start(DEFAULT_NOTIFICATION_STARTUP_TIMEOUT);
	}

	/**
	 * Starts the LISTEN/NOTIFY monitor threads and waits, <em>for a bounded time</em>, until both have
	 * registered their listener — failing if they do not.
	 * <p>
	 * Called by {@link PostgresEventStorage.Builder#build()}; a storage handed to application code is
	 * always already started, with its notifications working. A stopped storage is terminal and cannot be
	 * started again.
	 * <p>
	 * The wait is bounded because a monitor that cannot reach the database does not fail — it retries with
	 * backoff, forever, by design. Waiting on it without a deadline made an unreachable database hang
	 * application startup with no exception, no timeout and nothing logged above DEBUG. The deadline covers
	 * both monitors together, not each in turn.
	 * <p>
	 * On expiry this storage is closed and an {@link EventStorageException} is thrown. Starting anyway
	 * would be starting an event-sourced application that is not told when events are appended: its
	 * subscribers are never woken and its read models advance only when something happens to run a
	 * projection, so it serves stale data with nothing in its own logs to say so. That is worse than not
	 * starting, and it is not a state to be silently in — which is why there is no mode for it. Closing
	 * rather than merely throwing matters too: the two monitor threads started here would otherwise go on
	 * retrying behind a storage the caller never received.
	 *
	 * @param timeout how long to wait in total; {@code null} means {@link #DEFAULT_NOTIFICATION_STARTUP_TIMEOUT}
	 * @throws IllegalStateException if this storage has been stopped
	 * @throws EventStorageException if the wait expires, or if the calling thread is interrupted while
	 *                               waiting; in both cases this storage has been closed
	 */
	public void start ( Duration timeout ) {
		if ( stopped.get() ) {
			throw new IllegalStateException("event storage '%s' has been stopped and cannot be started again".formatted(name));
		}
		Duration effectiveTimeout = timeout == null ? DEFAULT_NOTIFICATION_STARTUP_TIMEOUT : timeout;

		this.eventMonitorReady = new CountDownLatch(1);
		this.bookmarkMonitorReady = new CountDownLatch(1);
		this.executorService.execute(new NewEventsAppendedMonitor("event-append-listener/" + name, listeners, monitoringDataSource, eventMonitorReady));
		this.executorService.execute(new BookmarkPlacedMonitor("bookmark-listener/" + name, listeners, monitoringDataSource, bookmarkMonitorReady));

		boolean ready;
		try {
			ready = awaitMonitorsReady(effectiveTimeout);
		} catch (InterruptedException e) {
			// an interrupt here means the application is going away mid-startup. Returning normally would
			// hand back a storage nobody can tell is unstarted, with two monitor threads still retrying
			// behind it, so wind them up and say what happened
			Thread.currentThread().interrupt();
			close();
			throw new EventStorageException("interrupted while starting event storage '%s'".formatted(name), e);
		}

		if ( ready ) {
			return;
		}

		close();
		throw new EventStorageException(
			("event storage '%s' could not establish LISTEN/NOTIFY within %dms. Without it nothing wakes a "
			+ "subscriber: appends and bookmark placements would not reach projections, which would then "
			+ "advance only when explicitly run. Check the monitoring DataSource — it may be configured "
			+ "separately from the main one, since LISTEN/NOTIFY does not survive a transaction pooler, so a "
			+ "deployment can have a working pooled connection and an unreachable direct one.")
				.formatted(name, effectiveTimeout.toMillis()));
	}

	/**
	 * Waits for both monitors to register their listener, in slices so that a concurrent {@link #close()}
	 * is noticed even under a generous timeout.
	 *
	 * @return {@code true} if both are listening, {@code false} if the deadline passed or the storage was
	 *         closed while waiting
	 */
	private boolean awaitMonitorsReady ( Duration timeout ) throws InterruptedException {
		long remainingMillis = Math.max(0, timeout.toMillis());
		for ( CountDownLatch latch : List.of(eventMonitorReady, bookmarkMonitorReady) ) {
			while ( latch.getCount() > 0 ) {
				if ( stopped.get() ) {
					return false;
				}
				if ( remainingMillis <= 0 ) {
					return false;
				}
				long slice = Math.min(START_POLL_SLICE_MILLIS, remainingMillis);
				if ( latch.await(slice, TimeUnit.MILLISECONDS) ) {
					break;
				}
				remainingMillis -= slice;
			}
		}
		// the latches are only the wake-up mechanism -- close() counts them down too, to release a caller
		// parked here -- so the verdict comes from the state the monitors actually publish
		return isNotificationsAvailable();
	}

	/**
	 * Closes this storage: stops the LISTEN/NOTIFY monitor threads, then closes the DataSources if —
	 * and only if — they were created by the builder rather than supplied by the caller.
	 * <p>
	 * Implements the contract on {@link EventStorage#close()}: idempotent, blocking until the monitor
	 * threads have actually finished and released their connections (typically within
	 * {@value #WAIT_FOR_NOTIFICATIONS_TIMEOUT}ms, bounded), terminal, and after it every operation on
	 * this storage throws {@link EventStorageClosedException}.
	 * <p>
	 * A caller who supplied the DataSource must close this storage <em>before</em> closing that pool.
	 * The other order leaves the monitors alive against a dead pool, where they cannot tell a closed
	 * pool from a database outage and will retry with backoff, logging as they go.
	 * <p>
	 * The listeners are dropped once the monitors have stopped — after, so that nothing is walking the
	 * list while it is emptied, and at all because they are held strongly: a closed storage that kept
	 * them would pin every stream ever subscribed to it, and every event store behind those streams.
	 */
	@Override
	public void close ( ) {
		boolean wasRunning = stopMonitors();
		listeners.clear();
		if ( wasRunning && ownsDataSources ) {
			closeDataSource(dataSource);
			if ( monitoringDataSource != dataSource ) {
				closeDataSource(monitoringDataSource);
			}
		}
	}

	private void closeDataSource ( DataSource dataSourceToClose ) {
		if ( dataSourceToClose instanceof AutoCloseable closeable ) {
			try {
				closeable.close();
			} catch (Exception e) {
				LOGGER.warn("failed to close the DataSource created for event storage '{}': {}", name, e.getMessage(), e);
			}
		}
	}

	/**
	 * Stops the LISTEN/NOTIFY monitor threads and waits for them to finish.
	 * <p>
	 * The monitors poll for notifications in {@value #WAIT_FOR_NOTIFICATIONS_TIMEOUT}ms slices, so they
	 * see the stop flag within that and wind themselves up: UNLISTEN, then the connection returns to the
	 * pool healthy and no shutdown noise is logged. Only if that has not happened within
	 * {@value #GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS}ms are they interrupted, which is abrupt — it closes the
	 * socket underneath the driver, so the pool discards the connection and logs a "marked as broken"
	 * warning about it.
	 * <p>
	 * Idempotent: only the first call does anything, later calls return immediately. When this method
	 * returns, no monitor thread of this storage is running any more (unless the bounded wait expired,
	 * which is logged).
	 *
	 * @deprecated use {@link #close()} instead, which is on the {@link EventStorage} interface and so
	 *             needs no downcast, and which additionally closes DataSources the builder created.
	 *             This method now simply delegates to it.
	 */
	@Deprecated(since = "0.10.0", forRemoval = true)
	public void stop ( ) {
		close();
	}

	/**
	 * Interrupts the monitor threads and waits for them to finish.
	 *
	 * @return {@code true} if this call was the one that stopped them, {@code false} if they were
	 *         already stopped
	 */
	private boolean stopMonitors ( ) {
		if ( !stopped.compareAndSet(false, true) ) {
			return false;
		}
		// release anyone still inside start(): the monitors they are waiting on have just been told to
		// stop and will never count these down themselves
		releaseStartLatches();
		eventMonitorListening.set(false);
		bookmarkMonitorListening.set(false);
		executorService.shutdown();
		try {
			// the monitors check the flag every WAIT_FOR_NOTIFICATIONS_TIMEOUT and wind themselves up
			// cleanly: UNLISTEN, then the connection goes back to the pool healthy
			if ( !executorService.awaitTermination(GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) ) {
				// a monitor is stuck somewhere it cannot see the flag; interrupt it, accepting that this
				// breaks its connection under the driver and that the pool will say so
				LOGGER.debug("monitor threads of event storage '{}' did not stop on their own, interrupting them", name);
				executorService.shutdownNow();
				if ( !executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) ) {
					LOGGER.warn("monitor threads of event storage '{}' did not terminate within {}s", name, SHUTDOWN_TIMEOUT_SECONDS);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return true;
	}

	private void releaseStartLatches ( ) {
		CountDownLatch eventLatch = this.eventMonitorReady;
		if ( eventLatch != null ) {
			eventLatch.countDown();
		}
		CountDownLatch bookmarkLatch = this.bookmarkMonitorReady;
		if ( bookmarkLatch != null ) {
			bookmarkLatch.countDown();
		}
	}

	/**
	 * Throws if this storage has been closed. Called at the top of every operation, so that a closed
	 * storage fails immediately and locatably instead of half-working with dead notifications.
	 */
	private void checkNotClosed ( ) {
		if ( stopped.get() ) {
			throw new EventStorageClosedException("event storage '%s' is closed".formatted(name));
		}
	}

	@Override
	public Stream<StoredEvent> query(EventQuery query, Optional<EventStreamId> stream, EventReference after, Limit limit, QueryDirection direction ) {
		checkNotClosed();
		// Handle the case where query matches none - return empty stream
		if (query.isMatchNone()) {
			return Stream.empty();
		}

		StringBuilder sqlBuilder = new StringBuilder();
		sqlBuilder.append(
			"""
				SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
				FROM %sevents
				WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
			""".formatted(prefix)
			);
		// pg_snapshot_xmin(pg_current_snapshot()) makes sure we don't read data committed by transaction that were started
		// after ones that are still running (race condition which would drop some events otherwise)
		// great insight found in the blogpost by Oskar Dudycz (https://event-driven.io/en/ordering_in_postgres_outbox/)

		List<Object> parameters = new ArrayList<>();

		// Seek past the reference if one is provided (exclusive)
		addCursorBoundary(sqlBuilder, parameters, after, direction);

		addUntilBoundary(sqlBuilder, parameters, query.until());

		// Add stream filtering
		if (stream.isPresent()) {
			if (!stream.get().isAnyContext()) {
				sqlBuilder.append(" AND stream_context = ?");
				parameters.add(stream.get().context());
			}
			if (!stream.get().isAnyPurpose()) {
				sqlBuilder.append(" AND stream_purpose = ?");
				parameters.add(stream.get().purpose());
			}
		}
		
		// Add EventFilter filtering (event types and tags)
		if (!query.isMatchAll()) {
			addEventFilterFiltering(sqlBuilder, parameters, query.filter());
		}
		
		// Order by position
		if ( direction == QueryDirection.BACKWARD ) {
			sqlBuilder.append(" ORDER BY event_tx::xid8 DESC, event_position DESC");
		} else {
			sqlBuilder.append(" ORDER BY event_tx::xid8, event_position ");
			
		}
		
		Limit effectiveLimit = effectiveLimit(limit);
		
		// Add limit if specified
		if (effectiveLimit != null && effectiveLimit.isSet()) {
			sqlBuilder.append(" LIMIT ? OFFSET 0");
			parameters.add(effectiveLimit.value());
		}
		
		try ( Connection readConnection = dataSource.getConnection() ) {
			readConnection.setAutoCommit(true);
			try (PreparedStatement stmt = readConnection.prepareStatement(sqlBuilder.toString())) {
				// Set parameters
				for (int i = 0; i < parameters.size(); i++) {
					stmt.setObject(i + 1, parameters.get(i));
				}
				
				try (ResultSet rs = stmt.executeQuery()) {
					List<StoredEvent> events = new ArrayList<>();
					while (rs.next()) {
						events.add(mapResultSetToEvent(rs));
					}
					if ( absoluteLimit != null && absoluteLimit.isSet() && events.size() > absoluteLimit.value() ) {
						throw new EventStorageException("query returned more results than the configured absolute limit of %d".formatted(absoluteLimit.value()));
					}
					return events.stream();
				}
			}
		} catch (SQLException e) {
			throw new EventStorageException("Failed to query events", e);
		}
	}
	
	/**
	 * Appends the exclusive cursor of a statement being built: "strictly after the given reference"
	 * going forward, "strictly before it" going backward.
	 * <p>
	 * The comparison is over the {@code (tx, position)} tuple, because that is the order events are
	 * read in — {@code ORDER BY event_tx, event_position}, matching
	 * {@link EventReference#happenedAfter(EventReference)}. Comparing on {@code event_position} alone
	 * would be a different order, and the two genuinely disagree: {@code event_position} comes from a
	 * sequence at insert time while {@code event_tx} defaults to {@code pg_current_xact_id()}, and the
	 * two are assigned independently, so a transaction can carry a lower position and a higher tx than
	 * one that committed before it.
	 * <p>
	 * That matters most for the optimistic-locking check, where the cursor is the caller's expected
	 * last event and anything after it that matches the filter is a new relevant fact. On a
	 * position-only comparison such an event is invisible to the check while every reader sorts it
	 * after the reference, so the append succeeds against a history the store does not agree with —
	 * silently, which is the worst way for a consistency boundary to fail. The read path's
	 * {@code pg_snapshot_xmin} barrier makes that reachable rather than theoretical: it deliberately
	 * withholds an event whose transaction is still in flight, so a reader legitimately takes a
	 * reference with a higher position than an event that becomes visible, and sorts later, moments
	 * afterwards.
	 * <p>
	 * The {@code index} part of an {@link EventReference} has no column, exactly as in
	 * {@link #addUntilBoundary}: it distinguishes sub-events an upcaster produced from one stored
	 * event. Two sub-events of the <em>same</em> stored event are therefore indistinguishable here —
	 * narrow, since they come from one atomic append, and on the read path {@code EventStoreImpl}
	 * re-applies the exact filter after upcasting.
	 *
	 * @param sqlBuilder the statement being built
	 * @param parameters the parameter list being built alongside it
	 * @param after the reference to seek past, or null for no cursor (in which case nothing is appended)
	 * @param direction which side of the reference to keep
	 */
	private void addCursorBoundary(StringBuilder sqlBuilder, List<Object> parameters, EventReference after, QueryDirection direction) {
		if ( after == null ) {
			return;
		}
		if ( direction == QueryDirection.FORWARD ) {
			sqlBuilder.append(" AND ((event_tx>?::xid8) OR (event_tx = ?::xid8 AND event_position > ?))");
		} else {
			sqlBuilder.append(" AND ((event_tx<?::xid8) OR (event_tx = ?::xid8 AND event_position < ?))");
		}
		parameters.add(Long.toUnsignedString(after.tx()));
		parameters.add(Long.toUnsignedString(after.tx()));
		parameters.add(after.position());
	}

	/**
	 * Appends the {@code until} boundary of an {@link EventFilter} to a statement being built.
	 * <p>
	 * The boundary is a matching criterion, not a traversal one: it is the inclusive upper bound over
	 * the same {@code (tx, position)} order the cursor uses, and it means the same thing whether the
	 * query runs forward or backward. Reading it as "traverse until you reach it" -- a lower bound when
	 * going backward -- returns the events on the wrong side of the boundary.
	 * <p>
	 * Comparing on {@code event_position} alone is not enough either. {@code event_tx} defaults to
	 * {@code pg_current_xact_id()}, assigned at a transaction's first write, while {@code event_position}
	 * comes from a sequence at insert time, so a transaction that wrote elsewhere first can carry a lower
	 * tx and a higher position. Ordering is by the tuple, so the boundary has to be too, or an event
	 * before the boundary is silently never fetched.
	 * <p>
	 * The {@code index} part of an {@link EventReference} has no column: it distinguishes sub-events an
	 * upcaster produced from one stored event. This predicate therefore stays a deliberate superset, and
	 * {@code EventStoreImpl} re-applies the exact filter after upcasting.
	 *
	 * @param sqlBuilder the statement being built
	 * @param parameters the parameter list being built alongside it
	 * @param until the boundary, or null for no boundary (in which case nothing is appended)
	 */
	private void addUntilBoundary(StringBuilder sqlBuilder, List<Object> parameters, EventReference until) {
		if ( until == null ) {
			return;
		}
		sqlBuilder.append(" AND ((event_tx<?::xid8) OR (event_tx = ?::xid8 AND event_position <= ?))");
		parameters.add(Long.toUnsignedString(until.tx()));
		parameters.add(Long.toUnsignedString(until.tx()));
		parameters.add(until.position());
	}

	private void addEventFilterFiltering(StringBuilder sqlBuilder, List<Object> parameters, EventFilter filter) {
		if (filter.items() == null || filter.items().isEmpty()) {
			return; // matchAll case is already handled
		}

		sqlBuilder.append(" AND (");
		boolean first = true;

		for (EventFilterItem item : filter.items()) {
			if (!first) {
				sqlBuilder.append(" OR ");
			}
			
			sqlBuilder.append("(");
			boolean hasEventTypeFilter = false;
			boolean hasTagFilter = false;
			
			// Add event type filtering
			if (item.eventTypes() != null && !item.eventTypes().eventTypes().isEmpty()) {
				sqlBuilder.append("event_type IN (");
				
				Iterator<EventType> itTypes = item.eventTypes().eventTypes().iterator();
				for (int i = 0; i < item.eventTypes().eventTypes().size(); i++) {
					if (i > 0) sqlBuilder.append(", ");
					sqlBuilder.append("?");
					parameters.add(itTypes.next().name());
				}
				sqlBuilder.append(")");
				hasEventTypeFilter = true;
			}
			
			// Add tag filtering
			if (item.tags() != null && !item.tags().tags().isEmpty()) {
				if (hasEventTypeFilter) {
					sqlBuilder.append(" AND ");
				}
				
				// Check that all required tags are present in the event's tags array
				sqlBuilder.append("event_tags @> ARRAY[");
				boolean firstTag = true;
				for (Tag tag : item.tags().tags()) {
					if (!firstTag) sqlBuilder.append(", ");
					sqlBuilder.append("?");
					parameters.add(tag.toString());
					firstTag = false;
				}
				sqlBuilder.append("]::text[]");
				hasTagFilter = true;
			}
			
			// If no specific filters, match all for this item (shouldn't happen in practice)
			if (!hasEventTypeFilter && !hasTagFilter) {
				sqlBuilder.append("1=1");
			}
			
			sqlBuilder.append(")");
			first = false;
		}
		
		sqlBuilder.append(")");
	}
	
	/**
	 * Returns the per-row VALUES fragment used in the INSERT statement of {@link #append}.
	 * <p>
	 * Default (PG18+) emits {@code uuidv7()} server-side so no event_id parameter is bound.
	 * The legacy subclass overrides this together with {@link #bindEventIdParameter} to bind
	 * a Java-generated UUIDv7 via a {@code ?::uuid} placeholder.
	 * <p>
	 * Internal extension point — override only in version-gated subclasses in this package.
	 */
	protected String appendValuesRowFragment ( ) {
		return "(uuidv7(), ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) ";
	}

	/**
	 * Appends the event_id parameter for a single row in {@link #append} when needed.
	 * <p>
	 * Default (PG18+) is a no-op — the server generates the id via {@code uuidv7()}.
	 * The legacy subclass overrides this to add a Java-generated UUIDv7 to the parameter list.
	 * <p>
	 * Internal extension point — override only in version-gated subclasses in this package.
	 */
	protected void bindEventIdParameter ( List<Object> parameters ) {
		// no-op: server-side generation
	}

	/**
	 * Statement that serializes conditional appends; see {@link #appendLockKey}.
	 * <p>
	 * It has to be executed as a <em>statement of its own, before</em> the conditional INSERT. Under
	 * READ COMMITTED a statement fixes its snapshot when it starts executing, so folding the lock into
	 * the INSERT's {@code WHERE} would block with the stale snapshot already taken and the check would
	 * still miss the other appender's row — the same race, now with a lock in front of it that proves
	 * nothing.
	 */
	private static final String ACQUIRE_APPEND_LOCK = "SELECT pg_advisory_xact_lock(?)";

	/**
	 * Statement that serializes schema scripts; see {@link #executeSqlScripts} and {@link #schemaLockKey}.
	 * Textually identical to {@link #ACQUIRE_APPEND_LOCK} but kept separate: the two serialize different
	 * things and share only the fact that PostgreSQL spells the lock the same way.
	 */
	private static final String ACQUIRE_SCHEMA_LOCK = "SELECT pg_advisory_xact_lock(?)";

	/**
	 * Lock scope for schema scripts. Distinct from every stream scope — those are either
	 * {@link #ANY_STREAM_SCOPE} or {@code context + UNIT_SEPARATOR + purpose}, and a stream context
	 * cannot start with a NUL — so schema work never contends with appends. Derived from the prefix as
	 * well, like the append key, so two storages sharing a database but not a table do not block each
	 * other.
	 */
	private static final String SCHEMA_SCOPE = "\u0000schema";

	/** Lock scope used when an append is not confined to one fully specified stream. */
	private static final String ANY_STREAM_SCOPE = "\u0000any-stream";

	/** Separates the parts of a lock scope, so that ("ab","c") and ("a","bc") cannot hash alike. */
	private static final char UNIT_SEPARATOR = '\u001F';

	/**
	 * Advisory lock key that makes the optimistic-locking check and the insert one indivisible step.
	 * <p>
	 * <b>Why a lock is needed at all.</b> The conditional append is an {@code INSERT … WHERE NOT EXISTS
	 * (…)}, and under PostgreSQL's default READ COMMITTED isolation two of them racing each other both
	 * evaluate {@code NOT EXISTS} against a snapshot taken before the other committed. Both find the
	 * boundary empty, both insert, both commit, and the consistency boundary the caller expressed is
	 * violated with nothing raised. The conflicting row is a <em>phantom</em> at the moment of the
	 * check, so no row-level lock can cover it — there is no row yet to lock.
	 * <p>
	 * <b>Why the key is the stream and not the filter.</b> Hashing the {@link AppendCriteria}'s filter
	 * would be finer grained and wrong: two filters that overlap without being equal (say tag {@code A}
	 * versus tags {@code A + B}) hash to different keys, so they would not exclude each other even
	 * though each one's write falls inside the other's boundary. The stream is the narrowest scope that
	 * provably contains every boundary an append can express, because the {@code NOT EXISTS} is itself
	 * confined to the stream. An append not confined to one fully specified stream can range over the
	 * whole table, so it falls back to a single storage-wide scope.
	 * <p>
	 * Only conditional appends take it. An {@link AppendCriteria#none()} append reads nothing, so it
	 * cannot observe a stale boundary, and a conditional append that misses it is still equivalent to
	 * the two having run in the order conditional-then-unconditional — a legitimate history. Leaving
	 * the unconditional path lock-free keeps bulk ingestion fully parallel.
	 * <p>
	 * The key is derived from the table prefix as well as the stream, so two storages sharing a
	 * database but not a table do not contend. Collisions are possible — the key is 64 bits of a digest
	 * and PostgreSQL's advisory lock space is global to the database — but they can only make two
	 * unrelated appends take turns, never let a real conflict through.
	 */
	private long appendLockKey ( Optional<EventStreamId> streamId ) {

		String scope = ANY_STREAM_SCOPE;
		if ( streamId.isPresent() && !streamId.get().isAnyContext() && !streamId.get().isAnyPurpose() ) {
			scope = streamId.get().context() + UNIT_SEPARATOR + streamId.get().purpose();
		}

		return advisoryLockKey(scope);
	}

	/**
	 * Advisory lock key that serializes the schema scripts of this prefix against each other; see
	 * {@link #executeSqlScripts} for what goes wrong without it.
	 * <p>
	 * It deliberately shares {@link #advisoryLockKey}'s derivation with {@link #appendLockKey}, on a
	 * scope no stream can produce, so schema work and appends never contend.
	 */
	private long schemaLockKey ( ) {
		return advisoryLockKey(SCHEMA_SCOPE);
	}

	/**
	 * Folds the table prefix and a scope into the 64-bit key {@code pg_advisory_xact_lock} takes.
	 * <p>
	 * Collisions are possible — 64 bits of a digest, and PostgreSQL's advisory lock space is global to
	 * the database — but they can only make two unrelated holders take turns, never let a real conflict
	 * through.
	 */
	private long advisoryLockKey ( String scope ) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest((prefix + UNIT_SEPARATOR + scope).getBytes(StandardCharsets.UTF_8));
			long key = 0;
			for ( int i = 0; i < Long.BYTES; i++ ) {
				key = (key << 8) | (digest[i] & 0xffL);
			}
			return key;
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is required of every JRE
			throw new EventStorageException("SHA-256 is unavailable, cannot derive an advisory lock key", e);
		}
	}

	/**
	 * Appends events, pairing each {@code RETURNING} row with the input event at the same index.
	 * <p>
	 * Nothing promises that order — {@code RETURNING} is not in the SQL standard, and PostgreSQL
	 * documents only "a row per row actually inserted". It holds because the {@code NOT EXISTS} below
	 * is <em>uncorrelated</em>: it references the events table and bound parameters, never a column of
	 * {@code new_events}. PostgreSQL therefore evaluates it once as an InitPlan and applies it as a
	 * One-Time Filter over the VALUES list, and every node in that plan preserves order. Being
	 * all-or-nothing is also what makes the {@code storedEvents.size() != events.size()} check below a
	 * sound conflict detector — the statement inserts every row or none, never a subset.
	 * <p>
	 * Correlating that predicate with {@code new_events} would let the planner turn it into an
	 * anti-join, which reorders and would silently mispair every event with another's id and position.
	 * Note the rows would still carry ascending positions, since the reordering precedes the insert, so
	 * a defensive monotonicity check would not catch it.
	 * <p>
	 * {@code importEvents} keys on the id it supplied instead, because {@code ON CONFLICT} makes its
	 * result a subset of its input. That is not an option here: from PG18 the id comes from a
	 * server-side {@code uuidv7()}, and {@code RETURNING} cannot return a source-only ordinal to key on.
	 */
	@Override
	public List<StoredEvent> append(AppendCriteria appendCriteria, Optional<EventStreamId> streamId, List<EventToStore> events) {
		checkNotClosed();
		List<StoredEvent> storedEvents = new ArrayList<>();

		if ( events.size() != 0 ) {

			// Build conditional insert with optimistic locking check
			StringBuilder sqlBuilder = new StringBuilder();
			sqlBuilder.append("INSERT INTO %sevents (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES ".formatted(prefix));
			String valuesRowFragment = appendValuesRowFragment();
			for ( int i = 0; i < events.size(); i++ ) {
				if ( i > 0 ) {
					sqlBuilder.append(", ");
				}
				sqlBuilder.append(valuesRowFragment);
			}
			// PostgreSQL made the alias optional for a FROM-clause subquery in 16, so it is not strictly
			// required at the current support floor. It is kept because omitting it is what made every
			// conditional append fail on 15 and older with "VALUES in FROM must have an alias" — the bug
			// that went unnoticed for as long as nothing ran below 17. Nothing references the alias; the
			// rows are consumed positionally by SELECT *.
			sqlBuilder.append(") AS new_events ");

			List<Object> parameters = new ArrayList<>();

			for ( EventToStore event: events ) {

				bindEventIdParameter(parameters);

				// Add to-be-appended-event parameters first
				parameters.add(event.idempotencyKey());
				parameters.add(event.stream().context());
				parameters.add(event.stream().purpose());
				parameters.add(event.type().name());

				parameters.add(event.immutableData());
				parameters.add(event.erasableData());

				// Convert tags to array
				// sized from the string set, not from tags(): a set sized larger than its contents leaves a
				// trailing null element, which would be written into the text[] column as a NULL tag
				String[] tagsArray = event.tags().toStrings().toArray(new String[0]);
				parameters.add(tagsArray);
			}

			if ( ! appendCriteria.isNone() ) {

				// Now add the optimistic locking conditions
				sqlBuilder.append(
						"""
					WHERE NOT EXISTS (
						SELECT 1 FROM %sevents
						WHERE 1=1 """.formatted(prefix));


				// Add stream filtering
				if (streamId.isPresent()) {
					if (!streamId.get().isAnyContext()) {
						sqlBuilder.append(" AND stream_context = ?");
						parameters.add(streamId.get().context());
					}
					if (!streamId.get().isAnyPurpose()) {
						sqlBuilder.append(" AND stream_purpose = ?");
						parameters.add(streamId.get().purpose());
					}
				}

				if ( appendCriteria.expectedLastEventReference().isPresent() ) {

					// Look for events after the expected last one, over the same (tx, position) order
					// readers see and EventReference.happenedAfter defines. See addCursorBoundary: on a
					// position-only comparison a committed event that every reader sorts after the
					// reference can carry a lower position, and the check would not see it.
					addCursorBoundary(sqlBuilder, parameters, appendCriteria.expectedLastEventReference().get(), QueryDirection.FORWARD);
				}


				// Add EventFilter filtering for the consistency boundary
				EventFilter lockingFilter = appendCriteria.eventFilter();

				// A consistency boundary is whatever its EventFilter matches, and a filter carrying an
				// "until" does not match past it -- so an event beyond the boundary is not a new relevant
				// fact and must not raise a conflict. Leaving this out made this backend lock where the
				// in-memory one, which runs the criteria through query(), did not.
				addUntilBoundary(sqlBuilder, parameters, lockingFilter.until());

				if (!lockingFilter.isMatchAll()) {
					addEventFilterFiltering(sqlBuilder, parameters, lockingFilter);
				}

				sqlBuilder.append(") ");
			}

			sqlBuilder.append("RETURNING event_position, event_timestamp, event_tx::text, event_id::text");


			try ( Connection writeConnection = dataSource.getConnection()) {
				writeConnection.setAutoCommit(false);

				try ( PreparedStatement stmt = writeConnection.prepareStatement(sqlBuilder.toString()) ) {

					if ( ! appendCriteria.isNone() ) {
						// Serialize this stream's conditional appends: the NOT EXISTS below is a phantom
						// check, which READ COMMITTED does not protect. Held until this transaction ends,
						// and taken as its own statement so the INSERT's snapshot is taken after the
						// previous holder committed. See appendLockKey.
						try ( PreparedStatement lock = writeConnection.prepareStatement(ACQUIRE_APPEND_LOCK) ) {
							lock.setLong(1, appendLockKey(streamId));
							lock.execute();
						}
					}

					// Set parameters
					for (int i = 0; i < parameters.size(); i++) {
						Object param = parameters.get(i);
						if (param instanceof String[]) {
							stmt.setArray(i + 1, writeConnection.createArrayOf("text", (String[]) param));
						} else {
							stmt.setObject(i + 1, param);
						}
					}

					try (ResultSet rs = stmt.executeQuery()) {

						Iterator<EventToStore> it = events.iterator();

						while (rs.next()) {
							long position = rs.getLong("event_position");
							long tx = Long.parseUnsignedLong(rs.getString("event_tx"));
							Timestamp timestamp = rs.getTimestamp("event_timestamp", Calendar.getInstance(TimeZone.getTimeZone("UTC")));
							EventId id = new EventId(rs.getString("event_id"));

							EventToStore e = it.next();

							EventReference reference = EventReference.of(id, position, tx);
							storedEvents.add(e.positionAt(reference, timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime()));
						}

						if ( storedEvents.size() != events.size() ) {
							// Insert failed due to optimistic locking conflict
							writeConnection.rollback();
							throw new OptimisticLockingException(appendCriteria.eventFilter(), appendCriteria.expectedLastEventReference());
						}
					}
					writeConnection.commit();

				} catch (SQLException e) {
					try {
						writeConnection.rollback();
					} catch (SQLException rollbackEx) {
						e.addSuppressed(rollbackEx);
					}

					// idempotency conflict: a duplicate (stream, idempotency_key) is silently ignored.
					// Detected via SQLState 23505 (unique_violation) on the stream-scoped idempotency index,
					// by the index name the server reports rather than a substring match on the message: the
					// message is translated under a non-English lc_messages, and matching it as a substring
					// also swallows an event_id or primary-key violation whenever the table prefix happens to
					// contain the word "idempotency".
					if ( isIdempotencyKeyViolation(e) ) {
						return Collections.emptyList();
					} else {
						throw new EventStorageException("SQLException during append", e);
					}
				}
				
			} catch (SQLException e) {
				throw new EventStorageException("SQLException during append", e);
			}
		}
		
		return storedEvents;
			
	}

	/**
	 * Number of events bound into a single INSERT statement.
	 * <p>
	 * Bounded by the wire protocol: each row binds 9 parameters against a hard ceiling of 65535 per
	 * statement, so roughly 7200 rows would fit. 5000 leaves headroom. This is a <em>statement</em>
	 * boundary only — every statement of one {@code importEvents} call runs in the same transaction,
	 * so the call stays all-or-nothing however many chunks it takes.
	 */
	private static final int IMPORT_CHUNK_SIZE = 5000;

	/** Matches the parenthesised value list Postgres reports in the DETAIL of a unique violation. */
	private static final Pattern CONFLICT_DETAIL_VALUES = Pattern.compile("\\)=\\((.*)\\) already exists", Pattern.DOTALL);

	@Override
	public List<StoredEvent> importEvents ( List<EventToImport> events, ImportMode mode ) {
		checkNotClosed();
		if ( events == null ) {
			throw new IllegalArgumentException("events to import must not be null");
		}
		if ( mode == null ) {
			throw new IllegalArgumentException("import mode must not be null");
		}
		if ( events.isEmpty() ) {
			return Collections.emptyList();
		}

		validateImportBatch(events);

		List<StoredEvent> imported = new ArrayList<>(events.size());

		try ( Connection writeConnection = dataSource.getConnection() ) {
			writeConnection.setAutoCommit(false);
			try {
				// Statement chunking is a wire-protocol concern; the transaction spans all chunks so the
				// whole call commits or rolls back as one unit.
				for ( int from = 0; from < events.size(); from += IMPORT_CHUNK_SIZE ) {
					List<EventToImport> chunk = events.subList(from, Math.min(from + IMPORT_CHUNK_SIZE, events.size()));
					imported.addAll(importChunk(writeConnection, chunk, mode));
				}
				writeConnection.commit();
			} catch (SQLException e) {
				try {
					writeConnection.rollback();
				} catch (SQLException rollbackEx) {
					e.addSuppressed(rollbackEx);
				}
				throw classifyImportFailure(e, events);
			} catch (RuntimeException e) {
				try {
					writeConnection.rollback();
				} catch (SQLException rollbackEx) {
					e.addSuppressed(rollbackEx);
				}
				throw e;
			}
		} catch (SQLException e) {
			throw new EventStorageException("SQLException during import", e);
		}

		return imported;
	}

	/**
	 * Rejects a batch that cannot possibly be inserted, before opening a connection: duplicate identifiers
	 * within the batch (which the unique index would reject in a way that varies by mode), and identifiers
	 * that are not UUIDs (which would fail on the {@code ::uuid} cast with an opaque message).
	 */
	private void validateImportBatch ( List<EventToImport> events ) {
		Set<EventId> seen = new HashSet<>();
		for ( EventToImport event : events ) {
			if ( !seen.add(event.id()) ) {
				throw new IllegalArgumentException("batch to import holds more than one event with id %s".formatted(event.id().value()));
			}
			try {
				UUID.fromString(event.id().value());
			} catch (IllegalArgumentException e) {
				throw new EventStorageException("event id '%s' cannot be imported: this storage requires event ids to be UUIDs".formatted(event.id().value()), e);
			}
		}
	}

	private List<StoredEvent> importChunk ( Connection writeConnection, List<EventToImport> chunk, ImportMode mode ) throws SQLException {

		StringBuilder sqlBuilder = new StringBuilder();
		sqlBuilder.append("INSERT INTO %sevents (event_id, idempotency_key, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags) VALUES ".formatted(prefix));
		for ( int i = 0; i < chunk.size(); i++ ) {
			if ( i > 0 ) {
				sqlBuilder.append(", ");
			}
			sqlBuilder.append("(?::uuid, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)");
		}

		if ( mode == ImportMode.SKIP_EXISTING_ID ) {
			// Infer on event_id specifically rather than a bare DO NOTHING: a violation of the stream-scoped
			// idempotency index must still raise, since skipping it would drop an event the target never saw.
			sqlBuilder.append(" ON CONFLICT (event_id) DO NOTHING");
		}

		sqlBuilder.append(" RETURNING event_position, event_tx::text, event_id::text");

		List<Object> parameters = new ArrayList<>(chunk.size() * 9);
		Map<String,EventToImport> byId = new HashMap<>(chunk.size());

		for ( EventToImport event : chunk ) {
			byId.put(normalizedId(event.id()), event);

			parameters.add(event.id().value());
			parameters.add(event.idempotencyKey());
			parameters.add(event.stream().context());
			parameters.add(event.stream().purpose());
			parameters.add(event.type().name());
			// The timestamp travels with the event. Bound as an OffsetDateTime at UTC so the instant is
			// unambiguous on the wire, mirroring the read path which renders event_timestamp back to a
			// UTC LocalDateTime. Note timestamptz keeps microseconds and rounds anything finer, so a
			// nanosecond-precision source timestamp lands up to half a microsecond off.
			parameters.add(OffsetDateTime.of(event.timestamp(), ZoneOffset.UTC));
			parameters.add(event.immutableData());
			parameters.add(event.erasableData());
			parameters.add(event.tags().toStrings().toArray(new String[0]));
		}

		List<StoredEvent> imported = new ArrayList<>(chunk.size());

		try ( PreparedStatement stmt = writeConnection.prepareStatement(sqlBuilder.toString()) ) {
			for ( int i = 0; i < parameters.size(); i++ ) {
				Object param = parameters.get(i);
				if ( param instanceof String[] ) {
					stmt.setArray(i + 1, writeConnection.createArrayOf("text", (String[]) param));
				} else {
					stmt.setObject(i + 1, param);
				}
			}

			try ( ResultSet rs = stmt.executeQuery() ) {
				while ( rs.next() ) {
					long position = rs.getLong("event_position");
					long tx = Long.parseUnsignedLong(rs.getString("event_tx"));
					String returnedId = rs.getString("event_id");

					// Matched on the identifier we supplied rather than on RETURNING row order: with
					// ON CONFLICT the returned rows are a subset of the input, so position in the result
					// set carries no meaning.
					EventToImport event = byId.get(returnedId == null ? null : returnedId.toLowerCase(Locale.ROOT));
					if ( event == null ) {
						throw new EventStorageException("import returned event id %s which was not part of the batch".formatted(returnedId));
					}

					imported.add(event.positionAt(position, tx));
				}
			}
		}

		return imported;
	}

	private static String normalizedId ( EventId id ) {
		return id.value().toLowerCase(Locale.ROOT);
	}

	/** SQLSTATE for {@code unique_violation}. */
	private static final String UNIQUE_VIOLATION = "23505";

	/** Unprefixed name of the partial unique index that scopes idempotency keys to a stream. */
	private static final String IDEMPOTENCY_INDEX = "idx_events_stream_idempotency";

	/**
	 * Name of the stream-scoped idempotency index for this storage's table prefix, as
	 * {@code ensure-schema.sql} writes it.
	 * <p>
	 * The identifier there is unquoted, so the server folds it to lower case — hence the
	 * case-insensitive comparison in {@link #isIdempotencyKeyViolation}. It also truncates identifiers
	 * at 63 bytes, which this name cannot reach: {@code MAX_PREFIX_LENGTH} caps the prefix at 32
	 * characters, leaving 61 at most.
	 *
	 * @return the unprefixed index name with this storage's prefix applied
	 */
	private String idempotencyIndexName ( ) {
		return prefix + IDEMPOTENCY_INDEX;
	}

	/**
	 * The structured error the server sent with a failure, or {@code null} for any {@link SQLException}
	 * that does not carry one — anything not raised by the server, and anything the driver produced
	 * itself.
	 *
	 * @param e the failure to inspect
	 * @return the server error message, or {@code null}
	 */
	private static ServerErrorMessage serverError ( SQLException e ) {
		return e instanceof PSQLException psqlException ? psqlException.getServerErrorMessage() : null;
	}

	/**
	 * The constraint or index the server blamed for a failed statement, or {@code null} if it did not
	 * say.
	 * <p>
	 * This is the field PostgreSQL fills in on a unique violation, and it names the offending
	 * <em>index</em> for a violation of a bare {@code CREATE UNIQUE INDEX} just as it names the
	 * constraint for a table constraint. It is the only structured way to tell one unique violation on
	 * the events table from another: the message text is translated when the server runs under a
	 * non-English {@code lc_messages}, and matching it as a substring also picks up the prefixed names
	 * of the <em>other</em> unique keys on the table.
	 *
	 * @param e the failure to inspect
	 * @return the constraint/index name, or {@code null} if the server did not report one
	 */
	private static String serverConstraintName ( SQLException e ) {
		ServerErrorMessage serverError = serverError(e);
		return serverError == null ? null : serverError.getConstraint();
	}

	/**
	 * Whether a failure is a duplicate of an idempotency key already used on the same stream, as opposed
	 * to any other unique violation the events table can raise.
	 * <p>
	 * Routed by the index name the server reports rather than by the exception message, so that it is
	 * unaffected by the server's message locale and cannot be triggered by a violation of
	 * {@code <prefix>events_pkey} or {@code <prefix>events_event_id_key} — which a substring match on
	 * the message does whenever the caller-supplied table prefix happens to contain the word
	 * "idempotency". Silently swallowing one of those would report a successful de-duplication for an
	 * append that in fact wrote nothing.
	 *
	 * @param e the failure to classify
	 * @return {@code true} if the stream-scoped idempotency index rejected the row
	 */
	private boolean isIdempotencyKeyViolation ( SQLException e ) {
		return UNIQUE_VIOLATION.equals(e.getSQLState())
				&& idempotencyIndexName().equalsIgnoreCase(serverConstraintName(e));
	}

	/**
	 * Turns a failed import into the most specific exception the server error allows.
	 * <p>
	 * A unique violation is routed by constraint name — the stream-scoped idempotency index versus the
	 * event_id uniqueness constraint — rather than by inspecting the message text. The offending event is
	 * recovered by matching the values Postgres reports in the error DETAIL against the batch; when the
	 * server does not supply a parseable DETAIL the conflict is still reported, without the specifics.
	 */
	private EventStorageException classifyImportFailure ( SQLException e, List<EventToImport> events ) {
		if ( !UNIQUE_VIOLATION.equals(e.getSQLState()) ) {
			return new EventStorageException("SQLException during import", e);
		}

		ServerErrorMessage serverError = serverError(e);
		List<String> conflictingValues = parseConflictValues(serverError == null ? null : serverError.getDetail());

		if ( isIdempotencyKeyViolation(e) ) {
			// DETAIL reports (stream_context, stream_purpose, idempotency_key)=(ctx, purpose, key)
			EventToImport conflicting = conflictingValues.size() == 3
					? events.stream()
						.filter(ev -> ev.stream().context().equals(conflictingValues.get(0))
								&& ev.stream().purpose().equals(conflictingValues.get(1))
								&& conflictingValues.get(2).equals(ev.idempotencyKey()))
						.findFirst().orElse(null)
					: null;
			return EventImportConflictException.duplicateIdempotencyKey(
					conflicting == null ? null : conflicting.stream(),
					conflicting == null ? null : conflicting.idempotencyKey(),
					e);
		}

		// DETAIL reports (event_id)=(uuid)
		EventId conflictingId = null;
		if ( conflictingValues.size() == 1 ) {
			String value = conflictingValues.get(0).toLowerCase(Locale.ROOT);
			conflictingId = events.stream()
					.map(EventToImport::id)
					.filter(id -> normalizedId(id).equals(value))
					.findFirst().orElse(null);
		}
		return EventImportConflictException.duplicateEventId(conflictingId, e);
	}

	private static List<String> parseConflictValues ( String detail ) {
		if ( detail == null ) {
			return List.of();
		}
		Matcher matcher = CONFLICT_DETAIL_VALUES.matcher(detail);
		if ( !matcher.find() ) {
			return List.of();
		}
		return List.of(matcher.group(1).split(", ", -1));
	}

	@Override
	public Optional<StoredEvent> getEventById(EventId eventId) {
		checkNotClosed();
		if ( eventId != null ) {
			String sql = """
				SELECT event_position, event_tx::text, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags, idempotency_key
				FROM %sevents
				WHERE event_id = ?::uuid
			""".formatted(prefix);
			
			try ( Connection readConnection = dataSource.getConnection() ) {
				readConnection.setAutoCommit(true);
				try (PreparedStatement stmt = readConnection.prepareStatement(sql)) {
					stmt.setString(1, eventId.value());
					
					try (ResultSet rs = stmt.executeQuery()) {
						if (rs.next()) {
							return Optional.of(mapResultSetToEvent(rs));
						}
					}
				} catch (SQLException e) {
					throw new EventStorageException("Failed to retrieve event by ID: " + eventId.value(), e);
				}
			} catch (SQLException e) {
				throw new EventStorageException("Failed to close connection", e);
			}
		}		
		return Optional.empty();
	}
	
	private <EVENT_TYPE> StoredEvent mapResultSetToEvent(ResultSet rs) throws SQLException {
		long position = rs.getLong("event_position");
		String eventIdValue = rs.getString("event_id");
		long eventTx = Long.parseUnsignedLong(rs.getString("event_tx"));
		String streamContext = rs.getString("stream_context");
		String streamPurpose = rs.getString("stream_purpose");
		String eventTypeName = rs.getString("event_type");
		Timestamp timestamp = rs.getTimestamp("event_timestamp", Calendar.getInstance(TimeZone.getTimeZone("UTC")));
		String eventDataJson = rs.getString("event_data");
		String eventErasableDataJson = rs.getString("event_erasable_data");
		String[] tagsArray = null;
		if (rs.getArray("event_tags") != null) {
			tagsArray = (String[]) rs.getArray("event_tags").getArray();
		}
		String idempotencyKey = rs.getString("idempotency_key");

		// Create EventReference
		EventId eventId = new EventId(eventIdValue);
		EventReference eventReference = EventReference.of(eventId, position, eventTx);

		// Create EventStreamId
		EventStreamId streamId = new EventStreamId(streamContext, streamPurpose);


		// Create Tags from tag array
		Tags tags = Tags.parse(tagsArray);

		return new StoredEvent(streamId, EventType.ofType(eventTypeName), eventReference, eventDataJson, eventErasableDataJson, tags, timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime(), idempotencyKey);
	}

	
	
	class NewEventsAppendedMonitor implements Runnable {

		private static final Logger LOGGER = LoggerFactory.getLogger(NewEventsAppendedMonitor.class);

		private String name;
		private List<EventStoreListener> listeners;
		private DataSource monitoringDataSource;
		private CountDownLatch readyLatch;
		/** the outer storage's flag for this channel: true exactly while this monitor holds a live LISTEN */
		private final AtomicBoolean listening = eventMonitorListening;

		public NewEventsAppendedMonitor ( String name, List<EventStoreListener> listeners, DataSource monitoringDataSource, CountDownLatch readyLatch ) {
			this.name = name;
			this.listeners = listeners;
			this.monitoringDataSource = monitoringDataSource;
			this.readyLatch = readyLatch;
		}

		@Override
		public void run() {
			Thread.currentThread().setName(name);

			LOGGER.info("starting ...");
			
			String listenStatement = "LISTEN %sevent_appended;".formatted(prefix);
			String unlistenStatement = "UNLISTEN %sevent_appended;".formatted(prefix);

			long retryDelayMs = INITIAL_RETRY_DELAY_MS;
			while ( !stopped.get() ) {

				try ( Connection monitorConnection = monitoringDataSource.getConnection(); Statement stmt = monitorConnection.createStatement() ){
					// Ensure connection is in the right state for LISTEN
					monitorConnection.setAutoCommit(true);

					stmt.execute(listenStatement);

					LOGGER.debug("... listening for event appends.");
					if ( listening.compareAndSet(false, true) ) {
						LOGGER.info("event append notifications are available for event storage '{}'", PostgresEventStorageImpl.this.name);
					}
					readyLatch.countDown();

					PGConnection pgConn = monitorConnection.unwrap(PGConnection.class);

					retryDelayMs = INITIAL_RETRY_DELAY_MS;

					while ( !stopped.get() ) { // loop using a single connnection without returning it to the pool

						LOGGER.debug("checking for notifications...");

						PGNotification[] notifications = pgConn.getNotifications(WAIT_FOR_NOTIFICATIONS_TIMEOUT); // returns as soon as a notification arrives, otherwise empty-handed after the poll slice

					    if (notifications != null) {
					        for (PGNotification notification : notifications) {
					            LOGGER.debug("Received: {}", notification.getParameter());
					            try {
									EventAppendedPostgresNotification msg = JSONMAPPER.readValue(notification.getParameter(), EventAppendedPostgresNotification.class);
									AppendsToEventStoreNotification aesn = msg.toNotification();

									listeners.forEach(listener -> {
										// one listener misbehaving must not kill this monitor: it is the only one this
										// storage has, so its death silently stops notifications for every store,
										// listener and projection attached to the storage
										try {
											listener.notify(aesn);
										} catch (Exception e) {
											LOGGER.error("event store listener failed handling a notification: {}", e.getMessage(), e);
										}
									});

								} catch (JacksonException e) {
									LOGGER.error("Failed to parse notification: " + e.getMessage());
								}
					        }
					    }
					}

					listening.set(false);

					// drop the registration so the connection is hygienic when returned to the pool
					try {
						stmt.execute(unlistenStatement);
					} catch (SQLException ue) {
						LOGGER.debug("UNLISTEN failed: {}", ue.getMessage());
					}

				} catch (SQLException e) {
					// notifications stop the moment this connection does, whether that is at startup or an
					// hour in; say so before anything else, so the gauge never claims a channel is up while
					// this monitor is sitting in the backoff below
					boolean wasListening = listening.getAndSet(false);
					if ( stopped.get() || Thread.currentThread().isInterrupted() ) {
						// shutting down: the connection was closed underneath us on purpose, nothing to report
						return;
					}
					if ( wasListening ) {
						LOGGER.warn("lost the notification connection of event storage '{}'; retrying with backoff. "
							+ "Until it is back, subscribers are not woken and projections only advance when run explicitly.",
							PostgresEventStorageImpl.this.name);
					}
					LOGGER.error(e.getMessage(), e);
					try {
						Thread.sleep(retryDelayMs);
						retryDelayMs = Math.min(retryDelayMs * 2, MAX_RETRY_DELAY_MS);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						return;
					}
				} finally {
					LOGGER.debug("loop done.");
				}
			}
		}
	}
	
	
	class BookmarkPlacedMonitor implements Runnable {

		private static final Logger LOGGER = LoggerFactory.getLogger(BookmarkPlacedMonitor.class);

		private String name;
		private List<EventStoreListener> listeners;
		private DataSource monitoringDataSource;
		private CountDownLatch readyLatch;
		/** the outer storage's flag for this channel: true exactly while this monitor holds a live LISTEN */
		private final AtomicBoolean listening = bookmarkMonitorListening;

		public BookmarkPlacedMonitor ( String name, List<EventStoreListener> listeners, DataSource monitoringDataSource, CountDownLatch readyLatch ) {
			this.name = name;
			this.listeners = listeners;
			this.monitoringDataSource = monitoringDataSource;
			this.readyLatch = readyLatch;
		}

		@Override
		public void run() {
			Thread.currentThread().setName(name);

			LOGGER.info("starting ...");
			
			String listenStatement = "LISTEN %sbookmark_placed;".formatted(prefix);
			String unlistenStatement = "UNLISTEN %sbookmark_placed;".formatted(prefix);

			JsonMapper jsonMapper = JsonMapper.builder().build();

			long retryDelayMs = INITIAL_RETRY_DELAY_MS;
			while ( !stopped.get() ) {

				try ( Connection monitorConnection = monitoringDataSource.getConnection(); Statement stmt = monitorConnection.createStatement() ){
					// Ensure connection is in the right state for LISTEN
					monitorConnection.setAutoCommit(true);

					stmt.execute(listenStatement);

					LOGGER.debug("... listening for bookmark updates.");
					if ( listening.compareAndSet(false, true) ) {
						LOGGER.info("bookmark notifications are available for event storage '{}'", PostgresEventStorageImpl.this.name);
					}
					readyLatch.countDown();

					PGConnection pgConn = monitorConnection.unwrap(PGConnection.class);

					retryDelayMs = INITIAL_RETRY_DELAY_MS;

					while ( !stopped.get() ) { // reuse single connection without returing in tot the pool

						LOGGER.debug("checking for notifications...");

						PGNotification[] notifications = pgConn.getNotifications(WAIT_FOR_NOTIFICATIONS_TIMEOUT); // returns as soon as a notification arrives, otherwise empty-handed after the poll slice
					    if (notifications != null) {
					        for (PGNotification notification : notifications) {
					            LOGGER.debug("Received: " + notification.getParameter());
					            try {
									BookmarkPlacedPostgresNotification msg = jsonMapper.readValue(notification.getParameter(), BookmarkPlacedPostgresNotification.class);
									BookmarkPlacedNotification bpn = msg.toNotification();
									LOGGER.debug("notification: " + bpn);

									listeners.forEach(listener -> {
										// one listener misbehaving must not kill this monitor: it is the only one this
										// storage has, so its death silently stops notifications for every store,
										// listener and projection attached to the storage
										try {
											listener.notify(bpn);
										} catch (Exception e) {
											LOGGER.error("event store listener failed handling a notification: {}", e.getMessage(), e);
										}
									});

								} catch (JacksonException e) {
									LOGGER.error("Failed to parse notification: " + e.getMessage());
								}
					        }
					    }
					}

					listening.set(false);

					// drop the registration so the connection is hygienic when returned to the pool
					try {
						stmt.execute(unlistenStatement);
					} catch (SQLException ue) {
						LOGGER.debug("UNLISTEN failed: {}", ue.getMessage());
					}

				} catch (SQLException e) {
					// notifications stop the moment this connection does, whether that is at startup or an
					// hour in; say so before anything else, so the gauge never claims a channel is up while
					// this monitor is sitting in the backoff below
					boolean wasListening = listening.getAndSet(false);
					if ( stopped.get() || Thread.currentThread().isInterrupted() ) {
						// shutting down: the connection was closed underneath us on purpose, nothing to report
						return;
					}
					if ( wasListening ) {
						LOGGER.warn("lost the notification connection of event storage '{}'; retrying with backoff. "
							+ "Until it is back, subscribers are not woken and projections only advance when run explicitly.",
							PostgresEventStorageImpl.this.name);
					}
					LOGGER.error(e.getMessage(), e);
					try {
						Thread.sleep(retryDelayMs);
						retryDelayMs = Math.min(retryDelayMs * 2, MAX_RETRY_DELAY_MS);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						return;
					}
				} finally {
					LOGGER.debug("loop done.");
				}
			}
		}
	}
	
	record EventAppendedPostgresNotification ( String streamContext, String streamPurpose, long eventPosition, long eventTx, String eventId ) { 
		public AppendsToEventStoreNotification toNotification ( ) {
			return new AppendsToEventStoreNotification ( 
					EventStreamId.forContext(streamContext).withPurpose(streamPurpose),
					EventReference.of(EventId.of(eventId), eventPosition, eventTx));
		}
	}

	record BookmarkPlacedPostgresNotification ( String reader, long eventPosition, long eventTx, String eventId  ) { 
		public BookmarkPlacedNotification toNotification ( ) {
			return new BookmarkPlacedNotification ( 
					reader,
					EventReference.of(EventId.of(eventId), eventPosition, eventTx));
		}
	}

	@Override
	public void subscribe(EventStoreListener listener) {
		checkNotClosed();
		// addIfAbsent, so re-registering the same listener does not double its notifications
		listeners.addIfAbsent(listener);
	}

	@Override
	public void unsubscribe(EventStoreListener listener) {
		// deliberately no checkNotClosed: unsubscribing from a closed storage is what an orderly
		// teardown looks like when the storage happened to be closed first, and it must not throw
		listeners.remove(listener);
	}

	@Override
	public Optional<EventReference> getBookmark(String reader) {
		checkNotClosed();
		String sql = """
			SELECT event_position, event_id, event_tx::text
			FROM %sbookmarks
			WHERE reader = ?
		""".formatted(prefix);

		try ( Connection readConnection = dataSource.getConnection() ) {
			readConnection.setAutoCommit(true);
			try (PreparedStatement stmt = readConnection.prepareStatement(sql)) {
				stmt.setString(1, reader);

				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						long position = rs.getLong("event_position");
						long tx = Long.parseUnsignedLong(rs.getString("event_tx"));
						String eventIdValue = rs.getString("event_id");
						EventId eventId = new EventId(eventIdValue);
						return Optional.of(EventReference.of(eventId, position, tx));
					}
				}
			} catch (SQLException e) {
				throw new EventStorageException("Failed to retrieve bookmark for reader: " + reader, e);
			}
		} catch (SQLException e) {
			throw new EventStorageException("Failed to close connection", e);
		}

		return Optional.empty();
	}

	@Override
	public List<Bookmark> getBookmarks() {
		checkNotClosed();
		String sql = """
			SELECT reader, event_position, event_id, event_tx::text, updated_at, updated_tags
			FROM %sbookmarks
		""".formatted(prefix);

		List<Bookmark> bookmarks = new ArrayList<>();
		try ( Connection readConnection = dataSource.getConnection() ) {
			readConnection.setAutoCommit(true);
			try ( PreparedStatement stmt = readConnection.prepareStatement(sql);
			      ResultSet rs = stmt.executeQuery() ) {
				while ( rs.next() ) {
					String reader = rs.getString("reader");
					long position = rs.getLong("event_position");
					long tx = Long.parseUnsignedLong(rs.getString("event_tx"));
					EventId eventId = new EventId(rs.getString("event_id"));
					EventReference reference = EventReference.of(eventId, position, tx);

					String[] tagsArray = new String[0];
					if ( rs.getArray("updated_tags") != null ) {
						tagsArray = (String[]) rs.getArray("updated_tags").getArray();
					}
					Tags tags = Tags.parse(tagsArray);

					Timestamp updatedAtTs = rs.getTimestamp("updated_at", Calendar.getInstance(TimeZone.getTimeZone("UTC")));
					Instant updatedAt = updatedAtTs != null ? updatedAtTs.toInstant() : Instant.EPOCH;

					bookmarks.add(new Bookmark(reader, reference, tags, updatedAt));
				}
			} catch ( SQLException e ) {
				throw new EventStorageException("Failed to list bookmarks", e);
			}
		} catch ( SQLException e ) {
			throw new EventStorageException("Failed to close connection", e);
		}
		return bookmarks;
	}

	
	@Override
	public void bookmark(String reader, EventReference eventReference, Tags tags ) {
		checkNotClosed();
		if ( eventReference == null ) {
			removeBookmark(reader);
		} else {
			String sql = """
				INSERT INTO %sbookmarks (reader, event_position, event_tx, event_id, updated_at, updated_tags)
				VALUES (?, ?, ?::xid8, ?::uuid, CURRENT_TIMESTAMP, ? )
				ON CONFLICT (reader)
				DO UPDATE SET
					event_position = EXCLUDED.event_position,
					event_tx = EXCLUDED.event_tx,
					event_id = EXCLUDED.event_id,
					updated_at = CURRENT_TIMESTAMP,
					updated_tags = EXCLUDED.updated_tags
			""".formatted(prefix); 
			
			try (Connection writeConnection = dataSource.getConnection() ) {
				try {
					writeConnection.setAutoCommit(false);
					
					if ( tags == null ) {
						tags = Tags.none();
					}
					
					try ( PreparedStatement stmt = writeConnection.prepareStatement(sql) ) {
						stmt.setString(1, reader.toString());
						stmt.setLong(2, eventReference == null?0:eventReference.position());
						stmt.setString(3, eventReference == null?"0":Long.toUnsignedString(eventReference.tx()));
						stmt.setString(4, eventReference==null?null:eventReference.id().value());
						
						// Convert tags to array
						String[] tagsArray = tags.toStrings().toArray(new String[0]);
						stmt.setArray(5, writeConnection.createArrayOf("text", (String[]) tagsArray));
						
						int rowsAffected = stmt.executeUpdate();
						if (rowsAffected == 0) {
							writeConnection.rollback();
							throw new EventStorageException("Failed to update bookmark for reader: " + reader);
						}
						writeConnection.commit();
					}
				} catch (SQLException e) {
					try {
						writeConnection.rollback();
					} catch (SQLException rollbackEx) {
						e.addSuppressed(rollbackEx);
					}
					throw new EventStorageException("Failed to bookmark event for reader: " + reader, e);
				}
			} catch (SQLException e) {
				throw new EventStorageException("Failed to close connection", e);
			}
		}
	}
	
	@Override
	public void removeBookmark(String reader ) {
		checkNotClosed();
		String sql = """
			DELETE FROM %sbookmarks  
			WHERE reader = ?
		""".formatted(prefix); 
		
		try (Connection writeConnection = dataSource.getConnection() ) {
			try {
				writeConnection.setAutoCommit(false);
				
				try ( PreparedStatement stmt = writeConnection.prepareStatement(sql) ) {
					stmt.setString(1, reader.toString());
					
					stmt.executeUpdate();
					writeConnection.commit();
				}
			} catch (SQLException e) {
				try {
					writeConnection.rollback();
				} catch (SQLException rollbackEx) {
					e.addSuppressed(rollbackEx);
				}
				throw new EventStorageException("Failed to remove bookmark for reader: " + reader, e);
			}
		} catch (SQLException e) {
			throw new EventStorageException("Failed to close connection", e);
		}
	}

	Limit effectiveLimit ( Limit softLimit ) {
		Limit result;
		if ( softLimit == null || softLimit.isNotSet() ) {
			if ( absoluteLimit != null && absoluteLimit.isSet() ) {
				result = Limit.to(absoluteLimit.value()+1);
			} else {
				result = Limit.none();
			}
		} else if ( absoluteLimit == null || absoluteLimit.isNotSet() ) {
			result = softLimit;
		} else if ( softLimit.value() <= absoluteLimit.value() ){
			result = softLimit;
		} else {
			throw new EventStorageException("query limit exceeds the configured absolute limit of %d".formatted(absoluteLimit.value()));
		}
		return result;
	}
	
	@Override
	public String name() {
		return name;
	}

}
