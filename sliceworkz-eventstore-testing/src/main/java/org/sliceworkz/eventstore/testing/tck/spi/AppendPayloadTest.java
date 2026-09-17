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
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Shared compliance scenarios for what {@link EventStorage#append} accepts as a payload, run against every
 * storage backend so a payload rejected by one is rejected by all.
 * <p>
 * The stream layer only ever hands a storage the JSON its serde produced, so this is about the raw SPI
 * path: an import, a fixture, a third-party caller writing {@link EventToStore} directly. Postgres
 * refuses anything its {@code ::jsonb} cast cannot parse; an in-memory backend has no column type to
 * refuse it for it, and one that accepts the payload anyway lets a mistake pass every test and fail
 * in production -- the divergence the in-memory backends exist not to have.
 */
public class AppendPayloadTest extends AbstractEventStoreTest {

	private final EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");

	private EventToStore event ( String payload ) {
		return new EventToStore(stream, EventType.named("Something"), payload, Tags.of("kind", "something"), null);
	}

	private List<StoredEvent> append ( EventToStore... events ) {
		return eventStorage().append(AppendCriteria.none(), stream, List.of(events));
	}

	private List<StoredEvent> allEvents ( ) {
		return eventStorage().query(EventFilter.matchAll(), stream, null, Limit.none(), Direction.FORWARD);
	}

	@ForEachBackend
	void aJsonDocumentIsStoredAndReadBackAsAnEquivalentDocument ( ) {
		List<StoredEvent> stored = append(event("{\"a\":1}"));

		assertEquals(1, stored.size());
		// equivalent, not byte-identical: a storage keeps the document, not its rendering. Postgres's
		// jsonb re-renders it (its own whitespace, keys reordered, duplicate keys collapsed), so the
		// contract a caller may rely on is the parsed document, which is what the serde reads
		assertEquals("{\"a\":1}", withoutWhitespace(allEvents().getFirst().payload()));
	}

	private static String withoutWhitespace ( String json ) {
		return json.replaceAll("\\s", "");
	}

	@ForEachBackend
	void aPayloadThatIsNotJsonIsRejected ( ) {
		assertThrows(EventStorageException.class, () -> append(event("not json at all")));
		assertTrue(allEvents().isEmpty(), "nothing may be stored from a rejected append");
	}

	@ForEachBackend
	void aBlankPayloadIsRejected ( ) {
		assertThrows(EventStorageException.class, () -> append(event("")));
		assertThrows(EventStorageException.class, () -> append(event("   ")));
		assertTrue(allEvents().isEmpty(), "nothing may be stored from a rejected append");
	}

	@ForEachBackend
	void aMissingPayloadIsRejected ( ) {
		assertThrows(EventStorageException.class, () -> append(event(null)));
		assertTrue(allEvents().isEmpty(), "nothing may be stored from a rejected append");
	}

	@ForEachBackend
	void aBatchWithOneBadPayloadStoresNothing ( ) {
		// a batch is one transaction: the good payloads must not survive the bad one
		assertThrows(EventStorageException.class, () -> append(event("{\"a\":1}"), event("{not json"), event("{\"c\":3}")));
		assertTrue(allEvents().isEmpty(), "a rejected batch must store none of its events");
	}
}
