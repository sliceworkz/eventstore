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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

public class TagTest {

	@Test
	void testParseNull ( ) {
		String s = null;
		Tag t = Tag.parse(s);
		assertNull(t);
	}

	@Test
	void testParseNoKey( ) {
		String s = ":value";
		Tag t = Tag.parse(s);
		assertNotNull(t);
		assertEquals(Tag.of(null, "value"), t);
	}

	@Test
	void testParseNoValue ( ) {
		String s = "key";
		Tag t = Tag.parse(s);
		assertNotNull(t);
		assertEquals(Tag.of("key"), t);
	}

	@Test
	void testParseNoValueButColon ( ) {
		String s = "key:";
		Tag t = Tag.parse(s);
		assertNotNull(t);
		assertEquals(Tag.of("key"), t);
	}

	@Test
	void testParseEmpty ( ) {
		String s = "";
		Tag t = Tag.parse(s);
		assertNull(t);
	}

	@Test
	void testParseSpaces ( ) {
		String s = "  ";
		Tag t = Tag.parse(s);
		assertNull(t);
	}

	@Test
	void testParseColonSpaces ( ) {
		String s = ":  ";
		Tag t = Tag.parse(s);
		assertNull(t);
	}

	@Test
	void testParseColon ( ) {
		String s = ":";
		Tag t = Tag.parse(s);
		assertNull(t);
	}

	@Test
	void testParseColonInSpaces ( ) {
		String s = "   :  ";
		Tag t = Tag.parse(s);
		assertNull(t);
	}
	
	@Test
	void testToStringKeyValue ( ) {
		assertEquals("key:value", Tag.of("key", "value").toString());
	}

	@Test
	void testToStringNullKey ( ) {
		assertEquals(":value", Tag.of(null, "value").toString());
	}

	@Test
	void testToStringNullValue ( ) {
		assertEquals("key", Tag.of("key").toString());
	}

	// ---------------------------------------------------------------------------------------------
	// The string form is the wire format: it is what a backend stores and what a tag query is matched
	// against, so every constructible tag has to survive parse(toString()) unchanged. These pin that
	// down, and pin down that the shapes which do not survive it are refused at construction rather
	// than at query time, where the failure is a silently empty result.
	// ---------------------------------------------------------------------------------------------

	private static void assertRoundTrips ( Tag tag ) {
		assertEquals(tag, Tag.parse(tag.toString()),
				"tag " + tag + " does not survive being rendered and parsed back");
	}

	@Test
	void testRoundTripsPlainTag ( ) {
		assertRoundTrips(Tag.of("customer", "123"));
		assertRoundTrips(Tag.of("region", "EU"));
	}

	@Test
	void testRoundTripsKeyOnlyTag ( ) {
		assertRoundTrips(Tag.of("important"));
	}

	@Test
	void testRoundTripsNullKeyTag ( ) {
		// legal, and Tag.parse(":value") keeps producing it, so it has to stay constructible
		assertRoundTrips(Tag.of(null, "value"));
	}

	@Test
	void testRoundTripsColonInValue ( ) {
		// only the FIRST colon separates, so a value may contain as many as it likes
		assertRoundTrips(Tag.of("url", "https://example.org:8443/x"));
		assertEquals(Tag.of("a", "b:c"), Tag.parse("a:b:c"));
	}

	@Test
	void testRoundTripsAwkwardButLegalCharacters ( ) {
		// whitespace and separators inside a key or value are fine -- only the ends matter
		assertRoundTrips(Tag.of("first name", "Jan Van Roey"));
		assertRoundTrips(Tag.of("path", "a/b\\c,d;e\"f'g"));
		assertRoundTrips(Tag.of("json", "{\"a\":1}"));
		assertRoundTrips(Tag.of("unicode", "élève-中文-🎉"));
		assertRoundTrips(Tag.of("multi", "line\nbreak"));
	}

	@Test
	void testColonInKeyIsRejected ( ) {
		// the defect: Tag.of("a:b","c") renders as "a:b:c" and parses back as Tag.of("a","b:c"),
		// which is a different tag -- and the very string a genuine Tag.of("a","b:c") renders to
		assertEquals("a:b:c", Tag.of("a", "b:c").toString());
		assertEquals(Tag.of("a", "b:c"), Tag.parse("a:b:c"));

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("a:b", "c"));
		assertNotNull(e.getMessage());

		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("a:b"));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("trailing:", "v"));
		assertThrows(IllegalArgumentException.class, ( ) -> new Tag(":", "v"));
	}

	@Test
	void testSurroundingWhitespaceIsRejected ( ) {
		// the defect: parse() strips, so " v " would come back as "v" -- a different tag, and one
		// whose query would silently match nothing
		assertEquals(Tag.of("k", "v"), Tag.parse("k: v "));

		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("k", " v "));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("k", "v "));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of(" k ", "v"));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("k\t", "v"));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("k", "\nv"));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("   "));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("k", "   "));
	}

	@Test
	void testEmptyKeyOrValueIsRejected ( ) {
		// the invisible one: Tag.of("k","") is stored as "k:" and read back as Tag.of("k"), so the
		// event visibly carries tag "k" while a query for Tag.of("k") -- stored form "k" -- finds nothing
		assertEquals("k", Tag.of("k").toString());
		assertEquals(Tag.of("k"), Tag.parse("k:"));

		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("k", ""));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of("", "v"));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of(""));
	}

	@Test
	void testEntirelyNullTagIsRejected ( ) {
		// renders as "", which parse() reads back as no tag at all: the tag would simply vanish
		assertNull(Tag.parse(""));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of(null, (String) null));
		assertThrows(IllegalArgumentException.class, ( ) -> Tag.of(null));
		assertThrows(IllegalArgumentException.class, ( ) -> new Tag(null, null));
	}

	@Test
	void testEveryTagParseProducesIsConstructible ( ) {
		// what keeps the read path lenient: parse() normalises legacy strings into tags the
		// constructor accepts, so reading tags written before this validation existed never throws
		List<String> legacy = List.of(
				"", "   ", ":", " : ", "k", "k:", "k: v ", " k :v", ":v", ": v ", "a:b:c", "k:v:",
				"\t k \t : \t v \t", "k:  ", "  :  ", "k:v");
		for ( String stored : legacy ) {
			Tag parsed = Tag.parse(stored);   // must not throw
			if ( parsed != null ) {
				assertRoundTrips(parsed);     // and must itself be stable from here on
			}
		}
	}

	@Test
	void testKeyOnlyTagIsNotAPrefixOfAKeyValueTag ( ) {
		// matching is exact containment, in memory and in SQL alike: a query for Tag.of("customer")
		// does not find events tagged Tag.of("customer","123")
		assertNotEquals(Tag.of("customer"), Tag.of("customer", "123"));
		assertNotEquals(Tag.of("customer").toString(), Tag.of("customer", "123").toString());
		assertFalse(Tags.of(Tag.of("customer", "123")).containsAll(Tags.of(Tag.of("customer"))));
	}

}
