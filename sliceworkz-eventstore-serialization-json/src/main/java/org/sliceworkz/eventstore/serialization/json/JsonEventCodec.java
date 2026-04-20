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
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.EventStreamId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON codec for {@link StoredEvent}.
 * <p>
 * Produces and consumes a stable shape intended for on-disk persistence and for
 * future export/import features:
 * <pre>
 * {
 *   "stream":        { "context": ..., "purpose": ... },
 *   "type":          "...",
 *   "reference":     { "id": ..., "position": ..., "tx": ..., "index": ... },
 *   "immutableData": { ... } | null,
 *   "erasableData":  { ... } | null,
 *   "tags":          [ { "key": ..., "value": ... }, ... ],
 *   "timestamp":     "ISO-8601 LocalDateTime"
 * }
 * </pre>
 */
public final class JsonEventCodec {

	private final ObjectMapper objectMapper;

	/**
	 * Creates a codec with a default {@link ObjectMapper}: JSR-310 dates as ISO-8601
	 * strings, pretty-printed output.
	 */
	public JsonEventCodec ( ) {
		this(defaultObjectMapper());
	}

	/**
	 * Creates a codec backed by the caller's {@link ObjectMapper}. The mapper is used
	 * as-is; callers that want compact output (e.g. for bulk export) can disable
	 * {@link SerializationFeature#INDENT_OUTPUT}.
	 */
	public JsonEventCodec ( ObjectMapper objectMapper ) {
		this.objectMapper = objectMapper;
	}

	public static ObjectMapper defaultObjectMapper ( ) {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.enable(SerializationFeature.INDENT_OUTPUT);
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return mapper;
	}

	public String write ( StoredEvent event ) {
		try {
			ObjectNode node = objectMapper.createObjectNode();

			ObjectNode streamNode = objectMapper.createObjectNode();
			streamNode.put("context", event.stream().context());
			streamNode.put("purpose", event.stream().purpose());
			node.set("stream", streamNode);

			node.put("type", event.type().name());

			ObjectNode refNode = objectMapper.createObjectNode();
			refNode.put("id", event.reference().id().value());
			refNode.put("position", event.reference().position());
			refNode.put("tx", event.reference().tx());
			refNode.put("index", event.reference().index());
			node.set("reference", refNode);

			if ( event.immutableData() != null ) {
				node.set("immutableData", objectMapper.readTree(event.immutableData()));
			} else {
				node.putNull("immutableData");
			}
			if ( event.erasableData() != null ) {
				node.set("erasableData", objectMapper.readTree(event.erasableData()));
			} else {
				node.putNull("erasableData");
			}

			ArrayNode tagsArray = objectMapper.createArrayNode();
			for ( Tag tag : event.tags().tags() ) {
				ObjectNode tagNode = objectMapper.createObjectNode();
				tagNode.put("key", tag.key());
				tagNode.put("value", tag.value());
				tagsArray.add(tagNode);
			}
			node.set("tags", tagsArray);

			node.put("timestamp", event.timestamp().toString());

			return objectMapper.writeValueAsString(node);
		} catch ( IOException e ) {
			throw new JsonCodecException("failed to serialize event", e);
		}
	}

	public StoredEvent read ( String json ) {
		try {
			JsonNode node = objectMapper.readTree(json);

			JsonNode streamNode = node.get("stream");
			EventStreamId stream = EventStreamId.forContext(streamNode.get("context").asText())
					.withPurpose(streamNode.get("purpose").asText());

			EventType type = EventType.ofType(node.get("type").asText());

			JsonNode refNode = node.get("reference");
			EventReference reference = EventReference.of(
					EventId.of(refNode.get("id").asText()),
					refNode.get("position").asLong(),
					refNode.get("tx").asLong(),
					refNode.get("index").asInt());

			String immutableData = node.has("immutableData") && !node.get("immutableData").isNull()
					? node.get("immutableData").toString()
					: null;
			String erasableData = node.has("erasableData") && !node.get("erasableData").isNull()
					? node.get("erasableData").toString()
					: null;

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

			LocalDateTime timestamp = LocalDateTime.parse(node.get("timestamp").asText());

			return new StoredEvent(stream, type, reference, immutableData, erasableData, tags, timestamp);
		} catch ( IOException e ) {
			throw new JsonCodecException("failed to deserialize event", e);
		}
	}

}
