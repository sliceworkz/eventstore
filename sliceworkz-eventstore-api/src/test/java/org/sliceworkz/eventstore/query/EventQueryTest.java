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
	
	@Test
	void testMatchAll ( ) {
		EventQuery q = EventQuery.matchAll();
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
		EventQuery q = EventQuery.forTags(Tags.of("A", "1"));
		assertEquals(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("A", "1")), q);
		assertFalse(q.filter().isMatchNone());
		assertFalse(q.filter().isMatchAll());

		assertFalse(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e2_event2NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertTrue(q.filter().matches(e4_event2TagsA1));
		assertTrue(q.filter().matches(e5_event1TagsA1B1));
		assertFalse(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchAllUntil ( ) {
		EventQuery q = EventQuery.matchAll().until(e4_event2TagsA1.reference());
		assertFalse(q.filter().isMatchNone());
		assertTrue(q.filter().isMatchAll());
		
		assertTrue(q.filter().matches(e1_event1NoTags));
		assertTrue(q.filter().matches(e2_event2NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertTrue(q.filter().matches(e4_event2TagsA1));
		assertFalse(q.filter().matches(e5_event1TagsA1B1));
		assertFalse(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchAllUntilOnStoredEvent ( ) {
		EventQuery q = EventQuery.matchAll().until(e4_event2TagsA1.reference());
		assertFalse(q.filter().isMatchNone());
		assertTrue(q.filter().isMatchAll());
		
		assertTrue(q.filter().matches(storedEvent(e1_event1NoTags)));
		assertTrue(q.filter().matches(storedEvent(e2_event2NoTags)));
		assertTrue(q.filter().matches(storedEvent(e3_event1TagsA1)));
		assertTrue(q.filter().matches(storedEvent(e4_event2TagsA1)));
		assertFalse(q.filter().matches(storedEvent(e5_event1TagsA1B1)));
		assertFalse(q.filter().matches(storedEvent(e6_event2TagsA2B1)));
	}

	@Test
	void testMatchNone ( ) {
		EventQuery q = EventQuery.matchNone();
		assertTrue(q.filter().isMatchNone());
		assertFalse(q.filter().isMatchAll());
		
		assertFalse(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e2_event2NoTags));
		assertFalse(q.filter().matches(e3_event1TagsA1));
		assertFalse(q.filter().matches(e4_event2TagsA1));
		assertFalse(q.filter().matches(e5_event1TagsA1B1));
		assertFalse(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchByType( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		assertFalse(q.filter().isMatchNone());
		assertFalse(q.filter().isMatchAll());
		
		assertTrue(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e2_event2NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertFalse(q.filter().matches(e4_event2TagsA1));
		assertTrue(q.filter().matches(e5_event1TagsA1B1));
		assertFalse(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchCombined ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1"));
		
		EventQuery q = q1.or(q2);
		
		assertFalse(q.filter().isMatchNone());
		assertFalse(q.filter().isMatchAll());
		
		assertTrue(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e2_event2NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertTrue(q.filter().matches(e4_event2TagsA1));
		assertTrue(q.filter().matches(e5_event1TagsA1B1));
		assertFalse(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchCombinedBothUntil ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e4_event2TagsA1.reference());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).until(e4_event2TagsA1.reference());
		
		EventQuery q = q1.or(q2);
		
		assertFalse(q.filter().isMatchNone());
		assertFalse(q.filter().isMatchAll());
		
		assertTrue(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e2_event2NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertTrue(q.filter().matches(e4_event2TagsA1));
		assertFalse(q.filter().matches(e5_event1TagsA1B1));
		assertFalse(q.filter().matches(e6_event2TagsA2B1)); 
	}


	@Test
	void testCombinedDifferentUntil ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e4_event2TagsA1.reference());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).until(e3_event1TagsA1.reference());
		
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()-> q1.or(q2) );
		assertEquals("can't combine two EventFilter that don't share the same until value (both different values)", e.getMessage());
	}
	
	@Test
	void testCombinedOneUntil ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).until(e3_event1TagsA1.reference());
		
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()-> q1.or(q2) );
		assertEquals("can't combine two EventFilter that don't share the same until value (one was not set)", e.getMessage());
	}

	@Test
	void testUntilIfEarlierFromNull ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		assertNull(q.filter().until());
		q = q.untilIfEarlier(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
	}

	@Test
	void testUntilIfEarlierAndSame( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
		q = q.untilIfEarlier(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
	}

	@Test
	void testUntilIfEarlierAndLater( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
		q = q.untilIfEarlier(e5_event1TagsA1B1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
	}
	

	@Test
	void testUntilIfEarlierAndEarlier( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
		q = q.untilIfEarlier(e2_event2NoTags.reference());
		assertEquals(e2_event2NoTags.reference(), q.filter().until());
	}
	
	@Test
	void testUntilIfEarlierAndNull ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
		q = q.untilIfEarlier(null);
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
	}
	
	@Test
	void testBackwardsDefaultsToForward ( ) {
		EventQuery q = EventQuery.matchAll();
		assertEquals(EventQuery.Direction.FORWARD, q.direction());
		assertFalse(q.isBackwards());
	}

	@Test
	void testBackwards ( ) {
		EventQuery q = EventQuery.matchAll().backwards();
		assertEquals(EventQuery.Direction.BACKWARD, q.direction());
		assertTrue(q.isBackwards());
	}

	@Test
	void testBackwardsPreservesOtherFields ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.of("A", "1"))
				.until(e3_event1TagsA1.reference())
				.backwards();
		assertTrue(q.isBackwards());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
		assertFalse(q.filter().isMatchAll());
		assertFalse(q.filter().isMatchNone());
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertFalse(q.filter().matches(e4_event2TagsA1));
	}

	@Test
	void testLimitDefaultsToNone ( ) {
		EventQuery q = EventQuery.matchAll();
		assertEquals(Limit.none(), q.limit());
	}

	@Test
	void testLimitWithLong ( ) {
		EventQuery q = EventQuery.matchAll().limit(5);
		assertEquals(Limit.to(5), q.limit());
	}

	@Test
	void testLimitWithLimitObject ( ) {
		EventQuery q = EventQuery.matchAll().limit(Limit.to(10));
		assertEquals(Limit.to(10), q.limit());
	}

	@Test
	void testBackwardsAndLimit ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())
				.backwards()
				.limit(1);
		assertTrue(q.isBackwards());
		assertEquals(Limit.to(1), q.limit());
		// matching still works as before
		assertTrue(q.filter().matches(e1_event1NoTags));
		assertFalse(q.filter().matches(e2_event2NoTags));
	}

	@Test
	void testFilter ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.of("A", "1"))
				.until(e4_event2TagsA1.reference())
				.backwards()
				.limit(1);

		EventFilter filter = q.filter();

		// filter contains items and until
		assertEquals(q.filter().items(), filter.items());
		assertEquals(q.filter().until(), filter.until());

		// matching behavior is identical to the query
		assertTrue(filter.matches(e3_event1TagsA1));
		assertFalse(filter.matches(e5_event1TagsA1B1));
	}

	@Test
	void testFilterMatchAll ( ) {
		EventFilter filter = EventQuery.matchAll().filter();
		assertTrue(filter.isMatchAll());
		assertFalse(filter.isMatchNone());
		assertTrue(filter.matches(e1_event1NoTags));
	}

	@Test
	void testFilterMatchNone ( ) {
		EventFilter filter = EventQuery.matchNone().filter();
		assertTrue(filter.isMatchNone());
		assertFalse(filter.isMatchAll());
		assertFalse(filter.matches(e1_event1NoTags));
	}

	@Test
	void testOrSameDirection ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards();
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).backwards();

		EventQuery combined = q1.or(q2);
		assertTrue(combined.isBackwards());
	}

	@Test
	void testOrDifferentDirectionThrows ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards();
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1"));

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> q1.or(q2));
		assertEquals("can't combine two EventQuery with different directions", e.getMessage());
	}

	@Test
	void testOrLimitOnEitherSideThrows ( ) {
		EventQuery unlimited = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery limited1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).limit(1);
		EventQuery limited5 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).limit(5);

		// limited on the left
		IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> limited1.or(unlimited));
		assertEquals("can't combine an EventQuery that has a limit set", e1.getMessage());

		// limited on the right
		IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> unlimited.or(limited1));
		assertEquals("can't combine an EventQuery that has a limit set", e2.getMessage());

		// limited on both
		IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class, () -> limited1.or(limited5));
		assertEquals("can't combine an EventQuery that has a limit set", e3.getMessage());
	}

	@Test
	void testOrBothUnlimitedSucceeds ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1"));

		EventQuery combined = q1.or(q2);

		assertTrue(combined.limit().isNotSet());
		// union semantics, mirrors testMatchCombined
		assertTrue(combined.filter().matches(e1_event1NoTags));
		assertFalse(combined.filter().matches(e2_event2NoTags));
		assertTrue(combined.filter().matches(e4_event2TagsA1));
		assertFalse(combined.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	void testUntilPreservesDirectionAndLimit ( ) {
		EventQuery q = EventQuery.matchAll().backwards().limit(3).until(e4_event2TagsA1.reference());
		assertTrue(q.isBackwards());
		assertEquals(Limit.to(3), q.limit());
		assertEquals(e4_event2TagsA1.reference(), q.filter().until());
	}

	@Test
	void testUntilIfEarlierPreservesDirectionAndLimit ( ) {
		EventQuery q = EventQuery.matchAll().backwards().limit(3);
		q = q.untilIfEarlier(e3_event1TagsA1.reference());
		assertTrue(q.isBackwards());
		assertEquals(Limit.to(3), q.limit());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
	}

	// --- the fluent form: forTypes(...).tagged(...).or(...) ---------------------------------------

	@Test
	void testForTypesTaggedIsForEvents ( ) {
		EventQuery q = EventQuery.forTypes(FirstDomainEvent.class).tagged("A", "1");
		assertEquals(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.of("A", "1")), q);
		assertEquals(EventFilter.forTypes(FirstDomainEvent.class).tagged("A", "1"), q.filter());

		assertFalse(q.filter().matches(e1_event1NoTags));
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertFalse(q.filter().matches(e4_event2TagsA1));
		assertTrue(q.filter().matches(e5_event1TagsA1B1));
	}

	@Test
	void testForTypesResolvesASealedRoot ( ) {
		EventQuery q = EventQuery.forTypes(MockDomainEvent.class);
		assertEquals(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), Tags.none()), q);
		assertTrue(q.filter().matches(e1_event1NoTags));
		assertTrue(q.filter().matches(e2_event2NoTags));
	}

	@Test
	void testTaggedKeepsDirectionAndLimit ( ) {
		EventQuery q = EventQuery.forTypes(FirstDomainEvent.class).backwards().limit(3).tagged("A", "1").tagged(Tags.of("B", "1"));
		assertTrue(q.isBackwards());
		assertEquals(Limit.to(3), q.limit());
		assertEquals(EventFilter.forTypes(FirstDomainEvent.class).tagged(Tags.of("A", "1", "B", "1")), q.filter());

		assertFalse(q.filter().matches(e3_event1TagsA1), "both tags are required");
		assertTrue(q.filter().matches(e5_event1TagsA1B1));
	}

	@Test
	void testOrWithMatchAllIsMatchAll ( ) {
		EventQuery q = EventQuery.forTypes(FirstDomainEvent.class).or(EventQuery.matchAll());
		assertTrue(q.filter().isMatchAll());
		assertTrue(q.filter().matches(e6_event2TagsA2B1));
	}

	@Test
	@SuppressWarnings("removal")
	void testCombineWithIsTheDeprecatedNameOfOr ( ) {
		EventQuery q1 = EventQuery.forTypes(FirstDomainEvent.class);
		EventQuery q2 = EventQuery.forTypes(SecondDomainEvent.class).tagged("A", "1");
		assertEquals(q1.or(q2), q1.combineWith(q2));
	}

	// --- the surface: the query builds its filter and reads nothing off it -------------------------

	/**
	 * Whether an event matches, whether everything or nothing does, the items and the boundary are the
	 * filter's to answer, through {@link EventQuery#filter()}; the query's own readers are the two it
	 * adds, {@link EventQuery#isBackwards()} and {@link EventQuery#limit()}. Pinned reflectively, so a
	 * filter reader put back on the query is a deliberate choice rather than a convenience that crept in.
	 */
	@Test
	void theReadersLiveOnTheFilter ( ) {
		Set<String> filterReaders = Set.of("matches", "isMatchAll", "isMatchNone", "items");
		List<String> reExposed = Arrays.stream(EventQuery.class.getDeclaredMethods())
				.filter(m -> Modifier.isPublic(m.getModifiers()))
				.filter(m -> filterReaders.contains(m.getName()) || (m.getName().equals("until") && m.getParameterCount() == 0))
				.map(Method::getName)
				.sorted()
				.toList();
		assertEquals(List.of(), reExposed, "a filter reader re-exposed on the query");

		// and the filter does answer them, for a query built either way
		EventQuery q = EventQuery.forTypes(FirstDomainEvent.class).tagged("A", "1").until(e3_event1TagsA1.reference());
		assertTrue(q.filter().matches(e3_event1TagsA1));
		assertFalse(q.filter().isMatchAll());
		assertFalse(q.filter().isMatchNone());
		assertNotNull(q.filter().items());
		assertEquals(e3_event1TagsA1.reference(), q.filter().until());
	}

	/**
	 * A query is built from a filter, never from a filter's items: {@link EventFilterItem} is the
	 * component type of {@link EventFilter#items()}, read by the backends, and nothing the query names.
	 */
	@Test
	void theQueryIsBuiltFromAFilterAndNamesNoFilterItem ( ) {
		for ( Constructor<?> constructor : EventQuery.class.getConstructors() ) {
			assertEquals(List.of(EventFilter.class, EventQuery.Direction.class, Limit.class), List.of(constructor.getParameterTypes()),
					"the query's one constructor takes the filter, the direction and the limit");
		}
		for ( Method method : EventQuery.class.getDeclaredMethods() ) {
			if ( Modifier.isPublic(method.getModifiers()) ) {
				assertFalse(Arrays.asList(method.getParameterTypes()).contains(EventFilterItem.class), method + " names EventFilterItem");
				assertFalse(method.getReturnType().equals(EventFilterItem.class), method + " names EventFilterItem");
			}
		}
	}

	private StoredEvent storedEvent ( Event<?> e ) {
		try {
			return new StoredEvent(e.stream(), EventType.of(e.data()), e.reference(), JsonMapper.builder().build().writeValueAsString(e.data()), e.tags(), e.timestamp() );
		} catch (JacksonException ex) {
			throw new RuntimeException(ex);
		}
	}
	
	sealed interface MockDomainEvent {
		
		public record FirstDomainEvent ( ) implements MockDomainEvent { } 

		public record SecondDomainEvent ( ) implements MockDomainEvent { } 
		
	}
	
}
