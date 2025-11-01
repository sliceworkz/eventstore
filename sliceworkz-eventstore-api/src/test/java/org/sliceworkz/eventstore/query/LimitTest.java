package org.sliceworkz.eventstore.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

}
