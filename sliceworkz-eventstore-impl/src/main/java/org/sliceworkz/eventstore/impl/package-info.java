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
 * Core EventStore implementation coordinating between API and storage backends.
 * <p>
 * This package provides the concrete implementation of the EventStore API, discovered at runtime
 * via Java's ServiceLoader mechanism. The implementation bridges the public API with pluggable
 * storage backends.
 *
 * <h2>Main Components:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.impl.EventStoreImpl} - Concrete EventStore implementation</li>
 *   <li>{@link org.sliceworkz.eventstore.impl.EventStoreFactoryImpl} - ServiceLoader-discoverable factory</li>
 * </ul>
 *
 * <h2>Key Responsibilities:</h2>
 * <ul>
 *   <li>Managing EventStream instances and their lifecycle</li>
 *   <li>Coordinating event serialization/deserialization</li>
 *   <li>Handling consistent and eventually consistent event notifications</li>
 *   <li>Supporting both typed and raw event payload modes</li>
 *   <li>Managing asynchronous listener notifications via virtual threads</li>
 * </ul>
 *
 * <h2>Event Payload Modes:</h2>
 * <ul>
 *   <li><strong>Typed Mode:</strong> Events are Java objects serialized to JSON, supporting sealed interfaces,
 *       upcasting, and GDPR-compliant erasure</li>
 *   <li><strong>Raw Mode:</strong> Events are stored and retrieved as JSON strings without type mapping</li>
 * </ul>
 *
 * <p>
 * This implementation module is automatically loaded by the API module through the ServiceLoader
 * registration in {@code META-INF/services/org.sliceworkz.eventstore.EventStoreFactory}.
 *
 * @see org.sliceworkz.eventstore.EventStore
 * @see org.sliceworkz.eventstore.EventStoreFactory
 * @see org.sliceworkz.eventstore.spi.EventStorage
 * @see org.sliceworkz.eventstore.impl.serde
 */
package org.sliceworkz.eventstore.impl;
