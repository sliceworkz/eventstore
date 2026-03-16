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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

class TenantScopedEventStoreTest {

	@Test
	void shouldRejectNullDelegate ( ) {
		assertThrows(IllegalArgumentException.class, () ->
			new TenantScopedEventStore(null, TenantId.of("t1"))
		);
	}

	@Test
	void shouldRejectNullTenantId ( ) {
		assertThrows(IllegalArgumentException.class, () ->
			new TenantScopedEventStore(new CapturingEventStore(), null)
		);
	}

	@Test
	void shouldPrefixContextWithTenantId ( ) {
		CapturingEventStore delegate = new CapturingEventStore();
		TenantScopedEventStore scoped = new TenantScopedEventStore(delegate, TenantId.of("acme"));

		EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
		scoped.getEventStream(streamId);

		assertEquals("acme/customer", delegate.capturedStreamId.context());
		assertEquals("123", delegate.capturedStreamId.purpose());
	}

	@Test
	void shouldPrefixContextWithDefaultPurpose ( ) {
		CapturingEventStore delegate = new CapturingEventStore();
		TenantScopedEventStore scoped = new TenantScopedEventStore(delegate, TenantId.of("acme"));

		EventStreamId streamId = EventStreamId.forContext("order");
		scoped.getEventStream(streamId);

		assertEquals("acme/order", delegate.capturedStreamId.context());
		assertEquals("default", delegate.capturedStreamId.purpose());
	}

	@Test
	void shouldPreserveAnyPurposeWildcard ( ) {
		CapturingEventStore delegate = new CapturingEventStore();
		TenantScopedEventStore scoped = new TenantScopedEventStore(delegate, TenantId.of("acme"));

		EventStreamId streamId = EventStreamId.forContext("customer").anyPurpose();
		scoped.getEventStream(streamId);

		assertEquals("acme/customer", delegate.capturedStreamId.context());
		assertNull(delegate.capturedStreamId.purpose());
	}

	@Test
	void shouldScopeAnyContextToTenantContext ( ) {
		CapturingEventStore delegate = new CapturingEventStore();
		TenantScopedEventStore scoped = new TenantScopedEventStore(delegate, TenantId.of("acme"));

		EventStreamId streamId = EventStreamId.anyContext();
		scoped.getEventStream(streamId);

		assertEquals("acme", delegate.capturedStreamId.context());
		assertNull(delegate.capturedStreamId.purpose());
	}

	@Test
	void shouldExposeTenantId ( ) {
		TenantId tenantId = TenantId.of("acme");
		TenantScopedEventStore scoped = new TenantScopedEventStore(new CapturingEventStore(), tenantId);

		assertEquals(tenantId, scoped.tenantId());
	}

	@Test
	void shouldPassThroughEventRootClasses ( ) {
		CapturingEventStore delegate = new CapturingEventStore();
		TenantScopedEventStore scoped = new TenantScopedEventStore(delegate, TenantId.of("acme"));

		Set<Class<?>> rootClasses = Set.of(String.class);
		Set<Class<?>> historicalClasses = Set.of(Integer.class);

		scoped.getEventStream(EventStreamId.forContext("test"), rootClasses, historicalClasses);

		assertEquals(rootClasses, delegate.capturedEventRootClasses);
		assertEquals(historicalClasses, delegate.capturedHistoricalEventRootClasses);
	}

	/**
	 * Captures the arguments passed to getEventStream for assertion.
	 */
	private static class CapturingEventStore implements EventStore {

		EventStreamId capturedStreamId;
		Set<Class<?>> capturedEventRootClasses;
		Set<Class<?>> capturedHistoricalEventRootClasses;

		@Override
		public <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream (
				EventStreamId eventStreamId,
				Set<Class<?>> eventRootClasses,
				Set<Class<?>> historicalEventRootClasses ) {
			this.capturedStreamId = eventStreamId;
			this.capturedEventRootClasses = eventRootClasses;
			this.capturedHistoricalEventRootClasses = historicalEventRootClasses;
			return null;
		}

	}

}
