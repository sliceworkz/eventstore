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
package org.sliceworkz.eventstore.infra.file;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The append-only binary log implementation of {@link EventStorage}.
 * <p>
 * Package-private: {@link FileEventStorage} is the way in, and everything the caller needs is on the
 * SPI. See that interface for what this storage is for and what it deliberately is not.
 *
 * <h2>Not yet implemented</h2>
 * This is the module skeleton. It exists so the build wiring, the TCK registration and the coverage
 * guard are in place and verified before any of the format is written — every operation throws.
 */
class FileEventStorageImpl implements EventStorage {

	private final String name;

	FileEventStorageImpl ( Path directory, String name, Limit absoluteLimit, Durability durability, long segmentSizeBytes ) {
		this.name = name;
		throw new UnsupportedOperationException("the file-backed event storage is not implemented yet");
	}

	@Override
	public String name ( ) {
		return name;
	}

	@Override
	public Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream,
			EventReference after, Limit limit, QueryDirection queryDirection ) {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<StoredEvent> append ( AppendCriteria appendCriteria, Optional<EventStreamId> stream, List<EventToStore> events ) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Optional<StoredEvent> getEventById ( EventId eventId ) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void subscribe ( EventStoreListener listener ) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void unsubscribe ( EventStoreListener listener ) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Optional<EventReference> getBookmark ( String reader ) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void bookmark ( String reader, EventReference eventReference, Tags tags ) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void removeBookmark ( String reader ) {
		throw new UnsupportedOperationException();
	}

	@Override
	public List<Bookmark> getBookmarks ( ) {
		throw new UnsupportedOperationException();
	}

}
