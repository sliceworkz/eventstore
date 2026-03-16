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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

class MultiTenantEventStoreTest {

	@Test
	void shouldRejectMissingTenantResolver ( ) {
		assertThrows(IllegalArgumentException.class, () ->
			MultiTenantEventStore.newBuilder()
				.tenantEventStoreFactory(id -> new StubEventStore())
				.build()
		);
	}

	@Test
	void shouldRejectMissingFactory ( ) {
		assertThrows(IllegalArgumentException.class, () ->
			MultiTenantEventStore.newBuilder()
				.tenantResolver(() -> TenantId.of("t1"))
				.build()
		);
	}

	@Test
	void shouldRouteToCorrectTenantEventStore ( ) {
		Map<TenantId, EventStore> stores = new ConcurrentHashMap<>();
		AtomicReference<TenantId> currentTenant = new AtomicReference<>(TenantId.of("tenant-a"));

		MultiTenantEventStore multiTenantStore = MultiTenantEventStore.newBuilder()
			.tenantResolver(currentTenant::get)
			.tenantEventStoreFactory(id -> {
				EventStore store = new StubEventStore();
				stores.put(id, store);
				return store;
			})
			.build();

		EventStreamId streamId = EventStreamId.forContext("test");

		// Access for tenant-a
		multiTenantStore.getEventStream(streamId);
		EventStore tenantAStore = stores.get(TenantId.of("tenant-a"));

		// Switch to tenant-b
		currentTenant.set(TenantId.of("tenant-b"));
		multiTenantStore.getEventStream(streamId);
		EventStore tenantBStore = stores.get(TenantId.of("tenant-b"));

		assertNotSame(tenantAStore, tenantBStore);
		assertEquals(2, stores.size());
	}

	@Test
	void shouldCacheTenantEventStores ( ) {
		AtomicReference<TenantId> currentTenant = new AtomicReference<>(TenantId.of("tenant-a"));
		int[] factoryCallCount = {0};

		MultiTenantEventStore multiTenantStore = MultiTenantEventStore.newBuilder()
			.tenantResolver(currentTenant::get)
			.tenantEventStoreFactory(id -> {
				factoryCallCount[0]++;
				return new StubEventStore();
			})
			.build();

		EventStreamId streamId = EventStreamId.forContext("test");

		multiTenantStore.getEventStream(streamId);
		multiTenantStore.getEventStream(streamId);
		multiTenantStore.getEventStream(streamId);

		assertEquals(1, factoryCallCount[0]);
	}

	@Test
	void shouldThrowWhenTenantResolverReturnsNull ( ) {
		MultiTenantEventStore multiTenantStore = MultiTenantEventStore.newBuilder()
			.tenantResolver(() -> null)
			.tenantEventStoreFactory(id -> new StubEventStore())
			.build();

		EventStreamId streamId = EventStreamId.forContext("test");

		IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
			multiTenantStore.getEventStream(streamId)
		);
		assertEquals("TenantResolver returned null — cannot determine the current tenant", ex.getMessage());
	}

	@Test
	void shouldAccessTenantDirectlyViaForTenant ( ) {
		MultiTenantEventStore multiTenantStore = MultiTenantEventStore.newBuilder()
			.tenantResolver(() -> TenantId.of("irrelevant"))
			.tenantEventStoreFactory(id -> new StubEventStore())
			.build();

		EventStore storeA = multiTenantStore.forTenant(TenantId.of("direct-tenant"));
		EventStore storeA2 = multiTenantStore.forTenant(TenantId.of("direct-tenant"));

		assertSame(storeA, storeA2);
	}

	/**
	 * Minimal stub EventStore for testing routing logic without requiring the impl module.
	 */
	private static class StubEventStore implements EventStore {
		@Override
		public <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream (
				EventStreamId eventStreamId,
				Set<Class<?>> eventRootClasses,
				Set<Class<?>> historicalEventRootClasses ) {
			return null; // stub - routing tests don't need actual streams
		}
	}

}
