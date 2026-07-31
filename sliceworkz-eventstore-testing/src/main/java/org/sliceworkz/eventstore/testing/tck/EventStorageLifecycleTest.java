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
package org.sliceworkz.eventstore.testing.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.ImportMode;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorageClosedException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;

/**
 * The lifecycle contract on {@link EventStorage#close()}, which every backend must honour.
 * <p>
 * A storage that holds nothing satisfies most of this for free; one that runs threads or holds
 * connections has to work for it. Both are asserted the same way here, so that code outliving its
 * storage — a projection, a stream held past a tenant's shutdown — fails identically everywhere
 * rather than depending on which backend is underneath.
 * <p>
 * Closing the storage inside a scenario is safe: the contract requires {@code close()} to be
 * idempotent, and the backend closes it again during teardown.
 */
public class EventStorageLifecycleTest extends AbstractEventStoreTest {

	/** close() must be bounded; this is a generous ceiling, not a performance assertion */
	private static final long CLOSE_BUDGET_MS = 5_000;

	private EventStream<MockDomainEvent> stream ( EventStore eventStore ) {
		return eventStore.getEventStream(EventStreamId.forContext("lifecycle"), MockDomainEvent.class);
	}

	private void append ( EventStream<MockDomainEvent> stream, String payload ) {
		stream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent(payload), Tags.none())));
	}

	@ForEachBackend
	void closeIsIdempotent ( ) {
		eventStorage().close();
		eventStorage().close();
		eventStorage().close();
	}

	@ForEachBackend
	void closeBlocksUntilItIsDoneAndReturnsWithinItsBudget ( ) {
		long start = System.nanoTime();
		eventStorage().close();
		long tookMs = (System.nanoTime() - start) / 1_000_000;

		assertTrue(tookMs < CLOSE_BUDGET_MS,
			"close() must return within %dms, took %dms".formatted(CLOSE_BUDGET_MS, tookMs));
	}

	@ForEachBackend
	void everyOperationThrowsAfterClose ( ) {
		EventStorage storage = eventStorage();
		storage.close();

		assertThrows(EventStorageClosedException.class,
			() -> storage.query(EventQuery.matchAll(), Optional.empty(), null, Limit.none(), QueryDirection.FORWARD));
		assertThrows(EventStorageClosedException.class,
			() -> storage.append(AppendCriteria.none(), Optional.empty(), Collections.emptyList()));
		assertThrows(EventStorageClosedException.class,
			() -> storage.getEventById(EventId.of(UUID.randomUUID().toString())));
		assertThrows(EventStorageClosedException.class,
			() -> storage.getBookmark("some-reader"));
		assertThrows(EventStorageClosedException.class,
			() -> storage.getBookmarks());
		assertThrows(EventStorageClosedException.class,
			() -> storage.removeBookmark("some-reader"));
		assertThrows(EventStorageClosedException.class,
			() -> storage.bookmark("some-reader", null, Tags.none()));
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void importThrowsAfterClose ( ) {
		EventStorage storage = eventStorage();
		storage.close();

		assertThrows(EventStorageClosedException.class,
			() -> storage.importEvents(Collections.emptyList(), ImportMode.FAIL_ON_EXISTING_ID));
	}

	@ForEachBackend
	void nameKeepsWorkingAfterClose ( ) {
		String name = eventStorage().name();
		eventStorage().close();

		assertEquals(name, eventStorage().name(),
			"name() must keep working after close, so logging and diagnostics do not break");
	}

	@ForEachBackend
	void closeNeedsNoCatchBlockInTryWithResources ( ) {
		// compiles only because close() narrows away the checked exception of AutoCloseable.close()
		try ( EventStorage storage = eventStorage() ) {
			assertNotNull(storage.name());
		}
	}

	@ForEachBackend
	void closingTheEventStoreLeavesTheStorageItWasGivenOpen ( ) {
		EventStream<MockDomainEvent> stream = stream(eventStore());
		append(stream, "before close");

		eventStore().close();

		// the storage was handed to the store, not created by it, so it stays open and usable
		List<EventStorage.StoredEvent> stored = eventStorage()
				.query(EventQuery.matchAll(), Optional.empty(), null, Limit.none(), QueryDirection.FORWARD)
				.toList();
		assertEquals(1, stored.size(), "closing an EventStore must not close a storage it was given");

		// the closed store is done, though: its notifications have stopped, so it must not read on
		assertThrows(EventStorageClosedException.class, () -> stream.query(EventQuery.matchAll()).count());
		assertThrows(EventStorageClosedException.class,
			() -> eventStore().getEventStream(EventStreamId.forContext("lifecycle"), MockDomainEvent.class));

		eventStore().close(); // idempotent on this side too
	}

	@ForEachBackend
	void closingOneStoreLeavesItsSiblingsWorking ( ) {
		EventStore closedStore = EventStoreFactory.get().eventStore(eventStorage());
		EventStore survivingStore = EventStoreFactory.get().eventStore(eventStorage());

		EventStream<MockDomainEvent> closedStream = stream(closedStore);
		EventStream<MockDomainEvent> survivingStream = stream(survivingStore);

		// listeners on both: the closed store's stream stays registered with the storage, so the
		// storage keeps offering it notifications it must now quietly decline rather than reject
		AtomicInteger notificationsAfterClose = new AtomicInteger();
		closedStream.subscribe((EventStreamEventuallyConsistentAppendListener) reference -> {
			notificationsAfterClose.incrementAndGet();
			return reference;
		});
		AtomicInteger survivorNotifications = new AtomicInteger();
		survivingStream.subscribe((EventStreamEventuallyConsistentAppendListener) reference -> {
			survivorNotifications.incrementAndGet();
			return reference;
		});

		closedStore.close();

		append(survivingStream, "after the sibling closed");
		assertEquals(1, survivingStream.query(EventQuery.matchAll()).count(),
			"a store sharing the storage must keep working after another store on it is closed");
		survivingStream.placeBookmark("reader",
			survivingStream.query(EventQuery.matchAll()).toList().getLast().reference(), Tags.none());

		// wait for the surviving store's listener, which proves the notification round trip completed;
		// the closed store's listener must have been passed over rather than notified
		waitBecauseOfEventualConsistency(() -> survivorNotifications.get() >= 1);
		assertEquals(0, notificationsAfterClose.get(),
			"a closed store must stop delivering notifications, without disturbing the storage");

		survivingStore.close();
	}

}
