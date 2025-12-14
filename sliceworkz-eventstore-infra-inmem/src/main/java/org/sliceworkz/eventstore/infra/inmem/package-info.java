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
 * In-memory event storage implementation for development and testing.
 * <p>
 * This package provides a non-persistent, thread-safe implementation of {@link org.sliceworkz.eventstore.spi.EventStorage}
 * that stores all events in memory. Ideal for development, unit testing, and rapid prototyping.
 *
 * <h2>Key Characteristics:</h2>
 * <ul>
 *   <li><strong>Non-persistent:</strong> All data is lost when the application stops</li>
 *   <li><strong>Thread-safe:</strong> Synchronized access for concurrent operations</li>
 *   <li><strong>Full-featured:</strong> Supports all EventStore capabilities including subscriptions and bookmarks</li>
 *   <li><strong>Fast:</strong> No I/O overhead, suitable for high-speed testing</li>
 *   <li><strong>Simple:</strong> No configuration or setup required</li>
 * </ul>
 *
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Quick setup with defaults
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 *
 * // With result limit for query protection
 * EventStore eventStore = InMemoryEventStorage.newBuilder()
 *     .resultLimit(10000)
 *     .buildStore();
 *
 * // Use in tests
 * @BeforeEach
 * void setup() {
 *     eventStore = InMemoryEventStorage.newBuilder().buildStore();
 * }
 * }</pre>
 *
 * <h2>Limitations:</h2>
 * <ul>
 *   <li>Not suitable for production use</li>
 *   <li>Data lost on application restart</li>
 *   <li>Memory consumption grows with event count</li>
 *   <li>Performance degrades with very large event logs</li>
 * </ul>
 *
 * <h2>Testing Recommendations:</h2>
 * <p>
 * In-memory storage is excellent for:
 * <ul>
 *   <li>Unit tests - fast execution with no external dependencies</li>
 *   <li>Integration tests - realistic EventStore behavior without database setup</li>
 *   <li>Development - quick prototyping and experimentation</li>
 *   <li>CI/CD pipelines - no database infrastructure required</li>
 * </ul>
 *
 * @see org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage
 * @see org.sliceworkz.eventstore.spi.EventStorage
 * @see org.sliceworkz.eventstore.EventStore
 */
package org.sliceworkz.eventstore.infra.inmem;
