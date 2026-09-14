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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.AppendsToEventStoreNotification;
import org.sliceworkz.eventstore.spi.EventStorage.BookmarkPlacedNotification;
import org.sliceworkz.eventstore.spi.EventStorage.EventStoreListener;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Shared compliance scenarios for what {@link EventStorage#append} does with the idempotency keys of a
 * batch, run against every storage backend. The stream layer checks the batch before it reaches the
 * SPI, so these pin the raw path — an import, a fixture, a third-party caller writing
 * {@link EventToStore} directly — where the storage is the only thing standing between a caller and a
 * batch stored in part or reported as de-duplicated when it never was.
 * <p>
 * The contract: a batch is de-duplicated as a whole, so any stored key in it stores nothing of it,
 * and a batch repeating a key is refused as an {@link IllegalArgumentException} before anything is
 * stored. The second matters on a backend whose unique index would reject such a batch on its own:
 * the rejection arrives as the same violation a duplicate of an earlier append raises, and a storage
 * reading it as one reports a first attempt as a successful de-duplication.
 */
public class AppendIdempotencyTest extends AbstractEventStoreTest {

	private final EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");

	private EventToStore event ( String value, String idempotencyKey ) {
		return new EventToStore(stream, EventType.ofType("Something"), "{\"v\":\"%s\"}".formatted(value), Tags.none(), idempotencyKey);
	}

	private List<StoredEvent> append ( EventToStore... events ) {
		return eventStorage().append(AppendCriteria.none(), Optional.of(stream), List.of(events));
	}

	private List<StoredEvent> allEvents ( ) {
		return eventStorage().query(EventQuery.matchAll(), Optional.of(stream), null, Limit.none(), QueryDirection.FORWARD).toList();
	}

	@ForEachBackend
	void aBatchRepeatingAKeyIsRejectedBeforeAnythingIsStored ( ) {
		assertThrows(IllegalArgumentException.class, () -> append(event("1", "cmd-4711"), event("2", "cmd-4711")));
		assertTrue(allEvents().isEmpty(), "a rejected batch must store none of its events");

		// the key was not spent by the rejected batch
		assertEquals(1, append(event("1", "cmd-4711")).size());
	}

	@ForEachBackend
	void aBatchWithAStoredKeyStoresNothingAndNotifiesNobody ( ) {
		assertEquals(1, append(event("1", "order-4711")).size());

		List<AppendsToEventStoreNotification> received = new CopyOnWriteArrayList<>();
		eventStorage().subscribe(new EventStoreListener() {
			@Override
			public void notify ( AppendsToEventStoreNotification newEventsInStore ) {
				received.add(newEventsInStore);
			}
			@Override
			public void notify ( BookmarkPlacedNotification bookmarkPlaced ) {
				// not what this scenario asserts
			}
		});

		// one stored key, one new key, one event without a key: none of them is stored
		List<StoredEvent> mixed = append(event("2", "order-4712"), event("3", "order-4711"), event("4", null));
		assertTrue(mixed.isEmpty(), "a batch holding a stored key must be reported as de-duplicated");
		assertEquals(1, allEvents().size(), "a batch holding a stored key must store none of its events");

		// and a batch that stored nothing announces nothing: the next real append is what proves the
		// listener is wired, and it is the only notification that may arrive
		assertEquals(1, append(event("5", "order-4712")).size());
		waitBecauseOfEventualConsistency(() -> !received.isEmpty());
		assertEquals(1, received.size(), "a swallowed batch must not be announced to listeners");
		assertEquals(allEvents().getLast().reference(), received.getFirst().atLeastUntil());
	}
}
