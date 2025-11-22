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
 * Production-ready PostgreSQL event storage implementation.
 * <p>
 * This package provides a robust, production-ready implementation of {@link org.sliceworkz.eventstore.spi.EventStorage}
 * backed by PostgreSQL. Features include optimized querying, real-time event notifications via LISTEN/NOTIFY,
 * and support for multi-tenancy through table prefixing.
 *
 * <h2>Key Features:</h2>
 * <ul>
 *   <li><strong>Production-ready:</strong> ACID guarantees, connection pooling (HikariCP), and optimized queries</li>
 *   <li><strong>Real-time notifications:</strong> PostgreSQL LISTEN/NOTIFY for instant event propagation</li>
 *   <li><strong>Table prefixing:</strong> Multi-tenancy support via isolated table sets</li>
 *   <li><strong>Schema management:</strong> Automatic database initialization</li>
 *   <li><strong>High performance:</strong> Prepared statements, indexes, and batch operations</li>
 *   <li><strong>Configurable:</strong> Custom DataSource or property-file based configuration</li>
 * </ul>
 *
 * <h2>Quick Start:</h2>
 * <pre>{@code
 * // Using default configuration from db.properties
 * EventStore eventStore = PostgresEventStorage.newBuilder()
 *     .initializeDatabase()
 *     .buildStore();
 * }</pre>
 *
 * <h2>Configuration:</h2>
 * <p>
 * Create a {@code db.properties} file in your project root or specify its location via:
 * <ul>
 *   <li>System property: {@code -Deventstore.db.config=/path/to/db.properties}</li>
 *   <li>Environment variable: {@code EVENTSTORE_DB_CONFIG=/path/to/db.properties}</li>
 * </ul>
 *
 * <p>
 * Example {@code db.properties}:
 * <pre>
 * # Pooled connection (for queries and appends)
 * db.pooled.jdbcUrl=jdbc:postgresql://localhost:5432/eventstore
 * db.pooled.username=eventstore_user
 * db.pooled.password=secret
 * db.pooled.maximumPoolSize=10
 *
 * # Non-pooled connection (for LISTEN/NOTIFY)
 * db.nonpooled.jdbcUrl=jdbc:postgresql://localhost:5432/eventstore
 * db.nonpooled.username=eventstore_user
 * db.nonpooled.password=secret
 * </pre>
 *
 * <h2>Advanced Configuration:</h2>
 * <pre>{@code
 * // Custom DataSource with table prefix for multi-tenancy
 * DataSource dataSource = // ... your DataSource
 *
 * EventStore eventStore = PostgresEventStorage.newBuilder()
 *     .name("tenant1-events")
 *     .dataSource(dataSource)
 *     .prefix("tenant1_")
 *     .resultLimit(10000)
 *     .initializeDatabase()
 *     .buildStore();
 * }</pre>
 *
 * <h2>Database Schema:</h2>
 * <p>
 * When {@code initializeDatabase()} is called, the following structures are created:
 * <ul>
 *   <li><strong>{@code PREFIX_events}</strong>: Main event storage table with columns for stream, type, data, tags, and timestamp</li>
 *   <li><strong>{@code PREFIX_bookmarks}</strong>: Event processor position tracking</li>
 *   <li><strong>Indexes:</strong> On stream, tags (GIN), and timestamp for efficient querying</li>
 *   <li><strong>Triggers:</strong> Automatic NOTIFY on event appends and bookmark updates</li>
 * </ul>
 *
 * <h2>Performance Considerations:</h2>
 * <ul>
 *   <li>Use separate pooled and non-pooled DataSources for optimal throughput</li>
 *   <li>Configure appropriate pool sizes based on expected concurrent load</li>
 *   <li>Add custom indexes for frequently queried tag combinations</li>
 *   <li>Use table prefixes to isolate tenants for better cache locality</li>
 *   <li>Set result limits to prevent unbounded queries</li>
 * </ul>
 *
 * @see org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage
 * @see org.sliceworkz.eventstore.infra.postgres.DataSourceFactory
 * @see org.sliceworkz.eventstore.infra.postgres.HikariConfigurationUtil
 * @see org.sliceworkz.eventstore.spi.EventStorage
 */
package org.sliceworkz.eventstore.infra.postgres;
