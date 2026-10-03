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

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON codec for reader bookmarks: a reader identifier paired with the
 * {@link EventReference} it points at, plus the metadata (tags and last-update timestamp)
 * supplied when the bookmark was placed.
 * <pre>
 * {
 *   "reader":    "...",
 *   "reference": { "id": ..., "position": ..., "tx": ..., "index": ... },
 *   "readUpTo":  { "id": ..., "position": ..., "tx": ..., "index": ... },
 *   "tags":      [ { "key": ..., "value": ... }, ... ],
 *   "updatedAt": "2026-04-30T12:34:56.789Z"
 * }
 * </pre>
 * The {@code tags} and {@code updatedAt} fields are optional on read, for backwards
 * compatibility with payloads written before the metadata extension. Absent fields read as
 * {@link Tags#none()} and {@link Instant#EPOCH} respectively. {@code readUpTo} — the event up to
 * which the reader has read the stream — is written only when the bookmark has one, and reads as
 * empty when it is absent or null. {@code reference} — the last event the reader handled — is written
 * only when the bookmark has one too: a reader that has read the stream without handling anything yet
 * has a read position alone. It reads as empty when absent or null, and a payload naming neither
 * position is rejected, since a bookmark always says something.
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
		return write(reader, reference, null, tags, updatedAt);
	}

	public String write ( String reader, EventReference reference, EventReference readUpTo, Tags tags, Instant updatedAt ) {
		return write(reader, Optional.of(reference), Optional.ofNullable(readUpTo), tags, updatedAt);
	}

	/**
	 * Writes a bookmark whose positions may each be absent, though not both.
	 *
	 * @param reader the reader
	 * @param reference the last event the reader handled, empty when it has handled nothing yet
	 * @param readUpTo the event up to which the reader has read the stream, empty for none
	 * @param tags the tags placed with it
	 * @param updatedAt when it was placed
	 * @return the JSON document
	 * @throws JsonCodecException if both positions are empty, or the document cannot be written
	 */
	public String write ( String reader, Optional<EventReference> reference, Optional<EventReference> readUpTo, Tags tags, Instant updatedAt ) {
		if ( reference.isEmpty() && readUpTo.isEmpty() ) {
			throw new JsonCodecException("bookmark for reader " + reader + " names neither a handled event nor a read position", null);
		}
		try {
			ObjectNode node = objectMapper.createObjectNode();
			node.put("reader", reader);

			reference.ifPresent(r -> node.set("reference", referenceNode(r)));
			readUpTo.ifPresent(r -> node.set("readUpTo", referenceNode(r)));

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
		} catch ( JacksonException e ) {
			throw new JsonCodecException("failed to serialize bookmark for reader " + reader, e);
		}
	}

	public JsonBookmark read ( String json ) {
		try {
			JsonNode node = objectMapper.readTree(json);
			String reader = node.get("reader").asText();
			Optional<EventReference> reference = optionalReference(node.get("reference"));
			Optional<EventReference> readUpTo = optionalReference(node.get("readUpTo"));
			if ( reference.isEmpty() && readUpTo.isEmpty() ) {
				throw new JsonCodecException("bookmark for reader " + reader + " names neither a handled event nor a read position", null);
			}

			Set<Tag> tagSet = new HashSet<>();
			JsonNode tagsNode = node.get("tags");
			if ( tagsNode != null && tagsNode.isArray() ) {
				for ( JsonNode tagNode : tagsNode ) {
					// see JsonEventCodec: asText() renders a JSON null as "", which is a key Tag rejects
					String key = tagNode.has("key") && !tagNode.get("key").isNull()
							? tagNode.get("key").asText()
							: null;
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

			return new JsonBookmark(reader, reference, readUpTo, tags, updatedAt);
		} catch ( JacksonException e ) {
			throw new JsonCodecException("failed to deserialize bookmark", e);
		}
	}

	private ObjectNode referenceNode ( EventReference reference ) {
		ObjectNode refNode = objectMapper.createObjectNode();
		refNode.put("id", reference.id().value());
		refNode.put("position", reference.position());
		refNode.put("tx", reference.tx());
		refNode.put("index", reference.index());
		return refNode;
	}

	private static Optional<EventReference> optionalReference ( JsonNode refNode ) {
		return refNode == null || refNode.isNull() ? Optional.empty() : Optional.of(reference(refNode));
	}

	private static EventReference reference ( JsonNode refNode ) {
		return EventReference.of(
				EventId.of(refNode.get("id").asText()),
				refNode.get("position").asLong(),
				refNode.get("tx").asLong(),
				refNode.get("index").asInt());
	}

}
