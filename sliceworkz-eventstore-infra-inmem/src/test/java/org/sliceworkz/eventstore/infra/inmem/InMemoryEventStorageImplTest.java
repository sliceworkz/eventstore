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

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorageImplTest.ProblematicParsing.ProblematicParsingRecord;
import org.sliceworkz.eventstore.spi.EventStorage;
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
