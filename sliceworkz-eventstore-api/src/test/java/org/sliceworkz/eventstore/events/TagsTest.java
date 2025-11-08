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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class TagsTest {

	
	@Test
	void testNone ( ) {
		Tags tags = Tags.none();
		assertNotNull(tags);
		assertEquals(0, tags.tags().size());
	}

	@Test
	void testOf ( ) {
		Tags tags = Tags.of("customer", "123");
		assertNotNull(tags);
		assertEquals(1, tags.tags().size());
		assertTrue(tags.tags().contains(Tag.parse("customer:123")));
	}
	
	@Test
	void testOfNull ( ) {
		Tag[] t = null;
		Tags tags = Tags.of(t);
		assertNotNull(tags);
		assertEquals(0, tags.tags().size());
	}

	@Test
	void testConstructorNull ( ) {
		assertThrows(IllegalArgumentException.class, ()-> new Tags(null));
	}

	@Test
	void testContainsAllWithSubset ( ) {
		Tags t1 = Tags.parse("customer:456", "order:ABC");
		Tags t2 = Tags.parse("customer:123", "customer:456", "order:ABC");
		assertTrue(t2.containsAll(t1));
		assertFalse(t1.containsAll(t2));
	}

	@Test
	void testParse ( ) {
		Tags tags = Tags.parse("customer:123", "customer:456", "order:ABC");
		assertNotNull(tags);
		assertEquals(3, tags.tags().size());
		assertTrue(tags.tags().contains(Tag.parse("customer:123")));
		assertTrue(tags.tags().contains(Tag.parse("customer:456")));
		assertTrue(tags.tags().contains(Tag.parse("order:ABC")));
		
		assertNotNull(tags.tag("order"));
		assertEquals(Tag.of("order", "ABC"), tags.tag("order").get());
	}
	
	@Test
	void testToStrings ( ) {
		String[] s = new String[]{"customer:123", "customer:456", "order:ABC"};
		Tags tags = Tags.parse(s);
		Set<String> expected = new HashSet<>(Arrays.asList(s));
		
		assertEquals(expected, tags.toStrings());
	}

	@Test
	void testToStringsNone ( ) {
		String[] s = new String[]{};
		Tags tags = Tags.parse(s);
		Set<String> expected = new HashSet<>();
		
		assertEquals(expected, tags.toStrings());
	}

	@Test
	void testDuplicate ( ) {
		Tags tags = Tags.parse("customer:123", "customer:456", "order:ABC", "customer:123", "customer:456", "order:ABC");
		assertNotNull(tags);
		assertEquals(3, tags.tags().size());
		assertTrue(tags.tags().contains(Tag.parse("customer:123")));
		assertTrue(tags.tags().contains(Tag.parse("customer:456")));
		assertTrue(tags.tags().contains(Tag.parse("order:ABC")));
	}
	
	@Test
	void testWithNullTag( ) {
		Tags tags = Tags.parse("customer:123", "customer:456", "" /* is null Tag */, "order:ABC");
		assertNotNull(tags);
		assertEquals(3, tags.tags().size());
		assertTrue(tags.tags().contains(Tag.parse("customer:123")));
		assertTrue(tags.tags().contains(Tag.parse("customer:456")));
		assertTrue(tags.tags().contains(Tag.parse("order:ABC")));
	}
}
