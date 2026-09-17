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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndPayload;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndSerializedPayload;

/**
 * The raw serde hands a stored payload back as the JSON document it was given, a {@link String}, under
 * its stored type: it parses nothing, maps nothing and upcasts nothing. Below the store, so that the
 * value is pinned by identity; {@code RawStreamTest} in the TCK pins the same contract per backend,
 * through the stream.
 */
class RawEventPayloadSerdeTest {

	private static final String DOCUMENT = "{\"name\": \"John\", \"age\": 42}";

	@Test
	void theStoredDocumentComesBackAsItIsUnderItsStoredType ( ) {
		EventPayloadSerializerDeserializer raw = EventPayloadSerializerDeserializer.raw();

		List<TypeAndPayload> read = raw.deserialize(new TypeAndSerializedPayload(EventType.ofType("CustomerRegistered"), DOCUMENT));

		assertEquals(1, read.size(), "one stored event is one raw event: nothing splits or drops it");
		assertEquals(EventType.ofType("CustomerRegistered"), read.getFirst().type());
		// the very string storage answered, not a copy, a tree, or a re-rendering of it
		assertSame(DOCUMENT, read.getFirst().eventData());
	}

	@Test
	void aDocumentIsHandedBackWithoutBeingParsed ( ) {
		// the storage guarantees a JSON document, so the raw serde does not check one: what it is handed
		// is what it answers, and nothing on this path can throw EventDeserializationException
		EventPayloadSerializerDeserializer raw = EventPayloadSerializerDeserializer.raw();

		assertEquals("not json", raw.deserialize(new TypeAndSerializedPayload(EventType.ofType("X"), "not json")).getFirst().eventData());
	}

	@Test
	void aRawSerdeMapsNoTypeAndIsNotTyped ( ) {
		EventPayloadSerializerDeserializer raw = EventPayloadSerializerDeserializer.raw();

		assertFalse(raw.isTyped());
		// no type is appendable through a raw stream: the stream layer refuses an append for any type
		// its serde cannot deserialize
		assertFalse(raw.canDeserialize("CustomerRegistered"));
	}

}
