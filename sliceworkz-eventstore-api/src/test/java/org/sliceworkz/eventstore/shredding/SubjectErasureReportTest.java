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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The whole-person erasure report: one per-category report per category that held keys, aggregated.
 */
public class SubjectErasureReportTest {

	private static final ErasureReason REASON = ErasureReason.of("art.17");
	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");

	@Test
	void aggregatesTheCategoryReports ( ) {
		KeyId d = KeyId.of("k-d");
		KeyId m1 = KeyId.of("k-m1");
		KeyId m2 = KeyId.of("k-m2");
		SubjectErasureReport report = new SubjectErasureReport("customer", "alice-42", REASON, List.of(
				new ErasureReport(ALICE, REASON, List.of(d), Instant.now()),
				new ErasureReport(ALICE.withCategory("marketing"), REASON, List.of(m1, m2), Instant.now())));

		assertEquals(3, report.keysShredded());
		assertEquals(Set.of(d, m1, m2), Set.copyOf(report.shreddedKeys()));
		assertEquals(List.of("default", "marketing"), report.categoriesErased());
		assertFalse(report.isNoop());
		assertTrue(report.toString().contains("alice-42"), report.toString());
		assertTrue(report.toString().contains("marketing"), report.toString());
	}

	@Test
	void anEmptyReportIsANoop ( ) {
		SubjectErasureReport report = new SubjectErasureReport("customer", "alice-42", REASON, List.of());
		assertTrue(report.isNoop());
		assertEquals(0, report.keysShredded());
		assertEquals(List.of(), report.shreddedKeys());
		assertEquals(List.of(), report.categoriesErased());
	}

	@Test
	void rejectsWhatItCannotMean ( ) {
		ErasureReport bobs = new ErasureReport(DataSubject.of("customer", "bob-77"), REASON, List.of(KeyId.of("k-b")), Instant.now());
		assertThrows(IllegalArgumentException.class, () -> new SubjectErasureReport("customer", "alice-42", REASON, List.of(bobs)),
				"a report about another subject cannot be part of this one");

		ErasureReport once = new ErasureReport(ALICE, REASON, List.of(KeyId.of("k-1")), Instant.now());
		ErasureReport twice = new ErasureReport(ALICE, REASON, List.of(KeyId.of("k-2")), Instant.now());
		assertThrows(IllegalArgumentException.class, () -> new SubjectErasureReport("customer", "alice-42", REASON, List.of(once, twice)),
				"one category, one report");

		assertThrows(IllegalArgumentException.class, () -> new SubjectErasureReport(null, "alice-42", REASON, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new SubjectErasureReport("customer", " ", REASON, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new SubjectErasureReport("customer", "alice-42", null, List.of()));
		assertThrows(IllegalArgumentException.class, () -> new SubjectErasureReport("customer", "alice-42", REASON, null));
	}

}
