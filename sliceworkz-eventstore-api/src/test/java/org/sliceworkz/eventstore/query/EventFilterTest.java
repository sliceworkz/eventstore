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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;

/**
 * The {@code until} boundary of an {@link EventFilter} bounds <em>stored</em> events: a reference
 * names a stored event, and every event that stored event upcasts into is at or before it. That is
 * how every storage compares it -- a storage only ever sees stored events, whose index is 0 -- and it
 * is what lets a reference obtained without upcasting (a stream's head, a bookmark read back from a
 * store that keeps no index) bound a typed read without cutting the newest stored event in pieces.
 */
public class EventFilterTest {

	private static final EventType TYPE = EventType.ofType("Something");

	private static EventReference row ( String id, long position, long tx, int index ) {
		return EventReference.of(EventId.of(id), position, tx, index);
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

}
