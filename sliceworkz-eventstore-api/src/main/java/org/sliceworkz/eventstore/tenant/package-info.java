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
/**
 * Multi-tenancy support for the EventStore.
 * <p>
 * This package provides abstractions for using a single {@link org.sliceworkz.eventstore.EventStore}
 * entry point in a multi-tenant application. The tenant isolation strategy is pluggable and can be
 * configured to use separate databases, schemas, table prefixes, or stream context prefixing.
 *
 * @see org.sliceworkz.eventstore.tenant.TenantId
 * @see org.sliceworkz.eventstore.tenant.TenantResolver
 * @see org.sliceworkz.eventstore.tenant.MultiTenantEventStore
 */
package org.sliceworkz.eventstore.tenant;
