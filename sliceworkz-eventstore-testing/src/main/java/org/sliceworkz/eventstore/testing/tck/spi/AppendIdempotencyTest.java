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
package org.sliceworkz.eventstore.testing.tck.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.AppendsToEventStoreNotification;
import org.sliceworkz.eventstore.spi.EventStorage.BookmarkPlacedNotification;
import org.sliceworkz.eventstore.spi.EventStorage.EventStoreListener;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.IdempotencyKeyConflictException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Shared compliance scenarios for what {@link EventStorage#append} does with the idempotency keys of a
 * batch, run against every storage backend. The stream layer checks the batch before it reaches the
 * SPI, so these pin the raw path — an import, a fixture, a third-party caller writing
 * {@link EventToStore} directly — where the storage is the only thing standing between a caller and a
 * batch stored in part or reported as de-duplicated when it never was.
 * <p>
 * The contract: a batch every key of which was stored before is a retry, swallowed whole; a batch
 * mixing stored and new keys is refused as an {@link IdempotencyKeyConflictException} with nothing
 * stored; and a batch repeating a key is refused as an {@link IllegalArgumentException} before
 * anything is stored. The last two matter on a backend whose unique index rejects such batches on
 * its own: the rejection arrives as the same violation a retry raises, and a storage reading every
 * violation as a retry reports a first attempt, or a lost batch, as a successful de-duplication.
 */
public class AppendIdempotencyTest extends AbstractEventStoreTest {

	private final EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");

	private EventToStore event ( String value, String idempotencyKey ) {
		return new EventToStore(stream, EventType.named("Something"), "{\"v\":\"%s\"}".formatted(value), Tags.none(), idempotencyKey);
	}

	private List<StoredEvent> append ( EventToStore... events ) {
		return eventStorage().append(AppendCriteria.none(), stream, List.of(events));
	}

	private List<StoredEvent> allEvents ( ) {
		return eventStorage().query(EventFilter.matchAll(), stream, null, Limit.none(), Direction.FORWARD);
	}

	@ForEachBackend
	void aBatchRepeatingAKeyIsRejectedBeforeAnythingIsStored ( ) {
		assertThrows(IllegalArgumentException.class, () -> append(event("1", "cmd-4711"), event("2", "cmd-4711")));
		assertTrue(allEvents().isEmpty(), "a rejected batch must store none of its events");

		// the key was not spent by the rejected batch
		assertEquals(1, append(event("1", "cmd-4711")).size());
	}

	/**
	 * Subscribes before anything is appended. Delivery is asynchronous on a backend notifying through
	 * the database (LISTEN/NOTIFY), so a listener registered after an append can still receive that
	 * append's notification; counting from before the first append, and waiting for each real
	 * append's own notification, is what makes the count below deterministic.
	 */
	private List<AppendsToEventStoreNotification> recordNotifications ( ) {
		List<AppendsToEventStoreNotification> received = new CopyOnWriteArrayList<>();
		eventStorage().subscribe(new EventStoreListener() {
			@Override
			public void notify ( AppendsToEventStoreNotification newEventsInStore ) {
				received.add(newEventsInStore);
			}
			@Override
			public void notify ( BookmarkPlacedNotification bookmarkPlaced ) {
				// not what these scenarios assert
			}
		});
		return received;
	}

	private void waitForNotificationAbout ( List<AppendsToEventStoreNotification> received, List<StoredEvent> appended ) {
		EventReference last = appended.getLast().reference();
		waitBecauseOfEventualConsistency(() -> received.stream().anyMatch(n -> n.atLeastUntil().equals(last)));
	}

	@ForEachBackend
	void aRetriedBatchStoresNothingAndNotifiesNobody ( ) {
		List<AppendsToEventStoreNotification> received = recordNotifications();

		List<StoredEvent> first = append(event("1", "cmd-4711/1"), event("2", "cmd-4711/2"), event("3", null));
		assertEquals(3, first.size());
		waitForNotificationAbout(received, first);

		// every key stored before: a retry, swallowed whole, the unkeyed event included
		List<StoredEvent> retry = append(event("1", "cmd-4711/1"), event("2", "cmd-4711/2"), event("3", null));
		assertTrue(retry.isEmpty(), "a retried batch must be reported as de-duplicated");
		assertEquals(3, allEvents().size(), "a retried batch must store none of its events");

		// a batch that stored nothing announces nothing: the next real append is announced, and a
		// notification for the swallowed batch would have arrived ahead of it
		List<StoredEvent> next = append(event("4", "cmd-4712"));
		assertEquals(1, next.size());
		waitForNotificationAbout(received, next);
		assertEquals(2, received.size(), "a swallowed batch must not be announced to listeners");
	}

	@ForEachBackend
	void aBatchMixingStoredAndNewKeysIsRefusedAndStoresNothing ( ) {
		List<AppendsToEventStoreNotification> received = recordNotifications();

		List<StoredEvent> first = append(event("1", "order-4711"));
		assertEquals(1, first.size());
		waitForNotificationAbout(received, first);

		// one stored key, one new key: not a retry of anything the store holds
		IdempotencyKeyConflictException conflict = assertThrows(IdempotencyKeyConflictException.class,
				() -> append(event("2", "order-4712"), event("3", "order-4711")));
		assertEquals(Set.of("order-4711"), conflict.storedKeys());
		assertEquals(Set.of("order-4712"), conflict.newKeys());
		assertEquals(1, allEvents().size(), "a refused batch must store none of its events");

		List<StoredEvent> next = append(event("4", "order-4712"));
		assertEquals(1, next.size());
		waitForNotificationAbout(received, next);
		assertEquals(2, received.size(), "a refused batch must not be announced to listeners");
	}
}
