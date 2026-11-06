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
package org.sliceworkz.eventstore.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The bounds of an {@link EventFilterItem} bound <em>stored</em> events: a reference names a stored
 * event, and every event that stored event upcasts into is on the same side of a bound. That is how
 * every storage compares them -- a storage only ever sees stored events, whose index is 0 -- and it is
 * what lets a reference obtained without upcasting (a stream's head, a bookmark read back from a store
 * that keeps no index) bound a typed read without cutting a stored event in pieces.
 */
public class EventFilterTest {

	private static final EventType TYPE = EventType.named("Something");

	private static EventReference row ( String id, long position, long tx, int index ) {
		return EventReference.of(EventId.of(id), position, tx, index);
	}

	@Test
	void forTagsIsForEventsOfAnyType ( ) {
		EventFilter filter = EventFilter.forTags(Tags.of("customer", "123"));
		assertEquals(EventFilter.forEvents(EventTypesFilter.any(), Tags.of("customer", "123")), filter);

		assertTrue(filter.matches(TYPE, Tags.of("customer", "123"), row("a", 1, 1, 0)));
		assertTrue(filter.matches(EventType.named("SomethingElse"), Tags.of("customer", "123", "region", "EU"), row("b", 2, 2, 0)));
		assertFalse(filter.matches(TYPE, Tags.of("customer", "124"), row("c", 3, 3, 0)));
		assertFalse(filter.matches(TYPE, Tags.none(), row("d", 4, 4, 0)));
	}

	@Test
	void untilIncludesEveryEventTheStoredEventAtTheBoundaryUpcastsInto ( ) {
		EventReference storedHead = row("head", 10, 42, 0);
		EventFilter bounded = EventFilter.matchAll().until(storedHead);

		assertTrue(bounded.matches(TYPE, Tags.none(), storedHead.withIndex(0)));
		assertTrue(bounded.matches(TYPE, Tags.none(), storedHead.withIndex(1)),
				"the second event the stored event at the boundary upcasts into is not past the boundary");
		assertTrue(bounded.matches(TYPE, Tags.none(), storedHead.withIndex(7)));
	}

	@Test
	void untilExcludesTheNextStoredEventWhateverItsIndex ( ) {
		EventFilter bounded = EventFilter.matchAll().until(row("head", 10, 42, 0));

		assertFalse(bounded.matches(TYPE, Tags.none(), row("next", 11, 42, 0)), "a later position in the same transaction");
		assertFalse(bounded.matches(TYPE, Tags.none(), row("later", 3, 43, 0)), "a later transaction, whatever its position");
	}

	@Test
	void untilIncludesEarlierStoredEventsAndTheirPieces ( ) {
		EventFilter bounded = EventFilter.matchAll().until(row("head", 10, 42, 0));

		assertTrue(bounded.matches(TYPE, Tags.none(), row("earlier", 9, 42, 0)));
		assertTrue(bounded.matches(TYPE, Tags.none(), row("earlier", 9, 42, 5)));
		assertTrue(bounded.matches(TYPE, Tags.none(), row("earlier-tx", 100, 41, 2)), "an earlier transaction, whatever its position");
	}

	@Test
	void aBoundaryOnAPieceStillBoundsItsWholeStoredEvent ( ) {
		// a reference read off an upcasted event carries an index; as a boundary it still means the
		// stored event it came from, whole
		EventFilter bounded = EventFilter.matchAll().until(row("head", 10, 42, 1));

		assertTrue(bounded.matches(TYPE, Tags.none(), row("head", 10, 42, 2)));
		assertFalse(bounded.matches(TYPE, Tags.none(), row("next", 11, 42, 0)));
	}

	/**
	 * "No restriction" on a filter item is {@link EventTypesFilter#any()} and {@link Tags#none()}, never
	 * {@code null}, and a null is refused with the exception every other value type here throws for a
	 * bad argument -- not a {@code java.security} type a caller has no reason to expect from a query.
	 */
	@Test
	void aNullHalfOfAFilterItemIsAnIllegalArgument ( ) {
		assertThrows(IllegalArgumentException.class, () -> new EventFilterItem(null, Tags.none()));
		assertThrows(IllegalArgumentException.class, () -> new EventFilterItem(EventTypesFilter.any(), null));
		new EventFilterItem(EventTypesFilter.any(), Tags.none());
	}

	// --- the fluent form: forTypes(...).tagged(...).or(...) ---------------------------------------

	sealed interface Customer {
		record Registered ( ) implements Customer { }
		record Churned ( ) implements Customer { }
	}

	record Unrelated ( ) { }

	private static final EventType REGISTERED = EventType.of(Customer.Registered.class);
	private static final EventType CHURNED = EventType.of(Customer.Churned.class);
	private static final EventType UNRELATED = EventType.of(Unrelated.class);

	@Test
	void forTypesIsForEventsOfThoseTypesWhateverTheirTags ( ) {
		EventFilter filter = EventFilter.forTypes(Customer.Registered.class, Unrelated.class);
		assertEquals(EventFilter.forEvents(EventTypesFilter.of(Customer.Registered.class, Unrelated.class), Tags.none()), filter);

		assertTrue(filter.matches(REGISTERED, Tags.none(), row("a", 1, 1, 0)));
		assertTrue(filter.matches(UNRELATED, Tags.of("customer", "123"), row("b", 2, 2, 0)));
		assertFalse(filter.matches(CHURNED, Tags.none(), row("c", 3, 3, 0)));
	}

	@Test
	void forTypesResolvesASealedRootIntoEveryTypeUnderIt ( ) {
		EventFilter filter = EventFilter.forTypes(Customer.class);
		assertEquals(EventFilter.forTypes(Customer.Registered.class, Customer.Churned.class), filter);

		assertTrue(filter.matches(REGISTERED, Tags.none(), row("a", 1, 1, 0)));
		assertTrue(filter.matches(CHURNED, Tags.none(), row("b", 2, 2, 0)));
		assertFalse(filter.matches(UNRELATED, Tags.none(), row("c", 3, 3, 0)));
	}

	@Test
	void taggedNarrowsToEventsCarryingTheTagOnTopOfTheTypes ( ) {
		EventFilter filter = EventFilter.forTypes(Customer.class).tagged("customer", "123");
		assertEquals(EventFilter.forEvents(EventTypesFilter.of(Customer.class), Tags.of("customer", "123")), filter,
				"the chain resolves to the same items as the two-halves form");

		assertTrue(filter.matches(REGISTERED, Tags.of("customer", "123"), row("a", 1, 1, 0)));
		assertTrue(filter.matches(CHURNED, Tags.of("customer", "123", "region", "EU"), row("b", 2, 2, 0)));
		assertFalse(filter.matches(REGISTERED, Tags.of("customer", "124"), row("c", 3, 3, 0)), "the tag is required");
		assertFalse(filter.matches(REGISTERED, Tags.none(), row("d", 4, 4, 0)));
		assertFalse(filter.matches(UNRELATED, Tags.of("customer", "123"), row("e", 5, 5, 0)), "the types still are");
	}

	@Test
	void taggedAccumulates ( ) {
		EventFilter filter = EventFilter.forTypes(Customer.class).tagged("customer", "123").tagged("region", "EU");
		assertEquals(EventFilter.forEvents(EventTypesFilter.of(Customer.class), Tags.of("customer", "123", "region", "EU")), filter);
		assertEquals(EventFilter.forTypes(Customer.class).tagged(Tags.of("customer", "123", "region", "EU")), filter);

		assertTrue(filter.matches(REGISTERED, Tags.of("customer", "123", "region", "EU"), row("a", 1, 1, 0)));
		assertFalse(filter.matches(REGISTERED, Tags.of("customer", "123"), row("b", 2, 2, 0)), "both tags are required");
	}

	@Test
	void taggedDistributesOverAUnion ( ) {
		EventFilter a = EventFilter.forTypes(Customer.Registered.class);
		EventFilter b = EventFilter.forTypes(Unrelated.class).tagged("region", "EU");
		Tags customer = Tags.of("customer", "123");

		EventFilter narrowed = a.or(b).tagged(customer);
		assertEquals(a.tagged(customer).or(b.tagged(customer)), narrowed, "every item gets the tag, none is singled out");

		assertTrue(narrowed.matches(REGISTERED, customer, row("a", 1, 1, 0)));
		assertFalse(narrowed.matches(REGISTERED, Tags.none(), row("b", 2, 2, 0)), "the first item now requires the tag");
		assertTrue(narrowed.matches(UNRELATED, Tags.of("customer", "123", "region", "EU"), row("c", 3, 3, 0)));
		assertFalse(narrowed.matches(UNRELATED, customer, row("d", 4, 4, 0)), "the second item keeps the tag it already required");
	}

	@Test
	void taggedOnMatchAllIsForTags ( ) {
		Tags customer = Tags.of("customer", "123");
		assertEquals(EventFilter.forTags(customer), EventFilter.matchAll().tagged(customer));
		assertEquals(EventFilter.forTags(customer), EventFilter.matchAll().tagged("customer", "123"));
	}

	@Test
	void taggedOnMatchNoneStaysMatchNone ( ) {
		EventFilter filter = EventFilter.matchNone().tagged("customer", "123");
		assertTrue(filter.isMatchNone());
		assertFalse(filter.matches(REGISTERED, Tags.of("customer", "123"), row("a", 1, 1, 0)));
	}

	@Test
	void taggedKeepsTheItemBounds ( ) {
		EventReference head = row("head", 10, 42, 0);
		EventFilter filter = EventFilter.forTypes(Customer.class).until(head).tagged("customer", "123");
		assertEquals(head, filter.items().getFirst().until());
		assertEquals(EventFilter.forTypes(Customer.class).tagged("customer", "123").until(head), filter);

		assertTrue(filter.matches(REGISTERED, Tags.of("customer", "123"), head));
		assertFalse(filter.matches(REGISTERED, Tags.of("customer", "123"), row("next", 11, 42, 0)));
	}

	@Test
	void taggedRefusesWhatATagRefuses ( ) {
		EventFilter filter = EventFilter.forTypes(Customer.class);
		assertThrows(IllegalArgumentException.class, () -> filter.tagged((Tags) null));
		assertThrows(IllegalArgumentException.class, () -> filter.tagged("a:b", "c"), "a colon in the key does not survive the wire format");
		assertThrows(IllegalArgumentException.class, () -> filter.tagged("", "c"));
	}

	@Test
	void orIsTheUnionOfBothFiltersItems ( ) {
		EventFilter a = EventFilter.forTypes(Customer.Registered.class);
		EventFilter b = EventFilter.forTags(Tags.of("customer", "123"));
		EventFilter union = a.or(b);

		assertEquals(2, union.items().size());
		assertTrue(union.matches(REGISTERED, Tags.none(), row("a", 1, 1, 0)), "matched by the first");
		assertTrue(union.matches(UNRELATED, Tags.of("customer", "123"), row("b", 2, 2, 0)), "matched by the second");
		assertFalse(union.matches(UNRELATED, Tags.none(), row("c", 3, 3, 0)), "matched by neither");
	}

	@Test
	void orWithMatchAllOnEitherSideIsMatchAll ( ) {
		EventFilter some = EventFilter.forTypes(Customer.Registered.class);
		assertTrue(EventFilter.matchAll().or(some).isMatchAll(), "a union with everything is everything");
		assertTrue(some.or(EventFilter.matchAll()).isMatchAll());
		assertTrue(some.or(EventFilter.matchAll()).matches(UNRELATED, Tags.none(), row("a", 1, 1, 0)));
	}

	@Test
	void orWithMatchNoneOnEitherSideIsTheOtherFilter ( ) {
		EventFilter some = EventFilter.forTypes(Customer.Registered.class);
		assertEquals(some, EventFilter.matchNone().or(some));
		assertEquals(some, some.or(EventFilter.matchNone()));
		assertTrue(EventFilter.matchNone().or(EventFilter.matchNone()).isMatchNone());
	}

	// --- the bounds of an item: after (exclusive), until (inclusive) -----------------------------

	@Test
	void orKeepsEachItemsOwnBounds ( ) {
		EventFilter a = EventFilter.forTypes(Customer.Registered.class).until(row("x", 1, 1, 0));
		EventFilter b = EventFilter.forTypes(Customer.Churned.class).after(row("y", 2, 2, 0));
		EventFilter union = a.or(b);

		assertEquals(List.of(a.items().getFirst(), b.items().getFirst()), union.items(), "differently bounded items unite, each with its own bounds");
		assertTrue(union.matches(REGISTERED, Tags.none(), row("x", 1, 1, 0)));
		assertFalse(union.matches(REGISTERED, Tags.none(), row("z", 3, 3, 0)), "past the first item's until");
		assertFalse(union.matches(CHURNED, Tags.none(), row("y", 2, 2, 0)), "the second item's after is exclusive");
		assertTrue(union.matches(CHURNED, Tags.none(), row("z", 3, 3, 0)));
		assertTrue(a.or(EventFilter.forTypes(Customer.Churned.class)).isBounded(), "a bound on one side only is fine too");
	}

	@Test
	void afterExcludesItsOwnStoredEventAndEverythingBefore ( ) {
		EventFilter bounded = EventFilter.matchAll().after(row("count", 10, 42, 0));

		assertFalse(bounded.matches(TYPE, Tags.none(), row("count", 10, 42, 0)), "exclusive");
		assertFalse(bounded.matches(TYPE, Tags.none(), row("count", 10, 42, 3)), "not a piece of the stored event at the bound either");
		assertFalse(bounded.matches(TYPE, Tags.none(), row("earlier", 9, 42, 0)));
		assertFalse(bounded.matches(TYPE, Tags.none(), row("earlier-tx", 100, 41, 0)), "an earlier transaction, whatever its position");
		assertTrue(bounded.matches(TYPE, Tags.none(), row("next", 11, 42, 0)));
		assertTrue(bounded.matches(TYPE, Tags.none(), row("later-tx", 3, 43, 0)), "a later transaction, whatever its position");
	}

	@Test
	void afterAndUntilTogetherBoundAnItemOnBothSides ( ) {
		EventFilter bounded = EventFilter.forTypes(Customer.class).after(row("a", 10, 10, 0)).until(row("b", 20, 20, 0));

		assertFalse(bounded.matches(REGISTERED, Tags.none(), row("a", 10, 10, 0)));
		assertTrue(bounded.matches(REGISTERED, Tags.none(), row("in", 15, 15, 0)));
		assertTrue(bounded.matches(REGISTERED, Tags.none(), row("b", 20, 20, 0)));
		assertFalse(bounded.matches(REGISTERED, Tags.none(), row("c", 21, 21, 0)));
	}

	@Test
	void anItemWhoseBoundsLeaveNoRoomMatchesNothing ( ) {
		EventFilter empty = EventFilter.matchAll().after(row("b", 20, 20, 0)).until(row("a", 10, 10, 0));
		assertFalse(empty.matches(TYPE, Tags.none(), row("in", 15, 15, 0)));
		assertFalse(empty.matches(TYPE, Tags.none(), row("later", 25, 25, 0)));
	}

	@Test
	void untilAndAfterReplaceAndNullRemoves ( ) {
		EventReference early = row("early", 5, 5, 0);
		EventReference late = row("late", 50, 50, 0);
		EventFilter filter = EventFilter.forTypes(Customer.class);

		assertEquals(late, filter.until(early).until(late).items().getFirst().until(), "the later call wins, even when it widens");
		assertEquals(early, filter.after(late).after(early).items().getFirst().after(), "the later call wins, even when it widens");
		assertEquals(filter, filter.until(early).until(null), "null removes the bound");
		assertEquals(filter, filter.after(early).after(null));
	}

	@Test
	void untilIfEarlierAndAfterIfLaterNarrow ( ) {
		EventReference early = row("early", 5, 5, 0);
		EventReference late = row("late", 50, 50, 0);
		EventFilter filter = EventFilter.forTypes(Customer.class);

		assertEquals(early, filter.untilIfEarlier(early).items().getFirst().until(), "no bound yet: it is set");
		assertEquals(early, filter.until(early).untilIfEarlier(late).items().getFirst().until(), "a later one does not widen");
		assertEquals(early, filter.until(late).untilIfEarlier(early).items().getFirst().until(), "an earlier one narrows");
		assertEquals(late, filter.afterIfLater(late).items().getFirst().after(), "no bound yet: it is set");
		assertEquals(late, filter.after(late).afterIfLater(early).items().getFirst().after(), "an earlier one does not widen");
		assertEquals(late, filter.after(early).afterIfLater(late).items().getFirst().after(), "a later one narrows");

		EventFilter bounded = filter.until(early).after(early);
		assertEquals(bounded, bounded.untilIfEarlier(null), "null changes nothing");
		assertEquals(bounded, bounded.afterIfLater(null));
	}

	@Test
	void theNarrowingMethodsJudgeStoredEventsSoAPieceIsNoNarrower ( ) {
		EventReference stored = row("head", 10, 42, 0);
		EventFilter filter = EventFilter.matchAll().until(stored);
		assertEquals(stored, filter.untilIfEarlier(stored.withIndex(3)).items().getFirst().until(),
				"a reference into the same stored event is not earlier");
	}

	@Test
	void boundsApplyToEveryItemAndEachItemNarrowsOnItsOwn ( ) {
		EventReference early = row("early", 5, 5, 0);
		EventReference late = row("late", 50, 50, 0);
		EventFilter union = EventFilter.forTypes(Customer.Registered.class).until(early)
				.or(EventFilter.forTypes(Customer.Churned.class));

		EventFilter narrowed = union.untilIfEarlier(late);
		assertEquals(early, narrowed.items().get(0).until(), "the first item keeps its earlier bound");
		assertEquals(late, narrowed.items().get(1).until(), "the second item gets the bound");

		EventFilter replaced = union.until(late);
		assertEquals(late, replaced.items().get(0).until());
		assertEquals(late, replaced.items().get(1).until());
	}

	@Test
	void aBoundOnMatchAllIsOneItemOfAnyTypeAndOnMatchNoneNothing ( ) {
		EventReference ref = row("ref", 5, 5, 0);
		assertEquals(EventFilter.forEvents(new EventFilterItem(EventTypesFilter.any(), Tags.none(), ref, null)), EventFilter.matchAll().after(ref));
		assertEquals(EventFilter.forEvents(new EventFilterItem(EventTypesFilter.any(), Tags.none(), null, ref)), EventFilter.matchAll().until(ref));
		assertTrue(EventFilter.matchNone().after(ref).isMatchNone());
		assertTrue(EventFilter.matchNone().until(ref).isMatchNone());
	}

	@Test
	void isBoundedSaysWhetherAnyItemHasABound ( ) {
		EventReference ref = row("ref", 5, 5, 0);
		assertFalse(EventFilter.matchAll().isBounded());
		assertFalse(EventFilter.matchNone().isBounded());
		assertFalse(EventFilter.forTypes(Customer.class).isBounded());
		assertTrue(EventFilter.forTypes(Customer.class).after(ref).isBounded());
		assertTrue(EventFilter.forTypes(Customer.class).or(EventFilter.forTypes(Unrelated.class).until(ref)).isBounded());
	}

	@Test
	void theTwoArgumentItemIsUnbounded ( ) {
		EventFilterItem item = new EventFilterItem(EventTypesFilter.any(), Tags.none());
		assertEquals(new EventFilterItem(EventTypesFilter.any(), Tags.none(), null, null), item);
		assertFalse(item.isBounded());
	}

}
