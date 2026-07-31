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
package org.sliceworkz.eventstore.testing.fixture;

import java.util.List;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A one-line event store for testing application code, and the entry point to the
 * {@code given / when / then} fixture.
 * <p>
 * Aimed at the shape every DCB application has: query the events relevant to a decision, decide,
 * append conditionally. That shape is awkward to test by hand — seeding takes a loop of
 * {@code append(AppendCriteria.none(), Event.of(...))}, assertions have to pick {@code data()} out
 * of events whose reference and timestamp are unpredictable, and provoking an
 * {@code OptimisticLockingException} deterministically needs an append to land in the window between
 * the decider's query and its own append.
 * <pre>{@code
 * class SubscribeStudentToCourseTest {
 *
 *     EventStoreFixture<LearningEvent> fixture =
 *         EventStoreFixture.inMemory(EventStreamId.forContext("learning"), LearningEvent.class);
 *
 *     @Test
 *     void studentCannotSubscribeTwice ( ) {
 *         fixture.given(
 *                   event(new CourseDefined("Java basics", 12)).tagged("course", "abc001"),
 *                   event(new StudentSubscribed("123", "abc001"))
 *                       .tagged("student", "123").tagged("course", "abc001"))
 *                .when(stream -> new Registrations(stream).subscribe("123", "abc001"))
 *                .expectResult(false)
 *                .expectNoEventsAppended();
 *     }
 * }
 * }</pre>
 * A fixture is single-use per test: build a new one per test method (a field initialiser is enough,
 * JUnit creates a fresh instance per test) so history never leaks between them.
 *
 * @param <DOMAIN_EVENT_TYPE> the stream's domain event type, normally a sealed interface
 */
public final class EventStoreFixture<DOMAIN_EVENT_TYPE> {

	private final EventStorage eventStorage;
	private final EventStore eventStore;
	private final EventStream<DOMAIN_EVENT_TYPE> stream;

	private EventStoreFixture ( EventStorage eventStorage, EventStreamId streamId, Class<DOMAIN_EVENT_TYPE> eventRootClass ) {
		this.eventStorage = eventStorage;
		this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
		this.stream = eventStore.getEventStream(streamId, eventRootClass);
	}

	/**
	 * A fixture over a fresh in-memory store. No database, no container, no cleanup.
	 *
	 * @param <E>            the stream's domain event type
	 * @param streamId       the stream under test
	 * @param eventRootClass the root of the domain event hierarchy
	 * @return the fixture
	 */
	public static <E> EventStoreFixture<E> inMemory ( EventStreamId streamId, Class<E> eventRootClass ) {
		return new EventStoreFixture<>(InMemoryEventStorage.newBuilder().name("fixture").build(), streamId, eventRootClass);
	}

	/**
	 * A fixture over a fresh in-memory store, on a stream in context {@code "test"}.
	 *
	 * @param <E>            the stream's domain event type
	 * @param eventRootClass the root of the domain event hierarchy
	 * @return the fixture
	 */
	public static <E> EventStoreFixture<E> inMemory ( Class<E> eventRootClass ) {
		return inMemory(EventStreamId.forContext("test"), eventRootClass);
	}

	/**
	 * A fixture over a storage you provide — a Postgres store, or one from an
	 * {@link org.sliceworkz.eventstore.testing.EventStoreBackend}. The fixture does not own it and
	 * will not close it.
	 *
	 * @param <E>            the stream's domain event type
	 * @param eventStorage   the storage to use
	 * @param streamId       the stream under test
	 * @param eventRootClass the root of the domain event hierarchy
	 * @return the fixture
	 */
	public static <E> EventStoreFixture<E> over ( EventStorage eventStorage, EventStreamId streamId, Class<E> eventRootClass ) {
		return new EventStoreFixture<>(eventStorage, streamId, eventRootClass);
	}

	/**
	 * Seeds history, then hands over to {@code when(...)} or {@code project(...)}. The events are
	 * appended unconditionally, in order.
	 *
	 * @param history the events that already happened
	 * @return the seeded fixture
	 */
	public Given<DOMAIN_EVENT_TYPE> given ( ExpectedEvent... history ) {
		return given(List.of(history));
	}

	/**
	 * Seeds history, then hands over to {@code when(...)} or {@code project(...)}.
	 *
	 * @param history the events that already happened
	 * @return the seeded fixture
	 */
	public Given<DOMAIN_EVENT_TYPE> given ( List<ExpectedEvent> history ) {
		return new Given<>(this).and(history);
	}

	/**
	 * Starts from an empty store. Same as {@code given()}, but says so.
	 *
	 * @return the empty fixture
	 */
	public Given<DOMAIN_EVENT_TYPE> givenNoHistory ( ) {
		return new Given<>(this);
	}

	/**
	 * @return the stream under test
	 */
	public EventStream<DOMAIN_EVENT_TYPE> stream ( ) {
		return stream;
	}

	/**
	 * @return the event store behind the fixture
	 */
	public EventStore eventStore ( ) {
		return eventStore;
	}

	/**
	 * @return the storage behind the fixture
	 */
	public EventStorage eventStorage ( ) {
		return eventStorage;
	}

}
