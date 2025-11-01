package org.sliceworkz.eventstore.impl.serde;

import org.sliceworkz.eventstore.events.EventType;

public interface EventPayloadSerializerDeserializer {

	TypeAndSerializedPayload serialize(Object payload);

	TypeAndPayload deserialize(String eventTypeName, String payload);

	boolean canDeserialize(String eventTypeName);
	
	EventPayloadSerializerDeserializer registerEventTypes ( Class<?> rootClass );

	public static EventPayloadSerializerDeserializer typed ( ) {
		return new TypedEventPayloadSerializerDeserializer();
	}
	
	public static EventPayloadSerializerDeserializer raw ( ) {
		return new RawEventPayloadSerializerDeserializer();
	}
	
	public record TypeAndPayload ( EventType type, Object eventData ) { }
	
	public record TypeAndSerializedPayload ( EventType type, String serializedPayload ) { }
	
}
