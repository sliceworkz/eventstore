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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
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
		return s.query(EventQuery.matchAll().backwards().limit(1)).stream().findFirst().orElseThrow().reference();
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

	/**
	 * A bookmark names a stored event by id, and the position and transaction read back are the
	 * store's own for that event — never the caller's copy. The natural alternative, keeping the
	 * reference as handed in, stores a cursor the id check never validates, so a bookmark could carry a
	 * stored id and a wrong position; and it makes a bookmark meaningless in any store but the one it
	 * was taken from, where resolving by id lets an imported bookmarks table stay valid as it stands.
	 * The reference passed here carries the right id under coordinates no event has.
	 */
	@ForEachBackend
	void bookmarkIsResolvedToTheStoresOwnCoordinates ( ) {
		EventReference real = appendOne();
		EventStream<MockDomainEvent> s = stream();

		EventReference foreignCoordinates = EventReference.of(real.id(), real.position() + 1_000_000, real.tx() + 1_000_000);
		s.placeBookmark("resolved-reader", foreignCoordinates, Tags.none());

		assertEquals(Optional.of(real), s.getBookmark("resolved-reader"),
				"the bookmark must read back as the store's own reference for the event it names");
		assertEquals(real, s.getBookmarks().getFirst().reference());
	}

	/** The notification a placement raises carries the store's coordinates too, not the caller's. */
	@ForEachBackend
	void bookmarkNotificationCarriesTheStoresOwnCoordinates ( ) {
		EventReference real = appendOne();
		List<EventStorage.BookmarkPlacedNotification> notifications = new CopyOnWriteArrayList<>();
		EventStorage.EventStoreListener listener = new EventStorage.EventStoreListener() {
			@Override public void notify ( EventStorage.AppendsToEventStoreNotification newEventsInStore ) { }
			@Override public void notify ( EventStorage.BookmarkPlacedNotification bookmarkPlaced ) { notifications.add(bookmarkPlaced); }
		};
		eventStorage().subscribe(listener);
		try {
			EventReference foreignCoordinates = EventReference.of(real.id(), real.position() + 1_000_000, real.tx() + 1_000_000);
			stream().placeBookmark("notified-reader", foreignCoordinates, Tags.none());

			waitBecauseOfEventualConsistency(() -> notifications.stream().anyMatch(n -> "notified-reader".equals(n.reader())));
			EventStorage.BookmarkPlacedNotification notification = notifications.stream()
					.filter(n -> "notified-reader".equals(n.reader())).findFirst().orElseThrow();
			assertEquals(real, notification.bookmark(), "the notification must carry the store's reference for the bookmarked event");
		} finally {
			eventStorage().unsubscribe(listener);
		}
	}

	/**
	 * A bookmark records a second position, the event up to which the reader has read the stream. It
	 * reads back beside the reference, through the list and through the single-reader lookup alike, and
	 * the resume point stays the reference.
	 */
	@ForEachBackend
	void readPositionIsStoredBesideTheReference ( ) {
		EventReference handled = appendOne();
		EventReference read = appendOne();
		EventStream<MockDomainEvent> s = stream();

		s.placeBookmark("reading-reader", handled, read, Tags.none());

		Bookmark bookmark = s.findBookmark("reading-reader").orElseThrow();
		assertEquals(handled, bookmark.reference());
		assertEquals(Optional.of(read), bookmark.readUpTo());
		assertEquals(read, bookmark.readUpToOrReference());
		assertEquals(Optional.of(read), s.getBookmarks().getFirst().readUpTo());
		assertEquals(Optional.of(handled), s.getBookmark("reading-reader"), "the resume point is the reference, never the read position");
	}

	/**
	 * A bookmark placed without a read position has none, and readers of it fall back to the reference.
	 * Placing one without it after one with it clears it, rather than leaving a read position the
	 * placement did not vouch for.
	 */
	@ForEachBackend
	void absentReadPositionReadsAsNone ( ) {
		EventReference handled = appendOne();
		EventReference read = appendOne();
		EventStream<MockDomainEvent> s = stream();

		s.placeBookmark("plain-reader", handled, Tags.none());
		Bookmark bookmark = s.findBookmark("plain-reader").orElseThrow();
		assertEquals(Optional.empty(), bookmark.readUpTo());
		assertEquals(handled, bookmark.readUpToOrReference());

		s.placeBookmark("plain-reader", handled, read, Tags.none());
		s.placeBookmark("plain-reader", handled, Tags.none());
		assertEquals(Optional.empty(), s.findBookmark("plain-reader").orElseThrow().readUpTo());
		assertEquals(Optional.empty(), s.findBookmark("no-such-reader"));
	}

	/**
	 * The read position is held to the rule the reference is: one naming an event this store never
	 * stored is rejected, and the bookmark already there stays as it was.
	 */
	@ForEachBackend
	void readPositionNamingNoStoredEventIsRejected ( ) {
		EventReference real = appendOne();
		EventStream<MockDomainEvent> s = stream();
		s.placeBookmark("guarded-reader", real, real, Tags.parse("phase:before"));

		EventReference fabricated = EventReference.create(real.position(), real.tx());
		assertThrows(EventStorageException.class,
				() -> s.placeBookmark("guarded-reader", real, fabricated, Tags.parse("phase:after")),
				"a read position must name an event this storage stored");

		Bookmark bookmark = s.findBookmark("guarded-reader").orElseThrow();
		assertEquals(Optional.of(real), bookmark.readUpTo(), "the previous bookmark must survive the rejected update");
		assertEquals(Tags.parse("phase:before"), bookmark.tags());
	}

	/** The read position, like the reference, reads back as the store's own coordinates for its event. */
	@ForEachBackend
	void readPositionIsResolvedToTheStoresOwnCoordinates ( ) {
		EventReference handled = appendOne();
		EventReference read = appendOne();
		EventStream<MockDomainEvent> s = stream();

		EventReference foreignCoordinates = EventReference.of(read.id(), read.position() + 1_000_000, read.tx() + 1_000_000);
		s.placeBookmark("resolved-reader", handled, foreignCoordinates, Tags.none());

		assertEquals(Optional.of(read), s.findBookmark("resolved-reader").orElseThrow().readUpTo());
		assertEquals(Optional.of(read), s.getBookmarks().getFirst().readUpTo());
	}

	/**
	 * The notification carries the read position too, as the store's own coordinates, and carries none
	 * for a bookmark placed without one.
	 */
	@ForEachBackend
	void bookmarkNotificationCarriesTheReadPosition ( ) {
		EventReference handled = appendOne();
		EventReference read = appendOne();
		List<EventStorage.BookmarkPlacedNotification> notifications = new CopyOnWriteArrayList<>();
		EventStorage.EventStoreListener listener = new EventStorage.EventStoreListener() {
			@Override public void notify ( EventStorage.AppendsToEventStoreNotification newEventsInStore ) { }
			@Override public void notify ( EventStorage.BookmarkPlacedNotification bookmarkPlaced ) { notifications.add(bookmarkPlaced); }
		};
		eventStorage().subscribe(listener);
		try {
			EventReference foreignCoordinates = EventReference.of(read.id(), read.position() + 1_000_000, read.tx() + 1_000_000);
			stream().placeBookmark("reading-reader", handled, foreignCoordinates, Tags.none());
			stream().placeBookmark("plain-reader", handled, Tags.none());

			waitBecauseOfEventualConsistency(() -> notifications.stream().anyMatch(n -> "reading-reader".equals(n.reader()))
					&& notifications.stream().anyMatch(n -> "plain-reader".equals(n.reader())));
			EventStorage.BookmarkPlacedNotification reading = notifications.stream()
					.filter(n -> "reading-reader".equals(n.reader())).findFirst().orElseThrow();
			assertEquals(handled, reading.bookmark());
			assertEquals(Optional.of(read), reading.readUpTo(), "the notification must carry the store's reference for the read position");
			EventStorage.BookmarkPlacedNotification plain = notifications.stream()
					.filter(n -> "plain-reader".equals(n.reader())).findFirst().orElseThrow();
			assertEquals(Optional.empty(), plain.readUpTo());
		} finally {
			eventStorage().unsubscribe(listener);
		}
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
