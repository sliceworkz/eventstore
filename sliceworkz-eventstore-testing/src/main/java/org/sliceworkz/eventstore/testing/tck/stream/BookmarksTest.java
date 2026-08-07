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
package org.sliceworkz.eventstore.testing.tck.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class BookmarksTest extends AbstractEventStoreTest {

	private EventStream<MockDomainEvent> stream ( ) {
		return eventStore().getEventStream(EventStreamId.forContext("a").withPurpose("p"), MockDomainEvent.class);
	}

	private EventReference appendOne ( ) {
		EventStream<MockDomainEvent> s = stream();
		s.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e"), Tags.none())));
		return s.query(EventQuery.matchAll().backwards().limit(1)).findFirst().orElseThrow().reference();
	}

	@ForEachBackend
	void emptyStoreReturnsEmptyList ( ) {
		List<Bookmark> bookmarks = stream().getBookmarks();
		assertNotNull(bookmarks);
		assertTrue(bookmarks.isEmpty());
	}

	@ForEachBackend
	void listsAllBookmarksAcrossReaders ( ) {
		EventReference ref = appendOne();
		EventStream<MockDomainEvent> s = stream();

		s.placeBookmark("reader-a", ref, Tags.none());
		s.placeBookmark("reader-b", ref, Tags.parse("k:v"));

		List<Bookmark> bookmarks = s.getBookmarks();
		assertEquals(2, bookmarks.size());

		Map<String, Bookmark> byReader = bookmarks.stream().collect(java.util.stream.Collectors.toMap(Bookmark::reader, b -> b));
		assertTrue(byReader.containsKey("reader-a"));
		assertTrue(byReader.containsKey("reader-b"));
		assertEquals(ref, byReader.get("reader-a").reference());
		assertEquals(ref, byReader.get("reader-b").reference());
	}

	@ForEachBackend
	void includesTagsAndUpdatedAt ( ) {
		EventReference ref = appendOne();
		Instant before = Instant.now().minusSeconds(2);

		EventStream<MockDomainEvent> s = stream();
		s.placeBookmark("tagged-reader", ref, Tags.parse("status:processed", "version:7"));

		Bookmark bookmark = s.getBookmarks().stream()
				.filter(b -> "tagged-reader".equals(b.reader()))
				.findFirst()
				.orElseThrow();

		assertEquals(ref, bookmark.reference());
		assertEquals(Tags.parse("status:processed", "version:7"), bookmark.tags());
		assertNotNull(bookmark.updatedAt());
		assertTrue(bookmark.updatedAt().isAfter(before),
				"updatedAt %s should be after %s".formatted(bookmark.updatedAt(), before));
	}

	@ForEachBackend
	void removeExcludesFromList ( ) {
		EventReference ref = appendOne();
		EventStream<MockDomainEvent> s = stream();
		s.placeBookmark("transient-reader", ref, Tags.none());
		assertEquals(1, s.getBookmarks().size());

		s.removeBookmark("transient-reader");
		assertTrue(s.getBookmarks().isEmpty());
	}

	@ForEachBackend
	void rePlacingBookmarkReplacesExistingEntry ( ) {
		EventReference ref = appendOne();
		EventStream<MockDomainEvent> s = stream();
		s.placeBookmark("repeat-reader", ref, Tags.parse("phase:first"));
		s.placeBookmark("repeat-reader", ref, Tags.parse("phase:second"));

		List<Bookmark> bookmarks = s.getBookmarks();
		assertEquals(1, bookmarks.size());
		assertEquals(Tags.parse("phase:second"), bookmarks.get(0).tags());
	}

	/**
	 * A bookmark is a position in the store's log, so a reference the store never stored — typically
	 * one taken from a <em>different</em> store or prefix in a miswired multi-store setup — is a
	 * caller error, rejected loudly at write time rather than stored as a cursor that poisons the
	 * reader. The Postgres backend enforces this through the {@code fk_bookmarks_event_id} foreign
	 * key; the in-memory backends check the event id against their log. The check is on the event id
	 * alone, matching the foreign key.
	 */
	@ForEachBackend
	void bookmarkNamingNoStoredEventIsRejected ( ) {
		EventReference real = appendOne();
		EventStream<MockDomainEvent> s = stream();

		// same position and tx as a stored event, but an id the store has never seen
		EventReference fabricated = EventReference.create(real.position(), real.tx());
		assertThrows(EventStorageException.class,
				() -> s.placeBookmark("misdirected-reader", fabricated, Tags.none()),
				"a bookmark must name an event this storage stored");
		assertTrue(s.getBookmarks().isEmpty(), "the rejected bookmark must not have been stored");
	}

	/**
	 * The rejection must not damage what was already there: a reader whose bookmark update is
	 * rejected keeps its previous bookmark — reference and tags — rather than being reset.
	 */
	@ForEachBackend
	void rejectedBookmarkLeavesThePreviousOneInPlace ( ) {
		EventReference real = appendOne();
		EventStream<MockDomainEvent> s = stream();
		s.placeBookmark("guarded-reader", real, Tags.parse("phase:before"));

		EventReference fabricated = EventReference.create(real.position(), real.tx());
		assertThrows(EventStorageException.class,
				() -> s.placeBookmark("guarded-reader", fabricated, Tags.parse("phase:after")));

		List<Bookmark> bookmarks = s.getBookmarks();
		assertEquals(1, bookmarks.size());
		assertEquals(real, bookmarks.get(0).reference(), "the previous bookmark must survive the rejected update");
		assertEquals(Tags.parse("phase:before"), bookmarks.get(0).tags());
	}

	@ForEachBackend
	void snapshotIsIndependentOfSubsequentMutations ( ) {
		EventReference ref = appendOne();
		EventStream<MockDomainEvent> s = stream();
		s.placeBookmark("snapshot-reader", ref, Tags.none());

		List<Bookmark> snapshot = s.getBookmarks();
		s.placeBookmark("other-reader", ref, Tags.none());

		// snapshot may or may not be a copy — if it is mutable, expect 1; if it is live we'd see 2.
		// the SPI/API contract calls it a snapshot, so we expect it to be unaffected.
		assertFalse(snapshot.size() > 1, "getBookmarks() should return a snapshot, not a live view");
	}

}
