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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class EventStreamIdTest {

	@Test
	void testCreation ( ) {
		EventStreamId i = EventStreamId.anyContext();
		assertNull(i.context());
		assertNull(i.purpose());
	}
	
	@Test
	void testCreationWithAndWithoutPurpose ( ) {
		EventStreamId i = EventStreamId.forContext("customer").withPurpose("42");
		assertEquals("customer", i.context());
		assertEquals("42", i.purpose());
		i = i.anyPurpose();
		assertEquals("customer", i.context());
		assertEquals(null, i.purpose());
		i = i.withPurpose("1337");
		assertEquals("customer", i.context());
		assertEquals("1337", i.purpose());
	}
	
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
	
	@Test
	void testCovers ( ) {
		EventStreamId customer1 = EventStreamId.forContext("customer").withPurpose("1");
		EventStreamId customer2 = EventStreamId.forContext("customer").withPurpose("2");
		EventStreamId customerDefault = EventStreamId.forContext("customer");
		EventStreamId customerDefaultExplicit = EventStreamId.forContext("customer").defaultPurpose();
		EventStreamId customerAnyPurpose = EventStreamId.forContext("customer").anyPurpose(); // read-only
		EventStreamId anyContextPurpose1 = EventStreamId.anyContext().withPurpose("1"); // read-only
		EventStreamId anyContextPurpose2 = EventStreamId.anyContext().withPurpose("2"); // read-only
		EventStreamId anyContextAnyPurpose = EventStreamId.anyContext(); // read-only
		EventStreamId anyContextDefaultPurpose = EventStreamId.anyContext().defaultPurpose(); // read-only
		EventStreamId anyContextAnyPurposeExplicit = EventStreamId.anyContext().anyPurpose(); // read-only
		
		assertFalse(customer1.isAnyContext() || customer1.isAnyPurpose());
		assertTrue(customer1.covers(customer1));
		assertFalse(customer2.covers(customer1));
		assertFalse(customerDefault.covers(customer1));
		assertFalse(customerDefaultExplicit.covers(customer1));
		assertTrue(customerAnyPurpose.covers(customer1));
		assertTrue(anyContextPurpose1.covers(customer1));
		assertFalse(anyContextPurpose2.covers(customer1));
		assertTrue(anyContextAnyPurpose.covers(customer1));
		assertFalse(anyContextDefaultPurpose.covers(customer1));
		assertTrue(anyContextAnyPurposeExplicit.covers(customer1));
		
		assertFalse(customer2.isAnyContext() || customer2.isAnyPurpose());
		assertFalse(customer1.covers(customer2));
		assertTrue(customer2.covers(customer2));
		assertFalse(customerDefault.covers(customer2));
		assertFalse(customerDefaultExplicit.covers(customer2));
		assertTrue(customerAnyPurpose.covers(customer2));
		assertFalse(anyContextPurpose1.covers(customer2));
		assertTrue(anyContextPurpose2.covers(customer2));
		assertTrue(anyContextAnyPurpose.covers(customer2));
		assertFalse(anyContextDefaultPurpose.covers(customer2));
		assertTrue(anyContextAnyPurposeExplicit.covers(customer2));

		assertFalse(customerDefault.isAnyContext() || customerDefault.isAnyPurpose());
		assertFalse(customer1.covers(customerDefault));
		assertFalse(customer2.covers(customerDefault));
		assertTrue(customerDefault.covers(customerDefault));
		assertTrue(customerDefaultExplicit.covers(customerDefault));
		assertTrue(customerAnyPurpose.covers(customerDefault));
		assertFalse(anyContextPurpose1.covers(customerDefault));
		assertFalse(anyContextPurpose2.covers(customerDefault));
		assertTrue(anyContextAnyPurpose.covers(customerDefault));
		assertTrue(anyContextDefaultPurpose.covers(customerDefault));
		assertTrue(anyContextAnyPurposeExplicit.covers(customerDefault));

		assertFalse(customerDefaultExplicit.isAnyContext() || customerDefaultExplicit.isAnyPurpose());
		assertFalse(customer1.covers(customerDefaultExplicit));
		assertFalse(customer2.covers(customerDefaultExplicit));
		assertTrue(customerDefault.covers(customerDefaultExplicit));
		assertTrue(customerDefaultExplicit.covers(customerDefaultExplicit));
		assertTrue(customerAnyPurpose.covers(customerDefaultExplicit));
		assertFalse(anyContextPurpose1.covers(customerDefaultExplicit));
		assertFalse(anyContextPurpose2.covers(customerDefaultExplicit));
		assertTrue(anyContextAnyPurpose.covers(customerDefaultExplicit));
		assertTrue(anyContextDefaultPurpose.covers(customerDefaultExplicit));
		assertTrue(anyContextAnyPurposeExplicit.covers(customerDefaultExplicit));
		
		// the others are read-only as no Event appends can be done on generic/wildcard streams
		
		assertTrue(customerAnyPurpose.isAnyContext() || customerAnyPurpose.isAnyPurpose());
		assertTrue(anyContextPurpose1.isAnyContext() || anyContextPurpose1.isAnyPurpose());
		assertTrue(anyContextPurpose2.isAnyContext() || anyContextPurpose2.isAnyPurpose());
		assertTrue(anyContextAnyPurpose.isAnyContext() || anyContextAnyPurpose.isAnyPurpose());
		assertTrue(anyContextDefaultPurpose.isAnyContext() || anyContextDefaultPurpose.isAnyPurpose());
		assertTrue(anyContextAnyPurposeExplicit.isAnyContext() || anyContextAnyPurposeExplicit.isAnyPurpose());
	}

}
