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
package org.sliceworkz.eventstore.infra.inmem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorageImplTest.ProblematicParsing.ProblematicParsingRecord;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

import tools.jackson.databind.json.JsonMapper;

public class InMemoryEventStorageImplTest {
	
	@Test
	void testNameDefault ( ) {
		EventStorage es = InMemoryEventStorage.newBuilder().build();
		assertTrue(es.name().startsWith("inmem-"));
	}

	@Test
	void testNameChosen ( ) {
		EventStorage es = InMemoryEventStorage.newBuilder().name("myChosenName").build();
		assertEquals("myChosenName", es.name());
	}

	@Test
	void testNameNullOrEmpty ( ) {
		assertThrows(IllegalArgumentException.class, ()->InMemoryEventStorage.newBuilder().name(null).build());
		assertThrows(IllegalArgumentException.class, ()->InMemoryEventStorage.newBuilder().name("").build());
		assertThrows(IllegalArgumentException.class, ()->InMemoryEventStorage.newBuilder().name(" ").build());
	}

	@Test
	void testUnparsableJsonNotAppendable ( ) {
		
		// no type mapping done
		EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
		
		EventDeserializationException e = assertThrows(EventDeserializationException.class, ()->
			eventStore.getEventStream(EventStreamId.forContext("ctx").withPurpose("purpose"), ProblematicParsing.class).append(
					AppendCriteria.none(),
					Collections.singletonList(
							Event.of(new ProblematicParsingRecord("value"), Tags.none())
					)
			)
		);
		// The event is written, and then fails on the way back out: append() returns enriched events, so
		// a payload that serializes but cannot be read back surfaces as a deserialization failure.
		assertEquals("Failed to deserialize stored event type 'ProblematicParsingRecord' onto org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorageImplTest$ProblematicParsing$ProblematicParsingRecord: Unrecognized property \"derivedValueThatIsNotPartOfRecord\" (class org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorageImplTest$ProblematicParsing$ProblematicParsingRecord), not marked as ignorable", e.getMessage());
		// one wrapping layer, not two: Jackson's own complaint is the direct cause
		assertEquals("Unrecognized property \"derivedValueThatIsNotPartOfRecord\" (class org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorageImplTest$ProblematicParsing$ProblematicParsingRecord), not marked as ignorable (one known property: \"value\")", e.getCause().getMessage().split("\n")[0]);
		assertEquals(EventType.ofType("ProblematicParsingRecord"), e.getEventType());
		assertTrue(e.getReference().isPresent(), "the stream layer should name the stored event that failed");
	}
	
	sealed interface ProblematicParsing {
			
		// as this is not annotated with @JsonIgnore, it will be part of the output, and when parsing the property will not be found on the record
		default String getDerivedValueThatIsNotPartOfRecord ( ) { return null; };
		
		public record ProblematicParsingRecord ( String value ) implements ProblematicParsing {
			
			@Override
			public String getDerivedValueThatIsNotPartOfRecord (  ) {
				return value;
			}
		}
	
	}
	
	
	// more documentation of a pattern that a test
	@Test
	public void parseInvalidOldValue ( ) throws Exception {
		String json = (JsonMapper.builder().build().writeValueAsString(new Name("someName")));
		Name ok = JsonMapper.builder().build().readValue(json, Name.class);
		assertNotNull(ok);
		assertEquals("someName", ok.value());

		// old value which isn't valid anymore according to our current business rules
		Name name = JsonMapper.builder().build().readValue("{\"value\":\"old\"}", Name.class);
		assertNotNull(name);
		assertEquals("old", name.value());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,()->Name.of("old"));
		assertEquals("must be at least 6", e.getMessage());
	}
	
	private static final EventStreamId STREAM = EventStreamId.forContext("ctx").withPurpose("p");

	private static StoredEvent storedAt ( long position, long tx ) {
		return new StoredEvent(STREAM, EventType.ofType("Something"), EventReference.create(position, tx), "{}", Tags.none(), Instant.now());
	}

	private static List<Long> positions ( java.util.stream.Stream<StoredEvent> events ) {
		return events.map(e -> e.reference().position()).toList();
	}

	/**
	 * The cursor is a boundary in the {@code (tx, position)} order, as it is on Postgres, where the two
	 * columns are assigned independently and can disagree: an event holding a lower position and a
	 * higher transaction than one committed before it is after that event for every reader. A cursor
	 * read as a positional skip does not see it.
	 */
	@Test
	void cursorIsTheTupleOrderNotAPositionalSkip ( ) {
		StoredEvent first = storedAt(2, 1);   // committed first: position 2, transaction 1
		StoredEvent second = storedAt(1, 2);  // committed second: position 1, transaction 2
		EventStorage storage = new InMemoryEventStorageImpl("tuple", Limit.none(), List.of(second, first), Map.of());

		assertEquals(List.of(2L, 1L), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), null, Limit.none(), QueryDirection.FORWARD)),
				"the log is read in (tx, position) order, whatever order it was preloaded in");
		assertEquals(List.of(1L), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), first.reference(), Limit.none(), QueryDirection.FORWARD)),
				"the event after (tx 1, position 2) is (tx 2, position 1)");
		assertEquals(List.of(), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), second.reference(), Limit.none(), QueryDirection.FORWARD)),
				"nothing is after the newest event");
		assertEquals(List.of(2L), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), second.reference(), Limit.none(), QueryDirection.BACKWARD)),
				"going backward from (tx 2, position 1) reaches (tx 1, position 2)");
		assertEquals(List.of(), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), first.reference(), Limit.none(), QueryDirection.BACKWARD)),
				"nothing is before the oldest event");
	}

	/**
	 * A cursor compares stored events: the index a reference carries names one of the events a stored
	 * event upcasts into, and the storage never sees those, so a cursor into the middle of a stored
	 * event still starts after the whole of it.
	 */
	@Test
	void cursorWithAnIndexStartsAfterTheWholeStoredEvent ( ) {
		StoredEvent first = storedAt(1, 1);
		StoredEvent second = storedAt(2, 1);
		EventStorage storage = new InMemoryEventStorageImpl("index", Limit.none(), List.of(first, second), Map.of());

		EventReference intoFirst = EventReference.of(first.reference().id(), 1, 1, 3);
		assertEquals(List.of(2L), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), intoFirst, Limit.none(), QueryDirection.FORWARD)));
		assertEquals(List.of(), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), intoFirst, Limit.none(), QueryDirection.BACKWARD)));
	}

	/**
	 * A cursor this log never assigned -- one from another store, or a reloaded log missing its tail --
	 * is an ordinary boundary: everything is before it going backward, nothing after it going forward.
	 * Neither direction may throw.
	 */
	@Test
	void cursorBeyondTheEndOfTheLogIsABoundaryNotAnError ( ) {
		EventStorage storage = new InMemoryEventStorageImpl("beyond", Limit.none(), List.of(storedAt(1, 1), storedAt(2, 1)), Map.of());
		EventReference beyond = EventReference.create(10, 1);

		assertEquals(List.of(2L, 1L), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), beyond, Limit.none(), QueryDirection.BACKWARD)));
		assertEquals(List.of(), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), beyond, Limit.none(), QueryDirection.FORWARD)));
	}

	/**
	 * A log reloaded with a gap in it -- the filesystem-backed storage after a crash between two writes
	 * that landed out of order -- has fewer events than its highest position. The next position comes
	 * from a counter seeded from that highest position, never from the size of the log, so no stored
	 * event ever shares a position with another; and the cursor, being the tuple, walks over the gap.
	 */
	@Test
	void appendAfterAReloadWithAGapDoesNotReissueAPosition ( ) {
		StoredEvent afterTheGap = storedAt(4, 2);
		EventStorage storage = new InMemoryEventStorageImpl("gap", Limit.none(), List.of(storedAt(1, 1), storedAt(2, 1), afterTheGap), Map.of());

		List<StoredEvent> appended = storage.append(AppendCriteria.none(), STREAM,
				List.of(new EventToStore(STREAM, EventType.ofType("Something"), "{}", Tags.none(), null)));
		assertEquals(5L, appended.get(0).reference().position());
		assertEquals(3L, appended.get(0).reference().tx());

		List<StoredEvent> imported = storage.importEvents(
				List.of(new EventToImport(STREAM, EventType.ofType("Something"), EventId.create(), "{}", Tags.none(), Instant.now(), null)),
				EventStorage.ImportMode.FAIL_ON_EXISTING_ID);
		assertEquals(6L, imported.get(0).reference().position());

		assertEquals(List.of(4L, 5L, 6L), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), EventReference.create(2, 1), Limit.none(), QueryDirection.FORWARD)),
				"a cursor before the gap reads everything after it");
		assertEquals(List.of(5L, 6L), positions(storage.query(EventFilter.matchAll(), EventStreamId.anyContext(), afterTheGap.reference(), Limit.none(), QueryDirection.FORWARD)),
				"a cursor at the event after the gap reads only what was appended since");
	}

	public record Name ( String value ) {
		
		// never to be called directly from code, only here for deserialization purposes
		public Name ( String value ) {
			if (value == null ) {
				throw new IllegalArgumentException("cannot be null");
			}
			this.value = value;
		}
		
		
		public static Name of ( String value ) {
			Name result = new Name(value);
			if ( value.length() < 6 ) {
				throw new IllegalArgumentException("must be at least 6");
			}
			return result;
		}
	}

}
