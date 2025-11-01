package org.sliceworkz.eventstore.impl.serde;

import org.sliceworkz.eventstore.events.EventType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

public class RawEventPayloadSerializerDeserializer extends AbstractEventPayloadSerializerDeserializer {
	
	@Override
	public TypeAndPayload deserialize ( String eventTypeName, String payload ) {
		JsonNode object;
		try {
			object = jsonMapper.readTree(payload);
		} catch (JsonMappingException e) {
			throw new RuntimeException("Failed to deserialize event data: JsonMappingException", e);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Failed to deserialize event data: JsonProcessingException", e);
		}
		return new TypeAndPayload(EventType.ofType(eventTypeName), object);
	}

	@Override
	public RawEventPayloadSerializerDeserializer registerEventTypes( Class<?> rootClass ) {
		// NO-OP, raw events only
		return this;
	}

	@Override
	public boolean canDeserialize(String eventTypeName) {
		return false;
	}
	
}
