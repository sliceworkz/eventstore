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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Optional;

import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.SecondDomainEvent;

/**
 * The {@link AppendCriteria} record contract every backend — and every caller inspecting the criteria
 * it is handed — may rely on: {@code expectedLastEventReference()} is never null, whichever factory or
 * constructor produced the criteria, and {@code isNone()} is derived from the filter alone.
 *
 * {@code AppendCriteria.none()} used to put a literal null in that Optional component, so the most common
 * criteria in the library threw a NullPointerException on {@code .isPresent()}. Both in-tree backends
 * carried their own null guard around it; a third-party {@link org.sliceworkz.eventstore.spi.EventStorage}
 * had to guess the same one.
 */
public class AppendCriteriaTest extends AbstractEventStoreTest {

	private static final String UNITTEST_BOUNDEDCONTEXT = "unittest";

	@ForEachBackend
	void testNoneCarriesAnEmptyOptionalAndNotNull() {
		AppendCriteria none = AppendCriteria.none();

		assertNotNull(none.expectedLastEventReference(), "AppendCriteria.none() must not hold a null Optional");
		assertTrue(none.expectedLastEventReference().isEmpty(), "AppendCriteria.none() expects no last event reference");
		assertTrue(none.isNone(), "AppendCriteria.none() must report isNone()");
	}

	@ForEachBackend
	void testEveryConstructionPathNormalisesNullToEmpty() {
		// the of(...) factories already used Optional.ofNullable
		assertNotNull(AppendCriteria.of(EventQuery.matchAll(), null).expectedLastEventReference());
		assertTrue(AppendCriteria.of(EventQuery.matchAll(), null).expectedLastEventReference().isEmpty());
		assertNotNull(AppendCriteria.of(EventFilter.matchAll(), null).expectedLastEventReference());
		assertTrue(AppendCriteria.of(EventFilter.matchAll(), null).expectedLastEventReference().isEmpty());

		// ... and the canonical constructor normalises a null handed to it directly
		assertNotNull(new AppendCriteria(EventFilter.matchAll(), null).expectedLastEventReference(),
				"the canonical constructor must normalise a null reference into Optional.empty()");
		assertTrue(new AppendCriteria(EventFilter.matchAll(), null).expectedLastEventReference().isEmpty());

		// a reference that is given survives untouched
		EventReference reference = EventReference.create(42, 42);
		assertEquals(Optional.of(reference), AppendCriteria.of(EventQuery.matchAll(), reference).expectedLastEventReference());
	}

	@ForEachBackend
	void testIsNoneIsDerivedFromTheFilterAloneAndNotFromTheReference() {
		EventReference reference = EventReference.create(42, 42);

		// a matchNone filter is 'no criteria', with or without a reference on it
		assertTrue(AppendCriteria.of(EventFilter.matchNone(), reference).isNone(),
				"isNone() must follow the filter, not the presence of a reference");
		assertTrue(AppendCriteria.of(EventFilter.matchNone(), null).isNone());

		// and a real filter is never 'no criteria', not even without a reference (that is 'I expect an empty stream')
		assertFalse(AppendCriteria.of(EventFilter.matchAll(), null).isNone(),
				"an absent reference means 'I decided on an empty stream', which is still a consistency boundary");
		assertFalse(AppendCriteria.of(EventFilter.matchAll(), reference).isNone());
	}

	@ForEachBackend
	void testAppendWithNoneIgnoresHistory() {
		EventStream<MockDomainEvent> eventStream = createEventStream();

		EphemeralEvent<FirstDomainEvent> first = Event.of(new FirstDomainEvent("test1"), Tags.none());
		eventStream.append(AppendCriteria.none(), Collections.singletonList(first));

		// appending with none() over a non-empty stream must still not raise: it reads no boundary at all
		EphemeralEvent<SecondDomainEvent> second = Event.of(new SecondDomainEvent("test2"), Tags.none());
		eventStream.append(AppendCriteria.none(), Collections.singletonList(second));

		assertEquals(2, eventStream.query(EventQuery.matchAll()).count(), "both events should be appended with none()");
	}

	@ForEachBackend
	void testAppendWithAReferenceStillLocksAsBefore() {
		EventStream<MockDomainEvent> eventStream = createEventStream();

		EphemeralEvent<FirstDomainEvent> first = Event.of(new FirstDomainEvent("test1"), Tags.none());
		Event<MockDomainEvent> stored = eventStream.append(AppendCriteria.none(), Collections.singletonList(first)).get(0);

		// up-to-date reference: append goes through
		AppendCriteria upToDate = AppendCriteria.of(EventQuery.matchAll(), stored.reference());
		eventStream.append(upToDate, Collections.singletonList(Event.of(new SecondDomainEvent("test2"), Tags.none())));
		assertEquals(2, eventStream.query(EventQuery.matchAll()).count());

		// the same reference is now stale — a new relevant fact sits after it
		assertThrows(OptimisticLockingException.class,
				() -> eventStream.append(upToDate, Collections.singletonList(Event.of(new SecondDomainEvent("test3"), Tags.none()))),
				"a stale reference must still raise an OptimisticLockingException");
		assertEquals(2, eventStream.query(EventQuery.matchAll()).count(), "the failed append must not have stored anything");
	}

	@ForEachBackend
	void testAppendWithoutAReferenceStillMeansExpectAnEmptyStream() {
		EventStream<MockDomainEvent> eventStream = createEventStream();

		// an empty Optional is not 'no criteria': against an empty stream the append succeeds ...
		AppendCriteria expectingEmpty = AppendCriteria.of(EventQuery.matchAll(), null);
		eventStream.append(expectingEmpty, Collections.singletonList(Event.of(new FirstDomainEvent("test1"), Tags.none())));
		assertEquals(1, eventStream.query(EventQuery.matchAll()).count());

		// ... and against a stream that is no longer empty it must not
		assertThrows(OptimisticLockingException.class,
				() -> eventStream.append(expectingEmpty, Collections.singletonList(Event.of(new SecondDomainEvent("test2"), Tags.none()))),
				"criteria with an empty reference expect an empty stream, and must lock when it is not");
		assertEquals(1, eventStream.query(EventQuery.matchAll()).count(), "the failed append must not have stored anything");
	}

	private EventStream<MockDomainEvent> createEventStream() {
		return EventStoreFactory.get().eventStore(eventStorage()).getEventStream(EventStreamId.forContext(UNITTEST_BOUNDEDCONTEXT), MockDomainEvent.class);
	}

}
