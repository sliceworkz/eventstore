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
		assertEquals(0, r.index());
	}

	@Test
	void testWithIdAndPosition (  ) {
		EventId id = EventId.create();
		EventReference r = EventReference.of(id, 123, 456);
		assertNotNull(r);
		assertEquals(id, r.id());
		assertEquals(123, r.position());
		assertEquals(456, r.tx());
		assertEquals(0, r.index());
	}

	@Test
	void testWithIdAndPositionAndIndex (  ) {
		EventId id = EventId.create();
		EventReference r = EventReference.of(id, 123, 456, 2);
		assertNotNull(r);
		assertEquals(id, r.id());
		assertEquals(123, r.position());
		assertEquals(456, r.tx());
		assertEquals(2, r.index());
	}

	@Test
	void testWithIndex (  ) {
		EventId id = EventId.create();
		EventReference r = EventReference.of(id, 10, 10);
		EventReference r2 = r.withIndex(3);
		assertEquals(id, r2.id());
		assertEquals(10, r2.position());
		assertEquals(10, r2.tx());
		assertEquals(3, r2.index());
		assertEquals(0, r.index()); // original unchanged
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
	void testWithNegativeIndex (  ) {
		EventId id = EventId.create();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->EventReference.of(id, 1, 1, -1));
		assertEquals(e.getMessage(), "index -1 is invalid, should be 0 or larger");
	}

	@Test
	void testHappenedBefore (  ) {
		EventReference pos_1_tx_1 = EventReference.of(EventId.create(), 1, 1);
		EventReference pos_2_tx_2 = EventReference.of(EventId.create(), 2, 2);
		EventReference pos_2_tx_3 = EventReference.of(EventId.create(), 2, 3);
		EventReference pos_3_tx_5 = EventReference.of(EventId.create(), 3, 5);
		EventReference pos_3_tx_6 = EventReference.of(EventId.create(), 3, 6);
		EventReference pos_4_tx_4 = EventReference.of(EventId.create(), 4, 4);
		EventReference pos_5_tx_7 = EventReference.of(EventId.create(), 5, 7);
		
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
		EventReference pos_1_tx_1 = EventReference.of(EventId.create(), 1, 1);
		EventReference pos_2_tx_2 = EventReference.of(EventId.create(), 2, 2);
		EventReference pos_2_tx_3 = EventReference.of(EventId.create(), 2, 3);
		EventReference pos_3_tx_5 = EventReference.of(EventId.create(), 3, 5);
		EventReference pos_3_tx_6 = EventReference.of(EventId.create(), 3, 6);
		EventReference pos_4_tx_4 = EventReference.of(EventId.create(), 4, 4);
		EventReference pos_5_tx_7 = EventReference.of(EventId.create(), 5, 7);
		
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
	void testHappenedBeforeWithIndex (  ) {
		EventId id = EventId.create();
		EventReference idx0 = EventReference.of(id, 5, 5, 0);
		EventReference idx1 = EventReference.of(id, 5, 5, 1);
		EventReference idx2 = EventReference.of(id, 5, 5, 2);

		// same tx, same position, different index
		assertTrue(idx0.happenedBefore(idx1));
		assertTrue(idx0.happenedBefore(idx2));
		assertTrue(idx1.happenedBefore(idx2));
		assertFalse(idx0.happenedBefore(idx0));
		assertFalse(idx1.happenedBefore(idx0));
		assertFalse(idx2.happenedBefore(idx1));

		// index is tertiary: tx and position still take precedence
		EventReference nextPos = EventReference.of(EventId.create(), 6, 5, 0);
		assertTrue(idx2.happenedBefore(nextPos));
		assertFalse(nextPos.happenedBefore(idx2));

		EventReference nextTx = EventReference.of(EventId.create(), 5, 6, 0);
		assertTrue(idx2.happenedBefore(nextTx));
		assertFalse(nextTx.happenedBefore(idx2));
	}

	@Test
	void testHappenedAfterWithIndex (  ) {
		EventId id = EventId.create();
		EventReference idx0 = EventReference.of(id, 5, 5, 0);
		EventReference idx1 = EventReference.of(id, 5, 5, 1);
		EventReference idx2 = EventReference.of(id, 5, 5, 2);

		assertFalse(idx0.happenedAfter(idx0));
		assertFalse(idx0.happenedAfter(idx1));
		assertTrue(idx1.happenedAfter(idx0));
		assertTrue(idx2.happenedAfter(idx0));
		assertTrue(idx2.happenedAfter(idx1));
		assertFalse(idx1.happenedAfter(idx2));
	}

	@Test
	void testNone ( ) {
		EventReference r = EventReference.none();
		assertNull(r);
	}

	@Test
	void testToStringWithoutIndex (  ) {
		EventId id = EventId.of("550e8400-e29b-41d4-a716-446655440000");
		EventReference r = EventReference.of(id, 42, 10);
		assertEquals("550e8400-e29b-41d4-a716-446655440000:10:42", r.toString());
	}

	@Test
	void testToStringWithIndex (  ) {
		EventId id = EventId.of("550e8400-e29b-41d4-a716-446655440000");
		EventReference r = EventReference.of(id, 42, 10, 3);
		assertEquals("550e8400-e29b-41d4-a716-446655440000:10:42:3", r.toString());
	}

	@Test
	void testToStringWithIndexZeroOmitsIndex (  ) {
		EventId id = EventId.of("550e8400-e29b-41d4-a716-446655440000");
		EventReference r = EventReference.of(id, 42, 10, 0);
		assertEquals("550e8400-e29b-41d4-a716-446655440000:10:42", r.toString());
	}

	@Test
	void testFromStringWithoutIndex (  ) {
		EventReference r = EventReference.fromString("550e8400-e29b-41d4-a716-446655440000:10:42");
		assertEquals("550e8400-e29b-41d4-a716-446655440000", r.id().value());
		assertEquals(10, r.tx());
		assertEquals(42, r.position());
		assertEquals(0, r.index());
	}

	@Test
	void testFromStringWithIndex (  ) {
		EventReference r = EventReference.fromString("550e8400-e29b-41d4-a716-446655440000:10:42:3");
		assertEquals("550e8400-e29b-41d4-a716-446655440000", r.id().value());
		assertEquals(10, r.tx());
		assertEquals(42, r.position());
		assertEquals(3, r.index());
	}

	@Test
	void testFromStringRoundtripWithoutIndex (  ) {
		EventId id = EventId.of("my-event-id");
		EventReference original = EventReference.of(id, 100, 50);
		EventReference parsed = EventReference.fromString(original.toString());
		assertEquals(original, parsed);
	}

	@Test
	void testFromStringRoundtripWithIndex (  ) {
		EventId id = EventId.of("my-event-id");
		EventReference original = EventReference.of(id, 100, 50, 7);
		EventReference parsed = EventReference.fromString(original.toString());
		assertEquals(original, parsed);
	}

	@Test
	void testFromStringNull (  ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventReference.fromString(null));
		assertEquals("EventReference string cannot be null or blank", e.getMessage());
	}

	@Test
	void testFromStringBlank (  ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventReference.fromString("  "));
		assertEquals("EventReference string cannot be null or blank", e.getMessage());
	}

	@Test
	void testFromStringEmpty (  ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventReference.fromString(""));
		assertEquals("EventReference string cannot be null or blank", e.getMessage());
	}

	@Test
	void testFromStringTooFewParts (  ) {
		assertThrows(IllegalArgumentException.class, () -> EventReference.fromString("id:10"));
	}

	@Test
	void testFromStringTooManyParts (  ) {
		assertThrows(IllegalArgumentException.class, () -> EventReference.fromString("id:10:42:3:extra"));
	}

	@Test
	void testFromStringInvalidTx (  ) {
		assertThrows(IllegalArgumentException.class, () -> EventReference.fromString("my-id:abc:42"));
	}

	@Test
	void testFromStringInvalidPosition (  ) {
		assertThrows(IllegalArgumentException.class, () -> EventReference.fromString("my-id:10:abc"));
	}

	@Test
	void testFromStringInvalidIndex (  ) {
		assertThrows(IllegalArgumentException.class, () -> EventReference.fromString("my-id:10:42:abc"));
	}

	@Test
	void testFromStringInvalidPositionZero (  ) {
		assertThrows(IllegalArgumentException.class, () -> EventReference.fromString("my-id:10:0"));
	}

	@Test
	void testFromStringInvalidNegativeIndex (  ) {
		assertThrows(IllegalArgumentException.class, () -> EventReference.fromString("my-id:10:42:-1"));
	}

}
