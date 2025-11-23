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
package org.sliceworkz.eventstore.impl;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.spi.EventStorage;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * ServiceLoader-discoverable implementation of {@link EventStoreFactory}.
 * <p>
 * This factory is automatically discovered at runtime via Java's {@link java.util.ServiceLoader} mechanism.
 * It creates {@link EventStoreImpl} instances backed by the provided {@link EventStorage} implementation.
 * <p>
 * The factory registration is configured in the {@code META-INF/services/org.sliceworkz.eventstore.EventStoreFactory}
 * file, allowing the API module to remain decoupled from the implementation module.
 *
 * <h2>Usage:</h2>
 * This class is not intended to be instantiated directly. Instead, obtain EventStore instances via:
 * <pre>{@code
 * // Obtain factory via ServiceLoader
 * EventStoreFactory factory = EventStoreFactory.get();
 *
 * // Create EventStore with desired storage backend
 * EventStorage storage = InMemoryEventStorage.newBuilder().build();
 * EventStore eventStore = factory.eventStore(storage);
 * }</pre>
 *
 * @see EventStoreFactory
 * @see EventStoreImpl
 * @see EventStorage
 */
public class EventStoreFactoryImpl implements EventStoreFactory {

	/**
	 * Creates a new EventStore instance backed by the specified storage implementation.
	 * <p>
	 * This method instantiates an {@link EventStoreImpl} with the provided storage backend,
	 * which can be any implementation of {@link EventStorage} (in-memory, PostgreSQL, etc.).
	 *
	 * @param eventStorage the storage backend for persisting and retrieving events
	 * @return a new EventStore instance using the provided storage
	 * @see EventStoreImpl
	 */
	@Override
	public EventStore eventStore(EventStorage eventStorage, MeterRegistry meterRegistry) {
		return new EventStoreImpl(eventStorage, meterRegistry);
	}

}
