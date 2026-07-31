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
 * The shared compliance scenarios every {@link org.sliceworkz.eventstore.spi.EventStorage}
 * implementation must satisfy.
 * <p>
 * These are ordinary JUnit classes annotated
 * {@link org.sliceworkz.eventstore.testing.ForEachBackend}, so they run once per registered
 * {@link org.sliceworkz.eventstore.testing.EventStoreBackend}. They live in {@code src/main/java}
 * so they can be depended on: point surefire's {@code dependenciesToScan} at this artifact and the
 * whole suite runs against your storage.
 * <p>
 * Together they pin the behaviour the SPI's javadoc describes in prose — stream scoping, tag
 * matching, optimistic locking, idempotency-key scoping per stream, bookmarks, listener
 * notification, upcasting and query direction, event import, UTC timestamps, and the visibility of
 * concurrent appends to a tailing reader.
 * <p>
 * Parts of the contract that are genuinely optional are declared through
 * {@link org.sliceworkz.eventstore.testing.EventStoreBackend.Capability} and skipped where a backend
 * does not support them.
 */
package org.sliceworkz.eventstore.testing.tck;
