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
/**
 * {@link org.sliceworkz.eventstore.testing.EventStoreBackend} implementations for the storages this
 * project ships.
 * <p>
 * {@link org.sliceworkz.eventstore.testing.backend.InMemoryBackend} needs nothing. The others rest on
 * optional dependencies of the testing module and must be declared explicitly to be used:
 * {@link org.sliceworkz.eventstore.testing.backend.InMemoryFsBackend} needs
 * {@code sliceworkz-eventstore-infra-inmem-fs}, and the PostgreSQL backends additionally need
 * Testcontainers, the PostgreSQL JDBC driver and HikariCP — so nobody inherits a Docker dependency
 * for using the fixture or for testing their own storage.
 */
package org.sliceworkz.eventstore.testing.backend;
