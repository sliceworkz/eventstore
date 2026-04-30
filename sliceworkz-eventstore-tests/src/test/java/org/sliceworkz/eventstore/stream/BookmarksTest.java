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
package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.mock.MockDomainEvent;
import org.sliceworkz.eventstore.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;

class BookmarksTest {

	abstract static class Tests {

		private EventStorage eventStorage;
		private EventStore eventStore;

		@BeforeEach
		public void setUp ( ) {
			this.eventStorage = createEventStorage();
			this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
		}

		@AfterEach
		public void tearDown ( ) {
			destroyEventStorage(eventStorage);
		}

		abstract EventStorage createEventStorage ( );

		void destroyEventStorage ( EventStorage storage ) {
		}

		private EventStream<MockDomainEvent> stream ( ) {
			return eventStore.getEventStream(EventStreamId.forContext("a").withPurpose("p"), MockDomainEvent.class);
		}

		private EventReference appendOne ( ) {
			EventStream<MockDomainEvent> s = stream();
			s.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e"), Tags.none())));
			return s.query(EventQuery.matchAll().backwards().limit(1)).findFirst().orElseThrow().reference();
		}

		@Test
		void emptyStoreReturnsEmptyList ( ) {
			List<Bookmark> bookmarks = stream().getBookmarks();
			assertNotNull(bookmarks);
			assertTrue(bookmarks.isEmpty());
		}

		@Test
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

		@Test
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

		@Test
		void removeExcludesFromList ( ) {
			EventReference ref = appendOne();
			EventStream<MockDomainEvent> s = stream();
			s.placeBookmark("transient-reader", ref, Tags.none());
			assertEquals(1, s.getBookmarks().size());

			s.removeBookmark("transient-reader");
			assertTrue(s.getBookmarks().isEmpty());
		}

		@Test
		void rePlacingBookmarkReplacesExistingEntry ( ) {
			EventReference ref = appendOne();
			EventStream<MockDomainEvent> s = stream();
			s.placeBookmark("repeat-reader", ref, Tags.parse("phase:first"));
			s.placeBookmark("repeat-reader", ref, Tags.parse("phase:second"));

			List<Bookmark> bookmarks = s.getBookmarks();
			assertEquals(1, bookmarks.size());
			assertEquals(Tags.parse("phase:second"), bookmarks.get(0).tags());
		}

		@Test
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

	@Nested
	class OnInMem extends Tests {
		@Override
		EventStorage createEventStorage ( ) {
			return InMemoryEventStorage.newBuilder().build();
		}
	}

	@Nested
	class OnPostgres17 extends Tests {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG17); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG17); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17); }

		@Override
		EventStorage createEventStorage ( ) {
			return PostgresEventStorage.newBuilder()
					.name("unit-test")
					.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG17))
					.initializeDatabase()
					.build();
		}

		@Override
		void destroyEventStorage ( EventStorage storage ) {
			((PostgresEventStorageImpl)storage).stop();
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG17);
		}
	}

	@Nested
	class OnPostgres18 extends Tests {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

		@Override
		EventStorage createEventStorage ( ) {
			return PostgresEventStorage.newBuilder()
					.name("unit-test")
					.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18))
					.initializeDatabase()
					.build();
		}

		@Override
		void destroyEventStorage ( EventStorage storage ) {
			((PostgresEventStorageImpl)storage).stop();
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}

}
