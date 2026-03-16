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
 * Strategy interface for resolving the current tenant in a multi-tenant event store.
 * <p>
 * Implementations determine the active tenant for the current operation context.
 * Common strategies include:
 * <ul>
 *   <li>ThreadLocal-based resolution (e.g., set by a servlet filter or interceptor)</li>
 *   <li>Request header-based resolution (e.g., from an HTTP X-Tenant-Id header)</li>
 *   <li>Security context-based resolution (e.g., derived from the authenticated user)</li>
 * </ul>
 *
 * <h2>ThreadLocal Example:</h2>
 * <pre>{@code
 * public class ThreadLocalTenantResolver implements TenantResolver {
 *
 *     private static final ThreadLocal<TenantId> CURRENT_TENANT = new ThreadLocal<>();
 *
 *     public static void setTenant(TenantId tenantId) {
 *         CURRENT_TENANT.set(tenantId);
 *     }
 *
 *     public static void clear() {
 *         CURRENT_TENANT.remove();
 *     }
 *
 *     @Override
 *     public TenantId resolve() {
 *         TenantId tenant = CURRENT_TENANT.get();
 *         if (tenant == null) {
 *             throw new IllegalStateException("No tenant set for current thread");
 *         }
 *         return tenant;
 *     }
 * }
 * }</pre>
 *
 * @see TenantId
 * @see MultiTenantEventStore
 */
@FunctionalInterface
public interface TenantResolver {

	/**
	 * Resolves the current tenant identifier.
	 * <p>
	 * This method is called on every event store operation to determine which
	 * tenant-specific event store should handle the request.
	 *
	 * @return the current tenant identifier (must not be null)
	 * @throws IllegalStateException if no tenant can be resolved for the current context
	 */
	TenantId resolve ( );

}
