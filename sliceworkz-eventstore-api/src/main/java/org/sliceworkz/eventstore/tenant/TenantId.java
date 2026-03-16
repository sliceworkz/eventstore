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

/**
 * Represents a tenant identifier in a multi-tenant event store configuration.
 * <p>
 * A TenantId is a simple value type wrapping a non-null, non-blank string that uniquely
 * identifies a tenant. It is used by {@link MultiTenantEventStore} to route event operations
 * to the correct tenant-specific event store.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * TenantId tenant = TenantId.of("acme-corp");
 * }</pre>
 *
 * @param value the tenant identifier string (must not be null or blank)
 * @see MultiTenantEventStore
 * @see TenantResolver
 */
public record TenantId ( String value ) {

	public TenantId {
		if ( value == null || value.isBlank() ) {
			throw new IllegalArgumentException("tenant id must not be null or blank");
		}
	}

	/**
	 * Creates a new TenantId with the specified value.
	 *
	 * @param value the tenant identifier string
	 * @return a new TenantId instance
	 * @throws IllegalArgumentException if value is null or blank
	 */
	public static TenantId of ( String value ) {
		return new TenantId(value);
	}

	@Override
	public String toString ( ) {
		return value;
	}

}
