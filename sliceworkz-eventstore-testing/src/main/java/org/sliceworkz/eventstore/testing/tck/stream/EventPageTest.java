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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventPage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastMultiTest.CurrentEvent;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastMultiTest.LegacyEvents;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastMultiTest.OriginalEvent;

/**
 * {@link org.sliceworkz.eventstore.stream.EventSource#page(EventQuery, EventReference)}: one page
 * of a paged read, with what it took from storage to produce it.
 * <p>
 * The events of a page are what a query with the same arguments returns. What a page adds is the
 * account of the <em>stored</em> events behind them — how many were read, and the reference of the
 * last one — which is what paging needs and the events alone cannot say once upcasting is involved:
 * a limit counts stored events, an upcaster may turn one into several or into none, and so a page
 * holding no events may sit in the middle of a stream while the reference to continue from is on no
 * event returned. That account is what lets a reader, {@code Projector} included, page past events
 * that upcast into nothing instead of re-reading them forever, and stop at a page shorter than its
 * limit rather than after one more empty read.
 */
public class EventPageTest extends AbstractEventStoreTest {

	private final EventStreamId streamId = EventStreamId.forContext("app").withPurpose("page");

	private EventStream<MockDomainEvent> seed ( int count ) {
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);
		for ( int i = 1; i <= count; i++ ) {
			stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("" + i), Tags.none()));
		}
		return stream;
	}

	private static List<String> values ( List<Event<MockDomainEvent>> events ) {
		return events.stream().map(e -> ((FirstDomainEvent) e.data()).value()).toList();
	}

	@ForEachBackend
	void aPageCarriesItsEventsTheStoredCountAndTheLastStoredReference ( ) {
		EventStream<MockDomainEvent> stream = seed(5);
		EventQuery twoAtATime = EventQuery.matchAll().limit(2);

		EventPage<MockDomainEvent> first = stream.page(twoAtATime, null);
		assertEquals(List.of("1", "2"), values(first.events()));
		assertEquals(2, first.storedEventCount());
		assertEquals(first.events().getLast().reference(), first.lastStoredEventReference().orElseThrow(),
				"without upcasting the last stored event is the last event");
		assertFalse(first.isExhausted());

		EventPage<MockDomainEvent> second = stream.page(twoAtATime, first.lastStoredEventReference().orElseThrow());
		assertEquals(List.of("3", "4"), values(second.events()));
		assertEquals(2, second.storedEventCount());

		EventPage<MockDomainEvent> third = stream.page(twoAtATime, second.lastStoredEventReference().orElseThrow());
		assertEquals(List.of("5"), values(third.events()));
		assertEquals(1, third.storedEventCount(), "a page short of its limit is the last one");
		assertFalse(third.isExhausted(), "short, but it did read something");

		EventPage<MockDomainEvent> fourth = stream.page(twoAtATime, third.lastStoredEventReference().orElseThrow());
		assertTrue(fourth.events().isEmpty());
		assertEquals(0, fourth.storedEventCount());
		assertTrue(fourth.lastStoredEventReference().isEmpty(), "a page that read nothing has nothing to continue from");
		assertTrue(fourth.isExhausted());
	}

	@ForEachBackend
	void aPageHoldsWhatAQueryWithTheSameArgumentsReturns ( ) {
		EventStream<MockDomainEvent> stream = seed(6);
		stream.append(AppendCriteria.none(), Event.of(new SecondDomainEvent("other"), Tags.none()));
		EventReference cursor = stream.query(EventQuery.matchAll().limit(1)).findFirst().orElseThrow().reference();

		for ( EventQuery q : List.of(
				EventQuery.matchAll().limit(3),
				EventQuery.matchAll().backwards().limit(3),
				EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).limit(2),
				EventQuery.matchAll() ) ) {
			assertEquals(stream.query(q, cursor).toList(), stream.page(q, cursor).events(), q.toString());
			assertEquals(stream.query(q, null).toList(), stream.page(q, null).events(), q.toString());
		}
	}

	@ForEachBackend
	void aQueryWithNoLimitReadsToTheEndAsOnePage ( ) {
		EventStream<MockDomainEvent> stream = seed(4);

		EventPage<MockDomainEvent> whole = stream.page(EventQuery.matchAll(), null);
		assertEquals(List.of("1", "2", "3", "4"), values(whole.events()));
		assertEquals(4, whole.storedEventCount());
	}

	@ForEachBackend
	void aBackwardsPageContinuesTowardsTheOldest ( ) {
		EventStream<MockDomainEvent> stream = seed(5);
		EventQuery newestFirst = EventQuery.matchAll().backwards().limit(2);

		EventPage<MockDomainEvent> first = stream.page(newestFirst, null);
		assertEquals(List.of("5", "4"), values(first.events()));
		assertEquals(first.events().getLast().reference(), first.lastStoredEventReference().orElseThrow(),
				"the last stored event read is the oldest of the page, which is where the next page continues");

		EventPage<MockDomainEvent> second = stream.page(newestFirst, first.lastStoredEventReference().orElseThrow());
		assertEquals(List.of("3", "2"), values(second.events()));

		EventPage<MockDomainEvent> third = stream.page(newestFirst, second.lastStoredEventReference().orElseThrow());
		assertEquals(List.of("1"), values(third.events()));
		assertEquals(1, third.storedEventCount());
	}

	/**
	 * The case the account of stored events exists for: a page whose stored events all upcast into
	 * nothing holds no events, and is neither the end of the stream nor a page with nowhere to
	 * continue from. A reader paging on the events alone would either stop here, missing everything
	 * after, or re-read the same page forever.
	 */
	@ForEachBackend
	void aPageWhoseStoredEventsAllUpcastIntoNothingStillCarriesItsCursor ( ) {
		EventStream<OriginalEvent> original = eventStore().getEventStream(streamId, OriginalEvent.class);
		for ( int i = 0; i < 3; i++ ) {
			original.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerLegacyAuditLog("audit " + i), Tags.none()));
		}
		EventStream<CurrentEvent> stream = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);
		stream.append(AppendCriteria.none(), Event.of(new CurrentEvent.CustomerChurned(), Tags.none()));

		EventQuery threeAtATime = EventQuery.matchAll().limit(3);

		EventPage<CurrentEvent> vanished = stream.page(threeAtATime, null);
		assertTrue(vanished.events().isEmpty(), "three audit logs, each upcasting into nothing");
		assertEquals(3, vanished.storedEventCount(), "but three stored events were read");
		assertFalse(vanished.isExhausted());
		EventReference cursor = vanished.lastStoredEventReference().orElseThrow();
		assertEquals(0, cursor.index(), "a stored event, whole");
		EventReference thirdAuditLog = eventStore().getEventStream(streamId).query(EventQuery.matchAll().limit(3)).toList().getLast().reference();
		assertEquals(thirdAuditLog, cursor, "the third audit log, read raw: the last stored event of the page");

		EventPage<CurrentEvent> next = stream.page(threeAtATime, cursor);
		assertEquals(1, next.events().size());
		assertEquals(CurrentEvent.CustomerChurned.class, next.events().get(0).data().getClass());
		assertEquals(1, next.storedEventCount());
	}

	/**
	 * A limit counts stored events and so does the page: one stored event upcasting into two is a
	 * page of two events that read one, and its cursor names that stored event whole.
	 */
	@ForEachBackend
	void aPageCountsStoredEventsNotTheEventsTheyUpcastInto ( ) {
		EventStream<OriginalEvent> original = eventStore().getEventStream(streamId, OriginalEvent.class);
		original.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerRegisteredWithAddress("John", "123 Main St", "Springfield"), Tags.none()));
		original.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerNameChanged("Jane"), Tags.none()));
		EventStream<CurrentEvent> stream = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

		EventPage<CurrentEvent> split = stream.page(EventQuery.matchAll().limit(1), null);
		assertEquals(2, split.events().size(), "one stored event, two events");
		assertEquals(1, split.storedEventCount());
		EventReference cursor = split.lastStoredEventReference().orElseThrow();
		assertEquals(0, cursor.index());
		assertEquals(split.events().get(0).reference().position(), cursor.position());
		assertEquals(1, split.events().get(1).reference().index(), "the events carry their index, the cursor does not");

		EventPage<CurrentEvent> rest = stream.page(EventQuery.matchAll().limit(1), cursor);
		assertEquals(1, rest.events().size());
		assertEquals(CurrentEvent.CustomerRenamed.class, rest.events().get(0).data().getClass());
	}

	/**
	 * Paging to the end by hand, over a stream mixing events that upcast into two, into none and into
	 * one: every event comes round exactly once, and the loop ends on the short page.
	 */
	@ForEachBackend
	void pagingByHandVisitsEveryEventOnceAndEndsOnTheShortPage ( ) {
		EventStream<OriginalEvent> original = eventStore().getEventStream(streamId, OriginalEvent.class);
		for ( int i = 0; i < 4; i++ ) {
			original.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerRegisteredWithAddress("John " + i, "street", "city"), Tags.none()));
			original.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerLegacyAuditLog("audit " + i), Tags.none()));
			original.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerNameChanged("Jane " + i), Tags.none()));
		}
		EventStream<CurrentEvent> stream = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);
		EventQuery q = EventQuery.matchAll().limit(5);

		List<EventReference> seen = new ArrayList<>();
		int pages = 0;
		EventReference cursor = null;
		EventPage<CurrentEvent> page;
		do {
			page = stream.page(q, cursor);
			pages++;
			page.events().forEach(e -> seen.add(e.reference()));
			cursor = page.lastStoredEventReference().orElse(null);
		} while ( page.storedEventCount() == 5 );

		assertEquals(3, pages, "12 stored events in pages of 5: 5, 5, 2");
		assertEquals(stream.query(EventQuery.matchAll()).map(Event::reference).toList(), seen,
				"every event once, in order: the three events per round, less the audit log");
		assertEquals(12, seen.size());
	}

	/**
	 * A page is read whole, where a query converts each payload as its stream is consumed. So a
	 * stored event this stream cannot read fails {@code page} itself, and hands out none of the page's
	 * events — a query returns quietly and fails from the caller's terminal operation.
	 */
	@ForEachBackend
	void aPoisonEventFailsThePageWholeWhereAQueryFailsLazily ( ) {
		EventStream<MockDomainEvent> stream = seed(2);
		eventStore().getEventStream(streamId, CurrentEvent.class)
				.append(AppendCriteria.none(), Event.of(new CurrentEvent.CustomerChurned(), Tags.none()));

		EventDeserializationException fromPage = assertThrows(EventDeserializationException.class,
				() -> stream.page(EventQuery.matchAll(), null));
		assertEquals(stream.head().orElseThrow(), fromPage.getReference().orElseThrow(), "the poison event is named");

		Stream<Event<MockDomainEvent>> lazy = stream.query(EventQuery.matchAll());
		EventDeserializationException fromTerminal = assertThrows(EventDeserializationException.class, lazy::toList);
		assertEquals(fromPage.getReference(), fromTerminal.getReference());

		// a page that stops short of the poison event is unaffected
		assertEquals(List.of("1", "2"), values(stream.page(EventQuery.matchAll().limit(2), null).events()));
	}

}
