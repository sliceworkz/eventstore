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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.ThirdDomainEvent;

/**
 * The {@code until} boundary of an {@link EventFilter}, in both traversal directions.
 * <p>
 * {@code until} is a matching criterion and lives on the filter, not on the query: it is the inclusive
 * upper bound over the total {@code (tx, position, index)} order that
 * {@link EventFilter#matches(Event)} implements. Direction decides the order results come back in and
 * nothing else, so the same boundary selects the same set of events forward and backward.
 * <p>
 * That is easy to get wrong in a way no forward-only test can see -- reading the boundary as "traverse
 * until you reach it" turns it into a lower bound when going backward, which selects the events on the
 * far side of it. The savepoint pattern ({@code .backwards().limit(1)} for the most recent summary
 * event) walks straight into it, and so does {@code Projector.runUntil}. Hence
 * {@link #untilSelectsTheSameEventsInBothDirections}, which asserts the property itself rather than a
 * handful of counts.
 */
public class EventQueryUntilBoundaryTest extends AbstractEventStoreTest {

	private EventStreamId streamId;
	private EventStream<MockDomainEvent> stream;

	/** Every event on the stream, oldest first, by position: 1..7. */
	private List<Event<MockDomainEvent>> all;

	@BeforeEach
	void seedStream ( ) {
		this.streamId = EventStreamId.forContext("app").withPurpose("until-boundary");
		this.stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		append(new FirstDomainEvent("1"));          // 1
		append(new ThirdDomainEvent("savepoint:a")); // 2
		append(new FirstDomainEvent("2"));          // 3
		append(new FirstDomainEvent("3"));          // 4
		append(new ThirdDomainEvent("savepoint:b")); // 5
		append(new FirstDomainEvent("4"));          // 6
		append(new SecondDomainEvent("head"));      // 7

		this.all = stream.query(EventQuery.matchAll()).toList();
		assertEquals(7, all.size(), "fixture");
	}

	/**
	 * The property the two backends disagreed on: forward and backward are the same selection in
	 * opposite orders, for every boundary and with or without one. A single wrong comparison in a
	 * storage breaks this for at least one boundary, whatever the counts happen to be.
	 */
	@ForEachBackend
	void untilSelectsTheSameEventsInBothDirections ( ) {
		List<EventQuery> shapes = List.of(
				EventQuery.matchAll(),
				EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()),
				EventQuery.forEvents(EventTypesFilter.of(ThirdDomainEvent.class), Tags.none()));

		for ( EventQuery shape : shapes ) {
			for ( Event<MockDomainEvent> boundary : all ) {
				EventQuery bounded = shape.until(boundary.reference());

				List<EventReference> forward = references(stream.query(bounded));
				List<EventReference> backward = references(stream.query(bounded.backwards()));

				List<EventReference> backwardReversed = new ArrayList<>(backward);
				java.util.Collections.reverse(backwardReversed);

				assertEquals(forward, backwardReversed,
						"boundary at position %d selects a different set backwards".formatted(boundary.reference().position()));
				assertTrue(forward.stream().noneMatch(r -> r.happenedAfter(boundary.reference())),
						"an event past the boundary was returned");
			}
		}
	}

	/**
	 * The savepoint pattern from the documentation, bounded: the most recent summary event at or before
	 * a point in time. Returning the newest savepoint in the store regardless of the boundary is as
	 * wrong as returning nothing.
	 */
	@ForEachBackend
	void backwardsWithLimitFindsTheNewestMatchAtOrBeforeTheBoundary ( ) {
		EventQuery savepoint = EventQuery.forEvents(EventTypesFilter.of(ThirdDomainEvent.class), Tags.none())
				.backwards().limit(1);

		assertEquals(List.of("savepoint:b"), values(stream.query(savepoint)));

		// boundary on the head event, which is not itself a savepoint
		assertEquals(List.of("savepoint:b"), values(stream.query(savepoint.until(at(7)))));

		// boundary on the savepoint itself: inclusive
		assertEquals(List.of("savepoint:b"), values(stream.query(savepoint.until(at(5)))));

		// boundary just before it: the previous savepoint, not the newest one
		assertEquals(List.of("savepoint:a"), values(stream.query(savepoint.until(at(4)))));
		assertEquals(List.of("savepoint:a"), values(stream.query(savepoint.until(at(2)))));

		// boundary before any savepoint exists: nothing, and the projection replays from the beginning
		assertEquals(List.of(), values(stream.query(savepoint.until(at(1)))));
	}

	@ForEachBackend
	void backwardsWithoutLimitStopsAtTheBoundary ( ) {
		EventQuery savepoints = EventQuery.forEvents(EventTypesFilter.of(ThirdDomainEvent.class), Tags.none()).backwards();

		assertEquals(List.of("savepoint:b", "savepoint:a"), values(stream.query(savepoints)));
		assertEquals(List.of("savepoint:b", "savepoint:a"), values(stream.query(savepoints.until(at(7)))));
		assertEquals(List.of("savepoint:a"), values(stream.query(savepoints.until(at(4)))));
		assertEquals(List.of(), values(stream.query(savepoints.until(at(1)))));
	}

	@ForEachBackend
	void forwardIsUnaffected ( ) {
		EventQuery firsts = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());

		assertEquals(List.of("1", "2", "3", "4"), values(stream.query(firsts)));
		assertEquals(List.of("1", "2", "3", "4"), values(stream.query(firsts.until(at(7)))));
		assertEquals(List.of("1", "2", "3"), values(stream.query(firsts.until(at(4)))));
		assertEquals(List.of("1"), values(stream.query(firsts.until(at(1)))));
		assertEquals(List.of("1", "2"), values(stream.query(firsts.until(at(4)), null, Limit.to(2))));
	}

	/**
	 * The boundary and the cursor are different things and have to compose: the cursor says where to
	 * start, the boundary says how far the selection reaches.
	 */
	@ForEachBackend
	void untilComposesWithACursor ( ) {
		EventQuery firsts = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());

		// forward: after position 1, up to position 4
		assertEquals(List.of("2", "3"), values(stream.query(firsts.until(at(4)), at(1))));

		// backward: before position 6, up to position 3
		assertEquals(List.of("2", "1"), values(stream.query(firsts.until(at(3)).backwards(), at(6))));
	}

	/**
	 * A boundary on an event of another stream still orders against this stream's events -- it is a
	 * position in the store, not in the stream.
	 */
	@ForEachBackend
	void untilOnAnEventOfAnotherStreamStillBounds ( ) {
		EventStream<MockDomainEvent> other = eventStore()
				.getEventStream(EventStreamId.forContext("otherApp").withPurpose("until-boundary"), MockDomainEvent.class);
		EventReference elsewhere = other
				.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("elsewhere"), Tags.none()))
				.get(0).reference();

		append(new FirstDomainEvent("5"));

		EventQuery firsts = EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());

		// "5" was appended after the other stream's event, so the boundary excludes it, in both directions
		assertEquals(List.of("1", "2", "3", "4"), values(stream.query(firsts.until(elsewhere))));
		assertEquals(List.of("4", "3", "2", "1"), values(stream.query(firsts.until(elsewhere).backwards())));
	}

	/**
	 * The boundary is part of the filter, so it is part of the consistency boundary too: an event past
	 * it is not a new relevant fact and must not raise a conflict. Backends disagreed here as well --
	 * one ran the criteria through its query path and honoured the boundary, the other built SQL that
	 * left it out.
	 */
	@ForEachBackend
	void appendCriteriaHonoursItsUntilBoundary ( ) {
		EventFilter firsts = EventFilter.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());

		// unbounded: positions 3, 4 and 6 are new relevant facts after position 1
		assertThrows(OptimisticLockingException.class,
				() -> stream.append(AppendCriteria.of(firsts, at(1)), Event.of(new FirstDomainEvent("x"), Tags.none())));

		// bounded at position 4: positions 3 and 4 are still inside the boundary, so still a conflict
		assertThrows(OptimisticLockingException.class,
				() -> stream.append(AppendCriteria.of(firsts.until(at(4)), at(1)), Event.of(new FirstDomainEvent("x"), Tags.none())));

		// bounded at position 1: nothing matching the filter lies after the reference and inside the
		// boundary, so the append goes through
		List<Event<MockDomainEvent>> appended = stream.append(
				AppendCriteria.of(firsts.until(at(1)), at(1)),
				Event.of(new FirstDomainEvent("x"), Tags.none()));
		assertEquals(1, appended.size());
	}

	private void append ( MockDomainEvent event ) {
		stream.append(AppendCriteria.none(), Event.of(event, Tags.none()));
	}

	/** The reference of the seeded event at the given position (1-based, as positions are). */
	private EventReference at ( int position ) {
		return all.get(position - 1).reference();
	}

	private static List<String> values ( java.util.stream.Stream<Event<MockDomainEvent>> events ) {
		return events.map(e -> switch ( e.data() ) {
			case FirstDomainEvent f -> f.value();
			case SecondDomainEvent s -> s.value();
			case ThirdDomainEvent t -> t.value();
		}).toList();
	}

	private static List<EventReference> references ( java.util.stream.Stream<Event<MockDomainEvent>> events ) {
		return events.map(Event::reference).toList();
	}

}
