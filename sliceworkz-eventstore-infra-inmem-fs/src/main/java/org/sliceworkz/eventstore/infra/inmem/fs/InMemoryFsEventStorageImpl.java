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
package org.sliceworkz.eventstore.infra.inmem.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * In-memory event storage with filesystem persistence.
 * <p>
 * Wraps an {@link InMemoryEventStorage} delegate, persisting events and bookmarks to disk
 * as JSON files on every write. On startup, previously persisted files are loaded back into memory.
 */
class InMemoryFsEventStorageImpl implements EventStorage {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

	private final EventStorage delegate;
	private final Path eventsDir;
	private final Path bookmarksDir;
	private final ObjectMapper objectMapper;

	InMemoryFsEventStorageImpl ( Path baseDirectory, String name, Limit limit ) {
		this.eventsDir = baseDirectory.resolve("events");
		this.bookmarksDir = baseDirectory.resolve("bookmarks");
		this.objectMapper = new ObjectMapper();
		this.objectMapper.registerModule(new JavaTimeModule());
		this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
		this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		createDirectories();

		List<StoredEvent> initialEvents = loadEvents();
		Map<String, EventReference> initialBookmarks = loadBookmarks();

		InMemoryEventStorage.Builder builder = InMemoryEventStorage.newBuilder()
				.name(name)
				.initialEvents(initialEvents)
				.initialBookmarks(initialBookmarks);
		if ( limit != null && limit.isSet() ) {
			builder.resultLimit(limit.value().intValue());
		}
		this.delegate = builder.build();
	}

	private void createDirectories ( ) {
		try {
			Files.createDirectories(eventsDir);
			Files.createDirectories(bookmarksDir);
		} catch ( IOException e ) {
			throw new IllegalStateException("failed to create storage directories at " + eventsDir.getParent(), e);
		}
	}

	// --- delegation ---

	@Override
	public String name ( ) {
		return delegate.name();
	}

	@Override
	public Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream, EventReference after, Limit limit, QueryDirection queryDirection ) {
		return delegate.query(query, stream, after, limit, queryDirection);
	}

	@Override
	public List<StoredEvent> append ( AppendCriteria appendCriteria, Optional<EventStreamId> stream, List<EventToStore> events ) {
		List<StoredEvent> stored = delegate.append(appendCriteria, stream, events);
		for ( StoredEvent event : stored ) {
			persistEvent(event);
		}
		return stored;
	}

	@Override
	public Optional<StoredEvent> getEventById ( EventId eventId ) {
		return delegate.getEventById(eventId);
	}

	@Override
	public void subscribe ( EventStoreListener listener ) {
		delegate.subscribe(listener);
	}

	@Override
	public Optional<EventReference> getBookmark ( String reader ) {
		return delegate.getBookmark(reader);
	}

	@Override
	public void bookmark ( String reader, EventReference eventReference, Tags tags ) {
		delegate.bookmark(reader, eventReference, tags);
		persistBookmark(reader, eventReference);
	}

	@Override
	public void removeBookmark ( String reader ) {
		delegate.removeBookmark(reader);
		deleteBookmark(reader);
	}

	// --- persistence: writing ---

	private void persistEvent ( StoredEvent event ) {
		try {
			ObjectNode node = objectMapper.createObjectNode();

			ObjectNode streamNode = objectMapper.createObjectNode();
			streamNode.put("context", event.stream().context());
			streamNode.put("purpose", event.stream().purpose());
			node.set("stream", streamNode);

			node.put("type", event.type().name());

			ObjectNode refNode = objectMapper.createObjectNode();
			refNode.put("id", event.reference().id().value());
			refNode.put("position", event.reference().position());
			refNode.put("tx", event.reference().tx());
			refNode.put("index", event.reference().index());
			node.set("reference", refNode);

			if ( event.immutableData() != null ) {
				node.set("immutableData", objectMapper.readTree(event.immutableData()));
			} else {
				node.putNull("immutableData");
			}
			if ( event.erasableData() != null ) {
				node.set("erasableData", objectMapper.readTree(event.erasableData()));
			} else {
				node.putNull("erasableData");
			}

			ArrayNode tagsArray = objectMapper.createArrayNode();
			for ( Tag tag : event.tags().tags() ) {
				ObjectNode tagNode = objectMapper.createObjectNode();
				tagNode.put("key", tag.key());
				tagNode.put("value", tag.value());
				tagsArray.add(tagNode);
			}
			node.set("tags", tagsArray);

			node.put("timestamp", event.timestamp().toString());

			String fileName = "%010d-%d-%s.json".formatted(event.reference().tx(), event.reference().position(), TIMESTAMP_FORMAT.format(event.timestamp()));
			Path filePath = eventsDir.resolve(fileName);
			Files.writeString(filePath, objectMapper.writeValueAsString(node));
		} catch ( IOException e ) {
			throw new EventStorageException("failed to persist event to " + eventsDir, e);
		}
	}

	private void persistBookmark ( String reader, EventReference reference ) {
		try {
			ObjectNode node = objectMapper.createObjectNode();
			node.put("reader", reader);

			ObjectNode refNode = objectMapper.createObjectNode();
			refNode.put("id", reference.id().value());
			refNode.put("position", reference.position());
			refNode.put("tx", reference.tx());
			refNode.put("index", reference.index());
			node.set("reference", refNode);

			String fileName = sanitizeFileName(reader) + ".json";
			Path filePath = bookmarksDir.resolve(fileName);
			Files.writeString(filePath, objectMapper.writeValueAsString(node));
		} catch ( IOException e ) {
			throw new EventStorageException("failed to persist bookmark for reader " + reader, e);
		}
	}

	private void deleteBookmark ( String reader ) {
		try {
			String fileName = sanitizeFileName(reader) + ".json";
			Path filePath = bookmarksDir.resolve(fileName);
			Files.deleteIfExists(filePath);
		} catch ( IOException e ) {
			throw new EventStorageException("failed to delete bookmark for reader " + reader, e);
		}
	}

	// --- persistence: loading ---

	private List<StoredEvent> loadEvents ( ) {
		List<StoredEvent> events = new ArrayList<>();
		try {
			if ( !Files.exists(eventsDir) ) {
				return events;
			}
			try ( Stream<Path> paths = Files.list(eventsDir) ) {
				List<Path> jsonFiles = paths
						.filter(p -> p.toString().endsWith(".json"))
						.toList();
				for ( Path file : jsonFiles ) {
					events.add(parseEvent(file));
				}
			}
		} catch ( IOException e ) {
			throw new EventStorageException("failed to load events from " + eventsDir, e);
		}

		events.sort(Comparator
				.<StoredEvent, Long>comparing(e -> e.reference().tx())
				.thenComparing(e -> e.reference().position())
				.thenComparing(e -> e.reference().index()));

		return events;
	}

	private StoredEvent parseEvent ( Path file ) {
		try {
			JsonNode node = objectMapper.readTree(Files.readString(file));

			JsonNode streamNode = node.get("stream");
			EventStreamId stream = EventStreamId.forContext(streamNode.get("context").asText())
					.withPurpose(streamNode.get("purpose").asText());

			EventType type = EventType.ofType(node.get("type").asText());

			JsonNode refNode = node.get("reference");
			EventReference reference = EventReference.of(
					EventId.of(refNode.get("id").asText()),
					refNode.get("position").asLong(),
					refNode.get("tx").asLong(),
					refNode.get("index").asInt());

			String immutableData = node.has("immutableData") && !node.get("immutableData").isNull()
					? node.get("immutableData").toString()
					: null;
			String erasableData = node.has("erasableData") && !node.get("erasableData").isNull()
					? node.get("erasableData").toString()
					: null;

			Set<Tag> tagSet = new HashSet<>();
			JsonNode tagsNode = node.get("tags");
			if ( tagsNode != null && tagsNode.isArray() ) {
				for ( JsonNode tagNode : tagsNode ) {
					String key = tagNode.get("key").asText();
					String value = tagNode.has("value") && !tagNode.get("value").isNull()
							? tagNode.get("value").asText()
							: null;
					tagSet.add(Tag.of(key, value));
				}
			}
			Tags tags = new Tags(tagSet);

			LocalDateTime timestamp = LocalDateTime.parse(node.get("timestamp").asText());

			return new StoredEvent(stream, type, reference, immutableData, erasableData, tags, timestamp);
		} catch ( IOException e ) {
			throw new EventStorageException("failed to parse event file " + file, e);
		}
	}

	private Map<String, EventReference> loadBookmarks ( ) {
		Map<String, EventReference> bookmarks = new HashMap<>();
		try {
			if ( !Files.exists(bookmarksDir) ) {
				return bookmarks;
			}
			try ( Stream<Path> paths = Files.list(bookmarksDir) ) {
				List<Path> jsonFiles = paths
						.filter(p -> p.toString().endsWith(".json"))
						.toList();
				for ( Path file : jsonFiles ) {
					parseBookmark(file, bookmarks);
				}
			}
		} catch ( IOException e ) {
			throw new EventStorageException("failed to load bookmarks from " + bookmarksDir, e);
		}
		return bookmarks;
	}

	private void parseBookmark ( Path file, Map<String, EventReference> bookmarks ) {
		try {
			JsonNode node = objectMapper.readTree(Files.readString(file));
			String reader = node.get("reader").asText();
			JsonNode refNode = node.get("reference");
			EventReference reference = EventReference.of(
					EventId.of(refNode.get("id").asText()),
					refNode.get("position").asLong(),
					refNode.get("tx").asLong(),
					refNode.get("index").asInt());
			bookmarks.put(reader, reference);
		} catch ( IOException e ) {
			throw new EventStorageException("failed to parse bookmark file " + file, e);
		}
	}

	// --- helpers ---

	private static String sanitizeFileName ( String name ) {
		return name.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

}
