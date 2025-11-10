/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventstore.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class EventReferenceTest {

	@Test
	void testCreate (  ) {
		EventReference r = EventReference.create(123);
		assertNotNull(r);
		assertNotNull(r.id());
		assertEquals(123, r.position());
	}
	
	@Test
	void testWithIdAndPosition (  ) {
		EventId id = EventId.create();
		EventReference r = EventReference.of(id, 123);
		assertNotNull(r);
		assertEquals(id, r.id());
		assertEquals(123, r.position());
	}

	@Test
	void testWithIdAndPositionZero (  ) {
		EventId id = EventId.create();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->EventReference.of(id, 0));
		assertEquals(e.getMessage(), "position 0 is invalid, should be larger than 0");
	}

	@Test
	void testWithIdAndNegativePosition (  ) {
		EventId id = EventId.create();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->EventReference.of(id, -1));
		assertEquals(e.getMessage(), "position -1 is invalid, should be larger than 0");
	}

	@Test
	void testNone ( ) {
		EventReference r = EventReference.none();
		assertNull(r);
	}
	
}
