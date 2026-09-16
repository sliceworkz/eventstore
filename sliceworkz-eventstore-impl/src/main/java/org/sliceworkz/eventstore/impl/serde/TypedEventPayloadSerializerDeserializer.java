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
package org.sliceworkz.eventstore.impl.serde;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingException;

import tools.jackson.core.JacksonException;

/**
 * Typed mode implementation of {@link EventPayloadSerializerDeserializer} that maps events to/from Java objects.
 * <p>
 * This implementation provides type-safe event handling with full support for:
 * <ul>
 *   <li>Sealed interfaces for discovering event types automatically</li>
 *   <li>Event upcasting from historical/legacy events using {@link LegacyEvent} annotations</li>
 *   <li>Personal data protected in place as {@link org.sliceworkz.eventstore.shredding.Shreddable} values</li>
 * </ul>
 * <p>
 * Event types must be registered via {@link #registerEventTypes(Class)} before they can be serialized or deserialized.
 * <p>
 * <strong>Upcasting follows a chain until it reaches a current type.</strong> An upcaster's
 * {@link Upcast#targetTypes() target} may itself be a {@link LegacyEvent}, registered on the same
 * stream, whose own upcaster is then applied to what the first one produced: a history written as
 * {@code V1}, then {@code V2}, then {@code V3} reads through the two upcasters that were written when
 * each version arrived, and nothing has to be rewritten into a {@code V1 → V3} upcaster when {@code V3}
 * arrives. A query or a consistency boundary over {@code V3} fetches the {@code V1} events too, since
 * {@link #determineLegacyTypes(Set)} traces the chain back to every legacy type that reaches a current
 * one. The alternative — one hop, with a legacy target handed back as if it were current — loses
 * because the stream's type parameter then lies: the value is a legacy class the caller's exhaustive
 * {@code switch} cannot cast, and the query path has no way to know that {@code V1} is a {@code V3}.
 * <p>
 * What an upcaster declares is checked, once the registrations are complete, by {@link #validate()}:
 * every target type must be a type registered on this stream, current or legacy, and the chains must
 * not cycle. Both fail as {@link IllegalArgumentException} at stream creation, like the other
 * registration checks, because a target this stream does not know is a query that silently skips
 * the legacy events (the trace-back finds no current type behind them) and a cycle is a read that
 * never returns. What an upcaster <em>produces</em> is checked on the read path: an event whose class
 * is not among the declared targets is reported as an {@link EventDeserializationException} naming
 * the upcaster, since a query for that type would never have fetched the event it came from.
 *
 * @see EventPayloadSerializerDeserializer#typed()
 */
public class TypedEventPayloadSerializerDeserializer extends AbstractEventPayloadSerializerDeserializer {

	/**
	 * Builds a typed serde with no shredding support. Registering an event type that declares a
	 * {@link Shreddable} component on it fails.
	 */
	public TypedEventPayloadSerializerDeserializer ( ) {
		super();
	}

	/**
	 * @param shreddingCodec seals and unseals {@link Shreddable} values, or null for no shredding
	 */
	public TypedEventPayloadSerializerDeserializer ( ShreddingCodec shreddingCodec ) {
		super(shreddingCodec);
	}

	private final Map<String,EventDeserializer> deserializers = new HashMap<>();
	/** every registered class by its stored name: what an upcast target has to resolve to */
	private final Map<String, Class<?>> registeredClasses = new HashMap<>();
	/** the legacy registrations by stored name; the upcast graph is resolved over these */
	private final Map<String, LegacyRegistration> legacyRegistrations = new HashMap<>();
	/**
	 * Resolved by {@link #validate()} over the complete set of registrations, and null until then. A
	 * registration after it clears it, so the next read or {@code validate()} resolves the graph again.
	 */
	private volatile UpcastGraph upcastGraph;

	/**
	 * A legacy type as registered: its upcaster, and the concrete event classes the upcaster declares it
	 * produces, with a sealed interface among {@link Upcast#targetTypes()} already resolved into the
	 * types under it.
	 */
	record LegacyRegistration ( EventType type, Class<?> clazz, Upcast<Object,Object> upcaster, Class<?> upcasterClass, Set<Class<?>> declaredTargets ) { }

	/**
	 * The upcast chains, resolved: for every registered type the current types it reads as (a current
	 * type reads as itself; a legacy type as whatever the end of every chain from it produces, which is
	 * nothing for an upcaster that drops its event), and the registration to continue a chain with when
	 * an upcaster produces a legacy event.
	 */
	record UpcastGraph ( Map<EventType, Set<EventType>> currentTypesOf, Map<Class<?>, LegacyRegistration> legacyByClass ) { }
	
	@Override
	public List<TypeAndPayload> deserialize ( TypeAndSerializedPayload serialized ) {
		EventType storedType = serialized.type();

		// "No mapping" is resolved before the try rather than thrown inside it. It used to be thrown into
		// the catch below and immediately re-wrapped, so the message naming the missing type only ever
		// reached a user as the cause of a second, vaguer one.
		EventDeserializer deserializer = deserializers.get(storedType.name());
		if ( deserializer == null ) {
			throw new EventDeserializationException(storedType, deserializers.isEmpty()
					? "No mapping found for event type '%s': this stream has no event types registered. Pass the Event root Class when creating the EventStream."
							.formatted(storedType.name())
					: "No mapping found for event type '%s'. Known mappings: %s. Either the stream was opened without the Event root Class covering it, or the event class was renamed (its simple name is the stored name)."
							.formatted(storedType.name(), knownMappings()));
		}

		try {
			return deserializer.deserialize(serialized.immutablePayload());
		} catch (EventDeserializationException e) {
			// already precise about what failed -- wrapping it again would only bury the message
			throw e;
		} catch (ShreddingException e) {
			// A key store that could not be reached is retryable; an unreadable event is not. Wrapping
			// this in EventDeserializationException would tell a caller to give up on an event that is
			// perfectly readable once the key store is back, and a Projector would bookmark past it.
			throw e;
		} catch (RuntimeException e) {
			throw new EventDeserializationException(storedType,
					"Failed to deserialize event data for type '%s': %s".formatted(storedType.name(), e.getMessage()), e);
		}
	}

	private String knownMappings ( ) {
		return deserializers.keySet().stream().sorted().collect(Collectors.joining(", ", "[", "]"));
	}
	
	@Override
	public TypedEventPayloadSerializerDeserializer registerEventTypes(Class<?> rootClass) {
		deserializersFor(rootClass).forEach(m->registerEventType(m.name(), m.clazz(), false));
		
		return this;
	}
	
	@Override
	public TypedEventPayloadSerializerDeserializer registerLegacyEventTypes(Class<?> rootClass) {
		deserializersFor(rootClass).forEach(m->registerEventType(m.name(), m.clazz(), true));
		
		return this;
	}

	@SuppressWarnings("unchecked")
	private void registerEventType ( String eventName, Class<?> clazz, boolean assumeUpcasters ) {
		String key = eventName;
		if ( deserializers.containsKey(key) ) {
			throw new IllegalArgumentException("duplicate event name " + key);
		}

		if ( shreddingCodec == null && declaresShreddable(clazz) ) {
			// Fails here, at stream creation, rather than on the first append: a store with no codec has
			// no key to seal with, so it would write personal data in the clear and leave nothing to
			// destroy when an erasure is asked for.
			throw new IllegalArgumentException(
					"event type %s declares a Shreddable component but this store has no ShreddingCodec configured; personal data would be stored in the clear and could never be erased. Configure shredding on the storage builder, or via EventStoreFactory.eventStore(storage, registry, meterOptions, codec)."
							.formatted(clazz.getName()));
		}

		EventType eventType = EventType.ofType(eventName);
		EventDeserializer eventDeserializer = new InstantiationEventDeserializer(clazz, eventType);
		upcastGraph = null;

		// when we need to upcast an historical legacy event
		if ( clazz.isAnnotationPresent(LegacyEvent.class)) {

			if ( !assumeUpcasters ) {
				throw new IllegalArgumentException("Event type %s should not be annotated as a @LegacyEvent, or moved to the legacy Event types".formatted(clazz));
			}

			LegacyEvent annotation = clazz.getAnnotation(LegacyEvent.class);
			Class<? extends Upcast<?,?>> upcastClass = annotation.upcast();
			Upcast<Object, Object> upcast;
			try {
				upcast = (Upcast<Object, Object>) upcastClass.getDeclaredConstructor().newInstance(new Object[0]);
			} catch (InvocationTargetException e) {
				// the constructor ran and threw: report what it threw, not the reflective wrapper
				throw new IllegalArgumentException(
						"Upcaster %s declared by @LegacyEvent on %s threw from its no-argument constructor: %s".formatted(
								upcastClass.getName(), clazz.getName(), e.getTargetException()),
						e.getTargetException());
			} catch (ReflectiveOperationException e) {
				// NoSuchMethod (no no-arg constructor -- an inner class needs to be static), Instantiation
				// (abstract or an interface) or IllegalAccess (not public). All are "the annotation names a
				// class that cannot be instantiated", and all three used to arrive as a bare
				// RuntimeException naming neither the upcaster nor the event.
				throw new IllegalArgumentException(
						"Upcaster %s declared by @LegacyEvent on %s cannot be instantiated: %s. It needs a public no-argument constructor, and must be a concrete, non-inner (or static nested) class."
								.formatted(upcastClass.getName(), clazz.getName(), e),
						e);
			}

			Set<Class<?>> targetClasses = upcast.targetTypes();
			if ( targetClasses == null ) {
				throw new IllegalArgumentException(
						"Upcaster %s declared by @LegacyEvent on %s returned null from targetTypes(); return an empty Set for an upcaster that produces no events."
								.formatted(upcastClass.getName(), clazz.getName()));
			}
			LegacyRegistration registration = new LegacyRegistration(eventType, clazz, upcast, upcastClass, declaredTargets(targetClasses, upcastClass, clazz));
			legacyRegistrations.put(key, registration);

			eventDeserializer = new InstantiationAndUpcastEventDeserializer(eventDeserializer, registration);

		} else {
			if  ( assumeUpcasters ) {
				throw new IllegalArgumentException("legacy Event type %s should be annotated as a @LegacyEvent and configured with an Upcaster".formatted(clazz));
			}
		}

		registeredClasses.put(key, clazz);
		deserializers.put(key, eventDeserializer);
	}

	/**
	 * The concrete event classes an upcaster declares, with a sealed interface among them standing for
	 * every event type under it — the same rule {@link org.sliceworkz.eventstore.query.EventTypesFilter}
	 * applies to a filter, so an upcaster typed over a whole hierarchy can declare the hierarchy. Whether
	 * they are registered is not decided here: a target may sit in a legacy root registered after this
	 * one, and the roots arrive as a {@link Set}, in no order. {@link #validate()} decides, over all of them.
	 */
	private Set<Class<?>> declaredTargets ( Set<Class<?>> targetClasses, Class<?> upcastClass, Class<?> legacyClass ) {
		Set<Class<?>> result = new LinkedHashSet<>();
		for ( Class<?> target : targetClasses ) {
			if ( target == null ) {
				throw new IllegalArgumentException(
						"Upcaster %s declared by @LegacyEvent on %s returned a null element from targetTypes()."
								.formatted(upcastClass.getName(), legacyClass.getName()));
			}
			collectDeclaredTargets(target, result, upcastClass, legacyClass);
		}
		return Collections.unmodifiableSet(result);
	}

	private void collectDeclaredTargets ( Class<?> target, Set<Class<?>> into, Class<?> upcastClass, Class<?> legacyClass ) {
		if ( target.isInterface() ) {
			if ( !target.isSealed() ) {
				throw new IllegalArgumentException(
						"Upcaster %s declared by @LegacyEvent on %s names the interface %s in targetTypes(), which is not sealed, so the event types under it cannot be determined; name the event classes, or a sealed interface over them."
								.formatted(upcastClass.getName(), legacyClass.getName(), target.getName()));
			}
			for ( Class<?> permitted : target.getPermittedSubclasses() ) {
				collectDeclaredTargets(permitted, into, upcastClass, legacyClass);
			}
		} else {
			into.add(target);
		}
	}

	/**
	 * Resolves the upcast chains over everything registered so far, and fails for what cannot be
	 * resolved. Idempotent; a read that arrives before it runs it first, so a serde used without the
	 * call behaves the same and only fails later.
	 *
	 * @throws IllegalArgumentException for an upcaster naming a target type this serde has no
	 *         registration for — or a different class under that stored name — and for upcasters that
	 *         form a cycle
	 */
	@Override
	public synchronized TypedEventPayloadSerializerDeserializer validate ( ) {
		if ( upcastGraph != null ) {
			return this;
		}

		Map<Class<?>, LegacyRegistration> legacyByClass = new HashMap<>();
		legacyRegistrations.values().forEach(r -> legacyByClass.put(r.clazz(), r));

		for ( LegacyRegistration registration : legacyRegistrations.values() ) {
			for ( Class<?> target : registration.declaredTargets() ) {
				Class<?> registered = registeredClasses.get(EventType.of(target).name());
				if ( registered == null ) {
					throw new IllegalArgumentException(
							"Upcaster %s declared by @LegacyEvent on %s names %s in targetTypes(), which is not registered on this stream (known mappings: %s). Register the root class covering it, as a current event type or as a further legacy type to upcast on from."
									.formatted(registration.upcasterClass().getName(), registration.clazz().getName(), target.getName(), knownMappings()));
				}
				if ( registered != target ) {
					throw new IllegalArgumentException(
							"Upcaster %s declared by @LegacyEvent on %s names %s in targetTypes(), but the type stored as '%s' on this stream is %s. A stored name maps to one class; give one of them another stored name with @EventName."
									.formatted(registration.upcasterClass().getName(), registration.clazz().getName(), target.getName(), EventType.of(target).name(), registered.getName()));
				}
			}
		}

		Map<EventType, Set<EventType>> currentTypesOf = new HashMap<>();
		registeredClasses.forEach((name, clazz) -> {
			LegacyRegistration registration = legacyRegistrations.get(name);
			if ( registration == null ) {
				currentTypesOf.put(EventType.ofType(name), Set.of(EventType.ofType(name)));
			} else {
				currentTypesOf.put(registration.type(), currentTypesReachedFrom(registration, legacyByClass, new ArrayDeque<>(), currentTypesOf));
			}
		});

		upcastGraph = new UpcastGraph(Map.copyOf(currentTypesOf), Map.copyOf(legacyByClass));
		return this;
	}

	/**
	 * The current types every chain from a legacy type ends in, walking the declared targets depth-first
	 * and memoising in {@code resolved}; a legacy type met again on the path being walked is a cycle.
	 */
	private Set<EventType> currentTypesReachedFrom ( LegacyRegistration registration, Map<Class<?>, LegacyRegistration> legacyByClass, Deque<LegacyRegistration> path, Map<EventType, Set<EventType>> resolved ) {
		Set<EventType> already = resolved.get(registration.type());
		if ( already != null ) {
			return already;
		}
		if ( path.contains(registration) ) {
			List<String> cycle = new ArrayList<>();
			boolean inCycle = false;
			for ( LegacyRegistration onPath : path ) {
				inCycle |= onPath == registration;
				if ( inCycle ) {
					cycle.add("%s (%s)".formatted(onPath.clazz().getName(), onPath.upcasterClass().getName()));
				}
			}
			cycle.add(registration.clazz().getName());
			throw new IllegalArgumentException(
					"the upcasters declared by @LegacyEvent form a cycle, so a legacy event would never upcast into a current type: %s. Every chain of upcasters has to end in a current event type."
							.formatted(String.join(" -> ", cycle)));
		}
		path.addLast(registration);
		Set<EventType> result = new HashSet<>();
		for ( Class<?> target : registration.declaredTargets() ) {
			LegacyRegistration next = legacyByClass.get(target);
			if ( next == null ) {
				result.add(EventType.of(target));
			} else {
				result.addAll(currentTypesReachedFrom(next, legacyByClass, path, resolved));
			}
		}
		path.removeLast();
		Set<EventType> reached = Set.copyOf(result);
		resolved.put(registration.type(), reached);
		return reached;
	}

	private UpcastGraph upcastGraph ( ) {
		UpcastGraph graph = upcastGraph;
		if ( graph == null ) {
			validate();
			graph = upcastGraph;
		}
		return graph;
	}
	
	/**
	 * The event classes under a root: every permitted class of a sealed interface, walking nested sealed
	 * interfaces, or the class itself when the root is a class. Interfaces are walked and never recorded:
	 * an interface is not the name of any stored event, and a filter naming one has already been resolved
	 * into the event types under it by {@link org.sliceworkz.eventstore.query.EventTypesFilter#of(List)}
	 * by the time it reaches this serde.
	 */
	private Set<EventNameAndEventClass> deserializersFor ( Class<?> eventRootClass ) {
		Set<EventNameAndEventClass> result = Collections.emptySet();
		if ( eventRootClass != null && !eventRootClass.equals(Object.class)) {
			if ( eventRootClass.isInterface() ) {
				
				if ( ! eventRootClass.isSealed() ) {
					throw new IllegalArgumentException("interface %s should be sealed to allow Event Type determination".formatted(eventRootClass.getName()));
				}
				
				Class<?>[] permittedSubclassses = eventRootClass.getPermittedSubclasses();
				if ( permittedSubclassses != null && permittedSubclassses.length > 0 ) {
					
					result = new HashSet<>();
					
					for ( Class<?> psc: permittedSubclassses ) {
						if ( psc.isInterface() ) {
							result.addAll(deserializersFor(psc));
						} else {
							result.add(EventNameAndEventClass.of(psc));
						}
					}
					
				} else {
					result = Collections.emptySet();
				}
			} else {
				result = Set.of(EventNameAndEventClass.of(eventRootClass));
			}
		}
		return result;
	}
	
	record EventNameAndEventClass (String name, Class<?> clazz) { 
		public static EventNameAndEventClass of ( Class<?> clazz ) {
			return new EventNameAndEventClass(EventType.of(clazz).name(), clazz);
		}
	}

	@Override
	public boolean canDeserialize(String eventTypeName) {
		return deserializers.keySet().contains(eventTypeName);
	}
	
	
	
	interface EventDeserializer {
		List<TypeAndPayload> deserialize ( String payload );
	}
	
	class InstantiationEventDeserializer implements EventDeserializer {
		
		private final Class<?> eventClass;
		private final EventType eventType;
		
		public InstantiationEventDeserializer ( Class<?> eventClass, EventType eventType ) {
			this.eventClass = eventClass;
			this.eventType = eventType;
		}

		@Override
		public List<TypeAndPayload> deserialize ( String payload ) {
			Object object;
			try {
				object = objectMapper.readValue(payload, eventClass);
			} catch (JacksonException e) {
				if ( e.getCause() instanceof ShreddingException shredding ) {
					// Jackson wraps whatever a ValueDeserializer throws. Unwrap so that "the key store is
					// unreachable" does not arrive as "this event can never be read".
					throw shredding;
				}
				// One catch, not two: DatabindException is a JacksonException, and both used to throw a
				// bare RuntimeException(e) -- no message of their own, so the only thing a user saw was
				// Jackson's field-level complaint with nothing saying which event class it was aimed at.
				throw new EventDeserializationException(eventType,
						"Failed to deserialize stored event type '%s' onto %s: %s".formatted(
								eventType.name(), eventClass.getName(), e.getOriginalMessage()),
						e);
			}
			return List.of(new TypeAndPayload(eventType, object));
		}

	}
	
	class InstantiationAndUpcastEventDeserializer implements EventDeserializer {

		private final LegacyRegistration registration;
		private final EventDeserializer deser;

		public InstantiationAndUpcastEventDeserializer ( EventDeserializer deser, LegacyRegistration registration ) {
			this.deser = deser;
			this.registration = registration;
		}

		@Override
		public List<TypeAndPayload> deserialize ( String payload ) {
			TypeAndPayload historical = deser.deserialize(payload).getFirst();
			return upcast(registration, historical.eventData(), historical.type(), upcastGraph().legacyByClass());
		}

		/**
		 * One hop, and the hops after it: what the upcaster produces is checked against what it declared,
		 * and a produced event that is itself a registered legacy type goes through its own upcaster
		 * before anything is returned, so a caller only ever sees current types. The chain terminates
		 * because {@link #validate()} refused a cycle before the first read.
		 */
		private List<TypeAndPayload> upcast ( LegacyRegistration hop, Object legacyEvent, EventType storedType, Map<Class<?>, LegacyRegistration> legacyByClass ) {
			List<Object> upcastedEvents;
			try {
				upcastedEvents = hop.upcaster().upcast(legacyEvent);
			} catch (RuntimeException e) {
				// An upcaster is application code running on the read path, and Upcast's own javadoc warns
				// that legacy data may not satisfy a current record's validation. Name the upcaster, so
				// its failure is not read as Jackson failing to parse the JSON.
				throw new EventDeserializationException(storedType,
						"Upcaster %s threw while upcasting %s from stored event type '%s': %s".formatted(
								hop.upcasterClass().getName(), hop.type().name(), storedType.name(), e),
						e);
			}
			if ( upcastedEvents == null ) {
				throw new EventDeserializationException(storedType,
						"Upcaster %s returned null for stored event type '%s'; return an empty List to drop an event."
								.formatted(hop.upcasterClass().getName(), storedType.name()));
			}
			List<TypeAndPayload> result = new ArrayList<>(upcastedEvents.size());
			for ( Object upcasted : upcastedEvents ) {
				if ( upcasted == null ) {
					throw new EventDeserializationException(storedType,
							"Upcaster %s returned a null element for stored event type '%s'; return an empty List to drop an event."
									.formatted(hop.upcasterClass().getName(), storedType.name()));
				}
				if ( !hop.declaredTargets().contains(upcasted.getClass()) ) {
					// A query for the produced type fetches the legacy events its upcasters *declare*, so an
					// event produced outside the declaration was never going to be found by one, and its
					// class may not even be registered on this stream.
					throw new EventDeserializationException(storedType,
							"Upcaster %s produced a %s for stored event type '%s', which is not among the types its targetTypes() declares %s; declare every type the upcaster can produce."
									.formatted(hop.upcasterClass().getName(), upcasted.getClass().getName(), storedType.name(),
											hop.declaredTargets().stream().map(Class::getName).sorted().collect(Collectors.joining(", ", "[", "]"))));
				}
				LegacyRegistration next = legacyByClass.get(upcasted.getClass());
				if ( next == null ) {
					result.add(new TypeAndPayload(EventType.of(upcasted), upcasted));
				} else {
					result.addAll(upcast(next, upcasted, storedType, legacyByClass));
				}
			}
			return result;
		}

	}

	@Override
	public Set<EventType> determineLegacyTypes(Set<EventType> currentTypes) {
		// the current types themselves, always, plus every legacy type whose chain of upcasters ends in
		// one of them. The names are stored type names: a sealed interface never reaches here, since
		// EventTypesFilter resolves one into the event types under it when the filter is built
		Set<EventType> result = new HashSet<>(currentTypes);
		result.addAll(upcastGraph().currentTypesOf().entrySet().stream()
				.filter(e -> e.getValue().stream().anyMatch(currentTypes::contains))
				.map(Map.Entry::getKey)
				.collect(Collectors.toSet()));
		return result;
	}

	/**
	 * Returns true to indicate this is a typed serializer/deserializer.
	 * <p>
	 * This information is used for observability and metrics tagging.
	 *
	 * @return true (typed mode)
	 */
	@Override
	public boolean isTyped() {
		return true;
	}

}
