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
 *   <li>GDPR compliance through {@link org.sliceworkz.eventstore.events.Erasable} field separation</li>
 *   <li>Separation of immutable and erasable data in storage</li>
 * </ul>
 *
 * <h2>Erasable Data Handling:</h2>
 * <p>
 * Fields marked with {@code @Erasable} are stored separately from immutable data, enabling
 * selective deletion for GDPR "right to be forgotten" compliance. The serializer uses
 * Jackson's {@code @JsonView} mechanism to separate:
 * <ul>
 *   <li><strong>Immutable data:</strong> Event structure that must be preserved</li>
 *   <li><strong>Erasable data:</strong> Personal information that can be deleted</li>
 * </ul>
 *
 * @see org.sliceworkz.eventstore.events.Erasable
 * @see org.sliceworkz.eventstore.events.PartlyErasable
 * @see org.sliceworkz.eventstore.events.LegacyEvent
 * @see org.sliceworkz.eventstore.events.Upcast
 */
package org.sliceworkz.eventstore.impl.serde;
