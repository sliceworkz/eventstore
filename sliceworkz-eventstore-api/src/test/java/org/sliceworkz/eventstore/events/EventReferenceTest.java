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
