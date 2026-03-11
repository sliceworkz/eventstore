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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A multi-tenant {@link EventStore} decorator that routes all operations to tenant-specific
 * event store instances based on the currently resolved tenant.
 * <p>
 * This implementation uses a {@link TenantResolver} to determine the active tenant for each
 * operation, and a {@link Function} to obtain (or create) the corresponding tenant-specific
 * {@link EventStore}. Tenant event stores are cached in a thread-safe map to avoid repeated
 * creation.
 * <p>
 * The tenant-to-event-store mapping is fully flexible. Depending on the factory function provided,
 * multi-tenancy can be achieved through different isolation strategies:
 *
 * <h2>Strategy 1: Table prefix isolation (same database, different tables per tenant)</h2>
 * <pre>{@code
 * MultiTenantEventStore.newBuilder()
 *     .tenantResolver(myTenantResolver)
 *     .tenantEventStoreFactory(tenantId -> PostgresEventStorage.newBuilder()
 *         .dataSource(sharedDataSource)
 *         .prefix(tenantId + "_")
 *         .ensureDatabase()
 *         .buildStore())
 *     .build();
 * }</pre>
 *
 * <h2>Strategy 2: Schema isolation (different PostgreSQL schemas per tenant)</h2>
 * <pre>{@code
 * MultiTenantEventStore.newBuilder()
 *     .tenantResolver(myTenantResolver)
 *     .tenantEventStoreFactory(tenantId -> {
 *         DataSource tenantDs = createDataSourceForSchema(tenantId.value());
 *         return PostgresEventStorage.newBuilder()
 *             .dataSource(tenantDs)
 *             .ensureDatabase()
 *             .buildStore();
 *     })
 *     .build();
 * }</pre>
 *
 * <h2>Strategy 3: Database isolation (separate database per tenant)</h2>
 * <pre>{@code
 * MultiTenantEventStore.newBuilder()
 *     .tenantResolver(myTenantResolver)
 *     .tenantEventStoreFactory(tenantId -> {
 *         DataSource tenantDs = createDataSourceForDatabase(tenantId.value());
 *         return PostgresEventStorage.newBuilder()
 *             .dataSource(tenantDs)
 *             .ensureDatabase()
 *             .buildStore();
 *     })
 *     .build();
 * }</pre>
 *
 * <h2>Strategy 4: Stream context prefixing (shared storage, tenant ID in stream context)</h2>
 * <pre>{@code
 * // Use a shared EventStore with tenant-scoped stream IDs
 * EventStore sharedStore = InMemoryEventStorage.newBuilder().buildStore();
 * MultiTenantEventStore.newBuilder()
 *     .tenantResolver(myTenantResolver)
 *     .tenantEventStoreFactory(tenantId -> new TenantScopedEventStore(sharedStore, tenantId))
 *     .build();
 * }</pre>
 *
 * @see TenantId
 * @see TenantResolver
 * @see EventStore
 */
public class MultiTenantEventStore implements EventStore {

	private final TenantResolver tenantResolver;
	private final Function<TenantId, EventStore> tenantEventStoreFactory;
	private final ConcurrentMap<TenantId, EventStore> tenantStores = new ConcurrentHashMap<>();

	private MultiTenantEventStore ( TenantResolver tenantResolver, Function<TenantId, EventStore> tenantEventStoreFactory ) {
		this.tenantResolver = tenantResolver;
		this.tenantEventStoreFactory = tenantEventStoreFactory;
	}

	/**
	 * Creates a new builder for configuring a multi-tenant event store.
	 *
	 * @return a new Builder instance
	 */
	public static Builder newBuilder ( ) {
		return new Builder();
	}

	@Override
	public <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses ) {
		TenantId tenantId = tenantResolver.resolve();
		if ( tenantId == null ) {
			throw new IllegalStateException("TenantResolver returned null — cannot determine the current tenant");
		}
		EventStore tenantStore = tenantStores.computeIfAbsent(tenantId, tenantEventStoreFactory);
		return tenantStore.getEventStream(eventStreamId, eventRootClasses, historicalEventRootClasses);
	}

	/**
	 * Returns the event store for a specific tenant, creating it if necessary.
	 * <p>
	 * This method provides direct access to a tenant's event store without relying on
	 * the {@link TenantResolver}. Useful for administrative operations or batch processing.
	 *
	 * @param tenantId the tenant to get the event store for
	 * @return the event store for the specified tenant
	 */
	public EventStore forTenant ( TenantId tenantId ) {
		return tenantStores.computeIfAbsent(tenantId, tenantEventStoreFactory);
	}

	/**
	 * Builder for creating {@link MultiTenantEventStore} instances.
	 */
	public static class Builder {

		private TenantResolver tenantResolver;
		private Function<TenantId, EventStore> tenantEventStoreFactory;

		private Builder ( ) {
		}

		/**
		 * Sets the tenant resolver that determines the current tenant for each operation.
		 *
		 * @param tenantResolver the tenant resolver strategy
		 * @return this Builder for method chaining
		 */
		public Builder tenantResolver ( TenantResolver tenantResolver ) {
			this.tenantResolver = tenantResolver;
			return this;
		}

		/**
		 * Sets the factory function that creates or retrieves the event store for a given tenant.
		 * <p>
		 * The factory is called at most once per tenant (results are cached). The function receives
		 * a {@link TenantId} and must return a fully configured {@link EventStore} for that tenant.
		 *
		 * @param factory the function that maps a TenantId to an EventStore
		 * @return this Builder for method chaining
		 */
		public Builder tenantEventStoreFactory ( Function<TenantId, EventStore> factory ) {
			this.tenantEventStoreFactory = factory;
			return this;
		}

		/**
		 * Builds the multi-tenant event store.
		 *
		 * @return a new MultiTenantEventStore instance
		 * @throws IllegalArgumentException if tenantResolver or tenantEventStoreFactory is not set
		 */
		public MultiTenantEventStore build ( ) {
			if ( tenantResolver == null ) {
				throw new IllegalArgumentException("tenantResolver must be set");
			}
			if ( tenantEventStoreFactory == null ) {
				throw new IllegalArgumentException("tenantEventStoreFactory must be set");
			}
			return new MultiTenantEventStore(tenantResolver, tenantEventStoreFactory);
		}

	}

}
