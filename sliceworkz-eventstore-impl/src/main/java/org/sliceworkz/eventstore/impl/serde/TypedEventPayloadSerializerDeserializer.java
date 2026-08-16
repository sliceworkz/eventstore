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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typed mode implementation of {@link EventPayloadSerializerDeserializer} that maps events to/from Java objects.
 * <p>
 * This implementation provides type-safe event handling with full support for:
 * <ul>
 *   <li>Sealed interfaces for discovering event types automatically</li>
 *   <li>Event upcasting from historical/legacy events using {@link LegacyEvent} annotations</li>
 *   <li>GDPR compliance via separate storage of erasable fields</li>
 * </ul>
 * <p>
 * Event types must be registered via {@link #registerEventTypes(Class)} before they can be serialized or deserialized.
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
	private final Map<EventType, Set<EventType>> mostRecentTypes = new HashMap<>();
	private final Map<EventType, Set<EventType>> mostRecentMultiTypes = new HashMap<>(); // for interface hierarchies, this maps interface->set of interface-implementing event types
	
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
			return deserializer.deserialize(serialized.immutablePayload(), serialized.erasablePayload());
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
		deserializersFor(rootClass, Collections.emptySet()).forEach(m->registerEventType(m.name(), m.clazz(), false));
		
		return this;
	}
	
	@Override
	public TypedEventPayloadSerializerDeserializer registerLegacyEventTypes(Class<?> rootClass) {
		deserializersFor(rootClass, Collections.emptySet()).forEach(m->registerEventType(m.name(), m.clazz(), true));
		
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
			mostRecentTypes.put(eventType, targetClasses.stream().map(EventType::of).collect(Collectors.toSet()));

			eventDeserializer = new InstantiationAndUpcastEventDeserializer(eventDeserializer, upcast, upcastClass);

		} else {
			if  ( assumeUpcasters ) {
				throw new IllegalArgumentException("legacy Event type %s should be annotated as a @LegacyEvent and configured with an Upcaster".formatted(clazz));
			}
			mostRecentTypes.put(eventType, Set.of(eventType)); // no upcasting needed
		}


		deserializers.put(key, eventDeserializer);
	}
	
	private Set<EventNameAndEventClass> deserializersFor ( Class<?> eventRootClass, Set<EventType> implementedInterfaces ) {
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
							
							Set<EventType> newImplementedInterfaces = new HashSet<>(implementedInterfaces);
							newImplementedInterfaces.add(EventType.of(psc));
							result.addAll(deserializersFor(psc, newImplementedInterfaces));
						} else {
							result.add(EventNameAndEventClass.of(psc));

							registerEventTypeWithParentInterfaceType(implementedInterfaces, EventType.of(psc)); 
							// add eg a CustomerRegistered record with a CustomerDomainEvent interface (to allow querying with typefilter CustomerDomainEvent.class, etc... 
							
						}
					}
					
				} else {
					result = Collections.emptySet();
				}
			} else {
				result = Stream.of(eventRootClass).map(EventNameAndEventClass::of).collect(Collectors.toSet()) ;
				registerEventTypeWithParentInterfaceType(implementedInterfaces, EventType.of(eventRootClass)); 
			}
		}
		return result;
	}

	private void registerEventTypeWithParentInterfaceType(Set<EventType> implementedInterfaces, EventType eventType) {
		// register this event class as a descendent of each of its implemented interfaces
		implementedInterfaces.forEach(parentTypeInterface->{
			if ( !mostRecentMultiTypes.containsKey(parentTypeInterface)) {
				mostRecentMultiTypes.put(parentTypeInterface, new HashSet<>());
			}
			mostRecentMultiTypes.get(parentTypeInterface).add(eventType);	
		});
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
		List<TypeAndPayload> deserialize ( String immutablePayload, String erasablePayload );
	}
	
	class InstantiationEventDeserializer implements EventDeserializer {
		
		private final Class<?> eventClass;
		private final EventType eventType;
		
		public InstantiationEventDeserializer ( Class<?> eventClass, EventType eventType ) {
			this.eventClass = eventClass;
			this.eventType = eventType;
		}

		@Override
		public List<TypeAndPayload> deserialize ( String immutablePayload, String erasablePayload ) {
			Object object;
			try {

				if ( erasablePayload == null ) {
					object = objectMapper.readValue(immutablePayload, eventClass);
				} else {
					// A legacy event, written when payloads were split across two documents. Nothing
					// writes the second one any more; see AbstractEventPayloadSerializerDeserializer.
					ObjectNode nodeImmutableData = (ObjectNode) objectMapper.readTree(immutablePayload);
					ObjectNode nodeErasableData = (ObjectNode) objectMapper.readTree(erasablePayload);

					deepMerge(nodeImmutableData, nodeErasableData);

					// Directly convert the merged JsonNode to the target class without string roundtrip
					object = objectMapper.treeToValue(nodeImmutableData, eventClass);
				}

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

		private final Upcast<Object,Object> upcaster;
		private final Class<?> upcasterClass;
		private final EventDeserializer deser;

		public InstantiationAndUpcastEventDeserializer ( EventDeserializer deser, Upcast<Object,Object> upcaster, Class<?> upcasterClass ) {
			this.deser = deser;
			this.upcaster = upcaster;
			this.upcasterClass = upcasterClass;
		}

		@Override
		public List<TypeAndPayload> deserialize ( String immutablePayload, String erasablePayload ) {
			TypeAndPayload historical = deser.deserialize(immutablePayload, erasablePayload).getFirst();
			List<Object> upcastedEvents;
			try {
				upcastedEvents = upcaster.upcast(historical.eventData());
			} catch (RuntimeException e) {
				// An upcaster is application code running on the read path, and Upcast's own javadoc warns
				// that legacy data may not satisfy a current record's validation. Its failure used to be
				// indistinguishable from Jackson failing to parse the JSON; name the upcaster instead.
				throw new EventDeserializationException(historical.type(),
						"Upcaster %s threw while upcasting stored event type '%s': %s".formatted(
								upcasterClass.getName(), historical.type().name(), e),
						e);
			}
			if ( upcastedEvents == null ) {
				throw new EventDeserializationException(historical.type(),
						"Upcaster %s returned null for stored event type '%s'; return an empty List to drop an event."
								.formatted(upcasterClass.getName(), historical.type().name()));
			}
			return upcastedEvents.stream()
					.map(e -> new TypeAndPayload(EventType.of(e), e))
					.toList();
		}

	}

	@Override
	public Set<EventType> determineLegacyTypes(Set<EventType> currentTypes) {
		// return all types that are upcasted to the currentType, and include the currentType itself as well
		Set<EventType> currentConcreteEventTypes = concreteEventTypesFor(currentTypes); // explode to concrete implementations if interfaces are passed
		Set<EventType> result = new HashSet<>(currentConcreteEventTypes); // we always include "current types", legacy types are optional - only if they are present
		result.addAll(mostRecentTypes.entrySet().stream()
				.filter(e -> e.getValue().stream().anyMatch(currentConcreteEventTypes::contains))
				.map(Map.Entry::getKey)
				.collect(Collectors.toSet()));
		return result;
	}
	
	private Set<EventType> concreteEventTypesFor ( Set<EventType> types ) {
		Set<EventType> result = new HashSet<>();
		for ( EventType e: types ) {
			if ( mostRecentMultiTypes.containsKey(e)) { // if type is an interface
				result.addAll(mostRecentMultiTypes.get(e));
			} else { // if type is a concrete event class
				result.add(e);
			}
		}
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
