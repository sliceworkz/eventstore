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
}
