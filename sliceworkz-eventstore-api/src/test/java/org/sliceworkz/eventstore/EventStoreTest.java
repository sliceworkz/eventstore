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
package org.sliceworkz.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.spi.EventStorage.EventStoreListener;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * What the api module can say about building a store with no implementation on the classpath: the
 * factory lookup fails by name, the builder fails the same way from {@code build()} and not before,
 * and the builder's own argument checks need no implementation to run. What a built store does with
 * its arguments is pinned in the tests module ({@code EventStoreBuilderTest}), where one exists.
 */
public class EventStoreTest {

	@Test
	void testWithNoImplOnClasspath ( ) {
		EventStorageException e = assertThrows(EventStorageException.class, ()->EventStoreFactory.get());
		assertEquals("no EventStore implementation found on classpath", e.getMessage());
	}

	@Test
	void theBuilderFailsAtBuildWithNoImplOnClasspath ( ) {
		// the lookup is build()'s, not on(): a builder is configured before anything is resolved
		EventStore.Builder builder = EventStore.on(new StubStorage())
				.meterRegistry(new SimpleMeterRegistry())
				.meterOptions(MeterOptions.withoutPurposeBreakdown())
				.shredding(ShreddingCodec.withholdingAll());
		EventStorageException e = assertThrows(EventStorageException.class, builder::build);
		assertEquals("no EventStore implementation found on classpath", e.getMessage());
	}

	@Test
	void theBuilderRefusesANullStorage ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventStore.on(null));
		assertEquals("eventStorage cannot be null", e.getMessage());
	}

	@Test
	void everySetterRefusesNullAndNamesTheDefaultItWouldOtherwiseKeep ( ) {
		EventStore.Builder builder = EventStore.on(new StubStorage());

		IllegalArgumentException registry = assertThrows(IllegalArgumentException.class, () -> builder.meterRegistry(null));
		assertEquals("meterRegistry cannot be null.  Leave it unset for Metrics.globalRegistry", registry.getMessage());

		IllegalArgumentException options = assertThrows(IllegalArgumentException.class, () -> builder.meterOptions(null));
		assertEquals("meterOptions cannot be null.  Leave it unset for MeterOptions.defaults()", options.getMessage());

		IllegalArgumentException codec = assertThrows(IllegalArgumentException.class, () -> builder.shredding(null));
		assertEquals("shreddingCodec cannot be null.  Leave it unset for the storage's own codec", codec.getMessage());
	}

	@Test
	void theSettersChain ( ) {
		EventStore.Builder builder = EventStore.on(new StubStorage());
		assertSame(builder, builder.meterRegistry(new SimpleMeterRegistry()));
		assertSame(builder, builder.meterOptions(MeterOptions.defaults()));
		assertSame(builder, builder.shredding(ShreddingCodec.withholdingAll()));
	}

	/** The least an {@link EventStorage} can be; nothing here is ever called. */
	private static final class StubStorage implements EventStorage {

		@Override
		public String name ( ) {
			return "stub";
		}

		@Override
		public List<StoredEvent> query ( EventFilter filter, EventStreamId stream, EventReference after, Limit limit, QueryDirection queryDirection ) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<StoredEvent> append ( AppendCriteria appendCriteria, EventStreamId stream, List<EventToStore> events ) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<StoredEvent> getEventById ( EventId eventId ) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void subscribe ( EventStoreListener listener ) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<EventReference> getBookmark ( String reader ) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void bookmark ( String reader, EventReference eventReference, Tags tags ) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void removeBookmark ( String reader ) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<Bookmark> getBookmarks ( ) {
			throw new UnsupportedOperationException();
		}
	}

}
