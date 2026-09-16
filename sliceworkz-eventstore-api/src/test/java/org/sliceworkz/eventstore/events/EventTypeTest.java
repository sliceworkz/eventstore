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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventTypeTest.MockDomainObject.SomeMockDomainOject;

public class EventTypeTest {

	@Test
	void testSimpleNames ( ) {
		assertEquals("Object", EventType.of(Object.class).name());
		assertEquals("SomeMockDomainOject", EventType.of(SomeMockDomainOject.class).name());
	}

	@Test
	void testDeclaredNameOverridesTheSimpleName ( ) {
		assertEquals("CustomerRegistered", EventType.of(MockDomainObject.CustomerSignedUp.class).name());
		assertEquals("CustomerRegistered", EventType.of(new MockDomainObject.CustomerSignedUp("x")).name());
		// the annotation names one class: a sibling without it keeps its simple name
		assertEquals("SomeMockDomainOject", EventType.of(SomeMockDomainOject.class).name());
	}

	@Test
	void testDeclaredNameIsNotInherited ( ) {
		// the interface carries a name; its permitted record does not, and must not pick it up
		assertEquals("Renamed", EventType.of(NamedInterface.class).name());
		assertEquals("Plain", EventType.of(NamedInterface.Plain.class).name());
	}

	@Test
	void testADeclaredNameMustBeUsable ( ) {
		IllegalArgumentException blank = assertThrows(IllegalArgumentException.class, () -> EventType.of(BadNames.Blank.class));
		assertTrue(blank.getMessage().contains(BadNames.Blank.class.getName()), blank.getMessage());
		assertThrows(IllegalArgumentException.class, () -> EventType.of(BadNames.Empty.class));
		assertThrows(IllegalArgumentException.class, () -> EventType.of(BadNames.Padded.class));
		// and it fails the same way every time: a rejected name is not remembered as anything
		assertThrows(IllegalArgumentException.class, () -> EventType.of(BadNames.Blank.class));
	}

	public sealed interface MockDomainObject {
		record SomeMockDomainOject ( String value ) implements MockDomainObject { }
		@EventName("CustomerRegistered")
		record CustomerSignedUp ( String value ) implements MockDomainObject { }
	}

	@EventName("Renamed")
	public sealed interface NamedInterface {
		record Plain ( String value ) implements NamedInterface { }
	}

	public sealed interface BadNames {
		@EventName(" ")
		record Blank ( String value ) implements BadNames { }
		@EventName("")
		record Empty ( String value ) implements BadNames { }
		@EventName(" Padded")
		record Padded ( String value ) implements BadNames { }
	}
	
}
