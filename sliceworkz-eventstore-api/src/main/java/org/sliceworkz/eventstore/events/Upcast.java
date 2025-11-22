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
package org.sliceworkz.eventstore.events;

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
 * <h2>Basic Example:</h2>
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
 *     public CustomerEvent.CustomerRegisteredV2 upcast(CustomerHistoricalEvent.CustomerRegistered historical) {
 *         // Transform String to Name value object
 *         return new CustomerEvent.CustomerRegisteredV2(new Name(historical.name()));
 *     }
 *
 *     @Override
 *     public Class<CustomerEvent.CustomerRegisteredV2> targetType() {
 *         return CustomerEvent.CustomerRegisteredV2.class;
 *     }
 * }
 * }</pre>
 *
 * <h2>Advanced Example - Adding New Fields:</h2>
 * <pre>{@code
 * // Legacy: event without email field
 * @LegacyEvent(upcast = CustomerRegisteredUpcaster.class)
 * record CustomerRegistered(String customerId, String name) implements CustomerHistoricalEvent {}
 *
 * // Current: event with required email field
 * record CustomerRegisteredV2(String customerId, Name name, Email email) implements CustomerEvent {}
 *
 * // Upcaster: provide default for missing field
 * public class CustomerRegisteredUpcaster
 *     implements Upcast<CustomerHistoricalEvent.CustomerRegistered, CustomerEvent.CustomerRegisteredV2> {
 *
 *     @Override
 *     public CustomerEvent.CustomerRegisteredV2 upcast(CustomerHistoricalEvent.CustomerRegistered historical) {
 *         return new CustomerEvent.CustomerRegisteredV2(
 *             historical.customerId(),
 *             new Name(historical.name()),
 *             Email.unknown() // default value for new required field
 *         );
 *     }
 *
 *     @Override
 *     public Class<CustomerEvent.CustomerRegisteredV2> targetType() {
 *         return CustomerEvent.CustomerRegisteredV2.class;
 *     }
 * }
 * }</pre>
 *
 * <h2>Advanced Example - Relaxing Validation:</h2>
 * <pre>{@code
 * // Current Name value object with strict validation
 * public record Name(String value) {
 *     public Name {
 *         if (value == null || value.length() < 3 || value.length() > 20) {
 *             throw new IllegalArgumentException("Name must be 3-20 characters");
 *         }
 *     }
 *
 *     // Lenient constructor for upcasting legacy data
 *     public static Name fromLegacy(String value) {
 *         return new Name(value == null || value.isEmpty() ? "Unknown" : value);
 *     }
 * }
 *
 * public class CustomerRegisteredUpcaster
 *     implements Upcast<CustomerHistoricalEvent.CustomerRegistered, CustomerEvent.CustomerRegisteredV2> {
 *
 *     @Override
 *     public CustomerEvent.CustomerRegisteredV2 upcast(CustomerHistoricalEvent.CustomerRegistered historical) {
 *         // Use lenient factory method to handle legacy data that doesn't meet new rules
 *         return new CustomerEvent.CustomerRegisteredV2(Name.fromLegacy(historical.name()));
 *     }
 *
 *     @Override
 *     public Class<CustomerEvent.CustomerRegisteredV2> targetType() {
 *         return CustomerEvent.CustomerRegisteredV2.class;
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
	 * Transforms a historical event instance to its current domain event representation.
	 * <p>
	 * This method is called automatically by the event store framework during event deserialization
	 * when a legacy event is read from storage. The implementation should be idempotent, stateless,
	 * and free of side effects.
	 * <p>
	 * Upcasters should handle cases where legacy data may not conform to current validation rules.
	 * For example, if a new value object enforces length constraints, the upcaster should either
	 * use a lenient constructor or provide sensible defaults for legacy data that violates the new rules.
	 *
	 * @param historicalEvent the legacy event instance to transform (never null)
	 * @return the current domain event representation (must not be null)
	 */
	DOMAIN_EVENT upcast ( HISTORICAL_EVENT historicalEvent );

	/**
	 * Returns the target event type that this upcaster produces.
	 * <p>
	 * This method is used by the event store framework to:
	 * <ul>
	 *   <li>Enable filtering on upcasted event types in {@link org.sliceworkz.eventstore.query.EventQuery}</li>
	 *   <li>Maintain type information for runtime type checking</li>
	 *   <li>Support polymorphic event handling in projections</li>
	 * </ul>
	 * <p>
	 * The returned class must match the {@code DOMAIN_EVENT} generic parameter.
	 *
	 * @return the Class object of the target event type (must not be null)
	 */
	Class<DOMAIN_EVENT> targetType ( );

}
