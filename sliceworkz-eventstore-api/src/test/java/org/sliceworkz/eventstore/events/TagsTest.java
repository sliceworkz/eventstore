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

	// ---------------------------------------------------------------------------------------------
	// Tags.parse(String[]) is the read path -- the PostgreSQL backend calls it on the text[] column.
	// It inherits Tag.parse's leniency towards tags written before construction was validated, and
	// adds a collapse of its own, because the result is a Set.
	// ---------------------------------------------------------------------------------------------

	@Test
	void testParseIsLenientForLegacyStoredForms ( ) {
		// none of these round-trip to what wrote them, and none of them may throw: they are what is
		// already sitting in databases written before Tag validated on construction
		assertEquals(Tag.of("k", "v"), Tag.parse("k: v "));
		assertEquals(Tag.of("k"), Tag.parse("k:"));
		assertEquals(Tag.of("a", "b:c"), Tag.parse("a:b:c"));
		assertEquals(Tag.of(null, "v"), Tag.parse(" : v "));

		Tags tags = Tags.parse("k: v ", "k2:", "a:b:c", "", "   ");
		assertEquals(3, tags.tags().size());
		assertTrue(tags.tags().contains(Tag.of("k", "v")));
		assertTrue(tags.tags().contains(Tag.of("k2")));
		assertTrue(tags.tags().contains(Tag.of("a", "b:c")));
	}

	@Test
	void testParseCollapsesLegacyFormsThatNormaliseToTheSameTag ( ) {
		// a row storing both of these comes back carrying ONE tag: the event loses a tag on read.
		// Only reachable for legacy rows -- no two tags the constructor accepts render alike.
		Tags tags = Tags.parse("k: v ", "k:v", " k :v");
		assertEquals(1, tags.tags().size());
		assertEquals(Tag.of("k", "v"), tags.tags().iterator().next());
	}

	@Test
	void testParseNullArray ( ) {
		String[] values = null;
		assertEquals(Tags.none(), Tags.parse(values));
	}

	@Test
	void testToStringsNeverCollapses ( ) {
		// what makes the stored text[] faithful: toString is injective over constructible tags, so a
		// three-tag event is stored as three array elements and read back as three tags
		Tags tags = Tags.of(Tag.of("a", "b:c"), Tag.of("customer", "123"), Tag.of("important"));
		assertEquals(3, tags.toStrings().size());
		assertEquals(tags, Tags.parse(tags.toStrings().toArray(new String[0])));
	}

	@Test
	void testKeyOnlyTagDoesNotMatchAKeyValueTag ( ) {
		// matching is exact containment, never key-prefix: a query for Tag.of("customer") does not
		// find events tagged Tag.of("customer","123"), and vice versa
		Tags onEvent = Tags.of(Tag.of("customer", "123"));
		Tags keyOnlyQuery = Tags.of(Tag.of("customer"));

		assertFalse(onEvent.containsAll(keyOnlyQuery));
		assertFalse(Tags.of(Tag.of("customer")).containsAll(onEvent));
		assertFalse(onEvent.toStrings().contains("customer"));
	}
}
