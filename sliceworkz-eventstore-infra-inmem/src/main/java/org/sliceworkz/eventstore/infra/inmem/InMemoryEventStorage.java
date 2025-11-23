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
package org.sliceworkz.eventstore.infra.inmem;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Factory interface for creating in-memory event storage instances for development and testing purposes.
 * <p>
 * InMemoryEventStorage provides a non-persistent, thread-safe implementation of the {@link EventStorage} interface
 * that stores all events in memory. This implementation is ideal for development, testing, and prototyping but
 * should NOT be used in production environments as all data is lost when the application restarts.
 * <p>
 * This storage implementation supports all features of the EventStore API including:
 * <ul>
 *   <li>Event querying with filters and limits</li>
 *   <li>Optimistic locking via {@link org.sliceworkz.eventstore.stream.AppendCriteria}</li>
 *   <li>Event subscriptions and listeners</li>
 *   <li>Bookmarking for event processing</li>
 *   <li>Tag-based event retrieval for Dynamic Consistency Boundaries (DCB)</li>
 * </ul>
 * <p>
 * The in-memory storage uses a simple list-based structure with synchronization to ensure thread safety.
 * All events are stored in append-only order with immutable references, making it a faithful implementation
 * of event sourcing principles.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Simple creation with default settings
 * EventStorage storage = InMemoryEventStorage.newBuilder().build();
 * EventStore eventStore = EventStoreFactory.get().eventStore(storage);
 *
 * // Convenience method to get EventStore directly
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 *
 * // With result limit for query protection
 * EventStore eventStore = InMemoryEventStorage.newBuilder()
 *     .resultLimit(1000)
 *     .buildStore();
 *
 * // Using the event store
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * stream.append(
 *     AppendCriteria.none(),
 *     Event.of(new CustomerRegistered("John"), Tags.of("region", "EU"))
 * );
 *
 * List<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll()).toList();
 * }</pre>
 *
 * <h2>Thread Safety:</h2>
 * The in-memory storage implementation is fully thread-safe. All append and query operations are synchronized
 * to ensure consistency, especially for optimistic locking scenarios where queries and appends must be atomic.
 *
 * <h2>Limitations:</h2>
 * <ul>
 *   <li>Data is not persisted - all events are lost on application restart</li>
 *   <li>Not suitable for production use</li>
 *   <li>Memory consumption grows with event count (no cleanup mechanism)</li>
 *   <li>Performance may degrade with very large event logs (millions of events)</li>
 * </ul>
 *
 * @see EventStorage
 * @see EventStore
 * @see EventStoreFactory
 * @see InMemoryEventStorageImpl
 */
public interface InMemoryEventStorage {

	/**
	 * Creates a new builder instance for configuring and creating an in-memory event storage.
	 * <p>
	 * This is the entry point for creating InMemoryEventStorage instances. The builder allows
	 * configuring various options before building the storage implementation.
	 *
	 * @return a new Builder instance with default settings
	 * @see Builder
	 */
	public static Builder newBuilder ( ) {
		return InMemoryEventStorage.Builder.newBuilder();
	}

	/**
	 * Builder for creating and configuring InMemoryEventStorage instances.
	 * <p>
	 * The Builder provides a fluent API for configuring the in-memory storage before instantiation.
	 * Currently supported configuration options include:
	 * <ul>
	 *   <li>Result limits for query protection</li>
	 * </ul>
	 * <p>
	 * The builder offers two methods for obtaining the final product:
	 * <ul>
	 *   <li>{@link #build()} - Returns the raw {@link EventStorage} implementation</li>
	 *   <li>{@link #buildStore()} - Convenience method that returns a fully configured {@link EventStore}</li>
	 * </ul>
	 *
	 * <h2>Example Usage:</h2>
	 * <pre>{@code
	 * // Build with default settings
	 * EventStore store = InMemoryEventStorage.newBuilder().buildStore();
	 *
	 * // Build with query result limit
	 * EventStore store = InMemoryEventStorage.newBuilder()
	 *     .resultLimit(1000)
	 *     .buildStore();
	 *
	 * // Build EventStorage directly (advanced usage)
	 * EventStorage storage = InMemoryEventStorage.newBuilder()
	 *     .resultLimit(500)
	 *     .build();
	 * EventStore store = EventStoreFactory.get().eventStore(storage);
	 * }</pre>
	 *
	 * @see InMemoryEventStorage
	 * @see EventStorage
	 * @see EventStore
	 */
	public static class Builder {
		
		private Limit limit = Limit.none();
		private MeterRegistry meterRegistry = Metrics.globalRegistry;
		
		private Builder ( ) {

		}

		/**
		 * Configures an absolute limit on the number of results that can be returned by any query.
		 * <p>
		 * This setting provides protection against unbounded queries that could consume excessive memory.
		 * When set, any query that would return more than this number of events will throw an
		 * {@link org.sliceworkz.eventstore.spi.EventStorageException}.
		 * <p>
		 * The absolute limit acts as a hard ceiling - even if a query specifies a higher limit via
		 * {@link Limit}, the absolute limit will take precedence.
		 *
		 * @param absoluteLimit the maximum number of events any query can return (must be positive)
		 * @return this Builder instance for method chaining
		 * @throws IllegalArgumentException if absoluteLimit is negative or zero
		 * @see Limit
		 */
		public Builder resultLimit ( int absoluteLimit ) {
			this.limit = Limit.to(absoluteLimit);
			return this;
		}

		public Builder meterRegistry ( MeterRegistry meterRegistry ) {
			this.meterRegistry = meterRegistry;
			return this;
		}
		
		/**
		 * Creates a new builder instance for configuring an in-memory event storage.
		 * <p>
		 * This factory method is the entry point for the builder pattern. It creates a new
		 * Builder with default settings that can be further configured before calling
		 * {@link #build()} or {@link #buildStore()}.
		 *
		 * @return a new Builder instance with default settings
		 */
		public static Builder newBuilder ( ) {
			return new Builder();
		}

		/**
		 * Builds and returns the configured {@link EventStorage} implementation.
		 * <p>
		 * This method creates an {@link InMemoryEventStorageImpl} instance with all the
		 * configured settings. The returned EventStorage can then be passed to
		 * {@link EventStoreFactory#eventStore(EventStorage)} to obtain an EventStore.
		 * <p>
		 * For convenience, consider using {@link #buildStore()} instead, which performs
		 * both steps in one call.
		 *
		 * @return a new InMemoryEventStorageImpl instance with the configured settings
		 * @see #buildStore()
		 * @see EventStorage
		 * @see InMemoryEventStorageImpl
		 */
		public EventStorage build ( ) {
			return new InMemoryEventStorageImpl(limit, meterRegistry);
		}

		/**
		 * Builds the storage and returns a fully configured {@link EventStore} instance.
		 * <p>
		 * This convenience method combines {@link #build()} and {@link EventStoreFactory#eventStore(EventStorage)}
		 * in a single call, providing a streamlined way to create a ready-to-use EventStore.
		 * <p>
		 * This is the recommended method for most use cases where you want to quickly set up
		 * an in-memory event store for development or testing.
		 * <p>
		 * Example:
		 * <pre>{@code
		 * // Create event store with defaults
		 * EventStore store = InMemoryEventStorage.newBuilder().buildStore();
		 *
		 * // Create event store with result limit
		 * EventStore store = InMemoryEventStorage.newBuilder()
		 *     .resultLimit(1000)
		 *     .buildStore();
		 * }</pre>
		 *
		 * @return a new EventStore instance backed by in-memory storage
		 * @see #build()
		 * @see EventStore
		 * @see EventStoreFactory#eventStore(EventStorage)
		 */
		public EventStore buildStore ( ) {
			return EventStoreFactory.get().eventStore(build(), meterRegistry);
		}
	}
	
}
