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
 * Harness for running tests against one or many {@link org.sliceworkz.eventstore.spi.EventStorage}
 * implementations.
 * <p>
 * Extend {@link org.sliceworkz.eventstore.testing.AbstractEventStoreTest} for a store that is fresh
 * and empty per test method. Annotate scenarios
 * {@link org.sliceworkz.eventstore.testing.ForEachBackend} to run each of them against every
 * registered {@link org.sliceworkz.eventstore.testing.EventStoreBackend}, discovered with the
 * {@code ServiceLoader}.
 * <p>
 * Implementing {@code EventStoreBackend} for a storage is the whole of what it takes to run the
 * shared compliance scenarios in {@link org.sliceworkz.eventstore.testing.tck} against it.
 */
package org.sliceworkz.eventstore.testing;
