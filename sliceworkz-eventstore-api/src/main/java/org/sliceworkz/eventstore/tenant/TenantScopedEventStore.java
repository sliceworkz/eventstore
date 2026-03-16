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

import java.util.Set;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * An {@link EventStore} decorator that scopes all stream operations to a specific tenant
 * by prefixing stream contexts with the tenant ID.
 * <p>
 * This enables logical multi-tenancy within a single shared {@link EventStore} and storage backend.
 * A stream with context {@code "customer"} for tenant {@code "acme"} becomes {@code "acme/customer"}.
 * <p>
 * This class implements <strong>Strategy 4</strong> from {@link MultiTenantEventStore}: stream context
 * prefixing with a shared storage backend. It provides the lightest-weight isolation — no separate
 * connections, schemas, or tables per tenant — at the cost of weaker physical isolation compared
 * to database- or schema-level strategies.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * EventStore sharedStore = InMemoryEventStorage.newBuilder().buildStore();
 * MultiTenantEventStore.newBuilder()
 *     .tenantResolver(myTenantResolver)
 *     .tenantEventStoreFactory(tenantId -> new TenantScopedEventStore(sharedStore, tenantId))
 *     .build();
 * }</pre>
 *
 * @see MultiTenantEventStore
 * @see TenantId
 */
public class TenantScopedEventStore implements EventStore {

	private final EventStore delegate;
	private final TenantId tenantId;

	/**
	 * Creates a new tenant-scoped event store.
	 *
	 * @param delegate the shared event store to delegate to
	 * @param tenantId the tenant whose streams this store scopes to
	 * @throws IllegalArgumentException if delegate or tenantId is null
	 */
	public TenantScopedEventStore ( EventStore delegate, TenantId tenantId ) {
		if ( delegate == null ) {
			throw new IllegalArgumentException("delegate must not be null");
		}
		if ( tenantId == null ) {
			throw new IllegalArgumentException("tenantId must not be null");
		}
		this.delegate = delegate;
		this.tenantId = tenantId;
	}

	@Override
	public <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses ) {
		return delegate.getEventStream(scopeStreamId(eventStreamId), eventRootClasses, historicalEventRootClasses);
	}

	/**
	 * Returns the tenant ID this event store is scoped to.
	 *
	 * @return the tenant ID
	 */
	public TenantId tenantId ( ) {
		return tenantId;
	}

	private EventStreamId scopeStreamId ( EventStreamId eventStreamId ) {
		if ( eventStreamId.isAnyContext() ) {
			return new EventStreamId(tenantId.value(), null);
		}
		String scopedContext = tenantId.value() + "/" + eventStreamId.context();
		return new EventStreamId(scopedContext, eventStreamId.purpose());
	}

}
