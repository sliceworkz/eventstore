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

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

class JsonBookmarkCodecTest {

	private final JsonBookmarkCodec codec = new JsonBookmarkCodec();

	@Test
	void roundTripsABookmarkWithoutMetadata ( ) {
		EventReference reference = EventReference.of(EventId.of("id-1"), 42L, 7L, 3);

		String json = codec.write("my-projection", reference);
		JsonBookmark restored = codec.read(json);

		assertEquals("my-projection", restored.reader());
		assertEquals(reference, restored.reference());
		assertEquals(Tags.none(), restored.tags());
		assertEquals(Instant.EPOCH, restored.updatedAt());
	}

	@Test
	void roundTripsABookmarkWithTagsAndUpdatedAt ( ) {
		EventReference reference = EventReference.of(EventId.of("id-1"), 42L, 7L, 3);
		Tags tags = Tags.parse("status:processed", "version:7");
		Instant updatedAt = Instant.parse("2026-04-30T12:34:56.789Z");

		String json = codec.write("my-projection", reference, tags, updatedAt);
		JsonBookmark restored = codec.read(json);

		assertEquals("my-projection", restored.reader());
		assertEquals(reference, restored.reference());
		assertEquals(tags, restored.tags());
		assertEquals(updatedAt, restored.updatedAt());
	}

	@Test
	void readsLegacyPayloadWithoutTagsOrUpdatedAt ( ) {
		String legacyJson = """
				{"reader":"legacy","reference":{"id":"id-1","position":42,"tx":7,"index":3}}
				""";

		JsonBookmark restored = codec.read(legacyJson);

		assertEquals("legacy", restored.reader());
		assertEquals(Tags.none(), restored.tags());
		assertEquals(Instant.EPOCH, restored.updatedAt());
	}

}
