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

public interface EventPayloadSerializerDeserializer {

	TypeAndSerializedPayload serialize(Object payload);

	TypeAndPayload deserialize(TypeAndSerializedPayload serialized);

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
	
	public record TypeAndSerializedPayload ( EventType type, String immutablePayload, String erasablePayload ) { }
	
}
