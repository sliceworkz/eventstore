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
package org.sliceworkz.eventstore.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The builder's argument checks. What a selection reads is pinned per backend by the TCK
 * ({@code EventImportTest}); this only covers what needs no storage.
 */
public class EventStoreImporterBuilderTest {

	private static EventStorage aStorage ( ) {
		return new EventStorage() {
			@Override public String name ( ) { return "stub"; }
			@Override public Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream, EventReference after, Limit limit, QueryDirection queryDirection ) { return Stream.empty(); }
			@Override public List<StoredEvent> append ( AppendCriteria appendCriteria, Optional<EventStreamId> stream, List<EventToStore> events ) { return List.of(); }
			@Override public Optional<StoredEvent> getEventById ( org.sliceworkz.eventstore.events.EventId id ) { return Optional.empty(); }
			@Override public void bookmark ( String reader, EventReference reference, org.sliceworkz.eventstore.events.Tags tags ) { }
			@Override public Optional<EventReference> getBookmark ( String reader ) { return Optional.empty(); }
			@Override public void removeBookmark ( String reader ) { }
			@Override public List<org.sliceworkz.eventstore.events.Bookmark> getBookmarks ( ) { return List.of(); }
			@Override public void subscribe ( EventStoreListener listener ) { }
			@Override public List<StoredEvent> importEvents ( List<EventToImport> events, ImportMode mode ) { return List.of(); }
		};
	}

	@Test
	void aNullStreamIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventStoreImporter.from(aStorage()).stream(null));
		assertEquals("stream is required, omit the call to read every stream", e.getMessage());
	}

	@Test
	void aNullFilterIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventStoreImporter.from(aStorage()).matching(null));
		assertEquals("filter is required, omit the call to read every event", e.getMessage());
	}

	@Test
	void aSelectionOnAnEmptySourceReadsNothing ( ) {
		ImportReport report = EventStoreImporter.from(aStorage()).to(aStorage())
				.stream(EventStreamId.forContext("ledger").withPurpose("2024Q1"))
				.matching(EventFilter.matchAll())
				.run();
		assertEquals(0, report.read());
	}

}
