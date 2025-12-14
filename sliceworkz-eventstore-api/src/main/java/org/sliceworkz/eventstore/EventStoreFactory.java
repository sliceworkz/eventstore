/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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

import java.util.NoSuchElementException;
import java.util.ServiceLoader;

import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Factory for creating {@link EventStore} instances.
 * <p>
 * This factory uses Java's {@link ServiceLoader} mechanism to discover the EventStore implementation at runtime.
 * The implementation module (sliceworkz-eventstore-impl) provides the concrete factory implementation.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create storage backend (in-memory for development/testing)
 * EventStorage storage = InMemoryEventStorage.newBuilder().build();
 *
 * // Get EventStore instance via factory
 * EventStore eventStore = EventStoreFactory.get().eventStore(storage);
 *
 * // Or use convenience method for in-memory storage
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 * }</pre>
 *
 * @see EventStore
 * @see org.sliceworkz.eventstore.spi.EventStorage
 */
public interface EventStoreFactory {

	/**
	 * Creates an EventStore instance backed by the provided storage implementation with observability support.
	 * <p>
	 * The storage backend determines where and how events are persisted (in-memory, PostgreSQL, etc.).
	 * The meter registry enables metrics collection for monitoring event store operations such as
	 * event stream creation, append operations, and query performance.
	 *
	 * @param eventStorage the storage backend implementation
	 * @param meterRegistry the Micrometer meter registry for collecting metrics and observability data
	 * @return a new EventStore instance using the provided storage
	 * @see org.sliceworkz.eventstore.spi.EventStorage
	 * @see io.micrometer.core.instrument.MeterRegistry
	 */
	EventStore eventStore ( EventStorage eventStorage, MeterRegistry meterRegistry );

	/**
	 * Creates an EventStore instance backed by the provided storage implementation using the global meter registry.
	 * <p>
	 * This convenience method uses {@link Metrics#globalRegistry} for observability, which provides
	 * a no-op fallback if no registry has been configured globally.
	 *
	 * @param eventStorage the storage backend implementation
	 * @return a new EventStore instance using the provided storage
	 * @see #eventStore(EventStorage, MeterRegistry)
	 * @see io.micrometer.core.instrument.Metrics#globalRegistry
	 */
	default EventStore eventStore ( EventStorage eventStorage ) {
		return eventStore ( eventStorage, Metrics.globalRegistry );
	}
	
	/**
	 * Obtains the EventStoreFactory implementation using Java's ServiceLoader mechanism.
	 * <p>
	 * The factory implementation is discovered at runtime from the classpath. Ensure that
	 * the implementation module (sliceworkz-eventstore-impl) is available on the classpath.
	 *
	 * @return the EventStoreFactory implementation
	 * @throws org.sliceworkz.eventstore.spi.EventStorageException if no implementation is found
	 */
	static EventStoreFactory get ( ) {
		try {
			return ServiceLoader.load(EventStoreFactory.class).findFirst().get();
		} catch (NoSuchElementException e) {
			throw new EventStorageException("no EventStore implementation found on classpath");
		}
	}

}
