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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sliceworkz.eventstore.testing.fixture.ExpectedEvent.event;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.ProjectionWithoutMetaData;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.fixture.EventStoreFixtureTest.LearningEvent.CourseDefined;
import org.sliceworkz.eventstore.testing.fixture.EventStoreFixtureTest.LearningEvent.StudentSubscribed;

/**
 * Exercises the fixture through the decider it exists to support, and doubles as its worked example.
 * <p>
 * {@link Registrations} is deliberately ordinary production-shaped code — query the relevant facts,
 * decide, append conditionally on the reference the decision was taken from — so what is being
 * demonstrated is that testing it needs no test-specific seams.
 */
class EventStoreFixtureTest {

	private final EventStoreFixture<LearningEvent> fixture =
			EventStoreFixture.inMemory(EventStreamId.forContext("learning"), LearningEvent.class);

	@Test
	void seedsHistoryAndCapturesWhatTheDeciderAppended ( ) {
		fixture.given(event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"))
				.when(stream -> new Registrations(stream).subscribe("123", "abc001"))
				.expectResult(true)
				.expectAppended(event(new StudentSubscribed("123", "abc001"))
						.tagged("student", "123").tagged("course", "abc001"));
	}

	@Test
	void capturesADecisionNotToAct ( ) {
		fixture.given(
					event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"),
					event(new StudentSubscribed("123", "abc001")).tagged("student", "123").tagged("course", "abc001"))
				.when(stream -> new Registrations(stream).subscribe("123", "abc001"))
				.expectResult(false)
				.expectNoEventsAppended();
	}

	@Test
	void tagsAreComparedNotJustPayloads ( ) {
		AssertionError failure = assertThrows(AssertionError.class, () ->
				fixture.given(event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"))
						.when(stream -> new Registrations(stream).subscribe("123", "abc001"))
						.expectAppended(event(new StudentSubscribed("123", "abc001")).tagged("student", "123")));

		assertTrue(failure.getMessage().contains("appended events do not match"), failure.getMessage());
	}

	@Test
	void anInterleavedAppendMakesTheConsistencyBoundaryFire ( ) {
		fixture.given(event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"))
				.whenConcurrently(
						stream -> new Registrations(stream).subscribe("123", "abc001"),
						event(new StudentSubscribed("123", "abc001")).tagged("student", "123").tagged("course", "abc001"))
				.expectOptimisticLockingFailure()
				.matchingTags("course", "abc001");
	}

	@Test
	void anInterleavedAppendOutsideTheBoundaryDoesNotFire ( ) {
		// a different course: relevant to nobody's decision here, so the append must still succeed
		fixture.given(event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"))
				.whenConcurrently(
						stream -> new Registrations(stream).subscribe("123", "abc001"),
						event(new CourseDefined("zzz999", "Unrelated", 5)).tagged("course", "zzz999"))
				.expectNoFailure()
				.expectResult(true);
	}

	@Test
	void expectFailureReportsTheDecidersOwnException ( ) {
		IllegalStateException thrown = fixture.givenNoHistory()
				.when(stream -> new Registrations(stream).subscribe("123", "does-not-exist"))
				.expectFailure(IllegalStateException.class);

		assertEquals("no such course: does-not-exist", thrown.getMessage());
	}

	@Test
	void assertingOnAppendsAfterAFailureSaysWhatActuallyHappened ( ) {
		AssertionError failure = assertThrows(AssertionError.class, () ->
				fixture.givenNoHistory()
						.when(stream -> new Registrations(stream).subscribe("123", "does-not-exist"))
						.expectNoEventsAppended());

		assertTrue(failure.getMessage().contains("the decision threw IllegalStateException"), failure.getMessage());
	}

	@Test
	void drivesAProjectionToAKnownPoint ( ) {
		EventReference upToTheSecondSubscription = fixture.given(
					event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"),
					event(new StudentSubscribed("1", "abc001")).tagged("course", "abc001"),
					event(new StudentSubscribed("2", "abc001")).tagged("course", "abc001"))
				.lastReference();

		fixture.given(event(new StudentSubscribed("3", "abc001")).tagged("course", "abc001"))
				.project(new SubscriptionCount("abc001"))
				.upTo(upToTheSecondSubscription)
				.expectEventsProcessed(2)
				.expectState(count -> assertEquals(2, count.count()));
	}

	@Test
	void withoutABoundaryTheProjectionRunsToTheHeadOfTheStore ( ) {
		fixture.given(
					event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"),
					event(new StudentSubscribed("1", "abc001")).tagged("course", "abc001"),
					event(new StudentSubscribed("2", "abc001")).tagged("course", "abc001"))
				.project(new SubscriptionCount("abc001"))
				.expectState(count -> assertEquals(2, count.count()));
	}

	// --- the code under test ------------------------------------------------------------------

	/** Ordinary DCB decider: read the relevant facts, decide, append against what it read. */
	static class Registrations {

		private final EventStream<LearningEvent> stream;

		Registrations ( EventStream<LearningEvent> stream ) {
			this.stream = stream;
		}

		boolean subscribe ( String studentId, String courseId ) {
			EventQuery relevant = EventQuery.forEvents(EventTypesFilter.any(), Tags.of("course", courseId));
			List<Event<LearningEvent>> facts = stream.query(relevant).toList();

			if ( facts.stream().noneMatch(e -> e.data() instanceof CourseDefined) ) {
				throw new IllegalStateException("no such course: " + courseId);
			}
			boolean alreadySubscribed = facts.stream()
					.anyMatch(e -> e.data() instanceof StudentSubscribed s && s.studentId().equals(studentId));
			if ( alreadySubscribed ) {
				return false;
			}

			stream.append(
					AppendCriteria.of(relevant, facts.getLast().reference()),
					Event.of(new StudentSubscribed(studentId, courseId),
							Tags.of(Tag.of("student", studentId), Tag.of("course", courseId))));
			return true;
		}

	}

	static class SubscriptionCount implements ProjectionWithoutMetaData<LearningEvent> {

		private final String courseId;
		private int count;

		SubscriptionCount ( String courseId ) {
			this.courseId = courseId;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(StudentSubscribed.class), Tags.of("course", courseId));
		}

		@Override
		public void when ( LearningEvent event ) {
			if ( event instanceof StudentSubscribed ) {
				count++;
			}
		}

		int count ( ) {
			return count;
		}

	}

	sealed interface LearningEvent {
		record CourseDefined ( String courseId, String name, int capacity ) implements LearningEvent { }
		record StudentSubscribed ( String studentId, String courseId ) implements LearningEvent { }
	}

}
