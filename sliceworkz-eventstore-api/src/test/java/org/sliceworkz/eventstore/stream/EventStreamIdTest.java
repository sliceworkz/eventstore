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
package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	
	@Test
	void testCanRead ( ) {
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
		assertTrue(customer1.canRead(customer1));
		assertFalse(customer2.canRead(customer1));
		assertFalse(customerDefault.canRead(customer1));
		assertFalse(customerDefaultExplicit.canRead(customer1));
		assertTrue(customerAnyPurpose.canRead(customer1));
		assertTrue(anyContextPurpose1.canRead(customer1));
		assertFalse(anyContextPurpose2.canRead(customer1));
		assertTrue(anyContextAnyPurpose.canRead(customer1));
		assertFalse(anyContextDefaultPurpose.canRead(customer1));
		assertTrue(anyContextAnyPurposeExplicit.canRead(customer1));
		
		assertFalse(customer2.isAnyContext() || customer2.isAnyPurpose());
		assertFalse(customer1.canRead(customer2));
		assertTrue(customer2.canRead(customer2));
		assertFalse(customerDefault.canRead(customer2));
		assertFalse(customerDefaultExplicit.canRead(customer2));
		assertTrue(customerAnyPurpose.canRead(customer2));
		assertFalse(anyContextPurpose1.canRead(customer2));
		assertTrue(anyContextPurpose2.canRead(customer2));
		assertTrue(anyContextAnyPurpose.canRead(customer2));
		assertFalse(anyContextDefaultPurpose.canRead(customer2));
		assertTrue(anyContextAnyPurposeExplicit.canRead(customer2));

		assertFalse(customerDefault.isAnyContext() || customerDefault.isAnyPurpose());
		assertFalse(customer1.canRead(customerDefault));
		assertFalse(customer2.canRead(customerDefault));
		assertTrue(customerDefault.canRead(customerDefault));
		assertTrue(customerDefaultExplicit.canRead(customerDefault));
		assertTrue(customerAnyPurpose.canRead(customerDefault));
		assertFalse(anyContextPurpose1.canRead(customerDefault));
		assertFalse(anyContextPurpose2.canRead(customerDefault));
		assertTrue(anyContextAnyPurpose.canRead(customerDefault));
		assertTrue(anyContextDefaultPurpose.canRead(customerDefault));
		assertTrue(anyContextAnyPurposeExplicit.canRead(customerDefault));

		assertFalse(customerDefaultExplicit.isAnyContext() || customerDefaultExplicit.isAnyPurpose());
		assertFalse(customer1.canRead(customerDefaultExplicit));
		assertFalse(customer2.canRead(customerDefaultExplicit));
		assertTrue(customerDefault.canRead(customerDefaultExplicit));
		assertTrue(customerDefaultExplicit.canRead(customerDefaultExplicit));
		assertTrue(customerAnyPurpose.canRead(customerDefaultExplicit));
		assertFalse(anyContextPurpose1.canRead(customerDefaultExplicit));
		assertFalse(anyContextPurpose2.canRead(customerDefaultExplicit));
		assertTrue(anyContextAnyPurpose.canRead(customerDefaultExplicit));
		assertTrue(anyContextDefaultPurpose.canRead(customerDefaultExplicit));
		assertTrue(anyContextAnyPurposeExplicit.canRead(customerDefaultExplicit));
		
		// the others are read-only as no Event appends can be done on generic/wildcard streams
		
		assertTrue(customerAnyPurpose.isAnyContext() || customerAnyPurpose.isAnyPurpose());
		assertTrue(anyContextPurpose1.isAnyContext() || anyContextPurpose1.isAnyPurpose());
		assertTrue(anyContextPurpose2.isAnyContext() || anyContextPurpose2.isAnyPurpose());
		assertTrue(anyContextAnyPurpose.isAnyContext() || anyContextAnyPurpose.isAnyPurpose());
		assertTrue(anyContextDefaultPurpose.isAnyContext() || anyContextDefaultPurpose.isAnyPurpose());
		assertTrue(anyContextAnyPurposeExplicit.isAnyContext() || anyContextAnyPurposeExplicit.isAnyPurpose());
	}

}
