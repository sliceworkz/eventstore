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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

	Event<MockDomainEvent> e1_event1NoTags = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 1, 1), EventType.of(FirstDomainEvent.class), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.none(), LocalDateTime.now()); 
	Event<MockDomainEvent> e2_event2NoTags = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 2, 2), EventType.of(SecondDomainEvent.class), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.none(), LocalDateTime.now()); 
	Event<MockDomainEvent> e3_event1TagsA1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 3, 3), EventType.of(FirstDomainEvent.class), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.of("A", "1"), LocalDateTime.now()); 
	Event<MockDomainEvent> e4_event2TagsA1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 4, 4), EventType.of(SecondDomainEvent.class), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.of("A", "1"), LocalDateTime.now());
	Event<MockDomainEvent> e5_event1TagsA1B1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 5, 5), EventType.of(FirstDomainEvent.class), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.of(Tag.of("A", "1"),Tag.of("B","1")), LocalDateTime.now());
	Event<MockDomainEvent> e6_event2TagsA2B1 = Event.<MockDomainEvent>of(EventStreamId.forContext("context"), EventReference.of(EventId.create(), 6, 6), EventType.of(SecondDomainEvent.class), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.of(Tag.of("A", "2"),Tag.of("B","1")), LocalDateTime.now()); 
	
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
		assertEquals("can't combine two EventFilter that don't share the same until value (both different values)", e.getMessage());
	}
	
	@Test
	void testCombinedOneUntil ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).until(e3_event1TagsA1.reference());
		
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()-> q1.combineWith(q2) );
		assertEquals("can't combine two EventFilter that don't share the same until value (one was not set)", e.getMessage());
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
		assertEquals(e3_event1TagsA1.reference(), q.until());
		assertFalse(q.isMatchAll());
		assertFalse(q.isMatchNone());
		assertTrue(q.matches(e3_event1TagsA1));
		assertFalse(q.matches(e4_event2TagsA1));
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
		assertTrue(q.matches(e1_event1NoTags));
		assertFalse(q.matches(e2_event2NoTags));
	}

	@Test
	void testFilter ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.of("A", "1"))
				.until(e4_event2TagsA1.reference())
				.backwards()
				.limit(1);

		EventFilter filter = q.filter();

		// filter contains items and until
		assertEquals(q.items(), filter.items());
		assertEquals(q.until(), filter.until());

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
	void testCombineWithSameDirection ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards();
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).backwards();

		EventQuery combined = q1.combineWith(q2);
		assertTrue(combined.isBackwards());
	}

	@Test
	void testCombineWithDifferentDirectionThrows ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards();
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1"));

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> q1.combineWith(q2));
		assertEquals("can't combine two EventQuery with different directions", e.getMessage());
	}

	@Test
	void testCombineWithLimitOnEitherSideThrows ( ) {
		EventQuery unlimited = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery limited1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).limit(1);
		EventQuery limited5 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).limit(5);

		// limited on the left
		IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> limited1.combineWith(unlimited));
		assertEquals("can't combine an EventQuery that has a limit set", e1.getMessage());

		// limited on the right
		IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> unlimited.combineWith(limited1));
		assertEquals("can't combine an EventQuery that has a limit set", e2.getMessage());

		// limited on both
		IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class, () -> limited1.combineWith(limited5));
		assertEquals("can't combine an EventQuery that has a limit set", e3.getMessage());
	}

	@Test
	void testCombineWithBothUnlimitedSucceeds ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1"));

		EventQuery combined = q1.combineWith(q2);

		assertTrue(combined.limit().isNotSet());
		// union semantics, mirrors testMatchCombined
		assertTrue(combined.matches(e1_event1NoTags));
		assertFalse(combined.matches(e2_event2NoTags));
		assertTrue(combined.matches(e4_event2TagsA1));
		assertFalse(combined.matches(e6_event2TagsA2B1));
	}

	@Test
	void testUntilPreservesDirectionAndLimit ( ) {
		EventQuery q = EventQuery.matchAll().backwards().limit(3).until(e4_event2TagsA1.reference());
		assertTrue(q.isBackwards());
		assertEquals(Limit.to(3), q.limit());
		assertEquals(e4_event2TagsA1.reference(), q.until());
	}

	@Test
	void testUntilIfEarlierPreservesDirectionAndLimit ( ) {
		EventQuery q = EventQuery.matchAll().backwards().limit(3);
		q = q.untilIfEarlier(e3_event1TagsA1.reference());
		assertTrue(q.isBackwards());
		assertEquals(Limit.to(3), q.limit());
		assertEquals(e3_event1TagsA1.reference(), q.until());
	}

	// --- merge(Collection) ---------------------------------------------------

	@Test
	void testMergeEmptyInput ( ) {
		MergedEventQueries merged = EventQuery.merge(Collections.emptyList());
		assertEquals(0, merged.mergedCount());
		assertTrue(merged.mergedQueries().isEmpty());
	}

	@Test
	void testMergeNullInputThrows ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventQuery.merge(null));
		assertEquals("queries collection must not be null", e.getMessage());
	}

	@Test
	void testMergeNullElementThrows ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventQuery.merge(Arrays.asList(q, null)));
		assertEquals("merge input must not contain null queries", e.getMessage());
	}

	@Test
	void testMergeSingleUnlimitedQuery ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		MergedEventQueries merged = EventQuery.merge(List.of(q));

		assertEquals(1, merged.mergedCount());
		assertEquals(q, merged.mergedFor(q));
		assertEquals(List.of(q), merged.originalsFor(merged.mergedFor(q)));
	}

	@Test
	void testMergeTwoUnlimitedSameDirectionSameUntil ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1"));

		MergedEventQueries merged = EventQuery.merge(List.of(q1, q2));

		assertEquals(1, merged.mergedCount());
		EventQuery m = merged.mergedQueries().get(0);
		assertEquals(m, merged.mergedFor(q1));
		assertEquals(m, merged.mergedFor(q2));
		assertEquals(2, merged.originalsFor(m).size());

		// union of both queries (mirrors testMatchCombined)
		assertTrue(m.matches(e1_event1NoTags));
		assertFalse(m.matches(e2_event2NoTags));
		assertTrue(m.matches(e4_event2TagsA1));
		assertFalse(m.matches(e6_event2TagsA2B1));
	}

	@Test
	void testMergeForwardAndBackwardStaySeparate ( ) {
		EventQuery fwd = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery bwd = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards();

		MergedEventQueries merged = EventQuery.merge(List.of(fwd, bwd));

		assertEquals(2, merged.mergedCount());
		assertFalse(merged.mergedFor(fwd).isBackwards());
		assertTrue(merged.mergedFor(bwd).isBackwards());
	}

	@Test
	void testMergeDifferentUntilStaySeparate ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e4_event2TagsA1.reference());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none()).until(e3_event1TagsA1.reference());

		MergedEventQueries merged = EventQuery.merge(List.of(q1, q2));

		assertEquals(2, merged.mergedCount());
		assertEquals(e4_event2TagsA1.reference(), merged.mergedFor(q1).until());
		assertEquals(e3_event1TagsA1.reference(), merged.mergedFor(q2).until());
	}

	@Test
	void testMergeSameUntilGroupedTogether ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e4_event2TagsA1.reference());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.of("A", "1")).until(e4_event2TagsA1.reference());

		MergedEventQueries merged = EventQuery.merge(List.of(q1, q2));

		assertEquals(1, merged.mergedCount());
		EventQuery m = merged.mergedQueries().get(0);
		assertEquals(e4_event2TagsA1.reference(), m.until());
		assertTrue(m.matches(e3_event1TagsA1));
		assertFalse(m.matches(e5_event1TagsA1B1)); // beyond the until boundary
	}

	@Test
	void testMergeLimitedQueryIsPassthrough ( ) {
		EventQuery unlimited = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery limited = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none()).backwards().limit(1);

		MergedEventQueries merged = EventQuery.merge(List.of(unlimited, limited));

		assertEquals(2, merged.mergedCount());
		// limited query is its own merged query, untouched
		assertEquals(limited, merged.mergedFor(limited));
		assertEquals(Limit.to(1), merged.mergedFor(limited).limit());
		// unlimited query is separate
		assertTrue(merged.mergedFor(unlimited).limit().isNotSet());
	}

	@Test
	void testMergeAllLimitedInput ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).limit(1);
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none()).limit(2);
		EventQuery q3 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.of("A", "1")).limit(3);

		MergedEventQueries merged = EventQuery.merge(List.of(q1, q2, q3));

		assertEquals(3, merged.mergedCount());
		assertEquals(q1, merged.mergedFor(q1));
		assertEquals(q2, merged.mergedFor(q2));
		assertEquals(q3, merged.mergedFor(q3));
	}

	@Test
	void testMergeMixLimitedAndUnlimited ( ) {
		EventQuery a = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery b = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none());
		EventQuery c = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.of("A", "1")).limit(1);

		MergedEventQueries merged = EventQuery.merge(List.of(a, b, c));

		// a + b fold into one; c passes through
		assertEquals(2, merged.mergedCount());
		assertEquals(merged.mergedFor(a), merged.mergedFor(b));
		assertEquals(c, merged.mergedFor(c));
		assertEquals(2, merged.originalsFor(merged.mergedFor(a)).size());
		assertEquals(1, merged.originalsFor(c).size());
	}

	@Test
	void testMergeMatchAllInGroupYieldsMatchAll ( ) {
		EventQuery all = EventQuery.matchAll();
		EventQuery specific = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());

		MergedEventQueries merged = EventQuery.merge(List.of(all, specific));

		assertEquals(1, merged.mergedCount());
		EventQuery m = merged.mergedQueries().get(0);
		assertTrue(m.isMatchAll());
		assertFalse(m.isBackwards());
		assertNull(m.until());
		// both originals route to the match-all merged query
		assertEquals(m, merged.mergedFor(all));
		assertEquals(m, merged.mergedFor(specific));
		// the merged query is a superset matching everything
		assertTrue(m.matches(e2_event2NoTags));
		assertTrue(m.matches(e6_event2TagsA2B1));
	}

	@Test
	void testMergeMatchAllPreservesUntil ( ) {
		EventQuery all = EventQuery.matchAll().until(e4_event2TagsA1.reference());
		EventQuery specific = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).until(e4_event2TagsA1.reference());

		MergedEventQueries merged = EventQuery.merge(List.of(all, specific));

		assertEquals(1, merged.mergedCount());
		EventQuery m = merged.mergedQueries().get(0);
		assertTrue(m.isMatchAll());
		assertEquals(e4_event2TagsA1.reference(), m.until());
		assertTrue(m.matches(e1_event1NoTags));
		assertTrue(m.matches(e4_event2TagsA1));
		assertFalse(m.matches(e5_event1TagsA1B1)); // beyond the until boundary
	}

	@Test
	void testMergeMatchNoneIsHarmless ( ) {
		EventQuery specific = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery none = EventQuery.matchNone();

		MergedEventQueries merged = EventQuery.merge(List.of(specific, none));

		assertEquals(1, merged.mergedCount());
		EventQuery m = merged.mergedQueries().get(0);
		// match-none contributes nothing: behaves like the specific query alone
		assertTrue(m.matches(e1_event1NoTags));
		assertFalse(m.matches(e2_event2NoTags));
		// the match-none original still has a mapping entry
		assertEquals(m, merged.mergedFor(none));
		assertEquals(2, merged.originalsFor(m).size());
	}

	@Test
	void testMergeAllMatchNoneGroup ( ) {
		EventQuery none1 = EventQuery.matchNone();
		EventQuery none2 = EventQuery.matchNone().until(e4_event2TagsA1.reference());

		MergedEventQueries merged = EventQuery.merge(List.of(none1, none2));

		// different until boundaries keep them in separate groups, each match-none
		assertEquals(2, merged.mergedCount());
		assertTrue(merged.mergedFor(none1).isMatchNone());
		assertTrue(merged.mergedFor(none2).isMatchNone());
	}

	@Test
	void testMergeDuplicateEqualQueries ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery dup = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());

		MergedEventQueries merged = EventQuery.merge(List.of(q, dup));

		assertEquals(1, merged.mergedCount());
		EventQuery m = merged.mergedQueries().get(0);
		assertEquals(m, merged.mergedFor(q));
		// the reverse list preserves duplicate count (completeness)
		assertEquals(2, merged.originalsFor(m).size());
	}

	@Test
	void testMergeLookupUnknownQueryThrows ( ) {
		EventQuery q = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery unknown = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none());

		MergedEventQueries merged = EventQuery.merge(List.of(q));

		IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> merged.mergedFor(unknown));
		assertEquals("query was not part of the merge input", e1.getMessage());
		IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> merged.originalsFor(unknown));
		assertEquals("query is not one of the merged queries", e2.getMessage());
	}

	@Test
	void testMergedQueriesAreUnlimited ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none());

		MergedEventQueries merged = EventQuery.merge(List.of(q1, q2));
		merged.mergedQueries().forEach(m -> assertTrue(m.limit().isNotSet()));
	}

	private StoredEvent storedEvent ( Event<?> e ) {
		try {
			return new StoredEvent(e.stream(), EventType.of(e.data()), e.reference(), JsonMapper.builder().build().writeValueAsString(e.data()), null, e.tags(), e.timestamp() );
		} catch (JacksonException ex) {
			throw new RuntimeException(ex);
		}
	}
	
	sealed interface MockDomainEvent {
		
		public record FirstDomainEvent ( ) implements MockDomainEvent { } 

		public record SecondDomainEvent ( ) implements MockDomainEvent { } 
		
	}
	
}
