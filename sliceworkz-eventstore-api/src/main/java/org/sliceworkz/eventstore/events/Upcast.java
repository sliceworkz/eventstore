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
 * <h2>Advanced Example - Splitting One Event Into Multiple:</h2>
 * <p>
 * Override {@link #upcastAll(Object)} and {@link #targetTypes()} to produce multiple events from a single
 * historical event. This is useful when a legacy event contained data that should now be modeled as
 * separate events.
 * </p>
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
 *     public CustomerEvent upcast(CustomerHistoricalEvent.FullCustomerRegistered historical) {
 *         return new CustomerEvent.CustomerRegisteredV2(new Name(historical.name()));
 *     }
 *
 *     @Override
 *     public Class<CustomerEvent> targetType() {
 *         return CustomerEvent.class;
 *     }
 *
 *     @Override
 *     public List<CustomerEvent> upcastAll(CustomerHistoricalEvent.FullCustomerRegistered historical) {
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
 * <h2>Advanced Example - Filtering Out Events (0 Events):</h2>
 * <p>
 * Override {@link #upcastAll(Object)} to return an empty list when a legacy event should be
 * excluded from the current event stream.
 * </p>
 * <pre>{@code
 * // Legacy event that is no longer relevant
 * @LegacyEvent(upcast = ObsoleteEventUpcaster.class)
 * record CustomerNoteAdded(String note) implements CustomerHistoricalEvent {}
 *
 * public class ObsoleteEventUpcaster
 *     implements Upcast<CustomerHistoricalEvent.CustomerNoteAdded, CustomerEvent> {
 *
 *     @Override
 *     public CustomerEvent upcast(CustomerHistoricalEvent.CustomerNoteAdded historical) {
 *         return null; // not used when upcastAll is overridden
 *     }
 *
 *     @Override
 *     public Class<CustomerEvent> targetType() {
 *         return CustomerEvent.class;
 *     }
 *
 *     @Override
 *     public List<CustomerEvent> upcastAll(CustomerHistoricalEvent.CustomerNoteAdded historical) {
 *         return List.of(); // filter out this legacy event
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
	 * <p>
	 * For upcasters that produce multiple event types, override {@link #targetTypes()} instead.
	 *
	 * @return the Class object of the target event type (must not be null)
	 */
	Class<DOMAIN_EVENT> targetType ( );

	/**
	 * Transforms a historical event instance to zero or more current domain event representations.
	 * <p>
	 * This method enables advanced upcasting scenarios:
	 * <ul>
	 *   <li><b>Splitting:</b> One historical event can be split into multiple current events
	 *       (e.g., a legacy event containing both customer and address data becomes two separate events)</li>
	 *   <li><b>Filtering:</b> Return an empty list to exclude obsolete or irrelevant historical events
	 *       from the current event stream</li>
	 *   <li><b>One-to-one:</b> The default implementation delegates to {@link #upcast(Object)} for
	 *       backward compatibility with existing upcasters</li>
	 * </ul>
	 * <p>
	 * All events in the returned list share the same {@link org.sliceworkz.eventstore.events.EventReference},
	 * {@link Tags}, and timestamp from the original stored event.
	 * <p>
	 * Override this method (along with {@link #targetTypes()}) when the upcaster needs to produce
	 * zero or more than one event. When overriding this method, {@link #upcast(Object)} will not be
	 * called by the framework.
	 *
	 * @param historicalEvent the legacy event instance to transform (never null)
	 * @return a list of current domain event representations (may be empty, must not be null,
	 *         individual elements must not be null)
	 */
	default List<DOMAIN_EVENT> upcastAll ( HISTORICAL_EVENT historicalEvent ) {
		return List.of(upcast(historicalEvent));
	}

	/**
	 * Returns all target event types that this upcaster can produce.
	 * <p>
	 * This method is used by the event store framework to expand queries so that legacy event types
	 * are included when querying for any of the target types. For example, if a legacy event
	 * {@code FullCustomerRegistered} can be upcasted to both {@code CustomerRegisteredV2} and
	 * {@code AddressRecorded}, then querying for either of those types will also fetch the
	 * legacy {@code FullCustomerRegistered} events.
	 * <p>
	 * The default implementation delegates to {@link #targetType()} for backward compatibility.
	 * Override this method when the upcaster produces events of multiple types.
	 *
	 * @return the set of all possible target event type classes (must not be null or empty)
	 */
	@SuppressWarnings("unchecked")
	default Set<Class<? extends DOMAIN_EVENT>> targetTypes ( ) {
		return Set.of((Class<? extends DOMAIN_EVENT>) targetType());
	}

}
