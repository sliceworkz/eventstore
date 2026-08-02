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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents the type of an event, identified by a name.
 * <p>
 * EventType is used to distinguish between different kinds of domain events. By default the type name is
 * the event class's simple name (e.g., "CustomerRegistered" for a class named CustomerRegistered). A class
 * annotated with {@link EventName} uses the name that annotation declares instead, which decouples the
 * stored name from the Java class identifier and lets a class be renamed or moved without breaking history.
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
 * @see EventName
 * @see LegacyEvent
 * @see Upcast
 */
public record EventType ( String name ) {

	/**
	 * Per-class cache of the resolved canonical name. Resolution reads an annotation, and
	 * {@code EventType.of} sits on the per-event serialize and in-memory filter-match paths, so the lookup
	 * is worth memoising. {@link ClassValue} keys on the class itself and is collected with it, so this
	 * does not pin classes loaded by a redeployed application's classloader.
	 */
	private static final ClassValue<EventType> CANONICAL_NAMES = new ClassValue<>() {
		@Override
		protected EventType computeValue ( Class<?> clazz ) {
			return new EventType(resolveName(clazz));
		}
	};

	private static final ClassValue<Set<EventType>> ALIASES = new ClassValue<>() {
		@Override
		protected Set<EventType> computeValue ( Class<?> clazz ) {
			return resolveAliases(clazz);
		}
	};

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
	 * The type name is the value of the class's {@link EventName} annotation when it carries one, and the
	 * class's simple name (not the fully qualified name) otherwise.
	 *
	 * @param clazz the class representing the domain event type
	 * @return the canonical EventType for the class
	 * @throws IllegalArgumentException if the class declares a blank {@link EventName}
	 */
	public static final EventType of ( Class<?> clazz ) {
		return CANONICAL_NAMES.get(clazz);
	}

	/**
	 * Returns the read-only alias names a class answers to, as declared by {@link EventName#aliases()}.
	 * <p>
	 * Aliases exist so that events written under a previous name keep deserializing onto the current class
	 * after a rename. They are never written: {@link #of(Class)} is the only name an append can produce.
	 *
	 * @param clazz the class representing the domain event type
	 * @return the alias event types, empty when the class declares none
	 * @throws IllegalArgumentException if an alias is blank or repeats the canonical name
	 */
	public static final Set<EventType> aliasesOf ( Class<?> clazz ) {
		return ALIASES.get(clazz);
	}

	private static String resolveName ( Class<?> clazz ) {
		EventName annotation = clazz.getAnnotation(EventName.class);
		if ( annotation == null ) {
			return clazz.getSimpleName();
		}
		String declared = annotation.value();
		if ( declared == null || declared.isBlank() ) {
			throw new IllegalArgumentException("@EventName on %s must declare a non-blank name".formatted(clazz.getName()));
		}
		return declared;
	}

	private static Set<EventType> resolveAliases ( Class<?> clazz ) {
		EventName annotation = clazz.getAnnotation(EventName.class);
		if ( annotation == null || annotation.aliases().length == 0 ) {
			return Set.of();
		}
		String canonical = resolveName(clazz);
		Arrays.stream(annotation.aliases()).forEach(alias -> {
			if ( alias == null || alias.isBlank() ) {
				throw new IllegalArgumentException("@EventName on %s declares a blank alias".formatted(clazz.getName()));
			}
			if ( alias.equals(canonical) ) {
				throw new IllegalArgumentException("@EventName on %s declares alias '%s', which repeats its own name".formatted(clazz.getName(), alias));
			}
		});
		return Arrays.stream(annotation.aliases())
				.map(EventType::ofType)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

}
