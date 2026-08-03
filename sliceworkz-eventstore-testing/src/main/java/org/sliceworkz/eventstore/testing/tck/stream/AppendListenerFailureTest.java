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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.FirstDomainEvent;

/**
 * A listener that throws is that listener's problem, and nobody else's.
 * <p>
 * Notifications are dispatched after the events are committed, so a listener can neither undo a write nor
 * be rolled back with one, and letting its throwable escape only damages bystanders — every other
 * subscriber of that notification, which an escaping throwable skips entirely. The store therefore
 * contains each listener's failure, logs it at ERROR, and carries on to the next.
 *
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

	/**
	 * A listener saying it processed nothing is saying it is caught up, not asking to be told again.
	 * <p>
	 * The decorator stops delivering once the listener has reached the target it was notified about, and it
	 * used to learn that only from a non-null return — so a listener returning null left it with nothing to
	 * compare against and nothing to reach, and it re-delivered the same target without pausing. Not an
	 * exotic listener either: {@code Projector.eventsAppended} returns {@code run().lastEventReference()},
	 * which is null whenever the query matched no events, so any subscribed projector whose event type had
	 * not occurred yet burned a core from the first unrelated append to its stream until the first matching
	 * one — nothing thrown, nothing logged.
	 * <p>
	 * The bound is loose on purpose. The distinction being drawn is not a subtle one: before this, a single
	 * append produced deliveries at roughly 700.000 a second for as long as the process ran, so anything in
	 * single figures separates "restrained" from "spinning" without depending on timing.
	 */
	@ForEachBackend
	void testListenerReportingNoProgressIsNotRedeliveredTo ( ) {
		AtomicInteger deliveries = new AtomicInteger();
		List<EventReference> seenByBystander = new CopyOnWriteArrayList<>();

		stream.subscribe((EventStreamEventuallyConsistentAppendListener) atLeastUntil -> {
			deliveries.incrementAndGet();
			return null; // "I processed nothing" -- what Projector returns when its query matched no events
		});
		stream.subscribe((EventStreamEventuallyConsistentAppendListener) atLeastUntil -> {
			seenByBystander.add(atLeastUntil);
			return atLeastUntil;
		});

		EventReference appended =
			stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("1"), Tags.none())).getLast().reference();

		// the bystander is what makes a low delivery count meaningful: it proves the notification round trip
		// ran, so a bounded count is restraint rather than the notification never having arrived
		waitBecauseOfEventualConsistency(() -> seenByBystander.contains(appended));
		waitBecauseOfEventualConsistency(() -> deliveries.get() >= 1);

		int settled = deliveries.get();
		assertTrue(settled <= 10,
			"one append must not be re-delivered to a listener reporting no progress, but it was delivered " + settled + " times");
	}
}
