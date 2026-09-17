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

import java.util.List;
import java.util.Optional;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Shared compliance scenarios for the stream scope of the SPI's read and check operations: the
 * {@link EventStreamId} that {@link EventStorage#query}, {@link EventStorage#append} and
 * {@link EventStorage#head} take. It is never absent -- the whole storage is
 * {@link EventStreamId#anyContext()}, a wildcard the scoping code handles like any other -- so a null
 * is a caller's mistake and is refused as one, on every backend alike, rather than left to surface as
 * a {@code NullPointerException} from wherever the backend first touches it.
 */
public class StreamScopeTest extends AbstractEventStoreTest {

	private static final EventStreamId ORDERS = EventStreamId.forContext("orders").withPurpose("42");
	private static final EventStreamId INVOICES = EventStreamId.forContext("invoices").withPurpose("42");

	private static EventToStore event ( EventStreamId stream, String type ) {
		return new EventToStore(stream, EventType.ofType(type), "{}", Tags.none(), null);
	}

	private List<StoredEvent> read ( EventStreamId scope ) {
		return eventStorage().query(EventFilter.matchAll(), scope, null, Limit.none(), Direction.FORWARD);
	}

	@ForEachBackend
	void aNullStreamIsRefusedByEveryScopedOperation ( ) {
		EventStorage storage = eventStorage();

		assertThrows(IllegalArgumentException.class,
				() -> storage.query(EventFilter.matchAll(), null, null, Limit.none(), Direction.FORWARD));
		assertThrows(IllegalArgumentException.class,
				() -> storage.append(AppendCriteria.none(), null, List.of(event(ORDERS, "OrderPlaced"))));
		assertThrows(IllegalArgumentException.class, () -> storage.head(null));

		assertEquals(List.of(), read(EventStreamId.anyContext()), "a refused append stores nothing");
	}

	@ForEachBackend
	void theWildcardStreamIsTheWholeStorageAndAWildcardPurposeTheWholeContext ( ) {
		EventStorage storage = eventStorage();
		StoredEvent placed = storage.append(AppendCriteria.none(), ORDERS, List.of(event(ORDERS, "OrderPlaced"))).getFirst();
		StoredEvent sent = storage.append(AppendCriteria.none(), INVOICES, List.of(event(INVOICES, "InvoiceSent"))).getFirst();
		StoredEvent shipped = storage.append(AppendCriteria.none(), ORDERS, List.of(event(ORDERS, "OrderShipped"))).getFirst();

		assertEquals(List.of(placed, sent, shipped), read(EventStreamId.anyContext()));
		assertEquals(List.of(placed, shipped), read(EventStreamId.forContext("orders").anyPurpose()));
		assertEquals(List.of(sent), read(INVOICES));

		assertEquals(Optional.of(shipped.reference()), storage.head(EventStreamId.anyContext()));
		assertEquals(Optional.of(sent.reference()), storage.head(INVOICES));
		assertEquals(Optional.empty(), storage.head(EventStreamId.forContext("payments").anyPurpose()));
	}

}
