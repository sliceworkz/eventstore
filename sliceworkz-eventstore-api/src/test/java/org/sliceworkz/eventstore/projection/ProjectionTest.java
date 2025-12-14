/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.ProjectionTest.MockDomainEvent;
import org.sliceworkz.eventstore.projection.ProjectionTest.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.projection.ProjectionTest.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class ProjectionTest {
	
	List<Event<MockDomainEvent>> mockEvents;
	
	@BeforeEach
	void setUp ( ) {
		EventStreamId mockStream = EventStreamId.forContext("unit").withPurpose("test");
		EventReference ref1 = EventReference.create(1, 1);
		EventReference ref2 = EventReference.create(2, 2);
		LocalDateTime now = LocalDateTime.now();
		this.mockEvents = Arrays.asList(new Event[] {
				Event.of(new FirstDomainEvent(), Tags.none()).positionAt(mockStream, ref1, now), 
				Event.of(new SecondDomainEvent(), Tags.none()).positionAt(mockStream,  ref2, now)});
	}
	
	
	@Test
	void testProjectIndividualEventWithMetaData ( ) {
		TestProjection projection = new TestProjection();
		mockEvents.stream().forEach(projection::when);
		assertEquals(mockEvents.size(), projection.counter());
		assertEquals(0, projection.counterList());
		assertEquals(0, projection.counterStream());
	}

	@Test
	void testProjectStreamOfEventsWithMetaData ( ) {
		TestProjection projection = new TestProjection();
		projection.when(mockEvents.stream());
		assertEquals(mockEvents.size(), projection.counter());
		assertEquals(0, projection.counterList());
		assertEquals(1, projection.counterStream());
	}

	@Test
	void testProjectListOfEventsWithMetaData ( ) {
		TestProjection projection = new TestProjection();
		projection.when(mockEvents);
		assertEquals(mockEvents.size(), projection.counter());
		assertEquals(1, projection.counterList());
		assertEquals(0, projection.counterStream());
	}
	
	@Test
	void testProjectIndividualEventWithoutMetaData ( ) {
		TestProjectionWithoutMetaData projection = new TestProjectionWithoutMetaData();
		mockEvents.stream().forEach(projection::when);
		assertEquals(mockEvents.size(), projection.counter());
		assertEquals(0, projection.counterList());
		assertEquals(0, projection.counterStream());
	}

	@Test
	void testProjectStreamOfEventsWithoutMetaData ( ) {
		TestProjectionWithoutMetaData projection = new TestProjectionWithoutMetaData();
		projection.when(mockEvents.stream());
		assertEquals(mockEvents.size(), projection.counter());
		assertEquals(0, projection.counterList());
		assertEquals(1, projection.counterStream());
	}

	@Test
	void testProjectListOfEventsWithoutMetaData ( ) {
		TestProjectionWithoutMetaData projection = new TestProjectionWithoutMetaData();
		projection.when(mockEvents);
		assertEquals(mockEvents.size(), projection.counter());
		assertEquals(1, projection.counterList());
		assertEquals(0, projection.counterStream());
	}
	
	sealed interface MockDomainEvent {
		
		public record FirstDomainEvent ( ) implements MockDomainEvent { } 

		public record SecondDomainEvent ( ) implements MockDomainEvent { } 
		
	}
}


class TestProjection implements Projection<MockDomainEvent> {
	
	private int counter;
	private int counterList;
	private int counterStream;
	
	public TestProjection ( ) {
	}

	@Override
	public void when(Event<MockDomainEvent> eventWithMeta) {
		System.out.println("event handled: %s".formatted(eventWithMeta));
		counter++;
	}

	@Override
	public void when(List<Event<MockDomainEvent>> eventsWithMeta) {
		System.out.println("list of events");
		eventsWithMeta.forEach(this::when);
		counterList++;
	}

	@Override
	public void when(Stream<Event<MockDomainEvent>> eventsWithMeta) {
		System.out.println("stream of events");
		eventsWithMeta.forEach(this::when);
		counterStream++;
	}

	@Override
	public EventQuery eventQuery() {
		return EventQuery.matchAll();
	}
	
	public int counter ( ) {
		return counter;
	}
	
	public int counterList ( ) {
		return counterList;
	}

	public int counterStream ( ) {
		return counterStream;
	}

};


class TestProjectionWithoutMetaData implements ProjectionWithoutMetaData<MockDomainEvent> {
	
	private int counter;
	private int counterList;
	private int counterStream;
	
	public TestProjectionWithoutMetaData ( ) {
	}

	@Override
	public void when(MockDomainEvent eventWithMeta) {
		System.out.println("event handled: %s".formatted(eventWithMeta));
		counter++;
	}

	@Override
	public void when(List<Event<MockDomainEvent>> eventsWithMeta) {
		System.out.println("list of events ...");
		eventsWithMeta.forEach(this::when);
		counterList++;
		System.out.println("... list of events done.");
	}

	@Override
	public void when(Stream<Event<MockDomainEvent>> eventsWithMeta) {
		System.out.println("stream of events ...");
		eventsWithMeta.forEach(this::when);
		counterStream++;
		System.out.println("... stream of events done.");
	}

	@Override
	public EventQuery eventQuery() {
		return EventQuery.matchAll();
	}
	
	public int counter ( ) {
		return counter;
	}
	
	public int counterList ( ) {
		return counterList;
	}

	public int counterStream ( ) {
		return counterStream;
	}

};