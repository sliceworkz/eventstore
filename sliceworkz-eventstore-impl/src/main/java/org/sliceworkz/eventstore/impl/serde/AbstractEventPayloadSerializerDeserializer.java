package org.sliceworkz.eventstore.impl.serde;

import org.sliceworkz.eventstore.events.EventType;

import com.fasterxml.jackson.databind.json.JsonMapper;

public abstract class AbstractEventPayloadSerializerDeserializer implements EventPayloadSerializerDeserializer {
	
	protected JsonMapper jsonMapper;
	
	public AbstractEventPayloadSerializerDeserializer (  ) {
		this.jsonMapper = new JsonMapper();
		this.jsonMapper.findAndRegisterModules();
	}
	
	@Override
	public TypeAndSerializedPayload serialize ( Object payload ) {
		String result;
		try {
			result = jsonMapper.writeValueAsString(payload);
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize event data", e);
		}
		return new TypeAndSerializedPayload(EventType.of(payload), result);
	}


}
