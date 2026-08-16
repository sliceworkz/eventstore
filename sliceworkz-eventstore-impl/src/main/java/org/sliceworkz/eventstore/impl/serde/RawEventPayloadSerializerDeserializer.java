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

import java.util.List;
import java.util.Set;

import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventType;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Raw mode implementation of {@link EventPayloadSerializerDeserializer} that works with JSON strings directly.
 * <p>
 * This implementation does not map events to Java classes. Events are deserialized as Jackson {@link JsonNode}
 * objects, allowing for schema-less event processing without requiring static type definitions.
 * <p>
 * Use this mode when event types are not statically known or when you need flexible JSON handling.
 *
 * <h2>Protected values are not decrypted here</h2>
 * A raw stream has no {@link org.sliceworkz.eventstore.shredding.ShreddingCodec} and no keys, so a
 * {@link org.sliceworkz.eventstore.shredding.Shreddable} value comes back as the sealed envelope it is
 * stored as — an object carrying {@code alg}, {@code dek}, {@code sub}, {@code iv} and {@code ct}. That
 * is deliberate rather than a gap: raw mode is what the import and export paths use, and copying an
 * event between stores must move the ciphertext verbatim without needing the keys, the domain classes,
 * or the right to read the personal data at all.
 *
 * @see EventPayloadSerializerDeserializer#raw()
 */
public class RawEventPayloadSerializerDeserializer extends AbstractEventPayloadSerializerDeserializer {
	
	@Override
	public List<TypeAndPayload> deserialize ( TypeAndSerializedPayload serialized ) {
		JsonNode object;
		try {

			if ( serialized.erasablePayload() == null ) {
				object = objectMapper.readTree(serialized.immutablePayload());
			} else {
				// A legacy event, written when payloads were split across two documents.
				ObjectNode nodeImmutableData = (ObjectNode) objectMapper.readTree(serialized.immutablePayload());
				ObjectNode nodeErasableData = (ObjectNode) objectMapper.readTree(serialized.erasablePayload());

				deepMerge(nodeImmutableData, nodeErasableData);

				object = nodeImmutableData; // with erasable merged in
			}

		} catch (JacksonException e) {
			// One catch, not two: DatabindException is a JacksonException, and naming which of the two
			// it was added nothing the cause does not already say.
			throw new EventDeserializationException(serialized.type(),
					"Failed to parse stored JSON for event type '%s' in raw mode: %s".formatted(
							serialized.type().name(), e.getOriginalMessage()),
					e);
		}
		return List.of(new TypeAndPayload(serialized.type(), object));
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

	/**
	 * Returns false to indicate this is a raw (untyped) serializer/deserializer.
	 * <p>
	 * This information is used for observability and metrics tagging.
	 *
	 * @return false (raw mode)
	 */
	@Override
	public boolean isTyped() {
		return false;
	}
	
}
