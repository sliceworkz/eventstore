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
package org.sliceworkz.eventstore.serialization.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.EventStreamId;

class JsonEventCodecTest {

	private final JsonEventCodec codec = new JsonEventCodec();

	@Test
	void roundTripsAnEvent ( ) {
		StoredEvent event = new StoredEvent(
				EventStreamId.forContext("customer").withPurpose("123"),
				EventType.ofType("CustomerRegistered"),
				EventReference.of(EventId.of("id-1"), 1L, 1L, 0),
				"{\"name\":\"John\"}",
				null,
				new Tags(Set.of(Tag.of("customer", "123"))),
				LocalDateTime.parse("2026-04-19T12:34:56.789"));

		String json = codec.write(event);
		StoredEvent restored = codec.read(json);

		assertEquals(event, restored);
	}

	@Test
	void writesHumanReadableShape ( ) {
		StoredEvent event = new StoredEvent(
				EventStreamId.forContext("ctx").withPurpose("p"),
				EventType.ofType("CustomerRegistered"),
				EventReference.of(EventId.of("id-1"), 1L, 1L, 0),
				"{\"name\":\"John Doe\"}",
				null,
				new Tags(Set.of(Tag.of("customer", "42"))),
				LocalDateTime.parse("2026-04-19T12:34:56.789"));

		String json = codec.write(event);

		assertTrue(json.contains("\"context\" : \"ctx\""));
		assertTrue(json.contains("\"purpose\" : \"p\""));
		assertTrue(json.contains("\"type\" : \"CustomerRegistered\""));
		assertTrue(json.contains("\"customer\""));
	}

	@Test
	void preservesNullImmutableAndErasableData ( ) {
		StoredEvent event = new StoredEvent(
				EventStreamId.forContext("ctx").withPurpose("p"),
				EventType.ofType("NoData"),
				EventReference.of(EventId.of("id-2"), 2L, 2L, 0),
				null,
				null,
				new Tags(Set.of()),
				LocalDateTime.parse("2026-04-19T00:00:00"));

		StoredEvent restored = codec.read(codec.write(event));

		assertNull(restored.immutableData());
		assertNull(restored.erasableData());
	}

}
