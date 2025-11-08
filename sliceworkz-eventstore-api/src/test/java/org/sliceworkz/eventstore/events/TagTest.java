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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

}
