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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;

/**
 * A raw stream hands every stored event back as the JSON document the storage holds for it, a
 * {@link String}, under its stored type.
 * <p>
 * The document is the storage's own answer — the text a {@link StoredEvent} carries — parsed by
 * nothing on the way out, so what a raw read hands back is what an export or an import moves. That is
 * what makes the raw stream the way to inspect an event a typed stream cannot read
 * ({@code SerdeFailureTest}), to see a legacy event in its stored shape ({@code UpcastTest}) and to see
 * a sealed value as its envelope ({@code ShreddableEventDataTest}); this scenario pins the value those
 * rely on, per backend, through {@code query} and through {@code getEventById} alike.
 */
public class RawStreamTest extends AbstractEventStoreTest {

	private final EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");

	@ForEachBackend
	void aRawQueryHandsBackTheStoredDocumentUnderItsStoredType ( ) {
		EventStream<MockDomainEvent> typed = eventStore().getEventStream(streamId, MockDomainEvent.class);
		Event<MockDomainEvent> appended = typed.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("John"), Tags.of("a", "1"))).getFirst();

		EventSource<String> raw = eventStore().getRawEventStream(streamId);
		List<Event<String>> read = raw.query(EventQuery.matchAll());

		assertEquals(1, read.size());
		Event<String> event = read.getFirst();
		assertEquals(EventType.of(FirstDomainEvent.class), event.type());
		assertEquals(EventType.of(FirstDomainEvent.class), event.storedType());
		assertEquals(appended.reference(), event.reference());
		assertEquals(Tags.of("a", "1"), event.tags());

		// the document is the storage's own text for this event, not a rendering of the store's making
		StoredEvent stored = eventStorage().getEventById(appended.reference().id()).orElseThrow();
		assertEquals(stored.payload(), event.data());
		assertTrue(event.data().contains("John"), "the document should hold the appended payload: " + event.data());
	}

	@ForEachBackend
	void aRawLookupByIdHandsBackTheSameDocument ( ) {
		EventStream<MockDomainEvent> typed = eventStore().getEventStream(streamId, MockDomainEvent.class);
		Event<MockDomainEvent> appended = typed.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("Jane"), Tags.none())).getFirst();

		// through a wildcard stream, as the presence check before an import and the read of a poison
		// event by the reference an EventDeserializationException names both do
		List<Event<String>> read = eventStore().getRawEventStream(EventStreamId.anyContext()).getEventById(appended.reference().id());

		assertEquals(1, read.size());
		assertEquals(eventStorage().getEventById(appended.reference().id()).orElseThrow().payload(), read.getFirst().data());
		assertEquals(streamId, read.getFirst().stream());
	}

}
