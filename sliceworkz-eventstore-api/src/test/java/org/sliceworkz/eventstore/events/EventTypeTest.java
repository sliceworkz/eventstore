package org.sliceworkz.eventstore.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventTypeTest.MockDomainObject.SomeMockDomainOject;

public class EventTypeTest {

	@Test
	void testSimpleNames ( ) {
		assertEquals("Object", EventType.of(Object.class).name());
		assertEquals("SomeMockDomainOject", EventType.of(SomeMockDomainOject.class).name());
	}

	public sealed interface MockDomainObject {
		record SomeMockDomainOject ( String value ) implements MockDomainObject { } 
	}
	
}
