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
package org.sliceworkz.eventstore.impl;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.observability.EventStoreObserver;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.spi.EventStorage;

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
 * This class is not intended to be instantiated directly, nor called directly. Obtain EventStore
 * instances via the builder on {@link EventStore}, which finds this factory through the ServiceLoader:
 * <pre>{@code
 * EventStorage storage = InMemoryEventStorage.newBuilder().build();
 * EventStore eventStore = EventStore.on(storage).build();
 * }</pre>
 *
 * @see EventStoreFactory
 * @see EventStoreImpl
 * @see EventStorage
 */
public class EventStoreFactoryImpl implements EventStoreFactory {

	/**
	 * Creates an {@link EventStoreImpl} on the given storage.
	 *
	 * @param eventStorage the storage backend for persisting and retrieving events
	 * @param observer what the store reports to, or null for the storage's own
	 * @param shreddingCodec seals and unseals {@link org.sliceworkz.eventstore.shredding.Shreddable}
	 *                       values, or null for the storage's own, if any
	 * @return a new EventStore instance
	 */
	@Override
	public EventStore eventStore ( EventStorage eventStorage, EventStoreObserver observer, ShreddingCodec shreddingCodec ) {
		return new EventStoreImpl(eventStorage, observer, shreddingCodec);
	}

}
