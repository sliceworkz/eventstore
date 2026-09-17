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
import java.util.Map;
import java.util.Set;

import org.sliceworkz.eventstore.events.EventType;

/**
 * Raw mode implementation of {@link EventPayloadSerializerDeserializer}: no type mapping, and every
 * stored event read as the JSON document it is stored as.
 * <p>
 * The payload comes back as a {@link String} holding the stored JSON document, exactly as the storage
 * answered it — nothing is parsed, so a raw read costs no more than the storage read behind it. That is
 * what a raw stream is for: following every append in a store, inspecting a stored event a typed
 * stream cannot read, checking whether an event is present before an import — paths that look inside
 * few of the events they read, and that want the payload in a form any JSON library can take. A caller
 * that wants a tree parses the string with the mapper of its choice. The alternative — parsing every
 * payload into a Jackson {@code JsonNode} — loses because the api module cannot name that type, so
 * the stream had to be an {@code EventSource<Object>} whose value only a caller importing Jackson 3
 * could use; and because it paid a parse per event on the one read path that never needed one. The
 * storage guarantees that what it holds is a JSON document (a payload that is not one is refused on
 * append and on import), so nothing is lost by not parsing it here.
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

	/**
	 * Hands the stored JSON document back as it is, under its stored type.
	 * <p>
	 * Never throws an {@link org.sliceworkz.eventstore.events.EventDeserializationException}: there is
	 * no mapping to fail on and no parse to fail in, which is what makes a raw stream the way to read
	 * an event a typed stream chokes on.
	 */
	@Override
	public List<TypeAndPayload> deserialize ( TypeAndSerializedPayload serialized ) {
		return List.of(new TypeAndPayload(serialized.type(), serialized.immutablePayload()));
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
	 * Raw mode registers no legacy types: every stored name is read as stored, so none is refused.
	 */
	@Override
	public Map<EventType, Set<EventType>> legacyTypesAmong ( Set<EventType> types ) {
		return Map.of();
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
