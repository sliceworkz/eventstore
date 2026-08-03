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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.FirstDomainEvent;

/**
 * A listener that throws is that listener's problem, and nobody else's.
 * <p>
 * Both notification paths run after the events are committed — the consistent one inline on the appending
 * thread, the eventually consistent one on a notification thread — so neither can undo a write, and letting
 * either fail its caller only damages a bystander. On the append thread the bystander is the appending
 * caller, told its committed write failed and handed no events, which is an invitation to append them
 * twice. On the notification thread it is every other subscriber of that notification. Both paths therefore
 * contain one listener's failure, log it, and carry on.
 *
 * @see EventStreamConsistentAppendListener
 * @see EventStreamEventuallyConsistentAppendListener
 */
public class AppendListenerFailureTest extends AbstractEventStoreTest {

	private EventStreamId streamId;
	private EventStream<MockDomainEvent> stream;

	@BeforeEach
	void openStream ( ) {
		streamId = EventStreamId.forContext("listenerfailure").withPurpose("default");
		stream = eventStore().getEventStream(streamId, MockDomainEvent.class);
	}

	/**
	 * The events are committed before the listener runs, so the append has already succeeded by then and
	 * says so. Reporting it as failed would describe a write that happened as one that did not, and the
	 * caller would not even receive the events it wrote — a retry loop reacting to that duplicates them.
	 */
	@ForEachBackend
	void testThrowingConsistentListenerDoesNotFailTheAppend ( ) {
		stream.subscribe((EventStreamConsistentAppendListener<MockDomainEvent>) events -> {
			throw new IllegalStateException("listener is broken");
		});

		List<Event<MockDomainEvent>> appended =
			stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));

		// the caller is told what it wrote, exactly as if nothing had been subscribed
		assertEquals(1, appended.size());

		// ... and what it wrote is in the stream, which is the whole reason the exception must not surface:
		// an append that reports failure while leaving the event behind cannot be retried safely
		assertEquals(1, stream.query(EventQuery.matchAll()).count());
	}

	/**
	 * One broken subscriber must not deprive the others of the notification. They are notified in
	 * subscription order, and a failure costs exactly one delivery to one listener.
	 */
	@ForEachBackend
	void testThrowingConsistentListenerDoesNotStarveTheOthers ( ) {
		List<String> notified = new ArrayList<>();

		stream.subscribe((EventStreamConsistentAppendListener<MockDomainEvent>) events -> notified.add("first"));
		stream.subscribe((EventStreamConsistentAppendListener<MockDomainEvent>) events -> {
			notified.add("throwing");
			throw new IllegalStateException("listener is broken");
		});
		stream.subscribe((EventStreamConsistentAppendListener<MockDomainEvent>) events -> notified.add("last"));

		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));

		assertEquals(List.of("first", "throwing", "last"), notified);
	}

	/**
	 * A consistent subscriber that fails on one append is still notified about the next: the failure is
	 * contained, not a deregistration.
	 */
	@ForEachBackend
	void testConsistentListenerKeepsBeingNotifiedAfterItThrows ( ) {
		AtomicInteger notifications = new AtomicInteger();

		stream.subscribe((EventStreamConsistentAppendListener<MockDomainEvent>) events -> {
			notifications.incrementAndGet();
			throw new IllegalStateException("listener is broken every single time");
		});

		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("2"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("3"), Tags.none()));

		assertEquals(3, notifications.get());
		assertEquals(3, stream.query(EventQuery.matchAll()).count());
	}

	/**
	 * The same rule one thread over. An eventually consistent listener throwing used to end the whole
	 * notification task, so every subscriber after it in the list missed that append too, and the throwable
	 * reached the notification thread's uncaught-exception handler — {@code System.err}, at no level, under
	 * no logger name. A {@code Projector} subscribed here is the ordinary case, and a projection that throws
	 * is how a read model stops advancing with nothing in the logs to say why.
	 */
	@ForEachBackend
	void testThrowingEventuallyConsistentListenerDoesNotStarveTheOthers ( ) {
		List<EventReference> seenByBystander = new CopyOnWriteArrayList<>();

		// subscribed first, so it is notified first and the bystander only ever hears about an append the
		// throwing listener has already failed on
		stream.subscribe((EventStreamEventuallyConsistentAppendListener) atLeastUntil -> {
			throw new IllegalStateException("listener is broken");
		});
		stream.subscribe((EventStreamEventuallyConsistentAppendListener) atLeastUntil -> {
			seenByBystander.add(atLeastUntil);
			return atLeastUntil;
		});

		EventReference firstAppend =
			stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none())).getLast().reference();

		waitBecauseOfEventualConsistency(() -> seenByBystander.contains(firstAppend));

		// and notifications keep coming after the failure, for both of them
		EventReference secondAppend =
			stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("2"), Tags.none())).getLast().reference();

		waitBecauseOfEventualConsistency(() -> seenByBystander.contains(secondAppend));

		assertTrue(seenByBystander.size() >= 2, "expected notifications for both appends, saw " + seenByBystander);
	}
}
