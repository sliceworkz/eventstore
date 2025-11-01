package org.sliceworkz.eventstore.impl.serde;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.EventType;

public class TypedEventPayloadSerializerDeserializer extends AbstractEventPayloadSerializerDeserializer {

	private Map<String,Class<?>> typeMapping = new HashMap<>();
	
	@Override
	public TypeAndPayload deserialize ( String eventTypeName, String payload ) {
		Object result;
		try {
			Class<?> clazz = typeMapping.get(eventTypeName);
			if ( clazz == null  ) {
				throw new RuntimeException("No mapping found for event type '" + eventTypeName + "'");
			}
			result = jsonMapper.readValue(payload, clazz); 
		} catch (Exception e) {
			if ( typeMapping.keySet().isEmpty() ) {
				throw new RuntimeException(String.format("Failed to deserialize event data for type '%s', no EventType mappings configured. Pass the Event root Class when creating the EventStream", eventTypeName) , e);
			} else {
				throw new RuntimeException(String.format("Failed to deserialize event data for type '%s', known mappings for %s", eventTypeName, typeMapping.keySet()) , e);
			}
		}
		return new TypeAndPayload(EventType.of(eventTypeName), result);
	}
	
	@Override
	public TypedEventPayloadSerializerDeserializer registerEventTypes(Class<?> rootClass) {
		mappingsFor(rootClass).forEach(m->registerEventType(m.name(), m.clazz()));
		
		return this;
	}
	
	private void registerEventType ( String eventName, Class<?> clazz ) {
		String key = eventName;
		if ( typeMapping.containsKey(key) ) {
			throw new IllegalArgumentException("duplicate event name " + key);
		}
		typeMapping.put(key, clazz);
	}
	
	private Set<Mapping> mappingsFor ( Class<?> eventRootClass ) {
		Set<Mapping> result = Collections.emptySet();
		if ( eventRootClass != null && !eventRootClass.equals(Object.class)) {
			if ( eventRootClass.isInterface() ) {
				
				if ( ! eventRootClass.isSealed() ) {
					throw new IllegalArgumentException(String.format("interface %s should be sealed to allow Event Type determination", eventRootClass.getName()));
				}
				
				Class<?>[] permittedSubclassses = eventRootClass.getPermittedSubclasses();
				if ( permittedSubclassses != null && permittedSubclassses.length > 0 ) {
					result =Stream.of(permittedSubclassses).map(Mapping::of).collect(Collectors.toSet()) ;
				} else {
					result = Collections.emptySet();
				}
			} else {
				result = Stream.of(eventRootClass).map(Mapping::of).collect(Collectors.toSet()) ;
			}
		}
		return result;
	}
	
	record Mapping (String name, Class<?> clazz) { 
		public static Mapping of ( Class<?> clazz ) {
			return new Mapping(EventType.of(clazz).name(), clazz);
		}
	}

	@Override
	public boolean canDeserialize(String eventTypeName) {
		return typeMapping.keySet().contains(eventTypeName);
	}
}
