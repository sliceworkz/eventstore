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
package org.sliceworkz.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;

public class EventStorePerformanceTest extends AbstractEventStoreTest {

	private static final String UNITTEST_BOUNDEDCONTEXT = "pertest";

	// Not on inmem-fs: 10.000 appends there means 10.000 file writes, which dominates CI time for a
	// throughput number nobody reads. The correctness of those appends is covered by the TCK.
	@ForEachBackend(excludingBackends = "inmem-fs")
	void testAppendPerformance ( ) {
		EventStream<MockDomainEvent> eventStream = createEventStream();

		List<? extends Event<? extends MockDomainEvent>> result = null;

		System.out.println("starting");

		Instant start = Instant.now();

		int COUNT = 10_000;

		for ( int i = 0; i < COUNT; i++ ) {
			// append with no criteria (should always succeed)
			EphemeralEvent<FirstDomainEvent> event = Event.of(new FirstDomainEvent("test1"), Tags.none());
			result = eventStream.append(AppendCriteria.none(), Collections.singletonList(event));
		}

		Instant stop = Instant.now();
		long ms = stop.toEpochMilli() - start.toEpochMilli();
		double eventsPerSecond = (COUNT*1000/(double)ms);

		assertEquals(COUNT, result.iterator().next().reference().position());

		System.out.println("ms                : " + ms);
		System.out.println("appended event/sec: " + eventsPerSecond);
	}

	private EventStream<MockDomainEvent> createEventStream() {
		return EventStoreFactory.get().eventStore(eventStorage()).getEventStream(EventStreamId.forContext(UNITTEST_BOUNDEDCONTEXT), MockDomainEvent.class);
	}

}
