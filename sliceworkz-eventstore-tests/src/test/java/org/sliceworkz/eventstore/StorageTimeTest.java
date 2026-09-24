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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.observability.Observation;
import org.sliceworkz.eventstore.observability.Outcome;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.RecordingObserver;


/**
 * Pins what the {@code storageTime} of a read's {@link Outcome.Read} measures: the storage fetch, and
 * nothing the store does around it.
 * <p>
 * The observation of a read spans the whole call — fetching, deserializing, upcasting, unsealing — so
 * a trace shows what the caller waited for. Its outcome carries the storage's share separately, so an
 * observer can still tell a store that is slow from a mapping that is slow; the alternative — timing
 * only the storage call, as the whole observation — loses because the rest of the wait, which on an
 * ordinary page is most of it, is then attributed to nothing.
 * <p>
 * This test asks a storage whose {@code query} is slow and checks that the storage time saw the whole
 * of that delay: a stopwatch that wrapped anything but the storage call would record less.
 */
class StorageTimeTest {

	private static final long STORAGE_QUERY_DELAY_MS = 250;

	sealed interface TestEvent {
		record Ping ( String id ) implements TestEvent { }
	}

	@Test
	void theStorageTimeOfAReadIsTheStorageFetch ( ) {

		RecordingObserver observer = new RecordingObserver();

		try ( EventStorage storage = slowQuerying(InMemoryEventStorage.newBuilder().build());
			  EventStore eventStore = EventStore.on(storage).observer(observer).build() ) {

			EventStream<TestEvent> stream =
					eventStore.getEventStream(EventStreamId.forContext("timer"), TestEvent.class);

			stream.append(AppendCriteria.none(), Event.of(new TestEvent.Ping("1"), Tags.none()));

			List<Event<TestEvent>> events = stream.query(EventQuery.matchAll());
			assertTrue(events.size() == 1, "expected the one appended event, got " + events.size());

			List<RecordingObserver.Recording> reads = observer.recordings(Observation.Query.class);
			assertTrue(reads.size() == 1, "expected exactly one observed read, got " + reads.size());
			long storageMillis = reads.getFirst().outcome(Outcome.Read.class).storageTime().toMillis();
			assertTrue(storageMillis >= STORAGE_QUERY_DELAY_MS,
					"storageTime was %d ms for a storage query that took at least %d ms — it is not measuring the storage fetch"
						.formatted(storageMillis, STORAGE_QUERY_DELAY_MS));
		}
	}

	/**
	 * Wraps a storage so that {@code query} takes a measurable amount of time, leaving every other
	 * operation untouched. A proxy rather than a hand-written delegate, so that adding a method to the
	 * SPI does not break this test.
	 */
	private static EventStorage slowQuerying ( EventStorage delegate ) {
		InvocationHandler handler = (proxy, method, args) -> {
			if ( "query".equals(method.getName()) ) {
				Thread.sleep(STORAGE_QUERY_DELAY_MS);
			}
			try {
				return method.invoke(delegate, args);
			} catch ( InvocationTargetException e ) {
				throw e.getCause();
			}
		};
		return (EventStorage) Proxy.newProxyInstance(
				EventStorage.class.getClassLoader(), new Class<?>[] { EventStorage.class }, handler);
	}
}
