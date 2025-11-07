package org.sliceworkz.eventstore.impl.serde;

import java.util.Set;

import org.sliceworkz.eventstore.events.EventType;

public interface EventPayloadSerializerDeserializer {

	TypeAndSerializedPayload serialize(Object payload);

	TypeAndPayload deserialize(String eventTypeName, String payload);

	boolean canDeserialize(String eventTypeName);
	
	EventPayloadSerializerDeserializer registerEventTypes ( Class<?> rootClass );
	
	// these should be annotated with Upcasting to be translated to current Event types
	EventPayloadSerializerDeserializer registerLegacyEventTypes ( Class<?> rootClass );
	
	Set<EventType> determineLegacyTypes ( Set<EventType> currentTypes );

	public static EventPayloadSerializerDeserializer typed ( ) {
		return new TypedEventPayloadSerializerDeserializer();
	}
	
	public static EventPayloadSerializerDeserializer raw ( ) {
		return new RawEventPayloadSerializerDeserializer();
	}
	
	public record TypeAndPayload ( EventType type, Object eventData ) { }
	
	public record TypeAndSerializedPayload ( EventType type, String serializedPayload ) { }
	
}
