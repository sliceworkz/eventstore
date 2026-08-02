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
package org.sliceworkz.eventstore.testing.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;

/**
 * An append notifies once per stream it touched, not once per event it wrote.
 * <p>
 * The consumer of {@link EventStorage.AppendsToEventStoreNotification} reads only {@code atLeastUntil},
 * and the impl module's optimizing decorator collapses a burst to the newest reference before the
 * delegate ever sees it. So a backend emitting one notification per row does work that is discarded by
 * construction: on PostgreSQL each surplus notification was built as JSON, written to the cluster-wide
 * async queue, pushed over the wire, parsed by Jackson and fanned out to every listener, only to lose a
 * comparison. A 1000-event append cost 1000 of those to convey one fact.
 * <p>
 * Two properties are asserted, and the second is the one that is easy to get wrong while fixing the
 * first. A backend aggregating a batch down to one notification has to report the maximum over the
 * total {@code (tx, position)} order — the order {@link EventReference#happenedAfter} defines and reads
 * are sorted by — and not the maximum position. The two genuinely disagree when position and
 * transaction are assigned independently, and a notification naming a reference the reader has already
 * passed is a notification the optimizing decorator drops, which strands the subscription silently.
 * <p>
 * The notification must also name a <em>concrete</em> stream, because
 * {@link EventStorage.AppendsToEventStoreNotification#isRelevantFor} matches through
 * {@link EventStreamId#canRead}: a wildcard or null-valued stream is rejected by every concrete
 * subscriber, so live updates stop with nothing thrown and nothing logged.
 */
public class EventAppendNotificationGranularityTest extends AbstractEventStoreTest {

	private static final int BATCH = 50;

	private final List<EventStorage.AppendsToEventStoreNotification> received = new CopyOnWriteArrayList<>();

	private void recordNotifications ( ) {
		eventStorage().subscribe(new EventStorage.EventStoreListener() {
			@Override
			public void notify ( EventStorage.AppendsToEventStoreNotification newEventsInStore ) {
				received.add(newEventsInStore);
			}

			@Override
			public void notify ( EventStorage.BookmarkPlacedNotification bookmarkPlaced ) {
				// not what this scenario asserts
			}
		});
	}

	private List<EphemeralEvent<? extends MockDomainEvent>> batchOf ( int count ) {
		List<EphemeralEvent<? extends MockDomainEvent>> events = new ArrayList<>(count);
		for ( int i = 0; i < count; i++ ) {
			events.add(Event.of(new FirstDomainEvent("event " + i), Tags.none()));
		}
		return events;
	}

	@ForEachBackend
	void aBatchAppendNotifiesOncePerStreamNotOncePerEvent ( ) {

		EventStreamId streamId = EventStreamId.forContext("notification").withPurpose("granularity");
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		recordNotifications();

		List<Event<MockDomainEvent>> appended = stream.append(AppendCriteria.none(), batchOf(BATCH));
		assertEquals(BATCH, appended.size(), "the whole batch should have been written");

		EventReference lastAppended = appended.getLast().reference();

		// wait for the notification to carry the batch's last event, so a backend delivering
		// asynchronously is not judged before it has delivered anything
		waitBecauseOfEventualConsistency(( ) -> received.stream()
				.anyMatch(n -> !lastAppended.happenedAfter(n.atLeastUntil())));

		// the whole point: one append touching one stream is one notification, whatever the batch size.
		// A per-row backend has delivered BATCH of them by now.
		assertEquals(1, received.size(),
				"one append to one stream must notify once, not once per event — got " + received.size()
				+ " notifications for a " + BATCH + "-event append");
	}

	@ForEachBackend
	void theNotificationNamesTheConcreteStreamAndTheBatchesLastEvent ( ) {

		EventStreamId streamId = EventStreamId.forContext("notification").withPurpose("reference");
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		recordNotifications();

		List<Event<MockDomainEvent>> appended = stream.append(AppendCriteria.none(), batchOf(BATCH));
		EventReference lastAppended = appended.getLast().reference();

		waitBecauseOfEventualConsistency(( ) -> !received.isEmpty());

		EventStorage.AppendsToEventStoreNotification notification = received.getFirst();

		// a concrete stream, so a concrete subscriber's canRead matches it
		assertEquals(streamId, notification.stream(), "the notification must name the stream that was appended to");
		assertTrue(notification.isRelevantFor(streamId), "a subscriber to this very stream must find it relevant");
		assertTrue(notification.isRelevantFor(EventStreamId.anyContext()), "a wildcard subscriber must find it relevant too");

		// atLeastUntil reaches the last event of the batch over the (tx, position) order. Reporting an
		// earlier one — for instance by aggregating on position alone, or by reporting the first event of
		// the batch — leaves the reader believing it is already caught up.
		assertTrue(!lastAppended.happenedAfter(notification.atLeastUntil()),
				"atLeastUntil (" + notification.atLeastUntil() + ") must reach the batch's last event (" + lastAppended + ")");
	}
}
