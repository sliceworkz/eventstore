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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventPage;
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
 * The bounds of a filter's items: each {@link org.sliceworkz.eventstore.query.EventFilterItem} may carry
 * its own {@code after} (exclusive) and {@code until} (inclusive), and an event matches the filter when
 * it matches the types, the tags <em>and</em> the bounds of at least one item.
 * <p>
 * Item bounds are part of the storage contract, so every backend answers them. What a backend answers
 * is checked against the filter's own {@link EventFilter#matches(Event)} over the whole stream, for
 * filters whose items share their bounds and filters whose items do not, in both directions, with and
 * without the query's read bound, read whole and paged. Whatever SQL or scan a backend chooses, the
 * selection has to be the one the filter describes, and paging has to visit every match once — a page
 * cut short by events that are discarded later reads as the last page, and ends a replay early.
 * <p>
 * The consistency check is held to the same bounds: an item bounded at or before the expected
 * reference is left out of an {@link AppendCriteria}, and the bounds that remain count.
 */
public class ItemBoundsTest extends AbstractEventStoreTest {

	private static final int EVENTS = 40;

	private EventStream<MockDomainEvent> stream;

	/** Every event on the stream, oldest first: event {@code n} is at index {@code n - 1}. */
	private List<Event<MockDomainEvent>> all;

	@BeforeEach
	void seedStream ( ) {
		this.stream = eventStore().getEventStream(EventStreamId.forContext("app").withPurpose("item-bounds"), MockDomainEvent.class);

		// event n: its type cycles First/Second/Third, tagged k:a or k:b by parity and g:0..4
		for ( int n = 1; n <= EVENTS; n++ ) {
			String value = String.valueOf(n);
			MockDomainEvent data = switch ( n % 3 ) {
				case 1 -> new FirstDomainEvent(value);
				case 2 -> new SecondDomainEvent(value);
				default -> new ThirdDomainEvent(value);
			};
			stream.append(AppendCriteria.none(), Event.of(data, Tags.of("k", n % 2 == 0 ? "a" : "b", "g", String.valueOf(n % 5))));
		}
		this.all = stream.query(EventFilter.matchAll());
		assertEquals(EVENTS, all.size(), "fixture");
	}

	/** Filters over the fixture, by name: items sharing their bounds and items that do not. */
	private Map<String, EventFilter> filters ( ) {
		Map<String, EventFilter> filters = new LinkedHashMap<>();
		filters.put("an early item beside a late one",
				EventFilter.forTypes(FirstDomainEvent.class).until(at(10))
						.or(EventFilter.forTypes(SecondDomainEvent.class).after(at(20))));
		filters.put("tag items bounded on both sides and on one",
				EventFilter.forTags("k", "a").after(at(5)).until(at(15))
						.or(EventFilter.forTags("g", "1").after(at(25))));
		filters.put("overlapping items of one type, and an unbounded one",
				EventFilter.forTypes(ThirdDomainEvent.class).until(at(30))
						.or(EventFilter.forTypes(ThirdDomainEvent.class).after(at(10)))
						.or(EventFilter.forTypes(FirstDomainEvent.class).tagged("k", "b")));
		filters.put("everything early beside one type late",
				EventFilter.matchAll().until(at(3))
						.or(EventFilter.forTypes(FirstDomainEvent.class).after(at(37))));
		filters.put("an item that leaves no room beside a bounded one",
				EventFilter.forTypes(FirstDomainEvent.class).after(at(30)).until(at(20))
						.or(EventFilter.forTypes(SecondDomainEvent.class).until(at(4))));
		filters.put("one bounded item",
				EventFilter.forTypes(FirstDomainEvent.class).after(at(5)).until(at(25)));
		filters.put("two items sharing their bounds",
				EventFilter.forTypes(FirstDomainEvent.class).or(EventFilter.forTypes(SecondDomainEvent.class)).after(at(8)).until(at(18)));
		filters.put("one item with nothing to select",
				EventFilter.forTypes(FirstDomainEvent.class).after(at(EVENTS)));
		return filters;
	}

	@ForEachBackend
	void aReadSelectsWhatTheFilterMatchesInBothDirections ( ) {
		for ( Map.Entry<String, EventFilter> filter : filters().entrySet() ) {
			for ( EventReference notAfter : readBounds() ) {
				EventQuery query = EventQuery.of(filter.getValue()).notAfter(notAfter);
				List<EventReference> expected = expected(filter.getValue(), notAfter);

				assertEquals(expected, references(stream.query(query)),
						"%s, not after %s, forward".formatted(filter.getKey(), notAfter));
				assertEquals(expected.reversed(), references(stream.query(query.backwards())),
						"%s, not after %s, backward".formatted(filter.getKey(), notAfter));
			}
		}
	}

	@ForEachBackend
	void pagingVisitsEveryMatchOnceInBothDirections ( ) {
		for ( Map.Entry<String, EventFilter> filter : filters().entrySet() ) {
			for ( EventReference notAfter : readBounds() ) {
				List<EventReference> expected = expected(filter.getValue(), notAfter);
				for ( int pageSize : List.of(1, 3, 7) ) {
					EventQuery paged = EventQuery.of(filter.getValue()).notAfter(notAfter).limit(pageSize);

					assertEquals(expected, readInPages(paged),
							"%s, not after %s, pages of %d forward".formatted(filter.getKey(), notAfter, pageSize));
					assertEquals(expected.reversed(), readInPages(paged.backwards()),
							"%s, not after %s, pages of %d backward".formatted(filter.getKey(), notAfter, pageSize));
				}
			}
		}
	}

	@ForEachBackend
	void anEventMatchingTwoItemsComesBackOnce ( ) {
		EventFilter overlapping = EventFilter.forTypes(ThirdDomainEvent.class).until(at(30))
				.or(EventFilter.forTypes(ThirdDomainEvent.class).after(at(10)));

		List<EventReference> read = references(stream.query(overlapping));
		assertEquals(read.stream().distinct().toList(), read, "no event twice");
		assertEquals(expected(overlapping, null), read);
		assertEquals(EVENTS / 3, read.size(), "every Third event, once: the two items cover the whole stream between them");
	}

	@ForEachBackend
	void aBackwardsLimitedReadFindsTheNewestMatchOfAnyItem ( ) {
		EventFilter filter = EventFilter.forTypes(FirstDomainEvent.class).until(at(10))
				.or(EventFilter.forTypes(SecondDomainEvent.class).until(at(20)));

		assertEquals(List.of(at(20)), references(stream.query(EventQuery.of(filter).backwards().limit(1))));
		assertEquals(List.of(at(17)), references(stream.query(EventQuery.of(filter).backwards().limit(1).notAfter(at(19)))),
				"the read bound applies on top of the items' own");
	}

	@ForEachBackend
	void aCursorPastAnItemsRangeLeavesTheOtherItems ( ) {
		EventFilter filter = EventFilter.forTypes(FirstDomainEvent.class).until(at(10))
				.or(EventFilter.forTypes(SecondDomainEvent.class).after(at(20)));

		List<EventReference> afterTheFirstItem = references(stream.query(filter, at(15)));
		List<EventReference> expected = expected(filter, null).stream().filter(r -> r.storedEventHappenedAfter(at(15))).toList();
		assertEquals(expected, afterTheFirstItem);
		assertTrue(afterTheFirstItem.stream().allMatch(r -> r.storedEventHappenedAfter(at(20))));
	}

	@ForEachBackend
	void aFilterIsReadByItselfAsAForwardQuery ( ) {
		EventFilter filter = EventFilter.forTypes(FirstDomainEvent.class).until(at(10))
				.or(EventFilter.forTypes(SecondDomainEvent.class).after(at(20)));
		assertEquals(references(stream.query(EventQuery.of(filter))), references(stream.query(filter)));
		assertEquals(references(stream.query(EventQuery.of(filter), at(5))), references(stream.query(filter, at(5))));
	}

	// --- the consistency check -------------------------------------------------------------------

	/**
	 * The bounds are part of what the filter matches, so they are part of the consistency boundary: an
	 * event outside an item's bounds is not a new relevant fact for that item and must not raise a
	 * conflict.
	 */
	@ForEachBackend
	void appendCriteriaHonoursItsItemBounds ( ) {
		EventFilter firsts = EventFilter.forTypes(FirstDomainEvent.class);

		// unbounded: every First after event 1 is a new relevant fact
		assertThrows(OptimisticLockingException.class,
				() -> stream.append(AppendCriteria.of(firsts, at(1)), Event.of(new FirstDomainEvent("x"), Tags.none())));

		// bounded until event 10: the Firsts up to it, after event 1, still conflict
		assertThrows(OptimisticLockingException.class,
				() -> stream.append(AppendCriteria.of(firsts.until(at(10)), at(1)), Event.of(new FirstDomainEvent("x"), Tags.none())));

		// bounded until event 1, the reference itself: nothing after the reference lies within it
		List<Event<MockDomainEvent>> appended = stream.append(AppendCriteria.of(firsts.until(at(1)), at(1)),
				Event.of(new FirstDomainEvent("x"), Tags.none()));
		assertEquals(1, appended.size());
	}

	@ForEachBackend
	void anItemBoundedAtOrBeforeTheReferenceIsLeftOutOfTheCheck ( ) {
		EventReference head = all.getLast().reference();
		EventFilter closed = EventFilter.forTypes(FirstDomainEvent.class).until(at(10));
		EventFilter open = EventFilter.forTypes(SecondDomainEvent.class);

		AppendCriteria criteria = AppendCriteria.of(closed.or(open), head);
		assertEquals(open, criteria.eventFilter(), "the item that can match nothing after the head is gone");

		// a First appended after the head is outside the first item's bounds: no conflict for it
		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("late"), Tags.none()));
		stream.append(criteria, Event.of(new ThirdDomainEvent("decided"), Tags.none()));

		// a Second is a new relevant fact for the item that is left
		stream.append(AppendCriteria.none(), Event.of(new SecondDomainEvent("late"), Tags.none()));
		assertThrows(OptimisticLockingException.class, () -> stream.append(criteria, Event.of(new ThirdDomainEvent("stale"), Tags.none())));
	}

	@ForEachBackend
	void aCriteriaWhoseItemsAreAllBoundedBeforeTheReferenceIsUnconditional ( ) {
		EventReference head = all.getLast().reference();
		AppendCriteria criteria = AppendCriteria.of(EventFilter.forTypes(FirstDomainEvent.class).until(at(10))
				.or(EventFilter.forTypes(SecondDomainEvent.class).until(head)), head);

		assertTrue(criteria.isNone(), "a decision on history no later append can change needs no check");
		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("late"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new SecondDomainEvent("late"), Tags.none()));
		assertEquals(1, stream.append(criteria, Event.of(new ThirdDomainEvent("decided"), Tags.none())).size());
	}

	@ForEachBackend
	void anItemBoundedAfterTheReferenceIsStillChecked ( ) {
		// the reference is event 10; the item is bounded until event 20, so events 11..20 still count
		EventFilter firsts = EventFilter.forTypes(FirstDomainEvent.class).until(at(20));
		AppendCriteria criteria = AppendCriteria.of(firsts, at(10));
		assertFalse(criteria.isNone());
		assertThrows(OptimisticLockingException.class, () -> stream.append(criteria, Event.of(new ThirdDomainEvent("x"), Tags.none())),
				"Firsts 13, 16 and 19 lie after the reference and within the bound");

		// bounded until event 11, which is no First: nothing after the reference lies within the bound
		assertEquals(1, stream.append(AppendCriteria.of(EventFilter.forTypes(FirstDomainEvent.class).until(at(11)), at(10)),
				Event.of(new ThirdDomainEvent("x"), Tags.none())).size());
	}

	@ForEachBackend
	void anAfterAtOrBeforeTheReferenceIsDroppedAndOneAfterItCounts ( ) {
		EventReference head = all.getLast().reference();

		// after event 5, reference at the head: the bound adds nothing, every First appended later conflicts
		AppendCriteria early = AppendCriteria.of(EventFilter.forTypes(FirstDomainEvent.class).after(at(5)), head);
		assertEquals(EventFilter.forTypes(FirstDomainEvent.class), early.eventFilter());

		// after event 30, reference at event 20: only the Firsts after 30 are new relevant facts
		AppendCriteria late = AppendCriteria.of(EventFilter.forTypes(FirstDomainEvent.class).after(at(30)), at(20));
		assertThrows(OptimisticLockingException.class, () -> stream.append(late, Event.of(new ThirdDomainEvent("x"), Tags.none())),
				"Firsts 31, 34, 37 and 40 count");
		AppendCriteria lateOfNothing = AppendCriteria.of(EventFilter.forTypes(FirstDomainEvent.class).after(at(40)), at(20));
		assertEquals(1, stream.append(lateOfNothing, Event.of(new ThirdDomainEvent("x"), Tags.none())).size(),
				"no First after event 40 had been appended");

		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("later"), Tags.none()));
		assertThrows(OptimisticLockingException.class, () -> stream.append(early, Event.of(new ThirdDomainEvent("y"), Tags.none())));
	}

	@ForEachBackend
	void anAbsentReferencePrunesNothing ( ) {
		EventFilter bounded = EventFilter.forTypes(FirstDomainEvent.class).until(at(10));
		AppendCriteria criteria = AppendCriteria.of(bounded, null);
		assertEquals(bounded, criteria.eventFilter(), "a decision on an empty boundary counts every matching event");
		assertThrows(OptimisticLockingException.class, () -> stream.append(criteria, Event.of(new ThirdDomainEvent("x"), Tags.none())));
	}

	@ForEachBackend
	void theCriteriaBuiltFromTheReadTakesItsFilterNeverItsReadBound ( ) {
		EventReference head = all.getLast().reference();
		EventQuery read = EventQuery.of(EventFilter.forTypes(SecondDomainEvent.class)).notAfter(head);
		stream.query(read);

		AppendCriteria criteria = AppendCriteria.of(read, head);
		assertEquals(read.filter(), criteria.eventFilter());

		stream.append(AppendCriteria.none(), Event.of(new SecondDomainEvent("concurrent"), Tags.none()));
		assertThrows(OptimisticLockingException.class, () -> stream.append(criteria, Event.of(new ThirdDomainEvent("stale"), Tags.none())),
				"the head the read was pinned at bounds the read, never the check");
	}

	// --- helpers ---------------------------------------------------------------------------------

	private List<EventReference> readBounds ( ) {
		List<EventReference> bounds = new ArrayList<>();
		bounds.add(null);
		bounds.add(at(28));
		return bounds;
	}

	/** What the filter selects, forward, computed from the filter's own matching over the whole stream. */
	private List<EventReference> expected ( EventFilter filter, EventReference notAfter ) {
		return all.stream()
				.filter(e -> notAfter == null || !e.reference().storedEventHappenedAfter(notAfter))
				.filter(filter::matches)
				.map(Event::reference)
				.toList();
	}

	/** Reads a query page by page, the way a projector does, until a page is shorter than its limit. */
	private List<EventReference> readInPages ( EventQuery query ) {
		List<EventReference> read = new ArrayList<>();
		EventReference cursor = null;
		for ( int pages = 0; pages <= EVENTS + 1; pages++ ) {
			EventPage<MockDomainEvent> page = stream.page(query, cursor);
			read.addAll(references(page.events()));
			if ( page.storedEventCount() < query.limit().value() ) {
				return read;
			}
			cursor = page.lastStoredEventReference().orElseThrow();
		}
		throw new AssertionError("paging did not end");
	}

	private EventReference at ( int n ) {
		return all.get(n - 1).reference();
	}

	private static List<EventReference> references ( List<Event<MockDomainEvent>> events ) {
		return events.stream().map(Event::reference).toList();
	}

}
