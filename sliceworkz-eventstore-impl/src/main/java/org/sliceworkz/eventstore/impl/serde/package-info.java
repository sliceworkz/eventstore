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
 * Event serialization and deserialization supporting typed and raw event payloads.
 * <p>
 * This package provides Jackson-based serialization for converting domain events to/from JSON,
 * with support for upcasting legacy events and GDPR-compliant data erasure.
 *
 * <h2>Serialization Modes:</h2>
 * <ul>
 *   <li><strong>Typed Serialization:</strong> Events are serialized/deserialized as Java objects with full type information</li>
 *   <li><strong>Raw Serialization:</strong> Events are stored and retrieved as JSON strings without type conversion</li>
 * </ul>
 *
 * <h2>Key Features:</h2>
 * <ul>
 *   <li>Support for sealed interfaces and record-based event hierarchies</li>
 *   <li>Automatic event type registration and discovery</li>
 *   <li>Legacy event upcasting via {@link org.sliceworkz.eventstore.events.LegacyEvent} annotation</li>
 *   <li>GDPR compliance through {@link org.sliceworkz.eventstore.shredding.Shreddable} values, encrypted per data subject</li>
 *   <li>Separation of immutable and erasable data in storage</li>
 * </ul>
 *
 * <h2>Personal Data Handling:</h2>
 * <p>
 * A payload is serialized to a single JSON document. Any
 * {@link org.sliceworkz.eventstore.shredding.Shreddable} value in it is encrypted in place, under the
 * key held for its data subject, and written as a sealed envelope. Erasure destroys the key rather than
 * touching the event, so the stored bytes never change and every copy of them — replicas, write-ahead
 * logs, backups — becomes unreadable at the same instant.
 * <p>
 * Events written before this, when payloads were split across an immutable and an erasable document,
 * are still read by merging the two.
 *
 * @see org.sliceworkz.eventstore.shredding.Shreddable
 * @see ShreddableModule
 * @see org.sliceworkz.eventstore.events.LegacyEvent
 * @see org.sliceworkz.eventstore.events.Upcast
 */
package org.sliceworkz.eventstore.impl.serde;
