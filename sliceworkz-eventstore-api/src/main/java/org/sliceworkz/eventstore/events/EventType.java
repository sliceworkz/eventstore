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
 * Represents the type of an event, identified by a name.
 * <p>
 * EventType is used to distinguish between different kinds of domain events. The type name is typically
 * derived from the event class's simple name (e.g., "CustomerRegistered" for a class named CustomerRegistered).
 * <p>
 * Event types support upcasting scenarios where historical events may have different types than their
 * current runtime representation. The {@link Event} record maintains both the current {@code type}
 * and the {@code storedType} to handle these cases.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Event type is automatically determined when creating events
 * EventType type = EventType.of(new CustomerRegistered("John"));
 * // type.name() returns "CustomerRegistered"
 *
 * // Or create from class
 * EventType type = EventType.of(CustomerRegistered.class);
 *
 * // Or from a string (useful for querying)
 * EventType type = EventType.ofType("CustomerRegistered");
 * }</pre>
 *
 * @param name the name identifying this event type
 * @see Event
 * @see LegacyEvent
 * @see Upcast
 */
public record EventType ( String name ) {

	/**
	 * Creates an EventType from a domain event object.
	 * <p>
	 * The type name is derived from the object's class simple name.
	 *
	 * @param object the domain event object
	 * @return an EventType based on the object's class
	 */
	public static final EventType of ( Object object ) {
		return of(object.getClass());
	}

	/**
	 * Creates an EventType from a string name.
	 * <p>
	 * Use this method when constructing queries or working with event types as strings.
	 *
	 * @param type the event type name
	 * @return an EventType with the specified name
	 */
	public static final EventType ofType ( String type ) {
		return new EventType(type);
	}

	/**
	 * Creates an EventType from a class.
	 * <p>
	 * The type name is derived from the class's simple name (not the fully qualified name).
	 *
	 * @param clazz the class representing the domain event type
	 * @return an EventType based on the class's simple name
	 */
	public static final EventType of ( Class<?> clazz ) {
		return new EventType(clazz.getSimpleName());
	}

}
