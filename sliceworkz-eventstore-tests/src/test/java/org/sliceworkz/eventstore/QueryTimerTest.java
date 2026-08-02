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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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
 * The timer used to wrap the whole expression that builds the event stream — the storage call plus
 * the lazy {@code peek}/{@code flatMap}/{@code filter} chain hung off it. Everything after the
 * storage call is lazy, so that timed the construction of a pipeline rather than any work, and it
 * reported the fetch only because {@link EventStorage#query} materialises its whole result set
 * before returning. Should a backend ever stream its result set instead, the metric would silently
 * fall to zero for every user of the library, with nothing failing to say so.
 * <p>
 * This test fails on that arrangement: it asks a storage whose {@code query} is slow, and never
 * consumes the returned stream, so a timer covering anything other than the storage call records
 * nothing.
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

			// deliberately NOT consumed: the timer must have recorded the fetch already
			Stream<Event<TestEvent>> unconsumed = stream.query(EventQuery.matchAll());

			Timer timer = meterRegistry.find("sliceworkz.eventstore.query.duration").timer();
			assertTrue(timer != null, "no sliceworkz.eventstore.query.duration timer was registered");
			assertTrue(timer.count() == 1, "expected exactly one recorded query, got " + timer.count());
			assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= STORAGE_QUERY_DELAY_MS,
					"query.duration recorded %.1f ms for a storage query that took at least %d ms — the timer is measuring the construction of the lazy pipeline, not the fetch"
						.formatted(timer.totalTime(TimeUnit.MILLISECONDS), STORAGE_QUERY_DELAY_MS));

			unconsumed.close();
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
