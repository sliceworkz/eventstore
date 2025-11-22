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

import java.util.ServiceLoader;

import org.sliceworkz.eventstore.spi.EventStorage;

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
	 * Creates an EventStore instance backed by the provided storage implementation.
	 * <p>
	 * The storage backend determines where and how events are persisted (in-memory, PostgreSQL, etc.).
	 *
	 * @param eventStorage the storage backend implementation
	 * @return a new EventStore instance using the provided storage
	 * @see org.sliceworkz.eventstore.spi.EventStorage
	 */
	EventStore eventStore ( EventStorage eventStorage );

	/**
	 * Obtains the EventStoreFactory implementation using Java's ServiceLoader mechanism.
	 * <p>
	 * The factory implementation is discovered at runtime from the classpath. Ensure that
	 * the implementation module (sliceworkz-eventstore-impl) is available on the classpath.
	 *
	 * @return the EventStoreFactory implementation
	 * @throws java.util.NoSuchElementException if no implementation is found
	 */
	static EventStoreFactory get ( ) {
		return ServiceLoader.load(EventStoreFactory.class).findFirst().get();
	}

}
