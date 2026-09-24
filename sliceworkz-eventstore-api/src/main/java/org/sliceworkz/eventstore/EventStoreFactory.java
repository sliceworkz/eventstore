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

import java.util.NoSuchElementException;
import java.util.ServiceLoader;

import org.sliceworkz.eventstore.observability.EventStoreObserver;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;

/**
 * The SPI through which an {@link EventStore} implementation is provided.
 * <p>
 * The implementation module (sliceworkz-eventstore-impl) registers its factory for Java's
 * {@link ServiceLoader}, and {@link #get()} finds it at runtime. Application code does not call this
 * factory: it builds a store with {@link EventStore#on(EventStorage)}, which resolves the factory and
 * hands it the storage, the observer and the codec, or takes the store from a storage builder's
 * {@code buildStore()}. The methods here are what those two paths call, and what an implementation of
 * this library provides.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create storage backend (in-memory for development/testing)
 * EventStorage storage = InMemoryEventStorage.newBuilder().build();
 *
 * // Build a store on it
 * EventStore eventStore = EventStore.on(storage).build();
 *
 * // Or use convenience method for in-memory storage
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 * }</pre>
 *
 * @see EventStore#on(EventStorage)
 * @see org.sliceworkz.eventstore.spi.EventStorage
 */
public interface EventStoreFactory {

	/**
	 * Creates an EventStore on the given storage.
	 * <p>
	 * The observer is what the store reports its operations to — see {@link EventStoreObserver} — and the
	 * codec seals {@link org.sliceworkz.eventstore.shredding.Shreddable} values on append, unseals them on
	 * read, and destroys the keys behind them on
	 * {@link EventStore#erase(String, String, org.sliceworkz.eventstore.shredding.ErasureReason) erase}.
	 * Either may be {@code null}, meaning the one the storage was configured with
	 * ({@link EventStorage#observer()}, {@link EventStorage#shreddingCodec()}), so a storage builder's
	 * {@code .observer(...)} and {@code .shredding(...)} reach a store built through this factory as much
	 * as one from the builder's {@code buildStore()}. A store with no codec at all refuses to register an
	 * event type declaring a {@code Shreddable} component, rather than storing it in the clear.
	 *
	 * @param eventStorage the storage backend implementation
	 * @param observer what the store reports to, or null for the storage's own
	 * @param shreddingCodec protects personal data in event payloads, or null for the storage's own, if any
	 * @return a new EventStore instance using the provided storage
	 * @see EventStore#on(EventStorage)
	 */
	EventStore eventStore ( EventStorage eventStorage, EventStoreObserver observer, ShreddingCodec shreddingCodec );

	/**
	 * Creates an EventStore on the given storage, with the observer and codec the storage was configured with.
	 *
	 * @param eventStorage the storage backend implementation
	 * @return a new EventStore instance using the provided storage
	 * @see #eventStore(EventStorage, EventStoreObserver, ShreddingCodec)
	 */
	default EventStore eventStore ( EventStorage eventStorage ) {
		return eventStore ( eventStorage, null, null );
	}

	/**
	 * Obtains the EventStoreFactory implementation using Java's ServiceLoader mechanism.
	 * <p>
	 * The factory implementation is discovered at runtime from the classpath. Ensure that
	 * the implementation module (sliceworkz-eventstore-impl) is available on the classpath.
	 * {@link EventStore#on(EventStorage)} calls this for you.
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
