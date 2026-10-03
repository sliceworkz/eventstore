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
		assertEquals(Optional.of(ref), byReader.get("reader-a").reference());
		assertEquals(Optional.of(ref), byReader.get("reader-b").reference());
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

		assertEquals(Optional.of(ref), bookmark.reference());
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
		assertEquals(Optional.of(real), bookmarks.get(0).reference(), "the previous bookmark must survive the rejected update");
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
		assertEquals(Optional.of(real), s.getBookmarks().getFirst().reference());
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
			assertEquals(Optional.of(real), notification.bookmark(), "the notification must carry the store's reference for the bookmarked event");
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
		assertEquals(Optional.of(handled), bookmark.reference());
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
			assertEquals(Optional.of(handled), reading.bookmark());
			assertEquals(Optional.of(read), reading.readUpTo(), "the notification must carry the store's reference for the read position");
			EventStorage.BookmarkPlacedNotification plain = notifications.stream()
					.filter(n -> "plain-reader".equals(n.reader())).findFirst().orElseThrow();
			assertEquals(Optional.empty(), plain.readUpTo());
		} finally {
			eventStorage().unsubscribe(listener);
		}
	}

	/**
	 * A reader that has read the stream without handling anything records its read position alone: the
	 * bookmark reads back with no handled reference and with the read position, as the store's own
	 * coordinates, through the single-reader lookup and the list alike. The resume point stays empty, so
	 * the reader resumes from the beginning, exactly as without a bookmark.
	 */
	@ForEachBackend
	void readPositionOnlyBookmarkHasNoHandledReference ( ) {
		EventReference read = appendOne();
		EventStream<MockDomainEvent> s = stream();

		EventReference foreignCoordinates = EventReference.of(read.id(), read.position() + 1_000_000, read.tx() + 1_000_000);
		s.placeReadPosition("idle-reader", foreignCoordinates, Tags.parse("phase:idle"));

		Bookmark bookmark = s.findBookmark("idle-reader").orElseThrow();
		assertEquals(Optional.empty(), bookmark.reference(), "nothing handled yet");
		assertEquals(Optional.of(read), bookmark.readUpTo(), "the read position, as the store's own reference");
		assertEquals(read, bookmark.readUpToOrReference());
		assertEquals(Tags.parse("phase:idle"), bookmark.tags());
		assertEquals(Optional.empty(), s.getBookmark("idle-reader"), "a read position is never a resume point");
		Bookmark listed = s.getBookmarks().stream().filter(b -> "idle-reader".equals(b.reader())).findFirst().orElseThrow();
		assertEquals(Optional.empty(), listed.reference());
		assertEquals(Optional.of(read), listed.readUpTo());
	}

	/**
	 * Placing a read position alone never clears the handled reference a bookmark already names: the
	 * reference stays, and only the read position and the tags are replaced.
	 */
	@ForEachBackend
	void readPositionOnlyPlacementNeverClearsAHandledReference ( ) {
		EventReference handled = appendOne();
		EventReference read = appendOne();
		EventStream<MockDomainEvent> s = stream();
		s.placeBookmark("busy-reader", handled, Tags.parse("phase:handled"));

		s.placeReadPosition("busy-reader", read, Tags.parse("phase:read"));

		Bookmark bookmark = s.findBookmark("busy-reader").orElseThrow();
		assertEquals(Optional.of(handled), bookmark.reference(), "the handled reference must survive a read position placed alone");
		assertEquals(Optional.of(read), bookmark.readUpTo());
		assertEquals(Tags.parse("phase:read"), bookmark.tags());
		assertEquals(Optional.of(handled), s.getBookmark("busy-reader"));
	}

	/** The first placement naming a handled event fills the reference of a read-position-only bookmark in. */
	@ForEachBackend
	void aLaterPlacementFillsInTheHandledReference ( ) {
		EventReference read = appendOne();
		EventReference handled = appendOne();
		EventStream<MockDomainEvent> s = stream();
		s.placeReadPosition("waking-reader", read, Tags.none());

		s.placeBookmark("waking-reader", handled, handled, Tags.none());

		Bookmark bookmark = s.findBookmark("waking-reader").orElseThrow();
		assertEquals(Optional.of(handled), bookmark.reference());
		assertEquals(Optional.of(handled), bookmark.readUpTo());
		assertEquals(Optional.of(handled), s.getBookmark("waking-reader"));
	}

	/**
	 * A read position placed alone is held to the rule every position is: one naming an event this store
	 * never stored is rejected, and the bookmark already there stays as it was.
	 */
	@ForEachBackend
	void readPositionOnlyNamingNoStoredEventIsRejected ( ) {
		EventReference real = appendOne();
		EventStream<MockDomainEvent> s = stream();
		EventReference fabricated = EventReference.create(real.position(), real.tx());

		assertThrows(EventStorageException.class, () -> s.placeReadPosition("misdirected-reader", fabricated, Tags.none()),
				"a read position must name an event this storage stored");
		assertEquals(Optional.empty(), s.findBookmark("misdirected-reader"), "the rejected bookmark must not have been stored");

		s.placeReadPosition("guarded-reader", real, Tags.parse("phase:before"));
		assertThrows(EventStorageException.class, () -> s.placeReadPosition("guarded-reader", fabricated, Tags.parse("phase:after")));
		Bookmark bookmark = s.findBookmark("guarded-reader").orElseThrow();
		assertEquals(Optional.of(real), bookmark.readUpTo(), "the previous bookmark must survive the rejected update");
		assertEquals(Tags.parse("phase:before"), bookmark.tags());
	}

	/**
	 * A bookmark always says something: one naming neither a handled event nor a read position is
	 * refused, whether it is built or placed — through the stream and straight at the storage alike — and
	 * nothing is stored.
	 */
	@ForEachBackend
	void aBookmarkWithNeitherPositionIsRefused ( ) {
		assertThrows(IllegalArgumentException.class,
				() -> new Bookmark("empty-reader", Optional.empty(), Optional.empty(), Tags.none(), Instant.now()));
		assertThrows(IllegalArgumentException.class,
				() -> new EventStorage.BookmarkPlacedNotification("empty-reader", Optional.empty(), Optional.empty()));
		assertThrows(NullPointerException.class, () -> stream().placeReadPosition("empty-reader", null, Tags.none()));
		assertThrows(NullPointerException.class, () -> eventStorage().bookmarkReadPosition("empty-reader", null, Tags.none()));
		assertEquals(Optional.empty(), stream().findBookmark("empty-reader"));
	}

	/** Removing a read-position-only bookmark removes it; there was no handled reference to answer. */
	@ForEachBackend
	void removingAReadPositionOnlyBookmarkRemovesIt ( ) {
		EventReference read = appendOne();
		EventStream<MockDomainEvent> s = stream();
		s.placeReadPosition("idle-reader", read, Tags.none());

		assertEquals(Optional.empty(), s.removeBookmark("idle-reader"));
		assertEquals(Optional.empty(), s.findBookmark("idle-reader"));
		assertTrue(s.getBookmarks().isEmpty());
	}

	/**
	 * The notification of a read position placed alone carries it, and no handled reference — or, when
	 * the bookmark already names a handled event, that one, since it stays.
	 */
	@ForEachBackend
	void readPositionOnlyNotificationCarriesTheReadPosition ( ) {
		EventReference handled = appendOne();
		EventReference read = appendOne();
		List<EventStorage.BookmarkPlacedNotification> notifications = new CopyOnWriteArrayList<>();
		EventStorage.EventStoreListener listener = new EventStorage.EventStoreListener() {
			@Override public void notify ( EventStorage.AppendsToEventStoreNotification newEventsInStore ) { }
			@Override public void notify ( EventStorage.BookmarkPlacedNotification bookmarkPlaced ) { notifications.add(bookmarkPlaced); }
		};
		eventStorage().subscribe(listener);
		try {
			stream().placeReadPosition("idle-reader", read, Tags.none());
			stream().placeBookmark("busy-reader", handled, Tags.none());
			stream().placeReadPosition("busy-reader", read, Tags.none());

			waitBecauseOfEventualConsistency(() -> notifications.stream().anyMatch(n -> "idle-reader".equals(n.reader()))
					&& notifications.stream().filter(n -> "busy-reader".equals(n.reader())).count() == 2);
			EventStorage.BookmarkPlacedNotification idle = notifications.stream()
					.filter(n -> "idle-reader".equals(n.reader())).findFirst().orElseThrow();
			assertEquals(Optional.empty(), idle.bookmark());
			assertEquals(Optional.of(read), idle.readUpTo());
			EventStorage.BookmarkPlacedNotification busy = notifications.stream()
					.filter(n -> "busy-reader".equals(n.reader())).reduce(( first, second ) -> second).orElseThrow();
			assertEquals(Optional.of(handled), busy.bookmark(), "the handled reference the bookmark kept");
			assertEquals(Optional.of(read), busy.readUpTo());
		} finally {
			eventStorage().unsubscribe(listener);
		}
	}

	/**
	 * A {@link org.sliceworkz.eventstore.stream.BookmarkListener} reports what a reader has processed, so a
	 * read position placed alone — nothing processed — is not passed to it. Bookmark listeners are told in
	 * placement order on one thread, so a later placement naming a handled event arriving alone shows the
	 * earlier one was not passed on.
	 */
	@ForEachBackend
	void aBookmarkListenerIsNotToldOfAReadPositionPlacedAlone ( ) {
		EventReference read = appendOne();
		EventReference handled = appendOne();
		EventStream<MockDomainEvent> s = stream();
		List<EventReference> processedUntil = new CopyOnWriteArrayList<>();
		try ( var subscription = s.subscribe(( String reader, EventReference processed ) -> processedUntil.add(processed)) ) {
			s.placeReadPosition("waking-reader", read, Tags.none());
			s.placeBookmark("waking-reader", handled, Tags.none());

			waitBecauseOfEventualConsistency(() -> !processedUntil.isEmpty());
			assertEquals(List.of(handled), processedUntil);
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
