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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.serialization.json.JsonBookmark;
import org.sliceworkz.eventstore.serialization.json.JsonBookmarkCodec;
import org.sliceworkz.eventstore.serialization.json.JsonEventCodec;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * In-memory event storage with filesystem persistence.
 * <p>
 * Wraps an {@link InMemoryEventStorage} delegate, persisting events and bookmarks to disk
 * as JSON files on every write. On startup, previously persisted files are loaded back into memory.
 */
class InMemoryFsEventStorageImpl implements EventStorage {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSSS");

	private final EventStorage delegate;
	private final Path eventsDir;
	private final Path bookmarksDir;
	private final JsonEventCodec eventCodec;
	private final JsonBookmarkCodec bookmarkCodec;

	InMemoryFsEventStorageImpl ( Path baseDirectory, String name, Limit limit ) {
		this.eventsDir = baseDirectory.resolve("events");
		this.bookmarksDir = baseDirectory.resolve("bookmarks");
		this.eventCodec = new JsonEventCodec();
		this.bookmarkCodec = new JsonBookmarkCodec();

		createDirectories();

		List<StoredEvent> initialEvents = loadEvents();
		Map<String, Bookmark> initialBookmarks = loadBookmarks();

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
	public List<Bookmark> getBookmarks ( ) {
		return delegate.getBookmarks();
	}

	@Override
	public void bookmark ( String reader, EventReference eventReference, Tags tags ) {
		delegate.bookmark(reader, eventReference, tags);
		// after delegate.bookmark, the snapshot in the delegate carries the canonical metadata
		// (effective tags, updatedAt assigned by the delegate); persist that snapshot to disk
		Bookmark snapshot = delegate.getBookmarks().stream()
				.filter(b -> b.reader().equals(reader))
				.findFirst()
				.orElseThrow(() -> new EventStorageException("bookmark snapshot missing for reader " + reader));
		persistBookmark(snapshot);
	}

	@Override
	public void removeBookmark ( String reader ) {
		delegate.removeBookmark(reader);
		deleteBookmark(reader);
	}

	// --- persistence: writing ---

	private void persistEvent ( StoredEvent event ) {
		try {
			String fileName = "%010d-%05d-%d-%s.json".formatted(event.reference().tx(), event.reference().position(), event.reference().index(), TIMESTAMP_FORMAT.format(event.timestamp()));
			Path filePath = eventsDir.resolve(fileName);
			Files.writeString(filePath, eventCodec.write(event));
		} catch ( IOException e ) {
			throw new EventStorageException("failed to persist event to " + eventsDir, e);
		}
	}

	private void persistBookmark ( Bookmark bookmark ) {
		try {
			String fileName = sanitizeFileName(bookmark.reader()) + ".json";
			Path filePath = bookmarksDir.resolve(fileName);
			Files.writeString(filePath, bookmarkCodec.write(bookmark.reader(), bookmark.reference(), bookmark.tags(), bookmark.updatedAt()));
		} catch ( IOException e ) {
			throw new EventStorageException("failed to persist bookmark for reader " + bookmark.reader(), e);
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
					events.add(readEvent(file));
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

	private StoredEvent readEvent ( Path file ) {
		try {
			return eventCodec.read(Files.readString(file));
		} catch ( IOException e ) {
			throw new EventStorageException("failed to read event file " + file, e);
		}
	}

	private Map<String, Bookmark> loadBookmarks ( ) {
		Map<String, Bookmark> bookmarks = new HashMap<>();
		try {
			if ( !Files.exists(bookmarksDir) ) {
				return bookmarks;
			}
			try ( Stream<Path> paths = Files.list(bookmarksDir) ) {
				List<Path> jsonFiles = paths
						.filter(p -> p.toString().endsWith(".json"))
						.toList();
				for ( Path file : jsonFiles ) {
					readBookmark(file, bookmarks);
				}
			}
		} catch ( IOException e ) {
			throw new EventStorageException("failed to load bookmarks from " + bookmarksDir, e);
		}
		return bookmarks;
	}

	private void readBookmark ( Path file, Map<String, Bookmark> bookmarks ) {
		try {
			JsonBookmark parsed = bookmarkCodec.read(Files.readString(file));
			bookmarks.put(parsed.reader(), new Bookmark(parsed.reader(), parsed.reference(), parsed.tags(), parsed.updatedAt()));
		} catch ( IOException e ) {
			throw new EventStorageException("failed to read bookmark file " + file, e);
		}
	}

	// --- helpers ---

	private static String sanitizeFileName ( String name ) {
		return name.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

}
