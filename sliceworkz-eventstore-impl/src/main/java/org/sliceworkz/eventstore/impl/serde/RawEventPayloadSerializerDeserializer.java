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

import java.util.Set;

import org.sliceworkz.eventstore.events.EventType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class RawEventPayloadSerializerDeserializer extends AbstractEventPayloadSerializerDeserializer {
	
	@Override
	public TypeAndPayload deserialize ( TypeAndSerializedPayload serialized ) {
		JsonNode object;
		try {
			
			if ( serialized.erasablePayload() == null ) {
				object = immutableDataMapper.readTree(serialized.immutablePayload());
			} else {
				// reconstruct the full object by merging
				ObjectNode nodeImmutableData = (ObjectNode) immutableDataMapper.readTree(serialized.immutablePayload());
				ObjectNode nodeErasableData = (ObjectNode) erasableDataMapper.readTree(serialized.erasablePayload());
				
				// Merge erasable data into immutable data
				deepMerge(nodeImmutableData, nodeErasableData);
				
				object = nodeImmutableData; // with erasable merged in
			}
			
		} catch (JsonMappingException e) {
			throw new RuntimeException("Failed to deserialize event data: JsonMappingException", e);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Failed to deserialize event data: JsonProcessingException", e);
		}
		return new TypeAndPayload(serialized.type(), object);
	}

	@Override
	public RawEventPayloadSerializerDeserializer registerEventTypes( Class<?> rootClass ) {
		// NO-OP, raw events only
		return this;
	}

	@Override
	public RawEventPayloadSerializerDeserializer registerLegacyEventTypes( Class<?> rootClass ) {
		// NO-OP, raw events only
		return this;
	}

	@Override
	public boolean canDeserialize(String eventTypeName) {
		return false;
	}

	@Override
	public Set<EventType> determineLegacyTypes(Set<EventType> currentTypes) {
		return currentTypes;
	}
	
}
