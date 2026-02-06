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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.mock.MockDomainEvent;
import org.sliceworkz.eventstore.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.mock.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.mock.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;

public class OptimisticLockingTest {

	private static final String UNITTEST_BOUNDEDCONTEXT = "unittest";
	
	EventStorage eventStorage;
	
	@BeforeEach
	public void setUp ( ) {
		this.eventStorage = createEventStorage();
	}
	
	@AfterEach
	public void tearDown ( ) {
		destroyEventStorage(eventStorage);
	}
	
	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().build();
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}
	
	@Test
	void testOptimisticLockingSucceedsWhenNoConflict() {
		EventStream<MockDomainEvent> eventStream = createEventStream();
		
		// First append with no criteria (should succeed)
		EphemeralEvent<FirstDomainEvent> firstEvent = Event.of(new FirstDomainEvent("test1"), Tags.none());
		Event<MockDomainEvent> firstEventStored = eventStream.append(AppendCriteria.none(), Collections.singletonList(firstEvent)).get(0);
		
		// Verify event was appended
		assertEquals(1, eventStream.query(EventQuery.matchAll()).count(), "First event should be appended successfully");

		// check that the reference (both id and position in the stream) is correctly registered
		EventReference er = eventStream.query(EventQuery.matchAll()).toList().get(0).reference(); 
		assertNotNull(er);
		assertEquals(1, er.position());
		assertEquals(firstEventStored.reference().id(), er.id());
	}

	@Test
	void testOptimisticLockingSucceedsWithCorrectExpectedEventId() {
		EventStream<MockDomainEvent> eventStream = createEventStream();
		
		// First append
		EphemeralEvent<FirstDomainEvent> firstEvent = Event.of(new FirstDomainEvent("test1"), Tags.none());
		eventStream.append(AppendCriteria.none(), Collections.singletonList(firstEvent));
		
		// Get the event ID of the first event
		EventReference lastEventInStream = eventStream.query(EventQuery.matchAll())
			.map(Event::reference)
			.reduce((first, second) -> second).orElse(null); // Get last event ID
		
		// Second append with correct expected event ID (should succeed)
		EphemeralEvent<SecondDomainEvent> secondEvent = Event.of(new SecondDomainEvent("test2"), Tags.none());
		AppendCriteria criteria = AppendCriteria.of(EventQuery.matchAll(), lastEventInStream);
		eventStream.append(criteria, Collections.singletonList(secondEvent));
		
		// Verify both events are in store
		assertEquals(2, eventStream.query(EventQuery.matchAll()).count(), "Both events should be in the store");
	}

	@Test
	void testOptimisticLockingMissesIncorrectExpectedEventId() {
		EventStream<MockDomainEvent> eventStream = createEventStream();
		
		// First append
		EphemeralEvent<FirstDomainEvent> firstEvent = Event.of(new FirstDomainEvent("test1"), Tags.none());
		eventStream.append(AppendCriteria.none(), Collections.singletonList(firstEvent));
		
		// Create a fake event ID that doesn't match the actual last event
		EventReference fakeEvent = EventReference.create(1234567890, 1234567890);
		
		// Second append with incorrect expected event ID (should fail)
		EphemeralEvent<SecondDomainEvent> secondEvent = Event.of(new SecondDomainEvent("test2"), Tags.none());
		AppendCriteria criteria = AppendCriteria.of(EventQuery.matchAll(), fakeEvent);
		
		// in the ideal world, we would throw an exception since the reference event would not be found, but not all implementations would be able to catch this ...
		eventStream.append(criteria, Collections.singletonList(secondEvent));
		
		// Verify only first event is in store (second was not appended)
		assertEquals(2, eventStream.query(EventQuery.matchAll()).count(), "Second event was expected to be appended as well");
	}

	@Test
	void testOptimisticLockingFailsWhenExpectingEmptyStreamButEventsExist() {
		EventStream<MockDomainEvent> eventStream = createEventStream();
		
		// First append
		EphemeralEvent<FirstDomainEvent> firstEvent = Event.of(new FirstDomainEvent("test1"), Tags.none());
		Event<MockDomainEvent> firstEventStored = eventStream.append(AppendCriteria.none(), Collections.singletonList(firstEvent)).get(0);
		
		// Second append
		EphemeralEvent<SecondDomainEvent> secondEvent = Event.of(new SecondDomainEvent("test2"), Tags.none());
		eventStream.append(AppendCriteria.none(), Collections.singletonList(secondEvent));

		// Try to append on expecting stream with assumed only first append (should fail)
		EphemeralEvent<ThirdDomainEvent> thirdEvent = Event.of(new ThirdDomainEvent("test3"), Tags.none());
		// re-read from stread to get position filled in
		AppendCriteria criteria = AppendCriteria.of(EventQuery.matchAll(), eventStream.getEventById(firstEventStored.reference().id()).get().reference());
		
		// Should throw OptimisticLockingException
		assertThrows(OptimisticLockingException.class, () -> {
			eventStream.append(criteria, Collections.singletonList(thirdEvent));
		}, "Should throw OptimisticLockingException when expecting empty stream but events exist");
		
		// Verify only first event is in store
		assertEquals(2, eventStream.query(EventQuery.matchAll()).count(), "Only first event should remain in store after failed append");
	}

	@Test
	void testOptimisticLockingSucceedsWhenExpectingEmptyStreamAndStreamIsEmpty() {
		EventStream<MockDomainEvent> eventStream = createEventStream();
		
		// Append to empty stream expecting it to be empty (should succeed)
		EphemeralEvent<FirstDomainEvent> firstEvent = Event.of(new FirstDomainEvent("test1"), Tags.none());
		AppendCriteria criteria = AppendCriteria.of(EventQuery.matchAll(), null);
		eventStream.append(criteria, Collections.singletonList(firstEvent));
		
		// Verify event was appended
		assertEquals(1, eventStream.query(EventQuery.matchAll()).count(), "Event should be appended to empty stream when expected");
	}

	@Test
	void testOptimisticLockingSucceedsWhenExpectingEmptyStreamAndStreamIsNotEmpty() {
		EventStream<MockDomainEvent> eventStream = createEventStream();
		
		// Append to empty stream expecting it to be empty (should succeed)
		EphemeralEvent<FirstDomainEvent> firstEvent = Event.of(new FirstDomainEvent("test1"), Tags.none());
		AppendCriteria criteria = AppendCriteria.of(EventQuery.matchAll(), null);

		// quickly append another event on the stream ...
		EphemeralEvent<FirstDomainEvent> intermediateEvent = Event.of(new FirstDomainEvent("test1"), Tags.none());
		eventStream.append(AppendCriteria.none(), Collections.singletonList(intermediateEvent));

		// ... so our own append (which expects an empty stream) should fail
		assertThrows(OptimisticLockingException.class, () -> {
			eventStream.append(criteria, Collections.singletonList(firstEvent));
		}, "Should throw OptimisticLockingException when expecting empty stream but events exist");

		// ... but adding it with no appendcriteria should work
		eventStream.append(AppendCriteria.none(), Collections.singletonList(firstEvent));
		
		// Verify event was appended
		assertEquals(2, eventStream.query(EventQuery.matchAll()).count(), "Event should be appended to stream when expected");
	}

	@Test
	void testOptimisticLockingWithEventQueryFiltering() {
		EventStream<MockDomainEvent> eventStream = createEventStream();

		// Append different types of events
		EphemeralEvent<FirstDomainEvent> firstEvent = Event.of(new FirstDomainEvent("test1"), Tags.none());
		EphemeralEvent<SecondDomainEvent> secondEvent = Event.of(new SecondDomainEvent("test2"), Tags.none());

		eventStream.append(AppendCriteria.none(), Collections.singletonList(firstEvent));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(secondEvent));

		// Query only for FirstDomainEvent types
		EventQuery firstEventQuery = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		EventReference idOfFirstEvent = eventStream.query(firstEventQuery)
			.map(Event::reference)
			.reduce((first, second) -> second).orElse(null);

		// Append another FirstDomainEvent with criteria based on FirstDomainEvent query
		EphemeralEvent<FirstDomainEvent> thirdEvent = Event.of(new FirstDomainEvent("test3"), Tags.none());
		AppendCriteria criteria = AppendCriteria.of(firstEventQuery, idOfFirstEvent);
		eventStream.append(criteria, Collections.singletonList(thirdEvent));

		// Verify all events are in store
		assertEquals(3, eventStream.query(EventQuery.matchAll()).count(), "All three events should be in the store");
		assertEquals(2, eventStream.query(firstEventQuery).count(), "Should have two FirstDomainEvent instances");

		//dumpEventsTable ( );
	}

	@Test
	void testOptimisticLockingWithBackwardsQueryLimit1 ( ) {
		EventStream<MockDomainEvent> eventStream = createEventStream();

		// Append a few events
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e1"), Tags.none())));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new SecondDomainEvent("e2"), Tags.none())));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e3"), Tags.none())));

		// Use a backwards query with limit 1 to get the most recent FirstDomainEvent
		EventQuery backwardsQuery = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards().limit(1);
		Event<MockDomainEvent> mostRecent = eventStream.query(backwardsQuery).findFirst().orElse(null);

		assertNotNull(mostRecent, "Should find the most recent FirstDomainEvent");
		assertEquals("e3", ((FirstDomainEvent) mostRecent.data()).value());

		// Use that same backwards query for append criteria — forLockingCheck() strips direction/limit
		// so the locking check still scans forward for any FirstDomainEvent after e3's reference
		AppendCriteria criteria = AppendCriteria.of(backwardsQuery, mostRecent.reference());
		eventStream.append(criteria, Collections.singletonList(Event.of(new FirstDomainEvent("e4"), Tags.none())));

		// Verify the append succeeded
		assertEquals(4, eventStream.query(EventQuery.matchAll()).count());
		assertEquals(3, eventStream.query(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())).count());
	}

	@Test
	void testOptimisticLockingWithBackwardsQueryLimit1FailsOnConflict ( ) {
		EventStream<MockDomainEvent> eventStream = createEventStream();

		// Append a few events
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e1"), Tags.none())));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new SecondDomainEvent("e2"), Tags.none())));

		// Query the most recent FirstDomainEvent using backwards + limit 1
		EventQuery backwardsQuery = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards().limit(1);
		Event<MockDomainEvent> mostRecent = eventStream.query(backwardsQuery).findFirst().orElse(null);

		assertNotNull(mostRecent);
		assertEquals("e1", ((FirstDomainEvent) mostRecent.data()).value());

		// Another FirstDomainEvent sneaks in after our read
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("conflict"), Tags.none())));

		// Our append should fail — forLockingCheck() ensures the query scans forward for ALL
		// matching FirstDomainEvents after our reference, detecting the conflicting event
		AppendCriteria criteria = AppendCriteria.of(backwardsQuery, mostRecent.reference());
		assertThrows(OptimisticLockingException.class, () -> {
			eventStream.append(criteria, Collections.singletonList(Event.of(new FirstDomainEvent("e3"), Tags.none())));
		}, "Should detect the conflicting FirstDomainEvent appended after our read");

		assertEquals(3, eventStream.query(EventQuery.matchAll()).count(), "Failed append should not have added an event");
	}

	@Test
	void testOptimisticLockingWithBackwardsQueryHigherLimit ( ) {
		EventStream<MockDomainEvent> eventStream = createEventStream();

		// Append several events of mixed types
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e1"), Tags.none())));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new SecondDomainEvent("e2"), Tags.none())));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e3"), Tags.none())));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new SecondDomainEvent("e4"), Tags.none())));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e5"), Tags.none())));

		// Use backwards query with limit 3 — gets the 3 most recent FirstDomainEvents (e5, e3, e1)
		EventQuery backwardsQuery = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards().limit(3);
		var recentEvents = eventStream.query(backwardsQuery).toList();

		assertEquals(3, recentEvents.size());
		// Backwards order: newest first
		assertEquals("e5", ((FirstDomainEvent) recentEvents.get(0).data()).value());
		assertEquals("e3", ((FirstDomainEvent) recentEvents.get(1).data()).value());
		assertEquals("e1", ((FirstDomainEvent) recentEvents.get(2).data()).value());

		// The most recent event (first in the backwards list) is the reference for optimistic locking
		EventReference latestAppended = recentEvents.get(0).reference();

		// Append using the backwards query as criteria — the reference is the most recent matching event
		AppendCriteria criteria = AppendCriteria.of(backwardsQuery, latestAppended);
		eventStream.append(criteria, Collections.singletonList(Event.of(new FirstDomainEvent("e6"), Tags.none())));

		// Verify the append succeeded
		assertEquals(6, eventStream.query(EventQuery.matchAll()).count());
		assertEquals(4, eventStream.query(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())).count());
	}

	@Test
	void testOptimisticLockingWithBackwardsQueryHigherLimitFailsOnConflict ( ) {
		EventStream<MockDomainEvent> eventStream = createEventStream();

		// Append several events
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e1"), Tags.none())));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new SecondDomainEvent("e2"), Tags.none())));
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("e3"), Tags.none())));

		// Query the 5 most recent FirstDomainEvents backwards (only 2 exist: e3, e1)
		EventQuery backwardsQuery = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards().limit(5);
		var recentEvents = eventStream.query(backwardsQuery).toList();

		assertEquals(2, recentEvents.size());
		assertEquals("e3", ((FirstDomainEvent) recentEvents.get(0).data()).value());
		assertEquals("e1", ((FirstDomainEvent) recentEvents.get(1).data()).value());

		// Use the first (most recent) event from the backwards result as the locking reference
		EventReference latestAppended = recentEvents.get(0).reference();

		// A conflicting FirstDomainEvent sneaks in
		eventStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("conflict"), Tags.none())));

		// Should fail — forLockingCheck() ensures full forward scan detects "conflict"
		AppendCriteria criteria = AppendCriteria.of(backwardsQuery, latestAppended);
		assertThrows(OptimisticLockingException.class, () -> {
			eventStream.append(criteria, Collections.singletonList(Event.of(new FirstDomainEvent("e4"), Tags.none())));
		}, "Should detect the conflicting FirstDomainEvent appended after our read");

		assertEquals(4, eventStream.query(EventQuery.matchAll()).count(), "Failed append should not have added an event");
	}

	private EventStream<MockDomainEvent> createEventStream() {
		return EventStoreFactory.get().eventStore(eventStorage).getEventStream(EventStreamId.forContext(UNITTEST_BOUNDEDCONTEXT), MockDomainEvent.class);
	}
	
}