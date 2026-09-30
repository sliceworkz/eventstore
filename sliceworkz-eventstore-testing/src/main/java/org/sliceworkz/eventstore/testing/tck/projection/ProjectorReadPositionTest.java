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
package org.sliceworkz.eventstore.testing.tck.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.SecondDomainEvent;

/**
 * A bookmarked projector records two positions: the last event it handled, where it resumes, and the
 * event up to which it has read the stream, which is what its lag is counted from. The projections here
 * read {@link FirstDomainEvent}s only, and the {@link SecondDomainEvent}s between and after them are the
 * events a reader is not interested in and must not look behind for good.
 */
public class ProjectorReadPositionTest extends AbstractEventStoreTest {

	private static final String READER = "read-position-reader";

	private EventStream<MockDomainEvent> es;

	@BeforeEach
	void openStream ( ) {
		es = eventStore().getEventStream(EventStreamId.forContext("app").withPurpose("default"), MockDomainEvent.class);
	}

	@ForEachBackend
	void aRunPastIrrelevantEventsEndsWithTheReadPositionAtTheHead ( ) {
		EventReference handled = append(new FirstDomainEvent("1"));
		append(new SecondDomainEvent("2"));
		EventReference head = append(new SecondDomainEvent("3"));

		projector(new FirstEvents(), Duration.ZERO).run();

		Bookmark bookmark = bookmark();
		assertEquals(handled, bookmark.reference(), "the resume point is the last event handled");
		assertEquals(Optional.of(head), bookmark.readUpTo(), "a run that read to the end has read up to the head");
	}

	@ForEachBackend
	void aRunThatHandledNothingMovesTheReadPositionOnly ( ) {
		EventReference handled = append(new FirstDomainEvent("1"));
		Projector<MockDomainEvent> projector = projector(new FirstEvents(), Duration.ZERO);
		projector.run();

		EventReference head = append(new SecondDomainEvent("2"));
		assertEquals(0, projector.run().eventsHandled());

		Bookmark bookmark = bookmark();
		assertEquals(handled, bookmark.reference());
		assertEquals(Optional.of(head), bookmark.readUpTo());
	}

	/**
	 * A run that fails part-way has read up to the last batch that landed, and no further: the head it
	 * took at the start is only reached by a run that read to the end.
	 */
	@ForEachBackend
	void aFailureMidRunLeavesTheReadPositionAtTheLastCommittedBatch ( ) {
		EventReference committed = append(new FirstDomainEvent("1"));
		append(new SecondDomainEvent("2"));
		append(new FirstDomainEvent("poison"));
		append(new SecondDomainEvent("4"));

		Projector<MockDomainEvent> projector = Projector.from(es).into(new FirstEvents()).bookmarkAs(READER)
				.inBatchesOf(1).idleBookmarkInterval(Duration.ZERO).build();
		assertThrows(ProjectorException.class, projector::run);

		Bookmark bookmark = bookmark();
		assertEquals(committed, bookmark.reference());
		assertEquals(Optional.of(committed), bookmark.readUpTo(), "never the head, when the run did not read to it");
	}

	/**
	 * A projector resumes from the last event it handled, never from the read position: a query that
	 * gains an event type is handed the events of that type between the two, which a resume from the read
	 * position would skip for good.
	 */
	@ForEachBackend
	void aProjectorResumesFromTheLastEventHandledNotFromTheReadPosition ( ) {
		append(new FirstDomainEvent("1"));
		EventReference second = append(new SecondDomainEvent("2"));
		projector(new FirstEvents(), Duration.ZERO).run();
		assertEquals(Optional.of(second), bookmark().readUpTo());

		BothEvents widened = new BothEvents();
		Projector.from(es).into(widened).bookmarkAs(READER).build().run();

		assertEquals(List.of("2"), widened.seen, "the event between the two positions is handed to the widened query");
		assertEquals(second, bookmark().reference());
	}

	/**
	 * A run that handled nothing moves the read position at most once per interval; a move held back is
	 * reported as due, and written by the first run after the interval has passed. A run that handled
	 * something is not held back.
	 */
	@ForEachBackend
	void idleRunsAreThrottled ( ) {
		append(new FirstDomainEvent("1"));
		Projector<MockDomainEvent> projector = projector(new FirstEvents(), Duration.ofMillis(500));
		projector.run();

		EventReference firstIdle = append(new SecondDomainEvent("2"));
		projector.run();
		assertEquals(Optional.of(firstIdle), bookmark().readUpTo(), "the first idle move is written");

		EventReference secondIdle = append(new SecondDomainEvent("3"));
		projector.run();
		assertEquals(Optional.of(firstIdle), bookmark().readUpTo(), "a second idle move within the interval is held back");
		Optional<Duration> dueIn = projector.deferredReadUpToDueIn();
		assertTrue(dueIn.isPresent() && !dueIn.get().isZero(), "the held-back move is reported, and not due yet: " + dueIn);

		waitBecauseOfEventualConsistency(() -> projector.deferredReadUpToDueIn().map(Duration::isZero).orElse(false));
		projector.run();
		assertEquals(Optional.of(secondIdle), bookmark().readUpTo(), "the first run after the interval writes it");
		assertEquals(Optional.empty(), projector.deferredReadUpToDueIn());

		// a run that handles something places its bookmark whatever the interval says
		append(new SecondDomainEvent("4"));
		projector.run();
		EventReference handled = append(new FirstDomainEvent("5"));
		projector.run();
		assertEquals(handled, bookmark().reference());
		assertEquals(Optional.of(handled), bookmark().readUpTo());
		assertEquals(Optional.empty(), projector.deferredReadUpToDueIn());
	}

	/** A bounded run has read up to its boundary at most, so a head beyond it is not recorded as read. */
	@ForEachBackend
	void aBoundedRunDoesNotRecordAHeadBeyondItsBoundary ( ) {
		EventReference handled = append(new FirstDomainEvent("1"));
		EventReference boundary = append(new SecondDomainEvent("2"));
		append(new SecondDomainEvent("3"));

		projector(new FirstEvents(), Duration.ZERO).runUntil(boundary);

		assertEquals(Optional.of(handled), bookmark().readUpTo());
	}

	private Projector<MockDomainEvent> projector ( Projection<MockDomainEvent> projection, Duration idleInterval ) {
		return Projector.from(es).into(projection).bookmarkAs(READER).idleBookmarkInterval(idleInterval).build();
	}

	private Bookmark bookmark ( ) {
		return es.findBookmark(READER).orElseThrow();
	}

	private EventReference append ( MockDomainEvent event ) {
		return es.append(Event.of(event, Tags.none())).getLast().reference();
	}

	/** Reads the first event type only, and fails on a value of "poison". */
	static class FirstEvents implements Projection<MockDomainEvent> {

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			if ( event.data() instanceof FirstDomainEvent first && "poison".equals(first.value()) ) {
				throw new IllegalStateException("poison");
			}
		}

	}

	/** Reads both event types, recording the values of the second. */
	static class BothEvents implements Projection<MockDomainEvent> {

		final List<String> seen = new ArrayList<>();

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, SecondDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			if ( event.data() instanceof SecondDomainEvent second ) {
				seen.add(second.value());
			}
		}

	}

}
