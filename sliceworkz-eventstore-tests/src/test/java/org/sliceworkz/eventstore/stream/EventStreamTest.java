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
package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.AbstractEventStoreTest;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorageImpl;
import org.sliceworkz.eventstore.mock.MockConsistentAppendListener;
import org.sliceworkz.eventstore.mock.MockDomainEventWithNonSealedInterface;
import org.sliceworkz.eventstore.mock.MockDomainEventWithNonSealedInterface.DomainEventPartOfMockDomainEventWithNonSealedInterface;
import org.sliceworkz.eventstore.mock.MockEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.mockdomain.MockDomainDuplicatedEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.mockdomain.OtherMockDomainEvent;
import org.sliceworkz.eventstore.mockdomain.OtherMockDomainEvent.AnotherDomainEvent;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;

public class EventStreamTest extends AbstractEventStoreTest {

	private EventStream<MockDomainEvent> es; 
	private EventStreamId stream;
	private EventStreamId testEventStream = EventStreamId.forContext("test").withPurpose("test");
	
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		
		stream = EventStreamId.forContext("app").withPurpose("default");
		es = eventStore().getEventStream(stream, MockDomainEvent.class);
		
	}

	@Test
	void testRegisterNonSealedInterface ( ) {
		IllegalArgumentException e =  assertThrows(IllegalArgumentException.class, ()->eventStore().getEventStream(testEventStream, MockDomainEventWithNonSealedInterface.class));
		assertEquals("interface org.sliceworkz.eventstore.mock.MockDomainEventWithNonSealedInterface should be sealed to allow Event Type determination", e.getMessage());
	}
	
	@Test
	void testRegisterConcreteEventType ( ) {
		// should be ok, as this is a concrete class
		eventStore().getEventStream(testEventStream, DomainEventPartOfMockDomainEventWithNonSealedInterface.class);
	}

	@Test
	void testRegisterDuplicateEventTypes ( ) {
		Set<Class<?>> rootEventClasses = new HashSet<>();
		rootEventClasses.add(MockDomainEvent.class);
		rootEventClasses.add(MockDomainDuplicatedEvent.class);
		
		IllegalArgumentException e =  assertThrows(IllegalArgumentException.class, ()->eventStore().getEventStream(testEventStream, rootEventClasses));
		assertTrue(e.getMessage().startsWith("duplicate event name"));
	}

	@Test
	void testSubscribeListener ( ) {
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");
		EventStreamId otherStream = EventStreamId.forContext("other").withPurpose("default");
		
		EventStream<MockDomainEvent> s1 = eventStore().getEventStream(stream, MockDomainEvent.class);
		EventStream<MockDomainEvent> s2 = eventStore().getEventStream(stream, MockDomainEvent.class);
		EventStream<OtherMockDomainEvent> s3 = eventStore().getEventStream(otherStream, OtherMockDomainEvent.class);
		
		MockConsistentAppendListener<MockDomainEvent> s1cal = new MockConsistentAppendListener<>();
		MockEventuallyConsistentAppendListener s1ecal = new MockEventuallyConsistentAppendListener();
		waitBecauseOfEventualConsistency();
		
		s1.subscribe(s1cal);
		s1.subscribe(s1ecal);

		MockConsistentAppendListener<MockDomainEvent> s2cal = new MockConsistentAppendListener<>();
		MockEventuallyConsistentAppendListener s2ecal = new MockEventuallyConsistentAppendListener();
		waitBecauseOfEventualConsistency();

		s2.subscribe(s2cal);
		s2.subscribe(s2ecal);

		MockConsistentAppendListener<OtherMockDomainEvent> s3cal = new MockConsistentAppendListener<>();
		MockEventuallyConsistentAppendListener s3ecal = new MockEventuallyConsistentAppendListener();
		waitBecauseOfEventualConsistency();

		s3.subscribe(s3cal);
		s3.subscribe(s3ecal);

		// first append via the first stream instance ...
		s1.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));
		s1.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));
		waitBecauseOfEventualConsistency();
		
		assertEquals(2, s1cal.count());
		assertEquals(2, s1ecal.count());

		assertEquals(0, s2cal.count());  // consistent listener not notified by other stream instance
		assertEquals(2, s2ecal.count()); // eventually consistent listener is notified

		assertEquals(0, s3cal.count());  // other stream, shouldn't be notified
		assertEquals(0, s3ecal.count()); // other stream, shouldn't be notified

		// ... now append via the other stream instance on the same logical stream
		s2.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));
		
		waitBecauseOfEventualConsistency();
		
		assertEquals(2, s1cal.count());
		assertEquals(3, s1ecal.count());

		assertEquals(1, s2cal.count());  
		assertEquals(3, s2ecal.count()); 

		assertEquals(0, s3cal.count());  // other stream, shouldn't be notified
		assertEquals(0, s3ecal.count()); // other stream, shouldn't be notified

		
		// ... and now append on another logical stream
		s3.append(AppendCriteria.none(), Event.of(new AnotherDomainEvent("1"), Tags.none()));
		waitBecauseOfEventualConsistency();
		
		assertEquals(2, s1cal.count());  // other stream, shouldn't be notified
		assertEquals(3, s1ecal.count()); // other stream, shouldn't be notified

		assertEquals(1, s2cal.count());  // other stream, shouldn't be notified
		assertEquals(3, s2ecal.count()); // other stream, shouldn't be notified

		assertEquals(1, s3cal.count());  
		assertEquals(1, s3ecal.count()); 
	}

	@Test
	void testAppend ( ) {
		
		MockEventuallyConsistentAppendListener appendListener = new MockEventuallyConsistentAppendListener();
		es.subscribe(appendListener);
		
		List<Event<MockDomainEvent>> events = es.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("1"), Tags.none())));
		assertEquals(1, events.size());

		waitBecauseOfEventualConsistency();
		
		assertEquals(1, appendListener.count()); // we expect one notification on our appendlistener
		assertEquals(events.getLast().reference(), appendListener.lastReference());

		
		EventId eventId = events.getFirst().reference().id();

		// check we can find it via getEvent on the same stream
		Optional<Event<MockDomainEvent>> retrieved = es.getEventById(eventId);
		assertTrue(retrieved.isPresent());
		assertEquals(eventId, retrieved.get().reference().id());
		// or from a query on the same
		assertTrue(es.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());
		
		
		// check we can find it via getEvent on a generic stream
		EventStreamId generic = EventStreamId.anyContext().anyPurpose();
		EventStream<MockDomainEvent> genericStream = eventStore().getEventStream(generic, MockDomainEvent.class);
		retrieved = genericStream.getEventById(eventId);
		assertTrue(retrieved.isPresent());
		assertEquals(eventId, retrieved.get().reference().id());
		// or from a query on the same
		assertTrue(genericStream.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

		
		// check we can't get it via another stream
		EventStreamId other = EventStreamId.forContext("test2").withPurpose("test2");
		EventStream<MockDomainEvent> otherStream = eventStore().getEventStream(other, MockDomainEvent.class);
		Optional<Event<MockDomainEvent>> notRetrieved = otherStream.getEventById(eventId);
		assertFalse(notRetrieved.isPresent());
		// and neither from a query on the same
		assertFalse(otherStream.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

	}
	
	
	@Test
	void testAppendMultiple ( ) {
		
		MockEventuallyConsistentAppendListener appendListener = new MockEventuallyConsistentAppendListener();
		es.subscribe(appendListener);
		
		EphemeralEvent<MockDomainEvent> e1 = Event.of(new FirstDomainEvent("1"), Tags.none());
		EphemeralEvent<MockDomainEvent> e2 = Event.of(new SecondDomainEvent("2"), Tags.none());
		
		List<Event<MockDomainEvent>> events = es.append(AppendCriteria.none(), List.of(e1, e2));
		assertEquals(2, events.size());
		
		waitBecauseOfEventualConsistency();
		
		assertEquals(1, appendListener.count()); // we want only one notification for both events (about the last one)
		assertEquals(events.getLast().reference(), appendListener.lastReference());
		
		
		EventId eventId = events.getFirst().reference().id();

		// check we can find it via getEvent on the same stream
		Optional<Event<MockDomainEvent>> retrieved = es.getEventById(eventId);
		assertTrue(retrieved.isPresent());
		assertEquals(eventId, retrieved.get().reference().id());
		// or from a query on the same
		assertTrue(es.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());
		
		
		// check we can find it via getEvent on a generic stream
		EventStreamId generic = EventStreamId.anyContext().anyPurpose();
		EventStream<MockDomainEvent> genericStream = eventStore().getEventStream(generic, MockDomainEvent.class);
		retrieved = genericStream.getEventById(eventId);
		assertTrue(retrieved.isPresent());
		assertEquals(eventId, retrieved.get().reference().id());
		// or from a query on the same
		assertTrue(genericStream.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

		
		// check we can't get it via another stream
		EventStreamId other = EventStreamId.forContext("test2").withPurpose("test2");
		EventStream<MockDomainEvent> otherStream = eventStore().getEventStream(other, MockDomainEvent.class);
		Optional<Event<MockDomainEvent>> notRetrieved = otherStream.getEventById(eventId);
		assertFalse(notRetrieved.isPresent());
		// and neither from a query on the same
		assertFalse(otherStream.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

	}
	
	@Test
	void testAppendWithConcreteEventClass ( ) {
		
		// this stream only contains this concrete event type (we use <Object> generic for test purposes only)
		EventStream<Object> specialEs = eventStore().getEventStream(stream, FirstDomainEvent.class);
		
		// should be ok
		specialEs.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("1"), Tags.none())));

		// should be not be ok
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> specialEs.append(AppendCriteria.none(), Collections.singletonList(Event.of(new SecondDomainEvent("2"), Tags.none()))));
		assertEquals("cannot append event type 'SecondDomainEvent' via this stream", e.getMessage());
	}

	@Test
	void testAppendToNonSpecificStream ( ) {
		var otherStream = eventStore().getEventStream(EventStreamId.anyContext());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,()->otherStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("1"), Tags.none()))));
		assertEquals("cannot append to non-specific eventstream ", e.getMessage());
	}

	@Override
	public EventStorage createEventStorage() {
		return new InMemoryEventStorageImpl();
	}
	
}
