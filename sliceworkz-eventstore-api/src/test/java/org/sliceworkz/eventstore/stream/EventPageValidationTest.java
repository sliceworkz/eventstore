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
package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;

/**
 * {@link EventPage} as a value: what it refuses, and that the list it holds is its own.
 * <p>
 * The one rule worth pinning is the consistency between the count and the reference. A page with a
 * count and no reference, or a reference and no count, would give a reader paging on one of them a
 * different answer from a reader paging on the other; the record refuses both, so a backend cannot
 * hand out a page that says "read three, continue from nowhere".
 */
public class EventPageValidationTest {

	private static final EventStreamId STREAM = EventStreamId.forContext("app");

	private static Event<String> event ( long position ) {
		return new Event<>(STREAM, EventType.named("Text"), EventType.named("Text"), EventReference.create(position, position), "text " + position, Tags.none(), Instant.EPOCH);
	}

	@Test
	void anEmptyPageHasNoReferenceAndIsExhausted ( ) {
		EventPage<String> page = new EventPage<>(List.of(), 0, Optional.empty());

		assertTrue(page.events().isEmpty());
		assertTrue(page.isExhausted());
	}

	@Test
	void aPageWithNoEventsButAStoredCountIsNotExhausted ( ) {
		EventPage<String> page = new EventPage<>(List.of(), 3, Optional.of(EventReference.create(3, 3)));

		assertTrue(page.events().isEmpty());
		assertFalse(page.isExhausted(), "stored events that upcast into nothing were read");
	}

	@Test
	void theCountAndTheReferenceMustAgree ( ) {
		assertThrows(IllegalArgumentException.class, () -> new EventPage<>(List.of(), 3, Optional.empty()),
				"read three, but nowhere to continue from");
		assertThrows(IllegalArgumentException.class, () -> new EventPage<>(List.of(), 0, Optional.of(EventReference.create(1, 1))),
				"read nothing, but somewhere to continue from");
	}

	@Test
	void nullsAndANegativeCountAreRefused ( ) {
		assertThrows(IllegalArgumentException.class, () -> new EventPage<>(null, 0, Optional.empty()));
		assertThrows(IllegalArgumentException.class, () -> new EventPage<>(List.of(), 0, null));
		assertThrows(IllegalArgumentException.class, () -> new EventPage<>(List.of(), -1, Optional.empty()));
	}

	@Test
	void theEventsAreCopiedAndUnmodifiable ( ) {
		List<Event<String>> events = new ArrayList<>(List.of(event(1), event(2)));
		EventPage<String> page = new EventPage<>(events, 2, Optional.of(EventReference.create(2, 2)));
		events.add(event(3));

		assertEquals(2, page.events().size(), "a later change to the caller's list is not the page's");
		assertThrows(UnsupportedOperationException.class, () -> page.events().add(event(4)));
	}

}
