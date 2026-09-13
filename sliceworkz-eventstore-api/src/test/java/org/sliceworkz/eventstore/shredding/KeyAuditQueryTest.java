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
package org.sliceworkz.eventstore.shredding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The shape of a key audit query: what narrows it, what it refuses, and that a query built the way
 * queries were built before keys could be named still means the same thing.
 */
class KeyAuditQueryTest {

	private static final KeyId K1 = KeyId.of("k-1");
	private static final KeyId K2 = KeyId.of("k-2");

	@Test
	void forKeysNamesTheKeysAndBoundsTheAnswerAtTheirCount ( ) {
		KeyAuditQuery query = KeyAuditQuery.forKeys(Set.of(K1, K2));

		assertEquals(Set.of(K1, K2), query.keys());
		assertNull(query.subjectType());
		assertNull(query.subjectId());
		assertNull(query.category());
		assertTrue(!query.shreddedOnly());
		// a key id names at most one record, so asking about n keys wants n answers, not the default 500
		assertEquals(2, query.limit());

		assertEquals(query, KeyAuditQuery.forKey(K1).withKeys(Set.of(K1, K2)).withLimit(2));
	}

	@Test
	void theKeyFilterNarrowsTheOtherPartsInsteadOfReplacingThem ( ) {
		KeyAuditQuery query = KeyAuditQuery.forSubject("customer", "alice-42").withCategory("marketing").onlyShredded().withKeys(Set.of(K1));

		assertEquals("customer", query.subjectType());
		assertEquals("alice-42", query.subjectId());
		assertEquals("marketing", query.category());
		assertTrue(query.shreddedOnly());
		assertEquals(Set.of(K1), query.keys());
		assertEquals(KeyAuditQuery.DEFAULT_LIMIT, query.limit());

		// and every other narrowing keeps the key filter
		assertEquals(Set.of(K1), query.withCategory("default").keys());
		assertEquals(Set.of(K1), query.withLimit(7).keys());
		assertEquals(Set.of(K1), query.onlyShredded().keys());
	}

	@Test
	void anEmptyOrNullBearingKeySetIsRefusedRatherThanMatchingNothing ( ) {
		assertThrows(IllegalArgumentException.class, () -> KeyAuditQuery.forKeys(Set.of()));
		assertThrows(IllegalArgumentException.class, () -> KeyAuditQuery.forKeys(null));
		assertThrows(IllegalArgumentException.class, () -> KeyAuditQuery.forKey(null));
		assertThrows(IllegalArgumentException.class, () -> KeyAuditQuery.all().withKeys(Set.of()));
		assertThrows(IllegalArgumentException.class, () -> KeyAuditQuery.all().withKeys(null));

		Set<KeyId> withNull = new HashSet<>();
		withNull.add(K1);
		withNull.add(null);
		assertThrows(IllegalArgumentException.class, () -> KeyAuditQuery.forKeys(withNull));
	}

	@Test
	void theKeySetIsCopiedSoALaterChangeToTheCallersSetDoesNotReachTheQuery ( ) {
		Set<KeyId> keys = new HashSet<>(Set.of(K1));
		KeyAuditQuery query = KeyAuditQuery.forKeys(keys);
		keys.add(K2);

		assertEquals(Set.of(K1), query.keys());
		assertThrows(UnsupportedOperationException.class, () -> query.keys().add(K2));
	}

	@Test
	void aQueryBuiltWithoutAKeyFilterMeansEveryKey ( ) {
		KeyAuditQuery fiveParts = new KeyAuditQuery("customer", "alice-42", "marketing", true, 10);

		assertNull(fiveParts.keys());
		assertEquals(new KeyAuditQuery("customer", "alice-42", "marketing", null, true, 10), fiveParts);
		assertNull(KeyAuditQuery.all().keys());
		assertNull(KeyAuditQuery.forSubject(DataSubject.of("customer", "alice-42")).keys());
	}

	@Test
	void theOlderRefusalsStillHold ( ) {
		assertThrows(IllegalArgumentException.class, () -> KeyAuditQuery.all().withLimit(0));
		assertThrows(IllegalArgumentException.class, () -> new KeyAuditQuery(null, "alice-42", null, null, false, 1));
	}

}
