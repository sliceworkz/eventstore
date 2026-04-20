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

import java.io.IOException;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON codec for reader bookmarks: a reader identifier paired with the
 * {@link EventReference} it points at.
 * <pre>
 * {
 *   "reader":    "...",
 *   "reference": { "id": ..., "position": ..., "tx": ..., "index": ... }
 * }
 * </pre>
 */
public final class JsonBookmarkCodec {

	private final ObjectMapper objectMapper;

	public JsonBookmarkCodec ( ) {
		this(JsonEventCodec.defaultObjectMapper());
	}

	public JsonBookmarkCodec ( ObjectMapper objectMapper ) {
		this.objectMapper = objectMapper;
	}

	public String write ( String reader, EventReference reference ) {
		try {
			ObjectNode node = objectMapper.createObjectNode();
			node.put("reader", reader);

			ObjectNode refNode = objectMapper.createObjectNode();
			refNode.put("id", reference.id().value());
			refNode.put("position", reference.position());
			refNode.put("tx", reference.tx());
			refNode.put("index", reference.index());
			node.set("reference", refNode);

			return objectMapper.writeValueAsString(node);
		} catch ( IOException e ) {
			throw new JsonCodecException("failed to serialize bookmark for reader " + reader, e);
		}
	}

	public JsonBookmark read ( String json ) {
		try {
			JsonNode node = objectMapper.readTree(json);
			String reader = node.get("reader").asText();
			JsonNode refNode = node.get("reference");
			EventReference reference = EventReference.of(
					EventId.of(refNode.get("id").asText()),
					refNode.get("position").asLong(),
					refNode.get("tx").asLong(),
					refNode.get("index").asInt());
			return new JsonBookmark(reader, reference);
		} catch ( IOException e ) {
			throw new JsonCodecException("failed to deserialize bookmark", e);
		}
	}

}
