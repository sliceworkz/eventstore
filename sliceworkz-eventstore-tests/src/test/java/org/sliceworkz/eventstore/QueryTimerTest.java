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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Pins what {@code sliceworkz.eventstore.query.duration} measures: the storage fetch, recorded when
 * the query is issued.
 * <p>
 * The timer covers the {@link EventStorage#query} call and nothing else. Deserialising and upcasting
 * the result happen inside the same {@code query} call, since it returns a list, but they are the
 * cost of the stream's mappings rather than of the store, and are counted separately per event type
 * by {@code sliceworkz.eventstore.query.event}. The alternative — timing the whole of the stream's
 * {@code query}, fetch and conversion together — loses because a store that is slow and a mapping
 * that is slow then read identically on the dashboard.
 * <p>
 * This test asks a storage whose {@code query} is slow and checks that the timer saw the whole of
 * that delay: a timer that wrapped anything but the storage call would record less.
 */
class QueryTimerTest {

	private static final long STORAGE_QUERY_DELAY_MS = 250;

	sealed interface TestEvent {
		record Ping ( String id ) implements TestEvent { }
	}

	@Test
	void queryTimerMeasuresTheStorageFetchAndNotThePipelineConstruction ( ) {

		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

		try ( EventStorage storage = slowQuerying(InMemoryEventStorage.newBuilder().build()) ) {

			EventStore eventStore = EventStoreFactory.get().eventStore(storage, meterRegistry);
			EventStream<TestEvent> stream =
					eventStore.getEventStream(EventStreamId.forContext("timer"), TestEvent.class);

			stream.append(AppendCriteria.none(), Event.of(new TestEvent.Ping("1"), Tags.none()));

			List<Event<TestEvent>> events = stream.query(EventQuery.matchAll());
			assertTrue(events.size() == 1, "expected the one appended event, got " + events.size());

			Timer timer = meterRegistry.find("sliceworkz.eventstore.query.duration").timer();
			assertTrue(timer != null, "no sliceworkz.eventstore.query.duration timer was registered");
			assertTrue(timer.count() == 1, "expected exactly one recorded query, got " + timer.count());
			assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= STORAGE_QUERY_DELAY_MS,
					"query.duration recorded %.1f ms for a storage query that took at least %d ms — the timer is not measuring the storage fetch"
						.formatted(timer.totalTime(TimeUnit.MILLISECONDS), STORAGE_QUERY_DELAY_MS));
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
