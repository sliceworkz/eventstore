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

/**
 * Represents the type of an event, identified by a name.
 * <p>
 * EventType is used to distinguish between different kinds of domain events. The type name is derived
 * from the event class: its simple name (e.g., "CustomerRegistered" for a class named CustomerRegistered),
 * or the name an {@link EventName} annotation on the class declares. That name is wire format — it is
 * stored with every event and matched by every query — so see {@link EventName} before renaming an event
 * class or giving two classes the same simple name.
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
 * // A class declaring its stored name is named by the annotation, not the class
 * @EventName("CustomerRegistered")
 * record CustomerSignedUp ( String name ) implements CustomerEvent { }
 * EventType.of(CustomerSignedUp.class).name();   // "CustomerRegistered"
 *
 * // Or from a string (useful for querying)
 * EventType type = EventType.ofType("CustomerRegistered");
 * }</pre>
 *
 * @param name the name identifying this event type
 * @see Event
 * @see EventName
 * @see LegacyEvent
 * @see Upcast
 */
public record EventType ( String name ) implements java.io.Serializable {

	/**
	 * Serializable so {@link EventDeserializationException} can carry the stored type name across a
	 * process boundary; see {@link EventReference} for the reasoning.
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * The stored name of each class, resolved once per class.
	 * <p>
	 * {@link #of(Class)} runs on every append, on every serialized event and on every in-memory filter
	 * match, so the annotation is read once and the name kept. A {@link ClassValue} rather than a map:
	 * it is keyed by the class itself, so it pins no class loader and needs no eviction. An annotation
	 * that fails validation is not remembered — {@code computeValue} throwing leaves nothing cached, so
	 * the same class fails the same way on the next call instead of the failure being forgotten.
	 */
	private static final ClassValue<String> STORED_NAMES = new ClassValue<>() {
		@Override
		protected String computeValue ( Class<?> clazz ) {
			EventName eventName = clazz.getAnnotation(EventName.class);
			if ( eventName == null ) {
				return clazz.getSimpleName();
			}
			String name = eventName.value();
			if ( name.isBlank() || !name.equals(name.strip()) ) {
				throw new IllegalArgumentException(
						"@EventName on %s must be a non-blank name without leading or trailing whitespace, was '%s'"
								.formatted(clazz.getName(), name));
			}
			return name;
		}
	};

	/**
	 * Creates an EventType from a domain event object.
	 * <p>
	 * The type name is derived from the object's class, as {@link #of(Class)} does.
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
	 * The type name is the class's simple name (not the fully qualified name), unless the class is
	 * annotated {@link EventName}, in which case it is the annotation's value. This is the only place a
	 * class is turned into a stored name, so the annotation is honoured everywhere a class is: on
	 * append, in a stream's type mappings, and in an {@link org.sliceworkz.eventstore.query.EventTypesFilter}.
	 * The simple name is the intended case and the annotation the exception, for a class whose stored
	 * name cannot be its own name — see {@link EventName} for when that is.
	 *
	 * @param clazz the class representing the domain event type
	 * @return an EventType named by the class's {@code @EventName}, or its simple name
	 * @throws IllegalArgumentException when the class carries an {@code @EventName} that is blank or
	 *         has leading or trailing whitespace
	 */
	public static final EventType of ( Class<?> clazz ) {
		return new EventType(STORED_NAMES.get(clazz));
	}

}
