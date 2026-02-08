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
package org.sliceworkz.eventstore.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class LimitTest {

	@Test
	void testWithValue ( ) {
		Limit l1 = new Limit ( 123l );
		Limit l2 = Limit.to(123);
		assertEquals(l1, l2);
		assertTrue(l1.isSet());
		assertFalse(l1.isNotSet());
		assertTrue(l2.isSet());
		assertFalse(l2.isNotSet());
		assertEquals(123l, l1.value());
		assertEquals(123l, l2.value());
	}
	
	@Test
	void testNull ( ) {
		Limit l1 = new Limit ( null );
		Limit l2 = Limit.none();
		assertEquals(l1, l2);
		assertTrue(l1.isNotSet());
		assertFalse(l1.isSet());
		assertTrue(l2.isNotSet());
		assertFalse(l2.isSet());
		assertNull(l1.value());
		assertNull(l2.value());
	}
	
	@Test
	void testNegative ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Limit.to(-1));
		assertEquals("limit -1 is invalid, should be larger than 0", e.getMessage());
	}

	@Test
	void testZero ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Limit.to(0));
		assertEquals("limit 0 is invalid, should be larger than 0", e.getMessage());
	}

	@Test
	void testOrIfLowerLong_WhenNone_ReturnsGivenValue ( ) {
		Limit result = Limit.none().orIfLower(100);
		assertEquals(Limit.to(100), result);
	}

	@Test
	void testOrIfLowerLong_WhenCurrentIsHigher_ReturnsGivenValue ( ) {
		Limit result = Limit.to(500).orIfLower(100);
		assertEquals(Limit.to(100), result);
	}

	@Test
	void testOrIfLowerLong_WhenCurrentIsLower_ReturnsCurrent ( ) {
		Limit original = Limit.to(50);
		Limit result = original.orIfLower(100);
		assertSame(original, result);
	}

	@Test
	void testOrIfLowerLong_WhenEqual_ReturnsCurrent ( ) {
		Limit original = Limit.to(100);
		Limit result = original.orIfLower(100);
		assertSame(original, result);
	}

	@Test
	void testOrIfLowerLong_WithInvalidValue_Throws ( ) {
		assertThrows(IllegalArgumentException.class, () -> Limit.to(100).orIfLower(0));
		assertThrows(IllegalArgumentException.class, () -> Limit.to(100).orIfLower(-1));
		assertThrows(IllegalArgumentException.class, () -> Limit.none().orIfLower(0));
	}

	@Test
	void testOrIfLowerLimit_WhenNone_ReturnsGivenLimit ( ) {
		Limit result = Limit.none().orIfLower(Limit.to(100));
		assertEquals(Limit.to(100), result);
	}

	@Test
	void testOrIfLowerLimit_WhenCurrentIsHigher_ReturnsGivenValue ( ) {
		Limit result = Limit.to(500).orIfLower(Limit.to(100));
		assertEquals(Limit.to(100), result);
	}

	@Test
	void testOrIfLowerLimit_WhenCurrentIsLower_ReturnsCurrent ( ) {
		Limit original = Limit.to(50);
		Limit result = original.orIfLower(Limit.to(100));
		assertSame(original, result);
	}

	@Test
	void testOrIfLowerLimit_WhenEqual_ReturnsCurrent ( ) {
		Limit original = Limit.to(100);
		Limit result = original.orIfLower(Limit.to(100));
		assertSame(original, result);
	}

	@Test
	void testOrIfLowerLimit_WhenGivenIsNone_ReturnsCurrent ( ) {
		Limit original = Limit.to(50);
		Limit result = original.orIfLower(Limit.none());
		assertSame(original, result);
	}

	@Test
	void testOrIfLowerLimit_WhenBothNone_ReturnsCurrent ( ) {
		Limit original = Limit.none();
		Limit result = original.orIfLower(Limit.none());
		assertSame(original, result);
	}

}
