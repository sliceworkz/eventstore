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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transforms a legacy event into its current domain event representation.
 * <p>
 * This interface is the core of the upcasting mechanism, enabling event-sourced systems to evolve
 * their event schemas over time without modifying stored events. When legacy events annotated with
 * {@link LegacyEvent} are read from the event store, their upcaster is invoked to transform them into
 * current event definitions.
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
 * sealed interface LegacyCustomerEvent {
 *     @LegacyEvent(upcaster = CustomerRegisteredUpcaster.class)
 *     record CustomerRegistered(String name) implements LegacyCustomerEvent {}
 * }
 *
 * // Current event (used in application)
 * sealed interface CustomerEvent {
 *     record CustomerRegisteredV2(Name name) implements CustomerEvent {}
 * }
 *
 * // Upcaster: one target, so targetTypes() is derived from the type argument
 * public class CustomerRegisteredUpcaster
 *     implements Upcaster<LegacyCustomerEvent.CustomerRegistered, CustomerEvent.CustomerRegisteredV2> {
 *
 *     @Override
 *     public List<CustomerEvent.CustomerRegisteredV2> upcast(LegacyCustomerEvent.CustomerRegistered legacy) {
 *         return List.of(new CustomerEvent.CustomerRegisteredV2(new Name(legacy.name())));
 *     }
 * }
 * }</pre>
 *
 * <h2>Example - Splitting One Event Into Multiple:</h2>
 * <pre>{@code
 * // Legacy event that combined customer and address data
 * @LegacyEvent(upcaster = FullCustomerRegisteredUpcaster.class)
 * record FullCustomerRegistered(String name, String street, String city) implements LegacyCustomerEvent {}
 *
 * // Current events: split into two separate concerns
 * record CustomerRegisteredV2(Name name) implements CustomerEvent {}
 * record AddressRecorded(String street, String city) implements CustomerEvent {}
 *
 * public class FullCustomerRegisteredUpcaster
 *     implements Upcaster<LegacyCustomerEvent.FullCustomerRegistered, CustomerEvent> {
 *
 *     @Override
 *     public List<CustomerEvent> upcast(LegacyCustomerEvent.FullCustomerRegistered legacy) {
 *         return List.of(
 *             new CustomerEvent.CustomerRegisteredV2(new Name(legacy.name())),
 *             new CustomerEvent.AddressRecorded(legacy.street(), legacy.city())
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
 * <h2>Example - A Chain of Versions:</h2>
 * <pre>{@code
 * // Three versions of one event. The first two are legacy; each upcasts to the next, and the
 * // upcaster written when V2 arrived is left alone when V3 arrives
 * sealed interface LegacyCustomerEvent {
 *     @LegacyEvent(upcaster = V1ToV2.class)
 *     record CustomerRegistered(String name) implements LegacyCustomerEvent {}
 *     @LegacyEvent(upcaster = V2ToV3.class)
 *     record CustomerRegisteredV2(String name, String email) implements LegacyCustomerEvent {}
 * }
 *
 * // the target is a legacy type: V2ToV3 runs next
 * public class V1ToV2 implements Upcaster<LegacyCustomerEvent.CustomerRegistered, LegacyCustomerEvent.CustomerRegisteredV2> {
 *     ...
 * }
 * }</pre>
 * A stored {@code CustomerRegistered} then reads as a {@code CustomerRegisteredV3}, and a query or a
 * consistency boundary over {@code CustomerRegisteredV3} fetches the stored {@code CustomerRegistered}
 * events too. The chain has to end in a current type; a cycle is rejected at stream creation.
 *
 * <h2>Example - Filtering Out Events (0 Events):</h2>
 * <pre>{@code
 * // Legacy event that is no longer relevant
 * @LegacyEvent(upcaster = ObsoleteEventUpcaster.class)
 * record CustomerNoteAdded(String note) implements LegacyCustomerEvent {}
 *
 * public class ObsoleteEventUpcaster
 *     implements Upcaster<LegacyCustomerEvent.CustomerNoteAdded, CustomerEvent> {
 *
 *     @Override
 *     public List<CustomerEvent> upcast(LegacyCustomerEvent.CustomerNoteAdded legacy) {
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
 * @param <LEGACY_EVENT> the legacy event type (annotated with {@link LegacyEvent})
 * @param <TARGET_EVENT> the event type to transform into: a current one, or the next legacy version
 *        on a chain of upcasters that ends in one
 * @see LegacyEvent
 * @see org.sliceworkz.eventstore.stream.EventStream
 */
public interface Upcaster<LEGACY_EVENT,TARGET_EVENT> {

	/**
	 * Transforms a legacy event instance to zero or more current domain event representations.
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
	 *   <li><b>Splitting:</b> Return {@code List.of(event1, event2)} to split one legacy event
	 *       into multiple current events</li>
	 *   <li><b>Filtering:</b> Return {@code List.of()} to exclude obsolete or irrelevant legacy events</li>
	 * </ul>
	 * <p>
	 * All events in the returned list share the same {@link org.sliceworkz.eventstore.events.EventReference},
	 * {@link Tags}, and timestamp from the original stored event.
	 *
	 * @param legacyEvent the legacy event instance to transform (never null)
	 * @return a list of current domain event representations (may be empty, must not be null,
	 *         individual elements must not be null)
	 */
	List<TARGET_EVENT> upcast ( LEGACY_EVENT legacyEvent );

	/**
	 * Returns all target event types that this upcaster can produce.
	 * <p>
	 * This method is used by the event store framework to expand queries so that legacy event types
	 * are included when querying for any of the target types. For example, if a legacy event
	 * {@code FullCustomerRegistered} can be upcasted to both {@code CustomerRegisteredV2} and
	 * {@code AddressRecorded}, then querying for either of those types will also fetch the
	 * legacy {@code FullCustomerRegistered} events.
	 * <p>
	 * <strong>The default is the {@code TARGET_EVENT} type argument, for an upcaster declared over one
	 * event class.</strong> An upcaster {@code implements Upcaster<CustomerRegistered, CustomerRegisteredV2>}
	 * produces {@code CustomerRegisteredV2} and nothing else, and its declaration already says so, so
	 * it need not say it twice: the default reads the type argument off the class (through a generic
	 * superclass or superinterface too, where the argument is bound there) and answers that one class.
	 * Whatever the default answers is checked exactly as an override is, below. The default cannot
	 * answer where the declaration does not fix one event class — a sealed interface as the type
	 * argument (the shape of an upcaster that splits or drops its event), a raw {@code Upcaster}, a type
	 * variable the class leaves open — and throws {@link IllegalStateException} saying so, which the
	 * store reports as an {@link IllegalArgumentException} at stream creation; such an upcaster
	 * overrides this method. The alternative — reading a sealed interface as "every type under it" —
	 * loses because an upcaster that drops its event, or produces one of the types, would then declare
	 * the whole hierarchy by omission, and every query for any type in it would fetch the legacy events
	 * only to discard what they upcast into, with nothing to say the declaration was never made.
	 * <p>
	 * Overriding: for one-to-one upcasters, return a singleton set: {@code Set.of(MyEvent.class)}.
	 * For filtering upcasters that produce no events, return an empty set: {@code Set.of()}.
	 * A sealed interface among the classes stands for every event type under it, as it does in an
	 * {@link org.sliceworkz.eventstore.query.EventTypesFilter}.
	 * <p>
	 * <strong>What is declared here is a commitment, and it is checked.</strong> Every class named must
	 * be an event type registered on the stream this upcaster is read through — a current type, or a
	 * further {@link LegacyEvent} whose own upcaster is then applied to what this one produced — and
	 * the chains so formed must end in a current type. A target the stream does not register, or a
	 * cycle, is an {@link IllegalArgumentException} at stream creation: a query for a type this
	 * upcaster does not declare would never fetch the legacy events it produces that type from, so an
	 * undeclared target is a silent gap in every read, not a slower one. On the read itself, an event
	 * produced outside the declared set fails as an
	 * {@link org.sliceworkz.eventstore.events.EventDeserializationException} naming this upcaster.
	 *
	 * @return the set of all possible target event type classes (must not be null)
	 * @throws IllegalStateException from the default, when the {@code TARGET_EVENT} type argument of
	 *         this class is not one event class
	 */
	@SuppressWarnings("unchecked")
	default Set<Class<? extends TARGET_EVENT>> targetTypes ( ) {
		return Set.of((Class<? extends TARGET_EVENT>) declaredTargetType(getClass()));
	}

	/**
	 * The {@code TARGET_EVENT} type argument of an upcaster class, when the declaration fixes it to one
	 * event class.
	 */
	private static Class<?> declaredTargetType ( Class<?> upcasterClass ) {
		Type target = targetArgument(upcasterClass, Map.of());
		if ( target instanceof Class<?> clazz && !clazz.isInterface() ) {
			return clazz;
		}
		String declared = target == null
				? "not fixed by its declaration (a raw Upcaster, or a type variable left open)"
				: target instanceof Class<?> ? "the interface " + target.getTypeName() : target.getTypeName();
		throw new IllegalStateException(
				"Upcaster %s does not override targetTypes(), and its TARGET_EVENT type argument is %s, so the types it produces cannot be derived from its declaration. Override targetTypes() to declare them: the event classes it produces, a sealed interface standing for every type under it, or an empty Set for an upcaster that drops its event."
						.formatted(upcasterClass.getName(), declared));
	}

	/**
	 * Walks the supertypes of {@code type} to {@code Upcaster} and answers its second type argument,
	 * with the type variables bound on the way substituted; null where the walk finds a raw
	 * {@code Upcaster} or an argument no declaration on the path fixes.
	 */
	private static Type targetArgument ( Type type, Map<TypeVariable<?>, Type> bindings ) {
		if ( type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw ) {
			TypeVariable<?>[] variables = raw.getTypeParameters();
			Type[] arguments = parameterized.getActualTypeArguments();
			Map<TypeVariable<?>, Type> bound = new HashMap<>();
			for ( int i = 0; i < variables.length; i++ ) {
				bound.put(variables[i], bindings.getOrDefault(arguments[i], arguments[i]));
			}
			if ( raw == Upcaster.class ) {
				Type target = bound.get(variables[1]);
				return target instanceof TypeVariable<?> ? null : target;
			}
			return targetArgumentOfSupertypes(raw, bound);
		}
		if ( type instanceof Class<?> clazz && clazz != Upcaster.class ) {
			return targetArgumentOfSupertypes(clazz, Map.of());
		}
		return null;
	}

	private static Type targetArgumentOfSupertypes ( Class<?> clazz, Map<TypeVariable<?>, Type> bindings ) {
		for ( Type supertype : clazz.getGenericInterfaces() ) {
			Type target = targetArgument(supertype, bindings);
			if ( target != null ) {
				return target;
			}
		}
		Type superclass = clazz.getGenericSuperclass();
		return superclass == null ? null : targetArgument(superclass, bindings);
	}

}
