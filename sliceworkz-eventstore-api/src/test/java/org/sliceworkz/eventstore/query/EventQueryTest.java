package org.sliceworkz.eventstore.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class EventQueryTest {

	Event<MockDomainEvent> e1_event1NoTags = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 1), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.none(), LocalDateTime.now()); 
	Event<MockDomainEvent> e2_event2NoTags = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 2), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.none(), LocalDateTime.now()); 
	Event<MockDomainEvent> e3_event1TagsA1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 3), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.of("A", "1"), LocalDateTime.now()); 
	Event<MockDomainEvent> e4_event2TagsA1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 4), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.of("A", "1"), LocalDateTime.now());
	Event<MockDomainEvent> e5_event1TagsA1B1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 5), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.of(Tag.of("A", "1"),Tag.of("B","1")), LocalDateTime.now());
	Event<MockDomainEvent> e6_event2TagsA2B1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 6), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.of(Tag.of("A", "2"),Tag.of("B","1")), LocalDateTime.now()); 
	
	@Test
	void testMatchAll ( ) {
		EventQuery q = EventQuery.matchAll();
		assertFalse(q.isMatchNone());
		assertTrue(q.isMatchAll());
		
		assertTrue(q.matches(e1_event1NoTags));
		assertTrue(q.matches(e2_event2NoTags));
		assertTrue(q.matches(e3_event1TagsA1));
		assertTrue(q.matches(e4_event2TagsA1));
		assertTrue(q.matches(e5_event1TagsA1B1));
		assertTrue(q.matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchAllUntil ( ) {
		EventQuery q = EventQuery.matchAll().until(e4_event2TagsA1.reference());
		assertFalse(q.isMatchNone());
		assertTrue(q.isMatchAll());
		
		assertTrue(q.matches(e1_event1NoTags));
		assertTrue(q.matches(e2_event2NoTags));
		assertTrue(q.matches(e3_event1TagsA1));
		assertTrue(q.matches(e4_event2TagsA1));
		assertFalse(q.matches(e5_event1TagsA1B1));
		assertFalse(q.matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchAllUntilOnStoredEvent ( ) {
		EventQuery q = EventQuery.matchAll().until(e4_event2TagsA1.reference());
		assertFalse(q.isMatchNone());
		assertTrue(q.isMatchAll());
		
		assertTrue(q.matches(storedEvent(e1_event1NoTags)));
		assertTrue(q.matches(storedEvent(e2_event2NoTags)));
		assertTrue(q.matches(storedEvent(e3_event1TagsA1)));
		assertTrue(q.matches(storedEvent(e4_event2TagsA1)));
		assertFalse(q.matches(storedEvent(e5_event1TagsA1B1)));
		assertFalse(q.matches(storedEvent(e6_event2TagsA2B1)));
	}

	@Test
	void testMatchNone ( ) {
		EventQuery q = EventQuery.matchNone();
		assertTrue(q.isMatchNone());
		assertFalse(q.isMatchAll());
		
		assertFalse(q.matches(e1_event1NoTags));
		assertFalse(q.matches(e2_event2NoTags));
		assertFalse(q.matches(e3_event1TagsA1));
		assertFalse(q.matches(e4_event2TagsA1));
		assertFalse(q.matches(e5_event1TagsA1B1));
		assertFalse(q.matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchByType( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		assertFalse(q.isMatchNone());
		assertFalse(q.isMatchAll());
		
		assertTrue(q.matches(e1_event1NoTags));
		assertFalse(q.matches(e2_event2NoTags));
		assertTrue(q.matches(e3_event1TagsA1));
		assertFalse(q.matches(e4_event2TagsA1));
		assertTrue(q.matches(e5_event1TagsA1B1));
		assertFalse(q.matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchCombined ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1"));
		
		EventQuery q = q1.combineWith(q2);
		
		assertFalse(q.isMatchNone());
		assertFalse(q.isMatchAll());
		
		assertTrue(q.matches(e1_event1NoTags));
		assertFalse(q.matches(e2_event2NoTags));
		assertTrue(q.matches(e3_event1TagsA1));
		assertTrue(q.matches(e4_event2TagsA1));
		assertTrue(q.matches(e5_event1TagsA1B1));
		assertFalse(q.matches(e6_event2TagsA2B1));
	}

	@Test
	void testMatchCombinedBothUntil ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e4_event2TagsA1.reference());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).until(e4_event2TagsA1.reference());
		
		EventQuery q = q1.combineWith(q2);
		
		assertFalse(q.isMatchNone());
		assertFalse(q.isMatchAll());
		
		assertTrue(q.matches(e1_event1NoTags));
		assertFalse(q.matches(e2_event2NoTags));
		assertTrue(q.matches(e3_event1TagsA1));
		assertTrue(q.matches(e4_event2TagsA1));
		assertFalse(q.matches(e5_event1TagsA1B1));
		assertFalse(q.matches(e6_event2TagsA2B1)); 
	}


	@Test
	void testCombinedDifferentUntil ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e4_event2TagsA1.reference());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).until(e3_event1TagsA1.reference());
		
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()-> q1.combineWith(q2) );
		assertEquals("can't combine two EventQuery that don't share the same until value (both different values)", e.getMessage());
	}
	
	@Test
	void testCombinedOneUntil ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).until(e3_event1TagsA1.reference());
		
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()-> q1.combineWith(q2) );
		assertEquals("can't combine two EventQuery don't share the same until value (one was not set)", e.getMessage());
	}

	@Test
	void testUntilIfEarlierFromNull ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		assertNull(q.until());
		q = q.untilIfEarlier(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.until());
	}

	@Test
	void testUntilIfEarlierAndSame( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.until());
		q = q.untilIfEarlier(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.until());
	}

	@Test
	void testUntilIfEarlierAndLater( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.until());
		q = q.untilIfEarlier(e5_event1TagsA1B1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.until());
	}
	

	@Test
	void testUntilIfEarlierAndEarlier( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.until());
		q = q.untilIfEarlier(e2_event2NoTags.reference());
		assertEquals(e2_event2NoTags.reference(), q.until());
	}
	
	@Test
	void testUntilIfEarlierAndNull ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e3_event1TagsA1.reference());
		assertEquals(e3_event1TagsA1.reference(), q.until());
		q = q.untilIfEarlier(null);
		assertEquals(e3_event1TagsA1.reference(), q.until());
	}
	
	private StoredEvent storedEvent ( Event<?> e ) {
		try {
			return new StoredEvent(e.stream(), EventType.of(e.data()), e.reference(), new JsonMapper().writeValueAsString(e.data()), e.tags(), e.timestamp() );
		} catch (JsonProcessingException ex) {
			throw new RuntimeException(ex);
		}
	}
	
	sealed interface MockDomainEvent {
		
		public record FirstDomainEvent ( ) implements MockDomainEvent { } 

		public record SecondDomainEvent ( ) implements MockDomainEvent { } 
		
	}
	
}
