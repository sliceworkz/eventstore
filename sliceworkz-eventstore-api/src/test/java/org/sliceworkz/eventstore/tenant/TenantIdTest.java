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
package org.sliceworkz.eventstore.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TenantIdTest {

	@Test
	void shouldCreateTenantId ( ) {
		TenantId tenantId = TenantId.of("acme");
		assertEquals("acme", tenantId.value());
	}

	@Test
	void shouldRejectNullValue ( ) {
		assertThrows(IllegalArgumentException.class, () -> TenantId.of(null));
	}

	@Test
	void shouldRejectBlankValue ( ) {
		assertThrows(IllegalArgumentException.class, () -> TenantId.of(""));
		assertThrows(IllegalArgumentException.class, () -> TenantId.of("   "));
	}

	@Test
	void shouldHaveValueEquality ( ) {
		assertEquals(TenantId.of("acme"), TenantId.of("acme"));
		assertNotEquals(TenantId.of("acme"), TenantId.of("other"));
	}

	@Test
	void shouldReturnValueAsToString ( ) {
		assertEquals("acme", TenantId.of("acme").toString());
	}

}
