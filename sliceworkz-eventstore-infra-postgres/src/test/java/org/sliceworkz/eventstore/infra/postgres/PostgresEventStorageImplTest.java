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
package org.sliceworkz.eventstore.infra.postgres;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class PostgresEventStorageImplTest {
	
	@Test
	void testPrefixNull ( ) {
		String prefix = null;
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}

	@Test
	void testPrefixCorrect ( ) {
		String prefix = "tenant_";
		PostgresEventStorageImpl.validatePrefix(prefix);
	}

	@Test
	void testPrefixEmpty ( ) {
		String prefix = "";
		PostgresEventStorageImpl.validatePrefix(prefix);
	}

	@Test
	void testPrefixSpace( ) {
		String prefix = " ";
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}

	@Test
	void testPrefixLetter( ) {
		String prefix = "a";
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}

	@Test
	void testPrefixNoUnderscore( ) {
		String prefix = "tenant";
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}

	@Test
	void testPrefixUnderscore ( ) {
		String prefix = "_";
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix(prefix));
	}
	
}
