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
package org.sliceworkz.eventstore.testing.tck.stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEventWithNonSealedInterface.DomainEventPartOfMockDomainEventWithNonSealedInterface;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEventWithNonSealedInterface;
import org.sliceworkz.eventstore.testing.tck.mock.MockEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainDuplicatedEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.FourthDomainEventWithErasableParts;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.OtherMockDomainEvent.AnotherDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.OtherMockDomainEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.testing.StorageOptions;

public class EventStreamTest extends AbstractEventStoreTest {

	private EventStream<MockDomainEvent> es;
	private EventStreamId stream;
	private EventStreamId testEventStream = EventStreamId.forContext("test").withPurpose("test");

	@Override
	protected StorageOptions storageOptions ( ) {
		// the only scenario that runs against a prefixed store, so prefixing stays exercised
		return StorageOptions.defaults().withPrefix("unittest_prefix_");
	}

	@BeforeEach
	void openStream ( ) {
		stream = EventStreamId.forContext("app").withPurpose("default");
		es = eventStore().getEventStream(stream, MockDomainEvent.class);
	}

	@ForEachBackend
	void testRegisterNonSealedInterface ( ) {
		IllegalArgumentException e =  assertThrows(IllegalArgumentException.class, ()->eventStore().getEventStream(testEventStream, MockDomainEventWithNonSealedInterface.class));
		assertEquals("interface org.sliceworkz.eventstore.testing.tck.mock.MockDomainEventWithNonSealedInterface should be sealed to allow Event Type determination", e.getMessage());
	}

	@ForEachBackend
	void testRegisterConcreteEventType ( ) {
		// should be ok, as this is a concrete class
		eventStore().getEventStream(testEventStream, DomainEventPartOfMockDomainEventWithNonSealedInterface.class);
	}

	@ForEachBackend
	void testRegisterDuplicateEventTypes ( ) {
		Set<Class<?>> rootEventClasses = new HashSet<>();
		rootEventClasses.add(MockDomainEvent.class);
		rootEventClasses.add(MockDomainDuplicatedEvent.class);

		IllegalArgumentException e =  assertThrows(IllegalArgumentException.class, ()->eventStore().getEventStream(testEventStream, rootEventClasses));
		assertTrue(e.getMessage().startsWith("duplicate event name"));
	}

	@ForEachBackend
	void testSubscribeListener ( ) {
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");
		EventStreamId otherStream = EventStreamId.forContext("other").withPurpose("default");

		EventStream<MockDomainEvent> s1 = eventStore().getEventStream(stream, MockDomainEvent.class);
		EventStream<MockDomainEvent> s2 = eventStore().getEventStream(stream, MockDomainEvent.class);
		EventStream<OtherMockDomainEvent> s3 = eventStore().getEventStream(otherStream, OtherMockDomainEvent.class);

		// s1 and s2 are two handles on the *same* logical stream: a subscriber on either must hear about
		// an append made through the other, since it is the stream that is subscribed to, not the handle
		MockEventuallyConsistentAppendListener s1ecal = new MockEventuallyConsistentAppendListener();
		s1.subscribe(s1ecal);

		MockEventuallyConsistentAppendListener s2ecal = new MockEventuallyConsistentAppendListener();
		s2.subscribe(s2ecal);

		MockEventuallyConsistentAppendListener s3ecal = new MockEventuallyConsistentAppendListener();
		s3.subscribe(s3ecal);

		// first append via the first stream instance ...
		s1.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));
		List<Event<MockDomainEvent>> secondAppend = s1.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));
		EventReference secondAppendRef = secondAppend.getLast().reference();

		// eventually consistent listeners are notified about the latest position (atLeastUntil),
		// not per-event — the number of callbacks depends on timing, so we wait for the
		// last reference rather than an exact count
		BooleanSupplier bothEventualListenersNotifiedUpToSecondAppend = () ->
			secondAppendRef.equals(s1ecal.lastReference()) && secondAppendRef.equals(s2ecal.lastReference());
		waitBecauseOfEventualConsistency(bothEventualListenersNotifiedUpToSecondAppend);

		assertTrue(s1ecal.count() >= 1); // at least once, up to twice depending on timing
		assertEquals(secondAppendRef, s1ecal.lastReference());

		assertTrue(s2ecal.count() >= 1); // the other handle on the same stream is notified too
		assertEquals(secondAppendRef, s2ecal.lastReference());

		assertNull(s3ecal.lastReference()); // other stream, shouldn't be notified

		// ... now append via the other stream instance on the same logical stream
		List<Event<MockDomainEvent>> thirdAppend = s2.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));
		EventReference thirdAppendRef = thirdAppend.getLast().reference();

		BooleanSupplier bothEventualListenersNotifiedUpToThirdAppend = () ->
			thirdAppendRef.equals(s1ecal.lastReference()) && thirdAppendRef.equals(s2ecal.lastReference());
		waitBecauseOfEventualConsistency(bothEventualListenersNotifiedUpToThirdAppend);

		assertEquals(thirdAppendRef, s1ecal.lastReference());
		assertEquals(thirdAppendRef, s2ecal.lastReference());

		assertNull(s3ecal.lastReference()); // other stream, shouldn't be notified

		// ... and now append on another logical stream
		List<Event<OtherMockDomainEvent>> fourthAppend = s3.append(AppendCriteria.none(), Event.of(new AnotherDomainEvent("1"), Tags.none()));
		EventReference fourthAppendRef = fourthAppend.getLast().reference();

		BooleanSupplier s3EventualListenerNotified = () -> fourthAppendRef.equals(s3ecal.lastReference());
		waitBecauseOfEventualConsistency(s3EventualListenerNotified);

		assertEquals(thirdAppendRef, s1ecal.lastReference()); // other stream, shouldn't be notified
		assertEquals(thirdAppendRef, s2ecal.lastReference()); // other stream, shouldn't be notified

		assertEquals(fourthAppendRef, s3ecal.lastReference());
	}

	@ForEachBackend
	void testAppend ( ) {

		MockEventuallyConsistentAppendListener appendListener = new MockEventuallyConsistentAppendListener();
		es.subscribe(appendListener);

		List<Event<MockDomainEvent>> events = es.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("1"), Tags.none())));
		assertEquals(1, events.size());

		waitBecauseOfEventualConsistency(()->appendListener.count()>=1);

		assertEquals(1, appendListener.count()); // we expect one notification on our appendlistener
		assertEquals(events.getLast().reference(), appendListener.lastReference());

		EventId eventId = events.getFirst().reference().id();

		// check we can find it via getEvent on the same stream
		List<Event<MockDomainEvent>> retrieved = es.getEventById(eventId);
		assertFalse(retrieved.isEmpty());
		assertEquals(eventId, retrieved.getFirst().reference().id());
		// or from a query on the same
		assertTrue(es.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

		// check we can find it via getEvent on a generic stream
		EventStreamId generic = EventStreamId.anyContext().anyPurpose();
		EventStream<MockDomainEvent> genericStream = eventStore().getEventStream(generic, MockDomainEvent.class);
		retrieved = genericStream.getEventById(eventId);
		assertFalse(retrieved.isEmpty());
		assertEquals(eventId, retrieved.getFirst().reference().id());
		// or from a query on the same
		assertTrue(genericStream.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

		// check we can't get it via another stream
		EventStreamId other = EventStreamId.forContext("test2").withPurpose("test2");
		EventStream<MockDomainEvent> otherStream = eventStore().getEventStream(other, MockDomainEvent.class);
		List<Event<MockDomainEvent>> notRetrieved = otherStream.getEventById(eventId);
		assertTrue(notRetrieved.isEmpty());
		// and neither from a query on the same
		assertFalse(otherStream.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

	}

	@ForEachBackend
	void testAppendWithIdempotency ( ) {

		MockEventuallyConsistentAppendListener appendListener = new MockEventuallyConsistentAppendListener();
		es.subscribe(appendListener);

		List<Event<MockDomainEvent>> events = es.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("some-idempotency-key")));
		assertEquals(1, events.size());

		waitBecauseOfEventualConsistency(()->appendListener.count()>=1);

		assertEquals(1, appendListener.count()); // we expect one notification on our appendlistener
		assertEquals(events.getLast().reference(), appendListener.lastReference());

		events = es.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("some-idempotency-key")));
		assertEquals(0, events.size());

		waitBecauseOfEventualConsistency(()->appendListener.count()>=1);

		assertEquals(1, appendListener.count()); // we expect one notification on our appendlistener
	}

	@ForEachBackend
	void testAppendMultiple ( ) {

		MockEventuallyConsistentAppendListener appendListener = new MockEventuallyConsistentAppendListener();
		es.subscribe(appendListener);

		EphemeralEvent<MockDomainEvent> e1 = Event.of(new FirstDomainEvent("1"), Tags.none());
		EphemeralEvent<MockDomainEvent> e2 = Event.of(new SecondDomainEvent("2"), Tags.none());

		List<Event<MockDomainEvent>> events = es.append(AppendCriteria.none(), List.of(e1, e2));
		assertEquals(2, events.size());

		BooleanSupplier listenerReceivedLastEvent = () -> events.getLast().reference().equals(appendListener.lastReference());
		waitBecauseOfEventualConsistency(listenerReceivedLastEvent);

		// could be one or two events, depending whether the second gets processed before the first was offered to the appendListener or not.
		assertEquals(events.getLast().reference(), appendListener.lastReference());

		EventId eventId = events.getFirst().reference().id();

		// check we can find it via getEvent on the same stream
		List<Event<MockDomainEvent>> retrieved = es.getEventById(eventId);
		assertFalse(retrieved.isEmpty());
		assertEquals(eventId, retrieved.getFirst().reference().id());
		// or from a query on the same
		assertTrue(es.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

		// check we can find it via getEvent on a generic stream
		EventStreamId generic = EventStreamId.anyContext().anyPurpose();
		EventStream<MockDomainEvent> genericStream = eventStore().getEventStream(generic, MockDomainEvent.class);
		retrieved = genericStream.getEventById(eventId);
		assertFalse(retrieved.isEmpty());
		assertEquals(eventId, retrieved.getFirst().reference().id());
		// or from a query on the same
		assertTrue(genericStream.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

		// check we can't get it via another stream
		EventStreamId other = EventStreamId.forContext("test2").withPurpose("test2");
		EventStream<MockDomainEvent> otherStream = eventStore().getEventStream(other, MockDomainEvent.class);
		List<Event<MockDomainEvent>> notRetrieved = otherStream.getEventById(eventId);
		assertTrue(notRetrieved.isEmpty());
		// and neither from a query on the same
		assertFalse(otherStream.query(EventQuery.matchAll()).map(e->e.reference().id()).filter(id->id.equals(eventId)).findAny().isPresent());

	}

	@ForEachBackend
	void testAppendMultipleWithIdempotency ( ) {

		// if at least one of the events carries an idempotency key, this is not possible
		EphemeralEvent<MockDomainEvent> e1 = Event.<MockDomainEvent>of(new FirstDomainEvent("1"), Tags.none());
		EphemeralEvent<MockDomainEvent> e2 = Event.<MockDomainEvent>of(new SecondDomainEvent("2"), Tags.none()).withIdempotencyKey("idempotency-key");

		IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, ()->
			es.append(AppendCriteria.none(), List.of(e1, e2))
		);
		assertEquals("cannot append multiple events in combination with an idempotency key", iae.getMessage());
	}

	@ForEachBackend
	void testIdempotencyIsScopedPerStream ( ) {

		// The same idempotency key used on two *different* streams must NOT collide: dedup is
		// scoped to the logical stream (context + purpose), not the storage instance, so the
		// mechanism does not leak across streams/stores that happen to share a storage.
		EventStreamId otherStreamId = EventStreamId.forContext("app-other").withPurpose("default");
		EventStream<MockDomainEvent> otherStream = eventStore().getEventStream(otherStreamId, MockDomainEvent.class);

		List<Event<MockDomainEvent>> first = es.append(AppendCriteria.none(),
			Collections.singletonList(Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("shared-key")));
		assertEquals(1, first.size());

		// same key, different stream -> still appended (no cross-stream leak)
		List<Event<MockDomainEvent>> second = otherStream.append(AppendCriteria.none(),
			Collections.singletonList(Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("shared-key")));
		assertEquals(1, second.size());

		// same key, same stream -> still deduped (silently ignored)
		List<Event<MockDomainEvent>> repeat = es.append(AppendCriteria.none(),
			Collections.singletonList(Event.of(new FirstDomainEvent("3"), Tags.none()).withIdempotencyKey("shared-key")));
		assertEquals(0, repeat.size());
	}

	@ForEachBackend
	void testIdempotencyKeyIsReadableFromStoredEvent ( ) {

		// The idempotency key round-trips onto StoredEvent so the SPI layer can read it back.
		es.append(AppendCriteria.none(),
			Collections.singletonList(Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("rt-key")));
		es.append(AppendCriteria.none(),
			Collections.singletonList(Event.of(new SecondDomainEvent("2"), Tags.none()))); // no key

		List<EventStorage.StoredEvent> stored = eventStorage()
			.query(EventQuery.matchAll(), Optional.of(stream), null, Limit.none())
			.toList();

		assertEquals(2, stored.size());
		assertEquals("rt-key", stored.get(0).idempotencyKey());
		assertNull(stored.get(1).idempotencyKey());

		// also readable via getEventById
		Optional<EventStorage.StoredEvent> byId = eventStorage().getEventById(stored.get(0).reference().id());
		assertTrue(byId.isPresent());
		assertEquals("rt-key", byId.get().idempotencyKey());
	}

	@ForEachBackend
	void testAppendWithConcreteEventClass ( ) {

		// this stream only contains this concrete event type (we use <Object> generic for test purposes only)
		EventStream<Object> specialEs = eventStore().getEventStream(stream, FirstDomainEvent.class);

		// should be ok
		specialEs.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("1"), Tags.none())));

		// should be not be ok
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> specialEs.append(AppendCriteria.none(), Collections.singletonList(Event.of(new SecondDomainEvent("2"), Tags.none()))));
		assertEquals("cannot append event type 'SecondDomainEvent' via this stream", e.getMessage());
	}

	@ForEachBackend
	void testAppendWithConcreteEventClassWithErasableParts ( ) {

		// this stream only contains this concrete event type (we use <Object> generic for test purposes only)
		EventStream<Object> specialEs = eventStore().getEventStream(stream, FourthDomainEventWithErasableParts.class);

		// should be ok
		specialEs.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FourthDomainEventWithErasableParts("1", "someName"), Tags.none())));

	}

	@ForEachBackend
	void testAppendEmptyEventList ( ) {
		List<Event<MockDomainEvent>> events = assertDoesNotThrow(
			() -> es.append(AppendCriteria.none(), Collections.emptyList())
		);
		assertEquals(0, events.size());
		assertEquals(0, es.query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testAppendToNonSpecificStream ( ) {
		var otherStream = eventStore().getEventStream(EventStreamId.anyContext());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,()->otherStream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent("1"), Tags.none()))));
		assertEquals("cannot append to non-specific eventstream ", e.getMessage());
	}

	@ForEachBackend
	void testNotificationsToSlowListener ( ) {

		SlowMockListener l = new SlowMockListener(100);

		es.subscribe(l);

		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));
		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("2"), Tags.none()));
		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("3"), Tags.none()));
		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("4"), Tags.none()));
		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("5"), Tags.none()));

		await()
		    .atMost(Duration.ofMillis(5000))
		    	.with()
		    	.pollInterval(Duration.ofMillis(100))
		    .until(() -> l.lastReference() != null && ( 5 == l.lastReference().position() ));

		assertEquals(5, l.lastReference().position()); // check that the listener has seen the last event
	}

	@ForEachBackend
	void testNotificationsToSlowListenerInTwoPhases ( ) {

		SlowMockListener l = new SlowMockListener(100);

		es.subscribe(l);

		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));
		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("2"), Tags.none()));
		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("3"), Tags.none()));

		await()
	    	.atMost(Duration.ofMillis(5000))
	    		.with()
	    		.pollInterval(Duration.ofMillis(100))
	    	.until(() -> l.lastReference() != null && ( 3 == l.lastReference().position())); // wait until 3 has been seen by listener

		// then append some extra events, this will force extra notification update calls

		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("4"), Tags.none()));
		es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("5"), Tags.none()));

		await()
			.atMost(Duration.ofMillis(5000))
				.with()
		    	.pollInterval(Duration.ofMillis(100))
		    .until(() -> l.lastReference()!=null && (5 == l.lastReference().position()));

		assertEquals(5, l.lastReference().position()); // check that the listener has seen the last event
		assertTrue(l.counter() >= 1); // at least one notification, but count depends on timing
	}

	@ForEachBackend
	void testNotificationsToProactivelyQueryingListener ( ) {
		// when a listener is notified and already queries further events proactively

		SlowMockListener l = new SlowMockListener(100);

		es.subscribe(l);

		// the appending caller tells the listener how far it has already been brought up to date. This is
		// what append's return value is for: the events are typed and carry their references, so reacting
		// to your own write on the appending thread needs no subscription
		for ( String payload : List.of("1", "2", "3", "4", "5") ) {
			List<Event<MockDomainEvent>> appended =
				es.append(AppendCriteria.none(), Event.of(new FirstDomainEvent(payload), Tags.none()));
			if ( appended.getLast().reference().position() <= 4 ) { // assume we won't "query" the last one ...
				l.mockLastQueried(appended.getLast().reference());
			}
		}

		await()
			.atMost(Duration.ofMillis(5000))
				.with()
		    	.pollInterval(Duration.ofMillis(100))
		    .until(() -> (l.lastReference()!=null) && (5 == l.lastReference().position()));

		assertEquals(5, l.lastReference().position()); // check that the listener has seen the last event
	}

}

class SlowMockListener implements EventStreamEventuallyConsistentAppendListener {

	private AtomicInteger counter = new AtomicInteger();
	private AtomicReference<EventReference> lastReference = new AtomicReference<>();
	private EventReference lastQueried;
	private int delayMs;

	public SlowMockListener ( int delayMs ) {
		this.delayMs = delayMs;
	}

	@Override
	public EventReference eventsAppended(EventReference atLeastUntil) {
//		System.out.println("notified by %s until %d".formatted(Thread.currentThread().threadId(), atLeastUntil.position()));
		try {
			Thread.sleep(delayMs);
		} catch (InterruptedException e) {
		}
		if ( lastReference.get() == null || (atLeastUntil.position() > lastReference.get().position()) ) {
			lastReference.set(atLeastUntil);
		}
		counter.incrementAndGet();
		return lastQueried==null?atLeastUntil:lastQueried;
	}

	public void mockLastQueried ( EventReference lastQueried ) {
		this.lastQueried = lastQueried;
		if ( lastReference.get() == null || ( lastQueried.position() > lastReference.get().position() ) ) {
			lastReference.set(lastQueried);
		}
	}

	public int counter ( ) {
		return counter.get();
	}

	public EventReference lastReference ( ) {
		return lastReference.get();
	}
}
