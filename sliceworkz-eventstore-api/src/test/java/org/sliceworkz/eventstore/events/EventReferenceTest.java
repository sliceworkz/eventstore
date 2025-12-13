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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class EventReferenceTest {

	@Test
	void testCreate (  ) {
		EventReference r = EventReference.create(123, 456);
		assertNotNull(r);
		assertNotNull(r.id());
		assertEquals(123, r.position());
		assertEquals(456, r.tx());
	}
	
	@Test
	void testWithIdAndPosition (  ) {
		EventId id = EventId.create();
		EventReference r = EventReference.of(id, 123, 456);
		assertNotNull(r);
		assertEquals(id, r.id());
		assertEquals(123, r.position());
		assertEquals(456, r.tx());
	}

	@Test
	void testWithIdAndPositionZero (  ) {
		EventId id = EventId.create();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->EventReference.of(id, 0, 0));
		assertEquals(e.getMessage(), "position 0 is invalid, should be larger than 0");
	}

	@Test
	void testWithIdAndNegativePosition (  ) {
		EventId id = EventId.create();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->EventReference.of(id, -1, 0));
		assertEquals(e.getMessage(), "position -1 is invalid, should be larger than 0");
	}

	@Test
	void testHappenedBefore (  ) {
		EventReference pos_1_tx_1 = new EventReference(EventId.create(), 1, 1);
		EventReference pos_2_tx_2 = new EventReference(EventId.create(), 2, 2);
		EventReference pos_2_tx_3 = new EventReference(EventId.create(), 2, 3);
		EventReference pos_3_tx_5 = new EventReference(EventId.create(), 3, 5);
		EventReference pos_3_tx_6 = new EventReference(EventId.create(), 3, 6);
		EventReference pos_4_tx_4 = new EventReference(EventId.create(), 4, 4);
		EventReference pos_5_tx_7 = new EventReference(EventId.create(), 5, 7);
		
		assertFalse(pos_1_tx_1.happenedBefore(pos_1_tx_1));
		assertTrue(pos_1_tx_1.happenedBefore(pos_2_tx_2));
		assertTrue(pos_1_tx_1.happenedBefore(pos_2_tx_3));
		assertTrue(pos_1_tx_1.happenedBefore(pos_3_tx_5));
		assertTrue(pos_1_tx_1.happenedBefore(pos_3_tx_6));
		assertTrue(pos_1_tx_1.happenedBefore(pos_4_tx_4));
		assertTrue(pos_1_tx_1.happenedBefore(pos_5_tx_7));
		
		assertFalse(pos_2_tx_2.happenedBefore(pos_1_tx_1));
		assertFalse(pos_2_tx_2.happenedBefore(pos_2_tx_2));
		assertTrue(pos_2_tx_2.happenedBefore(pos_2_tx_3));
		assertTrue(pos_2_tx_2.happenedBefore(pos_3_tx_5));
		assertTrue(pos_2_tx_2.happenedBefore(pos_3_tx_6));
		assertTrue(pos_2_tx_2.happenedBefore(pos_4_tx_4));
		assertTrue(pos_2_tx_2.happenedBefore(pos_5_tx_7));

		assertFalse(pos_2_tx_3.happenedBefore(pos_1_tx_1));
		assertFalse(pos_2_tx_3.happenedBefore(pos_2_tx_2));
		assertFalse(pos_2_tx_3.happenedBefore(pos_2_tx_3));
		assertTrue(pos_2_tx_3.happenedBefore(pos_3_tx_5));
		assertTrue(pos_2_tx_3.happenedBefore(pos_3_tx_6));
		assertTrue(pos_2_tx_3.happenedBefore(pos_4_tx_4));
		assertTrue(pos_2_tx_3.happenedBefore(pos_5_tx_7));

		assertFalse(pos_3_tx_5.happenedBefore(pos_1_tx_1));
		assertFalse(pos_3_tx_5.happenedBefore(pos_2_tx_2));
		assertFalse(pos_3_tx_5.happenedBefore(pos_2_tx_3));
		assertFalse(pos_3_tx_5.happenedBefore(pos_3_tx_5));
		assertTrue(pos_3_tx_5.happenedBefore(pos_3_tx_6));
		assertFalse(pos_3_tx_5.happenedBefore(pos_4_tx_4)); // tx goes first, even if pos is not in sequence then
		assertTrue(pos_3_tx_5.happenedBefore(pos_5_tx_7));

		assertFalse(pos_3_tx_6.happenedBefore(pos_1_tx_1));
		assertFalse(pos_3_tx_6.happenedBefore(pos_2_tx_2));
		assertFalse(pos_3_tx_6.happenedBefore(pos_2_tx_3));
		assertFalse(pos_3_tx_6.happenedBefore(pos_3_tx_5));
		assertFalse(pos_3_tx_6.happenedBefore(pos_3_tx_6));
		assertFalse(pos_3_tx_6.happenedBefore(pos_4_tx_4)); // tx goes first, even if pos is not in sequence then
		assertTrue(pos_3_tx_6.happenedBefore(pos_5_tx_7));

		assertFalse(pos_4_tx_4.happenedBefore(pos_1_tx_1));
		assertFalse(pos_4_tx_4.happenedBefore(pos_2_tx_2));
		assertFalse(pos_4_tx_4.happenedBefore(pos_2_tx_3));
		assertTrue(pos_4_tx_4.happenedBefore(pos_3_tx_5));  // tx goes first, even if pos is not in sequence then
		assertTrue(pos_4_tx_4.happenedBefore(pos_3_tx_6));  // tx goes first, even if pos is not in sequence then
		assertFalse(pos_4_tx_4.happenedBefore(pos_4_tx_4)); // tx goes first, even if pos is not in sequence then
		assertTrue(pos_4_tx_4.happenedBefore(pos_5_tx_7));

		assertFalse(pos_5_tx_7.happenedBefore(pos_1_tx_1));
		assertFalse(pos_5_tx_7.happenedBefore(pos_2_tx_2));
		assertFalse(pos_5_tx_7.happenedBefore(pos_2_tx_3));
		assertFalse(pos_5_tx_7.happenedBefore(pos_3_tx_5));  // tx goes first, even if pos is not in sequence then
		assertFalse(pos_5_tx_7.happenedBefore(pos_3_tx_6));  // tx goes first, even if pos is not in sequence then
		assertFalse(pos_5_tx_7.happenedBefore(pos_4_tx_4)); // tx goes first, even if pos is not in sequence then
		assertFalse(pos_5_tx_7.happenedBefore(pos_5_tx_7));
	}
	

	@Test
	void testHappenedAfter (  ) {
		EventReference pos_1_tx_1 = new EventReference(EventId.create(), 1, 1);
		EventReference pos_2_tx_2 = new EventReference(EventId.create(), 2, 2);
		EventReference pos_2_tx_3 = new EventReference(EventId.create(), 2, 3);
		EventReference pos_3_tx_5 = new EventReference(EventId.create(), 3, 5);
		EventReference pos_3_tx_6 = new EventReference(EventId.create(), 3, 6);
		EventReference pos_4_tx_4 = new EventReference(EventId.create(), 4, 4);
		EventReference pos_5_tx_7 = new EventReference(EventId.create(), 5, 7);
		
		assertFalse(pos_1_tx_1.happenedAfter(pos_1_tx_1));
		assertFalse(pos_1_tx_1.happenedAfter(pos_2_tx_2));
		assertFalse(pos_1_tx_1.happenedAfter(pos_2_tx_3));
		assertFalse(pos_1_tx_1.happenedAfter(pos_3_tx_5));
		assertFalse(pos_1_tx_1.happenedAfter(pos_3_tx_6));
		assertFalse(pos_1_tx_1.happenedAfter(pos_4_tx_4));
		assertFalse(pos_1_tx_1.happenedAfter(pos_5_tx_7));
		
		assertTrue(pos_2_tx_2.happenedAfter(pos_1_tx_1));
		assertFalse(pos_2_tx_2.happenedAfter(pos_2_tx_2));
		assertFalse(pos_2_tx_2.happenedAfter(pos_2_tx_3));
		assertFalse(pos_2_tx_2.happenedAfter(pos_3_tx_5));
		assertFalse(pos_2_tx_2.happenedAfter(pos_3_tx_6));
		assertFalse(pos_2_tx_2.happenedAfter(pos_4_tx_4));
		assertFalse(pos_2_tx_2.happenedAfter(pos_5_tx_7));

		assertTrue(pos_2_tx_3.happenedAfter(pos_1_tx_1));
		assertTrue(pos_2_tx_3.happenedAfter(pos_2_tx_2));
		assertFalse(pos_2_tx_3.happenedAfter(pos_2_tx_3));
		assertFalse(pos_2_tx_3.happenedAfter(pos_3_tx_5));
		assertFalse(pos_2_tx_3.happenedAfter(pos_3_tx_6));
		assertFalse(pos_2_tx_3.happenedAfter(pos_4_tx_4));
		assertFalse(pos_2_tx_3.happenedAfter(pos_5_tx_7));

		assertTrue(pos_3_tx_5.happenedAfter(pos_1_tx_1));
		assertTrue(pos_3_tx_5.happenedAfter(pos_2_tx_2));
		assertTrue(pos_3_tx_5.happenedAfter(pos_2_tx_3));
		assertFalse(pos_3_tx_5.happenedAfter(pos_3_tx_5));
		assertFalse(pos_3_tx_5.happenedAfter(pos_3_tx_6));
		assertTrue(pos_3_tx_5.happenedAfter(pos_4_tx_4)); // tx goes first, even if pos is not in sequence then
		assertFalse(pos_3_tx_5.happenedAfter(pos_5_tx_7));

		assertTrue(pos_3_tx_6.happenedAfter(pos_1_tx_1));
		assertTrue(pos_3_tx_6.happenedAfter(pos_2_tx_2));
		assertTrue(pos_3_tx_6.happenedAfter(pos_2_tx_3));
		assertTrue(pos_3_tx_6.happenedAfter(pos_3_tx_5));
		assertFalse(pos_3_tx_6.happenedAfter(pos_3_tx_6));
		assertTrue(pos_3_tx_6.happenedAfter(pos_4_tx_4)); // tx goes first, even if pos is not in sequence then
		assertFalse(pos_3_tx_6.happenedAfter(pos_5_tx_7));

		assertTrue(pos_4_tx_4.happenedAfter(pos_1_tx_1));
		assertTrue(pos_4_tx_4.happenedAfter(pos_2_tx_2));
		assertTrue(pos_4_tx_4.happenedAfter(pos_2_tx_3));
		assertFalse(pos_4_tx_4.happenedAfter(pos_3_tx_5));  // tx goes first, even if pos is not in sequence then
		assertFalse(pos_4_tx_4.happenedAfter(pos_3_tx_6));  // tx goes first, even if pos is not in sequence then
		assertFalse(pos_4_tx_4.happenedAfter(pos_4_tx_4)); // tx goes first, even if pos is not in sequence then
		assertFalse(pos_4_tx_4.happenedAfter(pos_5_tx_7));

		assertTrue(pos_5_tx_7.happenedAfter(pos_1_tx_1));
		assertTrue(pos_5_tx_7.happenedAfter(pos_2_tx_2));
		assertTrue(pos_5_tx_7.happenedAfter(pos_2_tx_3));
		assertTrue(pos_5_tx_7.happenedAfter(pos_3_tx_5));  // tx goes first, even if pos is not in sequence then
		assertTrue(pos_5_tx_7.happenedAfter(pos_3_tx_6));  // tx goes first, even if pos is not in sequence then
		assertTrue(pos_5_tx_7.happenedAfter(pos_4_tx_4)); // tx goes first, even if pos is not in sequence then
		assertFalse(pos_5_tx_7.happenedAfter(pos_5_tx_7));
	}
	
	@Test
	void testNone ( ) {
		EventReference r = EventReference.none();
		assertNull(r);
	}
	
}
