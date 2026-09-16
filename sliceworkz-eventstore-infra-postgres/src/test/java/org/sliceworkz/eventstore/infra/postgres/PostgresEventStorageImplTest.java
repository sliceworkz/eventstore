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
package org.sliceworkz.eventstore.infra.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

	@Test
	void testPrefixIsReturnedAsGivenWhenAlreadyLowercase ( ) {
		assertEquals("tenant1_", PostgresEventStorageImpl.validatePrefix("tenant1_"));
		assertEquals("", PostgresEventStorageImpl.validatePrefix(""));
	}

	/**
	 * PostgreSQL folds the unquoted identifier the prefix is used as, so the prefix is folded the same
	 * way: what comes back is the name the catalog holds, and every place the prefix is used as a
	 * string (the bound table name of validation, the trigger guard, the channel literal) agrees with
	 * every place it is used as an identifier.
	 */
	@Test
	void testPrefixWithUppercaseLettersIsFoldedToLowercase ( ) {
		assertEquals("tenant_", PostgresEventStorageImpl.validatePrefix("Tenant_"));
		assertEquals("tenant_", PostgresEventStorageImpl.validatePrefix("TENANT_"));
		assertEquals("acme_tenant1_", PostgresEventStorageImpl.validatePrefix("Acme_Tenant1_"));
	}

	/** {@code 1tenant_events} is not an identifier PostgreSQL parses unquoted, so it is refused here. */
	@Test
	void testPrefixStartingWithADigitIsRejected ( ) {
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix("1tenant_"));
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix("1_"));
	}

	@Test
	void testPrefixMayStartWithAnUnderscoreOrContainDigits ( ) {
		assertEquals("_tenant_", PostgresEventStorageImpl.validatePrefix("_tenant_"));
		assertEquals("t1_", PostgresEventStorageImpl.validatePrefix("t1_"));
	}

	@Test
	void testPrefixTooLongIsRejected ( ) {
		assertThrows(IllegalArgumentException.class, ()->PostgresEventStorageImpl.validatePrefix("a".repeat(32) + "_"));
		assertEquals("a".repeat(31) + "_", PostgresEventStorageImpl.validatePrefix("A".repeat(31) + "_"));
	}
	
}
