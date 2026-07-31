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
 * A {@code given / when / then} fixture for testing application code written against the event store.
 * <p>
 * Start at {@link org.sliceworkz.eventstore.testing.fixture.EventStoreFixture}. It targets the shape
 * every DCB application has — query the events relevant to a decision, decide, append conditionally —
 * and the three things that are awkward to test by hand: seeding history, asserting on events whose
 * reference and timestamp the store assigns, and provoking an
 * {@link org.sliceworkz.eventstore.stream.OptimisticLockingException} deterministically.
 * <p>
 * Assertions compare an event's payload and tags only. Stream, reference and timestamp are assigned
 * by the storage and cannot be predicted from a test: the in-memory store stamps events from the JVM
 * clock and the PostgreSQL store lets the server clock do it, with no clock seam in either. Assert on
 * timestamps with a tolerance window or not at all.
 */
package org.sliceworkz.eventstore.testing.fixture;
