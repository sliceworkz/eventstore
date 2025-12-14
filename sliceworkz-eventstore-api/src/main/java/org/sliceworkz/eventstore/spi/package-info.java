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
/**
 * Service Provider Interface (SPI) for implementing custom event storage backends.
 * <p>
 * This package defines the contract that storage implementations must fulfill to provide
 * event persistence for the EventStore. The SPI enables pluggable storage backends while
 * maintaining the same public API.
 *
 * <h2>Core SPI Components:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.spi.EventStorage} - Main SPI interface for storage implementations</li>
 *   <li>{@link org.sliceworkz.eventstore.spi.EventStorageException} - Exception wrapper for storage-layer errors</li>
 * </ul>
 *
 * <h2>Available Implementations:</h2>
 * <ul>
 *   <li><strong>InMemoryEventStorage</strong> - Non-persistent storage for development and testing</li>
 *   <li><strong>PostgresEventStorage</strong> - Production-ready PostgreSQL backend</li>
 * </ul>
 *
 * <h2>Storage Implementation Responsibilities:</h2>
 * <p>
 * Implementations of {@link org.sliceworkz.eventstore.spi.EventStorage} must handle:
 * <ul>
 *   <li>Event persistence with ACID properties (append operation)</li>
 *   <li>Event querying with type and tag filters</li>
 *   <li>Optimistic locking support for DCB pattern</li>
 *   <li>Event reference assignment (ID and position)</li>
 *   <li>Bookmark management for event processors</li>
 *   <li>Listener notifications for new events and bookmarks</li>
 *   <li>GDPR-compliant data separation (immutable vs erasable)</li>
 * </ul>
 *
 * <h2>Example Storage Usage:</h2>
 * <pre>{@code
 * // Using in-memory storage
 * EventStorage storage = InMemoryEventStorage.newBuilder()
 *     .resultLimit(10000)
 *     .build();
 * EventStore eventStore = EventStoreFactory.get().eventStore(storage);
 *
 * // Using PostgreSQL storage
 * EventStorage storage = PostgresEventStorage.newBuilder()
 *     .prefix("tenant1_")
 *     .initializeDatabase()
 *     .build();
 * EventStore eventStore = EventStoreFactory.get().eventStore(storage);
 * }</pre>
 *
 * <h2>Implementing Custom Storage:</h2>
 * <p>
 * To create a custom storage backend:
 * <ol>
 *   <li>Implement the {@link org.sliceworkz.eventstore.spi.EventStorage} interface</li>
 *   <li>Handle all required operations (query, append, bookmark, subscribe)</li>
 *   <li>Ensure thread safety for concurrent operations</li>
 *   <li>Implement proper optimistic locking validation</li>
 *   <li>Notify registered listeners of new events and bookmarks</li>
 *   <li>Separate immutable and erasable data for GDPR compliance</li>
 * </ol>
 *
 * <h2>Thread Safety Requirements:</h2>
 * <p>
 * Storage implementations must be thread-safe. Multiple threads may concurrently:
 * <ul>
 *   <li>Append events to different or the same streams</li>
 *   <li>Query events while appends are in progress</li>
 *   <li>Register and receive listener notifications</li>
 * </ul>
 * <p>
 * For optimistic locking to work correctly, the query-then-append operation within
 * {@link org.sliceworkz.eventstore.spi.EventStorage#append} must be atomic for each stream.
 *
 * @see org.sliceworkz.eventstore.spi.EventStorage
 * @see org.sliceworkz.eventstore.EventStore
 * @see org.sliceworkz.eventstore.EventStoreFactory
 */
package org.sliceworkz.eventstore.spi;
