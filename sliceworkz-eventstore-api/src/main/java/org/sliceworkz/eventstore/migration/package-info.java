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
 * Moving events between event stores.
 * <p>
 * This package holds {@link org.sliceworkz.eventstore.migration.EventStoreImporter}, which copies events
 * from one {@link org.sliceworkz.eventstore.spi.EventStorage} into another while preserving each event's
 * identity, timestamp and idempotency key. Position and transaction are always reassigned by the target:
 * an import reproduces the source <em>order</em>, never the source ordering numbers.
 *
 * <h2>Why it works at the storage level</h2>
 * The importer reads {@link org.sliceworkz.eventstore.spi.EventStorage.StoredEvent}s and writes
 * {@link org.sliceworkz.eventstore.spi.EventToImport}s, so payloads move as opaque JSON. That means no
 * domain classes on the classpath, no serialization round-trip, no upcasting, and no re-splitting of
 * erasable data against annotations that may since have changed. Going through
 * {@link org.sliceworkz.eventstore.stream.EventStream} instead would rewrite legacy events into current
 * ones and lose the idempotency key, which the public {@link org.sliceworkz.eventstore.events.Event}
 * record does not carry.
 *
 * <h2>This is a rewriting tool</h2>
 * The import transformation may change the stream, the tags, the payload, the type, the identifier and
 * the timestamp. That supports stream remapping, tenant splits, schema migration and cloning — and it
 * means the importer offers no fidelity guarantee of its own. What arrives in the target is whatever the
 * transformation asked for. Nothing is verified afterwards.
 *
 * <h2>Common uses</h2>
 * <ul>
 *   <li><b>Migrating a store</b> — read every event from one backend, write it into another, unchanged</li>
 *   <li><b>Catching up</b> — a follow-up run started after the previous run's boundary brings across
 *       whatever the source accumulated since</li>
 *   <li><b>Remapping</b> — import a source stream into a differently named context or purpose</li>
 *   <li><b>Cloning</b> — import a store into itself with fresh identifiers to duplicate a stream</li>
 * </ul>
 *
 * @see org.sliceworkz.eventstore.migration.EventStoreImporter
 * @see org.sliceworkz.eventstore.migration.ImportReport
 * @see org.sliceworkz.eventstore.spi.EventToImport
 */
package org.sliceworkz.eventstore.migration;
