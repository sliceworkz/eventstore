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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventQueryItem;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

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
	
	private String name;
	private String prefix;
	private DataSource dataSource;
	private Limit absoluteLimit;
	
	private List<EventStoreListener> listeners = new CopyOnWriteArrayList<>();
	private ExecutorService executorService;
	private boolean stopped;
	
	private static final JsonMapper JSONMAPPER = new JsonMapper();
	
	private static final int ONE_MINUTE = 60*1000;
	public static final int WAIT_FOR_NOTIFICATIONS_TIMEOUT = ONE_MINUTE;

	private static final String NO_PREFIX = "";
	private static final int MAX_PREFIX_LENGTH = 32;

	public PostgresEventStorageImpl ( String name, DataSource dataSource, DataSource monitoringDataSource, Limit absoluteLimit ) {
		this(name, dataSource, monitoringDataSource, absoluteLimit, NO_PREFIX);
	}

	public PostgresEventStorageImpl ( String name, DataSource dataSource, DataSource monitoringDataSource, Limit absoluteLimit, String prefix ) {
		this.prefix = validatePrefix(prefix);
		this.name = name;
		this.dataSource = dataSource;
		this.absoluteLimit = absoluteLimit;
		this.executorService = Executors.newVirtualThreadPerTaskExecutor();
		
		this.executorService.execute(new NewEventsAppendedMonitor("event-append-listener/" + name, listeners, monitoringDataSource));
		this.executorService.execute(new BookmarkPlacedMonitor("bookmark-listener/" + name, listeners, monitoringDataSource));
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
	
	public PostgresEventStorageImpl initializeDatabase ( ) {
		try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("initialisation.sql")) {
			if (inputStream == null) {
				throw new EventStorageException("Could not find initialisation.sql in classpath");
			}
			
			String sql = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			sql = sql.replaceAll("PREFIX_", prefix);
			
			try ( Connection writeConnection = dataSource.getConnection() ) {
				try (Statement statement = writeConnection.createStatement()) {
					statement.execute(sql);
				}
			}
			
		} catch (IOException | SQLException e) {
			throw new EventStorageException("Failed to initialize database", e);
		}
		return this;
	}
	
	public void stop ( ) {
		this.stopped = true;
		executorService.shutdown();
	}

	@Override
	public Stream<StoredEvent> query(EventQuery query, Optional<EventStreamId> stream, EventReference after, Limit limit, QueryDirection direction ) {
		return queryAfter(query, stream, after, limit, direction);
	}
	
	public Stream<StoredEvent> queryFrom(EventQuery query, Optional<EventStreamId> streamId, EventReference from, Limit limit, QueryDirection direction) {
		return queryFromOrAfter(query, streamId, limit, from, true, direction);
	}
	
	public Stream<StoredEvent> queryAfter(EventQuery query, Optional<EventStreamId> streamId, EventReference after, Limit limit, QueryDirection direction) {
		return queryFromOrAfter(query, streamId, limit, after, false, direction);
	}
	
	public Stream<StoredEvent> queryFromOrAfter(EventQuery query, Optional<EventStreamId> streamId, Limit limit, EventReference reference, boolean includeReference, QueryDirection direction ) {
		// Handle the case where query matches none - return empty stream
		if (query.isMatchNone()) {
			return Stream.empty();
		}
		
		// Handle case where reference exists but has no position - return empty stream for optimistic locking
		if (reference != null && reference.position() == null) {
			return Stream.empty();
		}
		
		StringBuilder sqlBuilder = new StringBuilder();
		sqlBuilder.append(
			"SELECT event_position, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags FROM %sevents WHERE 1=1".formatted(prefix)
			);
		
		List<Object> parameters = new ArrayList<>();
		
		// Add position filtering if reference is provided
		if (reference != null && reference.position() != null) {
			if (includeReference) {
				if ( direction == QueryDirection.FORWARD ) {
					sqlBuilder.append(" AND event_position >= ?");
				} else { 
					sqlBuilder.append(" AND event_position <= ?");
				}
			} else {
				if ( direction == QueryDirection.FORWARD ) {
					sqlBuilder.append(" AND event_position > ?");
				} else { 
					sqlBuilder.append(" AND event_position < ?");
				}
			}
			parameters.add(reference.position());
		}
		
		if ( query.until() != null ) {
			if ( direction == QueryDirection.FORWARD ) {
				sqlBuilder.append(" AND event_position <= ?");
			} else { 
				sqlBuilder.append(" AND event_position >= ?");
			}
			parameters.add(query.until().position());
		}
		
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
		
		// Add EventQuery filtering (event types and tags)
		if (!query.isMatchAll()) {
			addEventQueryFiltering(sqlBuilder, parameters, query);
		}
		
		// Order by position
		sqlBuilder.append(" ORDER BY event_position");
		if ( direction == QueryDirection.BACKWARD ) {
			sqlBuilder.append(" DESC");
		}
		
		Limit effectiveLimit = effectiveLimit(limit);
		
		// Add limit if specified
		if (effectiveLimit != null && effectiveLimit.isSet()) {
			sqlBuilder.append(" LIMIT ?");
			parameters.add(effectiveLimit.value());
		}
		
		try ( Connection readConnection = dataSource.getConnection() ) {
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
	
	private void addEventQueryFiltering(StringBuilder sqlBuilder, List<Object> parameters, EventQuery query) {
		if (query.items() == null || query.items().isEmpty()) {
			return; // matchAll case is already handled
		}
		
		sqlBuilder.append(" AND (");
		boolean first = true;
		
		for (EventQueryItem item : query.items()) {
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
	
	@Override
	public List<StoredEvent> append(AppendCriteria appendCriteria, Optional<EventStreamId> streamId, List<EventToStore> events) {
		// Build conditional insert with optimistic locking check
		StringBuilder sqlBuilder = new StringBuilder();
		sqlBuilder.append("INSERT INTO %sevents (event_id, stream_context, stream_purpose, event_type, event_data, event_erasable_data, event_tags) SELECT * FROM ( VALUES ".formatted(prefix));
		for ( int i = 0; i < events.size(); i++ ) {
			if ( i > 0 ) {
				sqlBuilder.append(", ");
			}
			sqlBuilder.append("(?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, ?)");
		}
		sqlBuilder.append(")");

		List<Object> parameters = new ArrayList<>();
		
		List<EventId> ids = new ArrayList<>();

		for ( EventToStore event: events ) {
			
			EventId id = EventId.create();
			ids.add(id);
			
			// Add to-be-appended-event parameters first
			parameters.add(id.value());
			parameters.add(event.stream().context());
			parameters.add(event.stream().purpose());
			parameters.add(event.type().name());
	
			parameters.add(event.immutableData());
			parameters.add(event.erasableData());
			
			// Convert tags to array
			String[] tagsArray = event.tags().toStrings().toArray(new String[event.tags().tags().size()]);
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
			
			if ( appendCriteria.expectedLastEventReference() != null && appendCriteria.expectedLastEventReference().isPresent() && appendCriteria.expectedLastEventReference().get().position()!=null ) {
				
				// Add position filtering - check for events after the expected last event
				sqlBuilder.append(" AND event_position > ?");
				parameters.add(appendCriteria.expectedLastEventReference().get().position());
			}
		
		
			// Add EventQuery filtering for the consistency boundary
			if (!appendCriteria.eventQuery().isMatchAll()) {
				addEventQueryFiltering(sqlBuilder, parameters, appendCriteria.eventQuery());
			}
			
			sqlBuilder.append(") ");
		}
		
		sqlBuilder.append("RETURNING event_position, event_timestamp");
		
		
		try ( Connection writeConnection = dataSource.getConnection(); PreparedStatement stmt = writeConnection.prepareStatement(sqlBuilder.toString())) {
				// Set parameters
				for (int i = 0; i < parameters.size(); i++) {
					Object param = parameters.get(i);
					if (param instanceof String[]) {
						stmt.setArray(i + 1, writeConnection.createArrayOf("text", (String[]) param));
					} else {
						stmt.setObject(i + 1, param);
					}
				}
				
				List<StoredEvent> storedEvents = new ArrayList<>();
				
				try (ResultSet rs = stmt.executeQuery()) {
					
					Iterator<EventToStore> it = events.iterator();
					Iterator<EventId> idIterator = ids.iterator();
					
					while (rs.next()) {
						long position = rs.getLong("event_position");
						Timestamp timestamp = rs.getTimestamp("event_timestamp");
						
						EventToStore e = it.next();
						EventId id = idIterator.next();
						
						EventReference reference = EventReference.of(id, position);
						storedEvents.add(e.positionAt(reference, timestamp.toLocalDateTime()));
					}
					
					if ( storedEvents.size() != events.size() ) {
						// Insert failed due to optimistic locking conflict
						throw new OptimisticLockingException(appendCriteria.eventQuery(), appendCriteria.expectedLastEventReference());
					}
				}
				
				return storedEvents;
		} catch (SQLException e) {
			throw new EventStorageException("SQLException during append", e);
		}
	}

	@Override
	public Optional<StoredEvent> getEventById(EventId eventId) {
		if ( eventId != null ) {
			String sql = """
				SELECT event_position, event_id, stream_context, stream_purpose, event_type, event_timestamp, event_data, event_erasable_data, event_tags 
				FROM %sevents 
				WHERE event_id = ?::uuid
			""".formatted(prefix);
			
			try ( Connection readConnection = dataSource.getConnection() ) {
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
		String streamContext = rs.getString("stream_context");
		String streamPurpose = rs.getString("stream_purpose");
		String eventTypeName = rs.getString("event_type");
		Timestamp timestamp = rs.getTimestamp("event_timestamp");
		String eventDataJson = rs.getString("event_data");
		String eventErasableDataJson = rs.getString("event_erasable_data");
		String[] tagsArray = null;
		if (rs.getArray("event_tags") != null) {
			tagsArray = (String[]) rs.getArray("event_tags").getArray();
		}
		
		// Create EventReference
		EventId eventId = new EventId(eventIdValue);
		EventReference eventReference = EventReference.of(eventId, position);
		
		// Create EventStreamId
		EventStreamId streamId = new EventStreamId(streamContext, streamPurpose);
		
		
		// Create Tags from tag array
		Tags tags = Tags.parse(tagsArray);
		
		return new StoredEvent(streamId, EventType.ofType(eventTypeName), eventReference, eventDataJson, eventErasableDataJson, tags, timestamp.toLocalDateTime());
	}

	
	
	class NewEventsAppendedMonitor implements Runnable {
		
		private static final Logger LOGGER = LoggerFactory.getLogger(NewEventsAppendedMonitor.class);
		
		private String name;
		private List<EventStoreListener> listeners;
		private DataSource monitoringDataSource;
		
		public NewEventsAppendedMonitor ( String name, List<EventStoreListener> listeners, DataSource monitoringDataSource ) {
			this.name = name;
			this.listeners = listeners;
			this.monitoringDataSource = monitoringDataSource;
		}

		@Override
		public void run() {
			Thread.currentThread().setName(name);

			LOGGER.info("starting ...");
			
			String listenStatement = "LISTEN %sevent_appended;".formatted(prefix);
			
			while ( !stopped ) {

				try ( Connection monitorConnection = monitoringDataSource.getConnection(); Statement stmt = monitorConnection.createStatement() ){
					// Ensure connection is in the right state for LISTEN
					monitorConnection.setAutoCommit(true);
					
					stmt.execute(listenStatement);

					LOGGER.debug("... listening for event appends.");

					PGConnection pgConn = monitorConnection.unwrap(PGConnection.class);

					LOGGER.debug("checking for notifications...");

					PGNotification[] notifications = pgConn.getNotifications(WAIT_FOR_NOTIFICATIONS_TIMEOUT); // wait at max so long for new notifications, then ask new connection and start waiting again
					
					// we'll only keep one notification (the last one) per eventstream to notify our listeners - avoids duplicate activity
					Map<EventStreamId,AppendsToEventStoreNotification> notificationsPerStream = new HashMap<>();
					
				    if (notifications != null) {
				        for (PGNotification notification : notifications) {
				            LOGGER.debug("Received: {}", notification.getParameter());
				            try {
								EventAppendedPostgresNotification msg = JSONMAPPER.readValue(notification.getParameter(), EventAppendedPostgresNotification.class);
								AppendsToEventStoreNotification aesn = msg.toNotification();
								
								notificationsPerStream.put(aesn.stream(), aesn); // this replaces any previous ones on the same eventstream
								
							} catch (JsonProcessingException e) {
								LOGGER.error("Failed to parse notification: " + e.getMessage());
							}
				        }
				    }

				    // notify all listeners - with max 1 notification per message stream (the most recent one)
				    notificationsPerStream.values().forEach(n->listeners.forEach(l->l.notify(n)));

				} catch (SQLException e) {
					if ( !stopped ) {
						throw new EventStorageException(e);
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
		
		public BookmarkPlacedMonitor ( String name, List<EventStoreListener> listeners, DataSource monitoringDataSource ) {
			this.name = name;
			this.listeners = listeners;
			this.monitoringDataSource = monitoringDataSource;
		}

		@Override
		public void run() {
			Thread.currentThread().setName(name);

			LOGGER.info("starting ...");
			
			String listenStatement = "LISTEN %sbookmark_placed;".formatted(prefix);
			
			JsonMapper jsonMapper = new JsonMapper ( );
			
			while ( !stopped ) {

				try ( Connection monitorConnection = monitoringDataSource.getConnection(); Statement stmt = monitorConnection.createStatement() ){
					// Ensure connection is in the right state for LISTEN
					monitorConnection.setAutoCommit(true);
					
					stmt.execute(listenStatement);

					LOGGER.debug("... listening for bookmark updates.");

					PGConnection pgConn = monitorConnection.unwrap(PGConnection.class);

					LOGGER.debug("checking for notifications...");

					// we'll only keep one notification (the last one) per reader to notify our listeners - avoids duplicate activity
					Map<String,BookmarkPlacedNotification> bookmarkPlacedsPerReader = new HashMap<>();

					PGNotification[] notifications = pgConn.getNotifications(WAIT_FOR_NOTIFICATIONS_TIMEOUT); // wait at for new notifications, then ask new connection and start waiting again 
				    if (notifications != null) {
				        for (PGNotification notification : notifications) {
				            LOGGER.debug("Received: " + notification.getParameter());
				            try {
								BookmarkPlacedPostgresNotification msg = jsonMapper.readValue(notification.getParameter(), BookmarkPlacedPostgresNotification.class);
								BookmarkPlacedNotification bpn = msg.toNotification();
								LOGGER.debug("notification: " + bpn);
								
								bookmarkPlacedsPerReader.put(bpn.reader(), bpn);
								
							} catch (JsonProcessingException e) {
								LOGGER.error("Failed to parse notification: " + e.getMessage());
							}
				        }
				    }

				    // notify all listeners - with max 1 notification per message stream (the most recent one)
				    bookmarkPlacedsPerReader.values().forEach(n->listeners.forEach(l->l.notify(n)));

				} catch (SQLException e) {
					if ( !stopped ) {
						throw new EventStorageException(e);
					}
				} finally {
					LOGGER.debug("loop done.");
				}
			}
		}
	}
	
	record EventAppendedPostgresNotification ( String streamContext, String streamPurpose, long eventPosition, String eventId, String eventType ) { 
		public AppendsToEventStoreNotification toNotification ( ) {
			return new AppendsToEventStoreNotification ( 
					EventStreamId.forContext(streamContext).withPurpose(streamPurpose),
					EventReference.of(EventId.of(eventId), eventPosition));
		}
	}

	record BookmarkPlacedPostgresNotification ( String reader, long eventPosition, String eventId  ) { 
		public BookmarkPlacedNotification toNotification ( ) {
			return new BookmarkPlacedNotification ( 
					reader,
					EventReference.of(EventId.of(eventId), eventPosition));
		}
	}

	@Override
	public void subscribe(EventStoreListener listener) {
		listeners.add(listener);
	}

	@Override
	public Optional<EventReference> getBookmark(String reader) {
		String sql = """
			SELECT event_position, event_id 
			FROM %sbookmarks 
			WHERE reader = ?
		""".formatted(prefix);
		
		try ( Connection readConnection = dataSource.getConnection() ) {
			try (PreparedStatement stmt = readConnection.prepareStatement(sql)) {
				stmt.setString(1, reader);
				
				try (ResultSet rs = stmt.executeQuery()) {
					if (rs.next()) {
						long position = rs.getLong("event_position");
						String eventIdValue = rs.getString("event_id");
						EventId eventId = new EventId(eventIdValue);
						return Optional.of(EventReference.of(eventId, position));
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
	public void bookmark(String reader, EventReference eventReference, Tags tags ) {
		String sql = """
			INSERT INTO %sbookmarks (reader, event_position, event_id, updated_at, updated_tags) 
			VALUES (?, ?, ?::uuid, CURRENT_TIMESTAMP, ? )
			ON CONFLICT (reader) 
			DO UPDATE SET 
				event_position = EXCLUDED.event_position,
				event_id = EXCLUDED.event_id,
				updated_at = CURRENT_TIMESTAMP,
				updated_tags = EXCLUDED.updated_tags
		""".formatted(prefix); 
		
		try (Connection writeConnection = dataSource.getConnection() ) {
			try {
				writeConnection.setAutoCommit(false);
				
				try ( PreparedStatement stmt = writeConnection.prepareStatement(sql) ) {
					stmt.setString(1, reader.toString());
					stmt.setLong(2, eventReference.position());
					stmt.setString(3, eventReference.id().value());
					
					// Convert tags to array
					String[] tagsArray = tags.toStrings().toArray(new String[tags.tags().size()]);
					stmt.setArray(4, writeConnection.createArrayOf("text", (String[]) tagsArray));
					
					int rowsAffected = stmt.executeUpdate();
					if (rowsAffected == 0) {
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
