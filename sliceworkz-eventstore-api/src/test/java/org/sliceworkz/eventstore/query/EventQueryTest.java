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
package org.sliceworkz.eventstore.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQueryTest.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.query.EventQueryTest.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.EventStreamId;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public class EventQueryTest {

	Event<MockDomainEvent> e1_event1NoTags = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 1, 1), EventType.of(FirstDomainEvent.class), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.none(), Instant.now()); 
	Event<MockDomainEvent> e2_event2NoTags = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 2, 2), EventType.of(SecondDomainEvent.class), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.none(), Instant.now()); 
	Event<MockDomainEvent> e3_event1TagsA1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 3, 3), EventType.of(FirstDomainEvent.class), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.of("A", "1"), Instant.now()); 
	Event<MockDomainEvent> e4_event2TagsA1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 4, 4), EventType.of(SecondDomainEvent.class), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.of("A", "1"), Instant.now());
	Event<MockDomainEvent> e5_event1TagsA1B1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 5, 5), EventType.of(FirstDomainEvent.class), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.of(Tag.of("A", "1"),Tag.of("B","1")), Instant.now());
	Event<MockDomainEvent> e6_event2TagsA2B1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 6, 6), EventType.of(SecondDomainEvent.class), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.of(Tag.of("A", "2"),Tag.of("B","1")), Instant.now()); 
	
	// --- matching is the filter's: a query built from it answers through it --------------------------

	@Test
	void testMatchAll ( ) {
		EventQuery q = EventQuery.of(EventFilter.matchAll());
		assertFalse(q.filter().isMatchNone());
		assertTrue(q.filter().isMatchAll());

		assertTrue(q.filter().matches(e1_event1NoTags));
		assertTrue(q.filter().matches(e2_event2NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertTrue(q.filter().matches(e4_event2TagsA1));
		assertTrue(q.filter().matches(e5_event1TagsA1B1));
		assertTrue(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testForTagsIsForEventsOfAnyType ( ) {
		EventQuery q = EventQuery.of(EventFilter.forTags("A", "1"));
		assertEquals(EventQuery.of(EventFilter.forTags("A", "1")), q);

		assertFalse(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e2_event2NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertTrue(q.filter().matches(e4_event2TagsA1));
		assertTrue(q.filter().matches(e5_event1TagsA1B1));
		assertFalse(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchNone ( ) {
		EventQuery q = EventQuery.of(EventFilter.matchNone());
		assertTrue(q.filter().isMatchNone());
		assertFalse(q.filter().isMatchAll());
		assertFalse(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchByType ( ) {
		EventQuery q = EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class));
		assertTrue(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e2_event2NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertFalse(q.filter().matches(e4_event2TagsA1));
		assertTrue(q.filter().matches(e5_event1TagsA1B1));
		assertFalse(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchCombined ( ) {
		EventFilter f1 = EventFilter.forTypes(FirstDomainEvent.class);
		EventFilter f2 = EventFilter.forTypes(SecondDomainEvent.class).tagged("A", "1");
		EventQuery q = EventQuery.of(f1.or(f2));

		assertTrue(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e2_event2NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertTrue(q.filter().matches(e4_event2TagsA1));
		assertTrue(q.filter().matches(e5_event1TagsA1B1));
		assertFalse(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void anItemBoundIsMatchedOnStoredEventsToo ( ) {
		EventQuery q = EventQuery.of(EventFilter.matchAll().until(e4_event2TagsA1.reference()));
		assertTrue(q.filter().matches(storedEvent(e1_event1NoTags)));
		assertTrue(q.filter().matches(storedEvent(e4_event2TagsA1)));
		assertFalse(q.filter().matches(storedEvent(e5_event1TagsA1B1)));
		assertFalse(q.filter().matches(storedEvent(e6_event2TagsA2B1)));
	}

	@Test
	void aUnionOfItemsBoundedDifferentlyMatchesEachWithinItsOwnBounds ( ) {
		// the first type only up to e3, the second type only after e4
		EventFilter early = EventFilter.forTypes(FirstDomainEvent.class).until(e3_event1TagsA1.reference());
		EventFilter late = EventFilter.forTypes(SecondDomainEvent.class).after(e4_event2TagsA1.reference());
		EventQuery q = EventQuery.of(early.or(late));

		assertTrue(q.filter().matches(e1_event1NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertFalse(q.filter().matches(e5_event1TagsA1B1), "past the first item's until");
		assertFalse(q.filter().matches(e2_event2NoTags), "before the second item's after");
		assertFalse(q.filter().matches(e4_event2TagsA1), "after is exclusive");
		assertTrue(q.filter().matches(e6_event2TagsA2B1));
	}

	// --- how a filter is read: direction, limit, read bound ---------------------------------------------

	@Test
	void ofReadsTheFilterForwardWithNoLimitAndNoReadBound ( ) {
		EventFilter filter = EventFilter.forTypes(FirstDomainEvent.class).tagged("A", "1");
		EventQuery q = EventQuery.of(filter);
		assertEquals(filter, q.filter());
		assertEquals(EventQuery.Direction.FORWARD, q.direction());
		assertFalse(q.isBackwards());
		assertEquals(Limit.none(), q.limit());
		assertNull(q.notAfter());
		assertEquals(new EventQuery(filter, EventQuery.Direction.FORWARD, Limit.none(), null), q);
	}

	@Test
	void ofRefusesANullFilter ( ) {
		assertThrows(IllegalArgumentException.class, () -> EventQuery.of(null));
	}

	@Test
	void theCanonicalConstructorDefaultsWhatIsAbsent ( ) {
		EventQuery q = new EventQuery(null, null, null, null);
		assertTrue(q.filter().isMatchNone());
		assertEquals(EventQuery.Direction.FORWARD, q.direction());
		assertEquals(Limit.none(), q.limit());
		assertNull(q.notAfter());
	}

	@Test
	void testBackwards ( ) {
		EventQuery q = EventQuery.of(EventFilter.matchAll()).backwards();
		assertEquals(EventQuery.Direction.BACKWARD, q.direction());
		assertTrue(q.isBackwards());
	}

	@Test
	void testLimitWithLong ( ) {
		assertEquals(Limit.to(5), EventQuery.of(EventFilter.matchAll()).limit(5).limit());
	}

	@Test
	void testLimitWithLimitObject ( ) {
		assertEquals(Limit.to(10), EventQuery.of(EventFilter.matchAll()).limit(Limit.to(10)).limit());
	}

	@Test
	void notAfterIsAPropertyOfTheReadAndLeavesTheFilterAlone ( ) {
		EventFilter filter = EventFilter.forTypes(FirstDomainEvent.class).tagged("A", "1");
		EventQuery q = EventQuery.of(filter).notAfter(e3_event1TagsA1.reference());

		assertEquals(e3_event1TagsA1.reference(), q.notAfter());
		assertEquals(filter, q.filter(), "the read bound leaves no trace on what is relevant");
		assertTrue(q.filter().matches(e5_event1TagsA1B1), "so the filter still matches what was recorded after it");
	}

	@Test
	void notAfterReplacesAndNullRemoves ( ) {
		EventQuery q = EventQuery.of(EventFilter.matchAll()).notAfter(e3_event1TagsA1.reference()).notAfter(e5_event1TagsA1B1.reference());
		assertEquals(e5_event1TagsA1B1.reference(), q.notAfter(), "the later call wins, even when it is later");
		assertNull(q.notAfter(null).notAfter());
	}

	@Test
	void directionLimitAndReadBoundCombineInAnyOrder ( ) {
		EventFilter filter = EventFilter.forTypes(FirstDomainEvent.class);
		EventReference bound = e4_event2TagsA1.reference();
		EventQuery one = EventQuery.of(filter).backwards().limit(3).notAfter(bound);
		EventQuery two = EventQuery.of(filter).notAfter(bound).limit(3).backwards();
		assertEquals(one, two);
		assertEquals(new EventQuery(filter, EventQuery.Direction.BACKWARD, Limit.to(3), bound), one);
	}

	// --- the surface: a query is a filter plus how it is read, and nothing else -------------------

	/**
	 * A query builds no filter and reads nothing off one: the types, tags, unions and bounds of what is
	 * relevant are {@link EventFilter}'s, reached through {@link EventQuery#filter()}. Pinned
	 * reflectively, so a filter builder put back on the query is a deliberate choice rather than a
	 * convenience that crept in.
	 */
	@Test
	void aQueryOffersOnlyHowAFilterIsRead ( ) {
		List<String> methods = Arrays.stream(EventQuery.class.getDeclaredMethods())
				.filter(m -> Modifier.isPublic(m.getModifiers()))
				.filter(m -> !Set.of("equals", "hashCode", "toString").contains(m.getName()))
				.map(Method::getName)
				.distinct()
				.sorted()
				.toList();
		assertEquals(List.of("backwards", "direction", "filter", "isBackwards", "limit", "notAfter", "of"), methods);

		List<String> statics = Arrays.stream(EventQuery.class.getDeclaredMethods())
				.filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
				.map(Method::getName)
				.toList();
		assertEquals(List.of("of"), statics, "no shortcut: a query is always built from a filter");
	}

	/**
	 * A query is built from a filter, never from a filter's items: {@link EventFilterItem} is the
	 * component type of {@link EventFilter#items()}, read by the backends, and nothing the query names.
	 */
	@Test
	void theQueryIsBuiltFromAFilterAndNamesNoFilterItem ( ) {
		for ( Constructor<?> constructor : EventQuery.class.getConstructors() ) {
			assertEquals(List.of(EventFilter.class, EventQuery.Direction.class, Limit.class, EventReference.class), List.of(constructor.getParameterTypes()),
					"the query's one constructor takes the filter, the direction, the limit and the read bound");
		}
		for ( Method method : EventQuery.class.getDeclaredMethods() ) {
			if ( Modifier.isPublic(method.getModifiers()) ) {
				assertFalse(Arrays.asList(method.getParameterTypes()).contains(EventFilterItem.class), method + " names EventFilterItem");
				assertFalse(method.getReturnType().equals(EventFilterItem.class), method + " names EventFilterItem");
			}
		}
	}

	/**
	 * A query and its filter render as their components only, bounds included, and read back as the
	 * same values: an observer or a log line that serializes one shows what was asked.
	 */
	@Test
	void aQueryWithItemBoundsSurvivesAJsonRoundTrip ( ) throws Exception {
		JsonMapper mapper = JsonMapper.builder().build();
		EventFilter filter = EventFilter.forTypes(FirstDomainEvent.class).tagged("A", "1").after(e1_event1NoTags.reference())
				.or(EventFilter.forTypes(SecondDomainEvent.class).until(e4_event2TagsA1.reference()));
		EventQuery q = EventQuery.of(filter).backwards().limit(7).notAfter(e5_event1TagsA1B1.reference());

		String json = mapper.writeValueAsString(q);
		assertFalse(json.contains("matchAll") || json.contains("bounded") || json.contains("backwards\""), json);
		assertEquals(q, mapper.readValue(json, EventQuery.class));
	}

	private StoredEvent storedEvent ( Event<?> e ) {
		try {
			return new StoredEvent(e.stream(), EventType.of(e.data().getClass()), e.reference(), JsonMapper.builder().build().writeValueAsString(e.data()), e.tags(), e.timestamp() );
		} catch (JacksonException ex) {
			throw new RuntimeException(ex);
		}
	}
	
	sealed interface MockDomainEvent {
		
		public record FirstDomainEvent ( ) implements MockDomainEvent { } 

		public record SecondDomainEvent ( ) implements MockDomainEvent { } 
		
	}
	
}
