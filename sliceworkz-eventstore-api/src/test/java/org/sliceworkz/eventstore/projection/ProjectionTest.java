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
package org.sliceworkz.eventstore.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.ProjectionTest.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.projection.ProjectionTest.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class ProjectionTest {
	
	List<Event<MockDomainEvent>> mockEvents;
	
	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp ( ) {
		EventStreamId mockStream = EventStreamId.forContext("unit").withPurpose("test");
		EventReference ref1 = EventReference.create(1, 1);
		EventReference ref2 = EventReference.create(2, 2);
		Instant now = Instant.now();
		this.mockEvents = Arrays.asList(new Event[] {
				Event.of(mockStream, ref1, EventType.of(FirstDomainEvent.class), EventType.of(FirstDomainEvent.class), new FirstDomainEvent(), Tags.none(), now),
				Event.of(mockStream, ref2, EventType.of(SecondDomainEvent.class), EventType.of(SecondDomainEvent.class), new SecondDomainEvent(), Tags.none(), now)});
	}
	
	
	/**
	 * "No initialization query" is spelled the way every absent filter in this API is spelled --
	 * {@link EventQuery#matchNone()} -- so a caller reading the default gets a query it can inspect
	 * rather than a null it has to guard.
	 */
	@Test
	void theDefaultInitQueryIsMatchNone ( ) {
		Projection<MockDomainEvent> projection = new TestProjection();
		assertNotNull(projection.initQuery());
		assertTrue(projection.initQuery().filter().isMatchNone());
	}

	@Test
	void aProjectionHandlesOneEventAtATimeWithItsMetaData ( ) {
		TestProjection projection = new TestProjection();
		mockEvents.forEach(projection::when);
		assertEquals(mockEvents, projection.handled());
	}

	/**
	 * The handler contract is one method: {@code when(Event)}. A second {@code when} beside it -- a
	 * payload-only overload, a {@code List} or {@code Stream} batch default -- is an entry point the
	 * projector never calls, so an override of it runs for nobody; this pins that none exists on the
	 * handler or on the projection, so one cannot come back as a convenience.
	 */
	@Test
	void theHandlerContractIsOneMethodTakingTheEvent ( ) {
		for ( Class<?> type : List.of(EventHandler.class, Projection.class) ) {
			List<Method> whens = Arrays.stream(type.getMethods())
					.filter(m -> m.getName().equals("when"))
					.toList();
			assertEquals(1, whens.size(), () -> type.getSimpleName() + " declares " + whens);
			Method when = whens.get(0);
			assertTrue(Modifier.isAbstract(when.getModifiers()), "when is abstract");
			assertEquals(1, when.getParameterCount());
			assertEquals(Event.class, when.getParameterTypes()[0]);
		}
	}

	sealed interface MockDomainEvent {
		
		public record FirstDomainEvent ( ) implements MockDomainEvent { } 

		public record SecondDomainEvent ( ) implements MockDomainEvent { } 
		
	}

	static class TestProjection implements Projection<MockDomainEvent> {
		
		private final List<Event<MockDomainEvent>> handled = new ArrayList<>();
		
		@Override
		public void when ( Event<MockDomainEvent> event ) {
			handled.add(event);
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}
		
		List<Event<MockDomainEvent>> handled ( ) {
			return handled;
		}

	}

}
