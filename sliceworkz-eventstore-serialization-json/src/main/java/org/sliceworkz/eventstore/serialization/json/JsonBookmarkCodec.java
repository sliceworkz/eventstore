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
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON codec for reader bookmarks: a reader identifier paired with the
 * {@link EventReference} it points at, plus the metadata (tags and last-update timestamp)
 * supplied when the bookmark was placed.
 * <pre>
 * {
 *   "reader":    "...",
 *   "reference": { "id": ..., "position": ..., "tx": ..., "index": ... },
 *   "tags":      [ { "key": ..., "value": ... }, ... ],
 *   "updatedAt": "2026-04-30T12:34:56.789Z"
 * }
 * </pre>
 * The {@code tags} and {@code updatedAt} fields are optional on read, for backwards
 * compatibility with payloads written before the metadata extension. Absent fields read as
 * {@link Tags#none()} and {@link Instant#EPOCH} respectively.
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
		return write(reader, reference, Tags.none(), Instant.EPOCH);
	}

	public String write ( String reader, EventReference reference, Tags tags, Instant updatedAt ) {
		try {
			ObjectNode node = objectMapper.createObjectNode();
			node.put("reader", reader);

			ObjectNode refNode = objectMapper.createObjectNode();
			refNode.put("id", reference.id().value());
			refNode.put("position", reference.position());
			refNode.put("tx", reference.tx());
			refNode.put("index", reference.index());
			node.set("reference", refNode);

			ArrayNode tagsArray = objectMapper.createArrayNode();
			for ( Tag tag : tags.tags() ) {
				ObjectNode tagNode = objectMapper.createObjectNode();
				tagNode.put("key", tag.key());
				tagNode.put("value", tag.value());
				tagsArray.add(tagNode);
			}
			node.set("tags", tagsArray);

			node.put("updatedAt", updatedAt.toString());

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

			Set<Tag> tagSet = new HashSet<>();
			JsonNode tagsNode = node.get("tags");
			if ( tagsNode != null && tagsNode.isArray() ) {
				for ( JsonNode tagNode : tagsNode ) {
					String key = tagNode.get("key").asText();
					String value = tagNode.has("value") && !tagNode.get("value").isNull()
							? tagNode.get("value").asText()
							: null;
					tagSet.add(Tag.of(key, value));
				}
			}
			Tags tags = new Tags(tagSet);

			Instant updatedAt = node.has("updatedAt") && !node.get("updatedAt").isNull()
					? Instant.parse(node.get("updatedAt").asText())
					: Instant.EPOCH;

			return new JsonBookmark(reader, reference, tags, updatedAt);
		} catch ( IOException e ) {
			throw new JsonCodecException("failed to deserialize bookmark", e);
		}
	}

}
