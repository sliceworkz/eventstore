package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class EventStreamIdTest {

	@Test
	void testToString ( ) {
		EventStreamId i = EventStreamId.anyContext();
		assertEquals("", i.toString());

		i = EventStreamId.forContext("ctx");
		assertEquals("ctx#default", i.toString());

		i = EventStreamId.forContext("ctx").anyPurpose();
		assertEquals("ctx", i.toString());

		i = EventStreamId.forContext("ctx").withPurpose("other");
		assertEquals("ctx#other", i.toString());
	}
	
}
