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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a historical event type that requires upcasting to a current event definition.
 * <p>
 * As event-sourced systems evolve, business requirements change and event structures need to be updated.
 * Rather than modifying existing persisted events (which violates event immutability), this annotation
 * enables transparent transformation of legacy events to their current representations when they are read
 * from the event store.
 * <p>
 * Legacy events are typically maintained in a separate sealed interface hierarchy from current events,
 * and are specified as additional type parameters when creating an {@link org.sliceworkz.eventstore.stream.EventStream}.
 * When events are queried from the store, the upcaster automatically transforms legacy event instances
 * to their modern equivalents, ensuring that application code only works with current event definitions.
 * <p>
 * Key benefits:
 * <ul>
 *   <li>Preserves event immutability - historical data remains unchanged</li>
 *   <li>Enables gradual schema evolution without breaking existing event streams</li>
 *   <li>Maintains backward compatibility with old event structures</li>
 *   <li>Transparent to application code - queries return only current event types</li>
 *   <li>Type-safe through Java's sealed interfaces and generic constraints</li>
 * </ul>
 *
 * <h2>Typical Workflow:</h2>
 * <ol>
 *   <li>Create current event definitions in one sealed interface</li>
 *   <li>Move deprecated event structures to a "historical" sealed interface</li>
 *   <li>Annotate each historical event with {@code @LegacyEvent} and specify the upcaster</li>
 *   <li>Implement the {@link Upcast} interface to transform legacy to current events</li>
 *   <li>Include the historical interface when creating the event stream</li>
 * </ol>
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Current event definitions
 * sealed interface CustomerEvent {
 *     record CustomerRegisteredV2(Name name, Email email) implements CustomerEvent {}
 *     record CustomerRenamed(Name name) implements CustomerEvent {}
 * }
 *
 * // Historical event definitions (deprecated, but needed for deserialization)
 * sealed interface CustomerHistoricalEvent {
 *     // Legacy event that stored name as a plain String
 *     @LegacyEvent(upcast = CustomerRegisteredUpcaster.class)
 *     record CustomerRegistered(String name) implements CustomerHistoricalEvent {}
 *
 *     // Legacy event that was renamed
 *     @LegacyEvent(upcast = CustomerNameChangedUpcaster.class)
 *     record CustomerNameChanged(String name) implements CustomerHistoricalEvent {}
 * }
 *
 * // Upcaster implementation
 * public class CustomerRegisteredUpcaster
 *     implements Upcast<CustomerHistoricalEvent.CustomerRegistered, CustomerEvent.CustomerRegisteredV2> {
 *
 *     public CustomerEvent.CustomerRegisteredV2 upcast(CustomerHistoricalEvent.CustomerRegistered legacy) {
 *         return new CustomerEvent.CustomerRegisteredV2(
 *             new Name(legacy.name()),
 *             Email.unknown() // provide default for new required field
 *         );
 *     }
 *
 *     public Class<CustomerEvent.CustomerRegisteredV2> targetType() {
 *         return CustomerEvent.CustomerRegisteredV2.class;
 *     }
 * }
 *
 * // Usage: specify both current and historical event types when creating the stream
 * EventStream<CustomerEvent> stream = eventstore.getEventStream(
 *     streamId,
 *     CustomerEvent.class,           // current event type
 *     CustomerHistoricalEvent.class  // historical event types
 * );
 *
 * // When querying, historical events are automatically upcasted
 * stream.query(EventQuery.matchAll())
 *     .forEach(event -> {
 *         // All events are of type CustomerEvent, never CustomerHistoricalEvent
 *         CustomerEvent currentEvent = event.data();
 *     });
 * }</pre>
 *
 * <h2>Advanced Example - Filtering on Upcasted Types:</h2>
 * <pre>{@code
 * // Query for events that match the upcasted target type
 * // This finds both new CustomerRenamed events AND legacy CustomerNameChanged events
 * stream.query(EventQuery.forEvents(
 *     EventTypesFilter.of(CustomerEvent.CustomerRenamed.class),
 *     Tags.none()
 * )).forEach(event -> {
 *     CustomerEvent.CustomerRenamed renamed = (CustomerEvent.CustomerRenamed) event.data();
 *     System.out.println("Customer renamed to: " + renamed.name());
 * });
 * }</pre>
 *
 * @see Upcast
 * @see org.sliceworkz.eventstore.stream.EventStream
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LegacyEvent {

	/**
	 * The upcaster class that transforms this legacy event to its current representation.
	 * <p>
	 * The specified class must implement {@link Upcast} with generic parameters matching
	 * the legacy event type (annotated with this annotation) and the target current event type.
	 * <p>
	 * The upcaster must have a no-argument constructor, as it will be instantiated by the
	 * event store framework during event deserialization.
	 *
	 * @return the upcaster class that converts this legacy event to its current form
	 */
	Class<? extends Upcast<?,?>> upcast();

}
