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
package org.sliceworkz.eventstore.events;

import java.util.List;
import java.util.Set;

/**
 * Transforms a historical event to its current domain event representation.
 * <p>
 * This interface is the core of the upcasting mechanism, enabling event-sourced systems to evolve
 * their event schemas over time without modifying historical data. When legacy events annotated with
 * {@link LegacyEvent} are read from the event store, their associated upcaster is invoked to
 * transform them into current event definitions.
 * <p>
 * Upcasters are discovered through the {@link LegacyEvent} annotation and must have a public
 * no-argument constructor. They are instantiated by the event store framework and cached for
 * efficient repeated use during event deserialization.
 * <p>
 * Key responsibilities:
 * <ul>
 *   <li>Transform legacy event structure to current event structure</li>
 *   <li>Provide default values for new fields that didn't exist in legacy events</li>
 *   <li>Handle data type migrations (e.g., String to value object)</li>
 *   <li>Map renamed fields or restructured data</li>
 *   <li>Apply backward-compatible business rules to legacy data</li>
 * </ul>
 *
 * <h2>Design Considerations:</h2>
 * <ul>
 *   <li><b>Idempotent:</b> Upcasting the same legacy event multiple times should always produce the same result</li>
 *   <li><b>Pure Function:</b> Should not have side effects or depend on external state</li>
 *   <li><b>Backward Compatible:</b> Old events may not adhere to new validation rules; handle gracefully</li>
 *   <li><b>Performance:</b> Upcasters are called for every legacy event read; keep them lightweight</li>
 *   <li><b>Type Safety:</b> Generic parameters ensure compile-time verification of event types</li>
 * </ul>
 *
 * <h2>Basic Example - One-to-One Upcast:</h2>
 * <pre>{@code
 * // Legacy event (stored in database)
 * sealed interface CustomerHistoricalEvent {
 *     @LegacyEvent(upcast = CustomerRegisteredUpcaster.class)
 *     record CustomerRegistered(String name) implements CustomerHistoricalEvent {}
 * }
 *
 * // Current event (used in application)
 * sealed interface CustomerEvent {
 *     record CustomerRegisteredV2(Name name) implements CustomerEvent {}
 * }
 *
 * // Upcaster
 * public class CustomerRegisteredUpcaster
 *     implements Upcast<CustomerHistoricalEvent.CustomerRegistered, CustomerEvent.CustomerRegisteredV2> {
 *
 *     @Override
 *     public List<CustomerEvent.CustomerRegisteredV2> upcast(CustomerHistoricalEvent.CustomerRegistered historical) {
 *         return List.of(new CustomerEvent.CustomerRegisteredV2(new Name(historical.name())));
 *     }
 *
 *     @Override
 *     public Set<Class<? extends CustomerEvent.CustomerRegisteredV2>> targetTypes() {
 *         return Set.of(CustomerEvent.CustomerRegisteredV2.class);
 *     }
 * }
 * }</pre>
 *
 * <h2>Example - Splitting One Event Into Multiple:</h2>
 * <pre>{@code
 * // Legacy event that combined customer and address data
 * @LegacyEvent(upcast = FullCustomerRegisteredUpcaster.class)
 * record FullCustomerRegistered(String name, String street, String city) implements CustomerHistoricalEvent {}
 *
 * // Current events: split into two separate concerns
 * record CustomerRegisteredV2(Name name) implements CustomerEvent {}
 * record AddressRecorded(String street, String city) implements CustomerEvent {}
 *
 * public class FullCustomerRegisteredUpcaster
 *     implements Upcast<CustomerHistoricalEvent.FullCustomerRegistered, CustomerEvent> {
 *
 *     @Override
 *     public List<CustomerEvent> upcast(CustomerHistoricalEvent.FullCustomerRegistered historical) {
 *         return List.of(
 *             new CustomerEvent.CustomerRegisteredV2(new Name(historical.name())),
 *             new CustomerEvent.AddressRecorded(historical.street(), historical.city())
 *         );
 *     }
 *
 *     @Override
 *     public Set<Class<? extends CustomerEvent>> targetTypes() {
 *         return Set.of(CustomerEvent.CustomerRegisteredV2.class, CustomerEvent.AddressRecorded.class);
 *     }
 * }
 * }</pre>
 *
 * <h2>Example - Filtering Out Events (0 Events):</h2>
 * <pre>{@code
 * // Legacy event that is no longer relevant
 * @LegacyEvent(upcast = ObsoleteEventUpcaster.class)
 * record CustomerNoteAdded(String note) implements CustomerHistoricalEvent {}
 *
 * public class ObsoleteEventUpcaster
 *     implements Upcast<CustomerHistoricalEvent.CustomerNoteAdded, CustomerEvent> {
 *
 *     @Override
 *     public List<CustomerEvent> upcast(CustomerHistoricalEvent.CustomerNoteAdded historical) {
 *         return List.of(); // filter out this legacy event
 *     }
 *
 *     @Override
 *     public Set<Class<? extends CustomerEvent>> targetTypes() {
 *         return Set.of();
 *     }
 * }
 * }</pre>
 *
 * @param <HISTORICAL_EVENT> the legacy event type (annotated with {@link LegacyEvent})
 * @param <DOMAIN_EVENT> the current event type to transform into
 * @see LegacyEvent
 * @see org.sliceworkz.eventstore.stream.EventStream
 */
public interface Upcast<HISTORICAL_EVENT,DOMAIN_EVENT> {

	/**
	 * Transforms a historical event instance to zero or more current domain event representations.
	 * <p>
	 * This method is called automatically by the event store framework during event deserialization
	 * when a legacy event is read from storage. The implementation should be idempotent, stateless,
	 * and free of side effects.
	 * <p>
	 * Upcasters should handle cases where legacy data may not conform to current validation rules.
	 * For example, if a new value object enforces length constraints, the upcaster should either
	 * use a lenient constructor or provide sensible defaults for legacy data that violates the new rules.
	 * <p>
	 * Common return patterns:
	 * <ul>
	 *   <li><b>One-to-one:</b> Return {@code List.of(newEvent)} for simple transformations or renames</li>
	 *   <li><b>Splitting:</b> Return {@code List.of(event1, event2)} to split one historical event
	 *       into multiple current events</li>
	 *   <li><b>Filtering:</b> Return {@code List.of()} to exclude obsolete or irrelevant historical events</li>
	 * </ul>
	 * <p>
	 * All events in the returned list share the same {@link org.sliceworkz.eventstore.events.EventReference},
	 * {@link Tags}, and timestamp from the original stored event.
	 *
	 * @param historicalEvent the legacy event instance to transform (never null)
	 * @return a list of current domain event representations (may be empty, must not be null,
	 *         individual elements must not be null)
	 */
	List<DOMAIN_EVENT> upcast ( HISTORICAL_EVENT historicalEvent );

	/**
	 * Returns all target event types that this upcaster can produce.
	 * <p>
	 * This method is used by the event store framework to expand queries so that legacy event types
	 * are included when querying for any of the target types. For example, if a legacy event
	 * {@code FullCustomerRegistered} can be upcasted to both {@code CustomerRegisteredV2} and
	 * {@code AddressRecorded}, then querying for either of those types will also fetch the
	 * legacy {@code FullCustomerRegistered} events.
	 * <p>
	 * For one-to-one upcasters, return a singleton set: {@code Set.of(MyEvent.class)}.
	 * For filtering upcasters that produce no events, return an empty set: {@code Set.of()}.
	 *
	 * @return the set of all possible target event type classes (must not be null)
	 */
	Set<Class<? extends DOMAIN_EVENT>> targetTypes ( );

}
