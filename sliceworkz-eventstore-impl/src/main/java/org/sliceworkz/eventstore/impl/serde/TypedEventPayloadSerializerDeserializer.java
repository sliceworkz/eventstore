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
package org.sliceworkz.eventstore.impl.serde;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Upcast;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class TypedEventPayloadSerializerDeserializer extends AbstractEventPayloadSerializerDeserializer {

	private Map<String,EventDeserializer> deserializers = new HashMap<>();
	private Map<EventType, EventType> mostRecentTypes = new HashMap<>();
	
	@Override
	public TypeAndPayload deserialize ( TypeAndSerializedPayload serialized ) {
		TypeAndPayload result;
		try {
			EventDeserializer deserializer = deserializers.get(serialized.type().name());
			if ( deserializer == null  ) {
				throw new RuntimeException("No mapping found for event type '" + serialized.type().name() + "'");
			}
			result = new TypeAndPayload(deserializer.eventType(), deserializer.deserialize(serialized.immutablePayload(), serialized.erasablePayload())); 
		} catch (Exception e) {
			if ( deserializers.keySet().isEmpty() ) {
				throw new RuntimeException(String.format("Failed to deserialize event data for type '%s', no EventType mappings configured. Pass the Event root Class when creating the EventStream", serialized.type().name()) , e);
			} else {
				throw new RuntimeException(String.format("Failed to deserialize event data for type '%s', known mappings for %s", serialized.type().name(), deserializers.keySet()) , e);
			}
		}
		return result;
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

	private void registerEventType ( String eventName, Class<?> clazz, boolean assumeUpcasters ) {
		String key = eventName;
		if ( deserializers.containsKey(key) ) {
			throw new IllegalArgumentException("duplicate event name " + key);
		}
		
		EventType eventType = EventType.ofType(eventName);
		EventDeserializer eventDeserializer = new InstantiationEventDeserializer(clazz, eventType);

		// when we need to upcast an historical legacy event
		if ( clazz.isAnnotationPresent(LegacyEvent.class)) {
			
			if ( !assumeUpcasters ) {
				throw new RuntimeException(String.format("Event type %s should not be annotated as a @LegacyEvent, or moved to the legacy Event types", clazz));
			}
			
			LegacyEvent annotation = clazz.getAnnotation(LegacyEvent.class);
			Upcast upcast;
			try {
				
				upcast = (Upcast) annotation.upcast().getDeclaredConstructor().newInstance(new Object[0]);
				
				mostRecentTypes.put(eventType, EventType.of(upcast.targetType()));
				
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e);
			} catch (InstantiationException e) {
				throw new RuntimeException(e);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			}
			eventDeserializer = new InstantiationAndUpcastEventDeserializer(eventDeserializer, upcast); 
			
		} else {
			if  ( assumeUpcasters ) {
				throw new RuntimeException(String.format("legacy Event type %s should be annotated as a @LegacyEvent and configured with an Upcaster", clazz));
			}
			mostRecentTypes.put(eventType, eventType); // no upcasting needed
		}
		
		
		deserializers.put(key, eventDeserializer);
	}
	
	private Set<EventNameAndEventClass> deserializersFor ( Class<?> eventRootClass ) {
		Set<EventNameAndEventClass> result = Collections.emptySet();
		if ( eventRootClass != null && !eventRootClass.equals(Object.class)) {
			if ( eventRootClass.isInterface() ) {
				
				if ( ! eventRootClass.isSealed() ) {
					throw new IllegalArgumentException(String.format("interface %s should be sealed to allow Event Type determination", eventRootClass.getName()));
				}
				
				Class<?>[] permittedSubclassses = eventRootClass.getPermittedSubclasses();
				if ( permittedSubclassses != null && permittedSubclassses.length > 0 ) {
					result =Stream.of(permittedSubclassses).map(EventNameAndEventClass::of).collect(Collectors.toSet()) ;
				} else {
					result = Collections.emptySet();
				}
			} else {
				result = Stream.of(eventRootClass).map(EventNameAndEventClass::of).collect(Collectors.toSet()) ;
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
		Object deserialize ( String immutablePayload, String erasablePayload );
		EventType eventType ( );
	}
	
	class InstantiationEventDeserializer implements EventDeserializer {
		
		private Class<?> eventClass;
		private EventType eventType;
		
		public InstantiationEventDeserializer ( Class<?> eventClass, EventType eventType ) {
			this.eventClass = eventClass;
			this.eventType = eventType;
		}

		@Override
		public Object deserialize ( String immutablePayload, String erasablePayload ) {
			Object object;
			try {
				
				if ( erasablePayload == null ) {
					object = immutableDataMapper.readValue(immutablePayload, eventClass);
				} else {
					// reconstruct the full object by merging
					ObjectNode nodeImmutableData = (ObjectNode) immutableDataMapper.readTree(immutablePayload);
					ObjectNode nodeErasableData = (ObjectNode) erasableDataMapper.readTree(erasablePayload);

					// Merge erasable data into immutable data
					deepMerge(nodeImmutableData, nodeErasableData);

					// Directly convert the merged JsonNode to the target class without string roundtrip
					object = immutableDataMapper.treeToValue(nodeImmutableData, eventClass);
				}

			} catch (JsonMappingException e) {
				throw new RuntimeException(e);
			} catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			} 
			return object;
		}

		@Override
		public EventType eventType() {
			return eventType;
		}
		
	}
	
	class InstantiationAndUpcastEventDeserializer implements EventDeserializer {

		private Upcast<Object,Object> upcaster;
		private EventDeserializer deser;
		private EventType eventType;

		public InstantiationAndUpcastEventDeserializer ( EventDeserializer deser, Upcast<Object,Object> upcaster ) {
			this.deser = deser;
			this.upcaster = upcaster;
			this.eventType = EventType.of(upcaster.targetType());
		}
		
		@Override
		public Object deserialize ( String immutablePayload, String erasablePayload ) {
			return upcaster.upcast(deser.deserialize(immutablePayload, erasablePayload));
		}

		@Override
		public EventType eventType() {
			return eventType;
		}

	}

	@Override
	public Set<EventType> determineLegacyTypes(Set<EventType> currentTypes) {
		// return all types that are upcasted to the currentType, and include the currentType itself as well
		Set<EventType> result = new HashSet<>(currentTypes); // in case no mapping is found (no match), keep them
		result.addAll(mostRecentTypes.entrySet().stream().filter(e->currentTypes.contains(e.getValue())).map(e->e.getKey()).collect(Collectors.toSet()));
		return result;
	}
	
}
