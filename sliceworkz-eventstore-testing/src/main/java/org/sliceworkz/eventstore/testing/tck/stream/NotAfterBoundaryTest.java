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

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.ThirdDomainEvent;

/**
 * The read bound of an {@link EventQuery}, {@link EventQuery#notAfter(EventReference)}, in both
 * traversal directions.
 * <p>
 * The read bound is a matching criterion, not a traversal one: it is the inclusive upper bound over
 * stored events in the {@code (tx, position)} order, and the storage SPI is handed it as a parameter of
 * its own. Direction decides the order results come back in and nothing else, so the same bound selects
 * the same set of events forward and backward.
 * <p>
 * That is easy to get wrong in a way no forward-only test can see -- reading the bound as "traverse
 * until you reach it" turns it into a lower bound when going backward, which selects the events on the
 * far side of it. The savepoint pattern ({@code .backwards().limit(1)} for the most recent summary
 * event) walks straight into it, and so does {@code Projector.runUntil}. Hence
 * {@link #notAfterSelectsTheSameEventsInBothDirections}, which asserts the property itself rather than a
 * handful of counts. The bounds of a filter's items are held to the same rules by {@code ItemBoundsTest}.
 */
public class NotAfterBoundaryTest extends AbstractEventStoreTest {

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

		this.all = stream.query(EventFilter.matchAll());
		assertEquals(7, all.size(), "fixture");
	}

	/**
	 * The property the two backends disagreed on: forward and backward are the same selection in
	 * opposite orders, for every boundary and with or without one. A single wrong comparison in a
	 * storage breaks this for at least one boundary, whatever the counts happen to be.
	 */
	@ForEachBackend
	void notAfterSelectsTheSameEventsInBothDirections ( ) {
		List<EventQuery> shapes = List.of(
				EventQuery.of(EventFilter.matchAll()),
				EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class)),
				EventQuery.of(EventFilter.forTypes(ThirdDomainEvent.class)));

		for ( EventQuery shape : shapes ) {
			for ( Event<MockDomainEvent> boundary : all ) {
				EventQuery bounded = shape.notAfter(boundary.reference());

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
		EventQuery savepoint = EventQuery.of(EventFilter.forTypes(ThirdDomainEvent.class))
				.backwards().limit(1);

		assertEquals(List.of("savepoint:b"), values(stream.query(savepoint)));

		// boundary on the head event, which is not itself a savepoint
		assertEquals(List.of("savepoint:b"), values(stream.query(savepoint.notAfter(at(7)))));

		// boundary on the savepoint itself: inclusive
		assertEquals(List.of("savepoint:b"), values(stream.query(savepoint.notAfter(at(5)))));

		// boundary just before it: the previous savepoint, not the newest one
		assertEquals(List.of("savepoint:a"), values(stream.query(savepoint.notAfter(at(4)))));
		assertEquals(List.of("savepoint:a"), values(stream.query(savepoint.notAfter(at(2)))));

		// boundary before any savepoint exists: nothing, and the projection replays from the beginning
		assertEquals(List.of(), values(stream.query(savepoint.notAfter(at(1)))));
	}

	@ForEachBackend
	void backwardsWithoutLimitStopsAtTheBoundary ( ) {
		EventQuery savepoints = EventQuery.of(EventFilter.forTypes(ThirdDomainEvent.class)).backwards();

		assertEquals(List.of("savepoint:b", "savepoint:a"), values(stream.query(savepoints)));
		assertEquals(List.of("savepoint:b", "savepoint:a"), values(stream.query(savepoints.notAfter(at(7)))));
		assertEquals(List.of("savepoint:a"), values(stream.query(savepoints.notAfter(at(4)))));
		assertEquals(List.of(), values(stream.query(savepoints.notAfter(at(1)))));
	}

	@ForEachBackend
	void forwardIsUnaffected ( ) {
		EventQuery firsts = EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class));

		assertEquals(List.of("1", "2", "3", "4"), values(stream.query(firsts)));
		assertEquals(List.of("1", "2", "3", "4"), values(stream.query(firsts.notAfter(at(7)))));
		assertEquals(List.of("1", "2", "3"), values(stream.query(firsts.notAfter(at(4)))));
		assertEquals(List.of("1"), values(stream.query(firsts.notAfter(at(1)))));
		assertEquals(List.of("1", "2"), values(stream.query(firsts.notAfter(at(4)).limit(2), null)));
	}

	/**
	 * The boundary and the cursor are different things and have to compose: the cursor says where to
	 * start, the boundary says how far the selection reaches.
	 */
	@ForEachBackend
	void notAfterComposesWithACursor ( ) {
		EventQuery firsts = EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class));

		// forward: after position 1, up to position 4
		assertEquals(List.of("2", "3"), values(stream.query(firsts.notAfter(at(4)), at(1))));

		// backward: before position 6, up to position 3
		assertEquals(List.of("2", "1"), values(stream.query(firsts.notAfter(at(3)).backwards(), at(6))));
	}

	/**
	 * A boundary on an event of another stream still orders against this stream's events -- it is a
	 * position in the store, not in the stream.
	 */
	@ForEachBackend
	void notAfterOnAnEventOfAnotherStreamStillBounds ( ) {
		EventStream<MockDomainEvent> other = eventStore()
				.getEventStream(EventStreamId.forContext("otherApp").withPurpose("until-boundary"), MockDomainEvent.class);
		EventReference elsewhere = other
				.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("elsewhere"), Tags.none()))
				.get(0).reference();

		append(new FirstDomainEvent("5"));

		EventQuery firsts = EventQuery.of(EventFilter.forTypes(FirstDomainEvent.class));

		// "5" was appended after the other stream's event, so the boundary excludes it, in both directions
		assertEquals(List.of("1", "2", "3", "4"), values(stream.query(firsts.notAfter(elsewhere))));
		assertEquals(List.of("4", "3", "2", "1"), values(stream.query(firsts.notAfter(elsewhere).backwards())));
	}

	private void append ( MockDomainEvent event ) {
		stream.append(AppendCriteria.none(), Event.of(event, Tags.none()));
	}

	/** The reference of the seeded event at the given position (1-based, as positions are). */
	private EventReference at ( int position ) {
		return all.get(position - 1).reference();
	}

	private static List<String> values ( List<Event<MockDomainEvent>> events ) {
		return events.stream().map(e -> switch ( e.data() ) {
			case FirstDomainEvent f -> f.value();
			case SecondDomainEvent s -> s.value();
			case ThirdDomainEvent t -> t.value();
		}).toList();
	}

	private static List<EventReference> references ( List<Event<MockDomainEvent>> events ) {
		return events.stream().map(Event::reference).toList();
	}

}
