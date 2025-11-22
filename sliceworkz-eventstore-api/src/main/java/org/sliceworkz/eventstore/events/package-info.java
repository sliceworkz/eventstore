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
 * Event model - domain events, tags, types, and event handling interfaces.
 * <p>
 * This package provides the core event modeling abstractions for the EventStore:
 *
 * <h2>Event Lifecycle:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.events.EphemeralEvent} - Lightweight pre-persistence event representation</li>
 *   <li>{@link org.sliceworkz.eventstore.events.Event} - Full persisted event with metadata (stream, reference, timestamp)</li>
 * </ul>
 *
 * <h2>Event Identity and Types:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.events.EventId} - Globally unique event identifier (UUID)</li>
 *   <li>{@link org.sliceworkz.eventstore.events.EventReference} - Combines ID with position for ordering and optimistic locking</li>
 *   <li>{@link org.sliceworkz.eventstore.events.EventType} - Event type identifier derived from class names</li>
 * </ul>
 *
 * <h2>Dynamic Consistency Boundary (DCB) Support:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.events.Tag} - Single key-value tag for event categorization</li>
 *   <li>{@link org.sliceworkz.eventstore.events.Tags} - Collection of tags enabling cross-type queries and DCB boundaries</li>
 * </ul>
 *
 * <h2>Event Evolution and GDPR:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.events.LegacyEvent} - Marks historical events requiring upcasting</li>
 *   <li>{@link org.sliceworkz.eventstore.events.Upcast} - Interface for transforming legacy events to current types</li>
 *   <li>{@link org.sliceworkz.eventstore.events.Erasable} - Marks fields that can be deleted for GDPR compliance</li>
 *   <li>{@link org.sliceworkz.eventstore.events.PartlyErasable} - Marks nested objects with mixed erasability</li>
 * </ul>
 *
 * <h2>Event Processing:</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.events.EventHandler} - Simple event handler processing only event data</li>
 *   <li>{@link org.sliceworkz.eventstore.events.EventWithMetaDataHandler} - Handler with access to full event metadata</li>
 * </ul>
 *
 * <h2>Example Domain Event Model:</h2>
 * <pre>{@code
 * // Define events as records in a sealed interface
 * public sealed interface CustomerEvent {
 *     record CustomerRegistered(String customerId, String name) implements CustomerEvent { }
 *     record CustomerNameChanged(String customerId, String newName) implements CustomerEvent { }
 *
 *     @Erasable  // For GDPR compliance
 *     record CustomerChurned(String customerId, @Erasable String reason) implements CustomerEvent { }
 * }
 *
 * // Create and append events with tags
 * EphemeralEvent<CustomerEvent> event = Event.of(
 *     new CustomerRegistered("cust-123", "John Doe"),
 *     Tags.of("customer", "cust-123", "region", "EU")
 * );
 * }</pre>
 *
 * @see org.sliceworkz.eventstore.events.Event
 * @see org.sliceworkz.eventstore.events.Tags
 * @see org.sliceworkz.eventstore.query
 * @see org.sliceworkz.eventstore.stream
 */
package org.sliceworkz.eventstore.events;
