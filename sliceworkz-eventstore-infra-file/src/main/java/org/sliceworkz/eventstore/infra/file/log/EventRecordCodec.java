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
package org.sliceworkz.eventstore.infra.file.log;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Turns a {@link StoredEvent} into the bytes of one log record, and back.
 *
 * <h2>Layout, version 1</h2>
 * <pre>
 *  u8   recordVersion = 1
 *  u16  tagCount
 *  u64  position
 *  u64  tx
 *  i64  timestamp, seconds from the epoch at UTC
 *  i32  timestamp, nanosecond of second
 *  str  eventId                 never null
 *  str  streamContext
 *  str  streamPurpose
 *  str  eventType
 *  str  idempotencyKey          nullable
 *  tag[tagCount]                each a nullable key and a nullable value
 *  ---------------------------- everything above is the indexable prefix
 *  str  immutableData
 *  str  erasableData            nullable
 * </pre>
 *
 * <h2>Why the payloads are last</h2>
 * Rebuilding the indexes after a restart has to visit every record, and the payload is the large part
 * and the part an index never looks at. With the payloads at the end, {@link #decodePrefix} stops
 * reading at the tag list, so an index rebuild decodes the metadata of the whole log without ever
 * materialising a single event body. It is the difference between a rebuild that costs what the
 * metadata costs and one that costs what the store costs.
 *
 * <h2>Three field choices worth the paragraph</h2>
 * <ul>
 *   <li><b>The event id is a string, not sixteen bytes.</b> {@link EventId} is a record over an
 *       arbitrary non-blank {@code String}; only the PostgreSQL backend requires a UUID, because its
 *       column is one. Packing it as a UUID here would silently reject ids this SPI allows, and would
 *       hand back a different string than was stored for anything that is a UUID but not in the
 *       canonical lower-case spelling.</li>
 *   <li><b>The timestamp is exact.</b> Seconds plus nanosecond-of-second round-trips a
 *       {@link LocalDateTime} bit for bit, which is what an import between two stores on this backend
 *       requires — it compares timestamps with no tolerance. PostgreSQL's {@code timestamptz} rounds to
 *       microseconds and so cannot make that promise; that is a property of its column type, not
 *       something to imitate.</li>
 *   <li><b>Tags are stored as pairs, with both halves nullable.</b> The flattened {@code "key:value"}
 *       form that PostgreSQL stores exists to make a {@code text[]} containment query work, and
 *       {@link Tag} rejects the shapes that would not survive it. Nothing here needs the flattening, so
 *       nothing here depends on that validation — and a null key stays a null key rather than becoming
 *       an empty string, which would be a different tag.</li>
 * </ul>
 */
public final class EventRecordCodec {

	/** The record layout this class writes. */
	public static final byte RECORD_VERSION = 1;

	private EventRecordCodec ( ) {

	}

	/**
	 * The part of a record an index or a filter needs, without the payloads.
	 * <p>
	 * {@link #reference()} is assembled rather than stored: the index component of an
	 * {@link EventReference} is always zero at rest, because non-zero values are minted above the SPI
	 * by upcasting one stored event into several. Storing it would be storing a constant.
	 *
	 * @param reference the event's own reference, with a zero index
	 * @param stream the stream the event was appended to
	 * @param type the event type as stored
	 * @param tags the event's tags
	 * @param timestamp when the event was stored, at UTC
	 * @param idempotencyKey the key it was appended with, or null
	 * @param payloadOffset the offset within the record body at which the payloads begin
	 */
	public record Prefix ( EventReference reference, EventStreamId stream, EventType type, Tags tags,
			LocalDateTime timestamp, String idempotencyKey, int payloadOffset ) {

		/**
		 * The event's position in the log.
		 *
		 * @return the position, counting from one
		 */
		public long position ( ) {
			return reference.position();
		}
	}

	/**
	 * Encodes an event as the body of one log record.
	 *
	 * @param event the event to encode
	 * @return the record body, ready to be framed
	 */
	public static byte[] encode ( StoredEvent event ) {
		Set<Tag> tags = event.tags() == null ? Set.of() : event.tags().tags();
		if ( tags.size() > Character.MAX_VALUE ) {
			throw new IllegalArgumentException("an event cannot carry more than %d tags".formatted((int) Character.MAX_VALUE));
		}

		ByteBuffer buffer = BinaryFormat.buffer(size(event, tags));

		buffer.put(RECORD_VERSION);
		buffer.putShort((short) tags.size());
		buffer.putLong(event.reference().position());
		buffer.putLong(event.reference().tx());
		buffer.putLong(event.timestamp().toEpochSecond(ZoneOffset.UTC));
		buffer.putInt(event.timestamp().getNano());
		BinaryFormat.putString(buffer, event.reference().id().value());
		BinaryFormat.putString(buffer, event.stream().context());
		BinaryFormat.putString(buffer, event.stream().purpose());
		BinaryFormat.putString(buffer, event.type().name());
		BinaryFormat.putString(buffer, event.idempotencyKey());
		for ( Tag tag : tags ) {
			BinaryFormat.putString(buffer, tag.key());
			BinaryFormat.putString(buffer, tag.value());
		}
		BinaryFormat.putString(buffer, event.immutableData());
		BinaryFormat.putString(buffer, event.erasableData());

		return buffer.array();
	}

	/**
	 * Reads everything but the payloads, leaving the buffer positioned where they begin.
	 *
	 * @param body a buffer positioned at the start of a record body
	 * @return the record's metadata
	 * @throws MalformedRecordException if the bytes do not decode as a record of a known version
	 */
	public static Prefix decodePrefix ( ByteBuffer body ) {
		int start = body.position();

		byte version = body.get();
		if ( version != RECORD_VERSION ) {
			throw new MalformedRecordException(
					"record version %d is not supported by this release, which writes version %d".formatted(version, RECORD_VERSION));
		}

		int tagCount = Short.toUnsignedInt(body.getShort());
		long position = body.getLong();
		long tx = body.getLong();
		long epochSecond = body.getLong();
		int nanoOfSecond = body.getInt();
		String eventId = BinaryFormat.getString(body);
		String context = BinaryFormat.getString(body);
		String purpose = BinaryFormat.getString(body);
		String type = BinaryFormat.getString(body);
		String idempotencyKey = BinaryFormat.getString(body);

		Set<Tag> tags = new LinkedHashSet<>();
		for ( int i = 0; i < tagCount; i++ ) {
			String key = BinaryFormat.getString(body);
			String value = BinaryFormat.getString(body);
			tags.add(new Tag(key, value));
		}

		if ( eventId == null ) {
			throw new MalformedRecordException("record at body offset %d carries no event id".formatted(start));
		}
		if ( nanoOfSecond < 0 || nanoOfSecond > 999_999_999 ) {
			throw new MalformedRecordException("nanosecond-of-second %d is out of range".formatted(nanoOfSecond));
		}

		EventReference reference = EventReference.of(new EventId(eventId), position, tx);
		LocalDateTime timestamp = LocalDateTime.ofEpochSecond(epochSecond, nanoOfSecond, ZoneOffset.UTC);

		return new Prefix(reference, new EventStreamId(context, purpose), new EventType(type), new Tags(tags),
				timestamp, idempotencyKey, body.position());
	}

	/**
	 * Reads a whole record, payloads included.
	 *
	 * @param body a buffer positioned at the start of a record body
	 * @return the event as it was stored
	 * @throws MalformedRecordException if the bytes do not decode as a record of a known version
	 */
	public static StoredEvent decode ( ByteBuffer body ) {
		Prefix prefix = decodePrefix(body);
		return withPayloads(prefix, body);
	}

	/**
	 * Reads the payloads that follow a prefix already decoded from this buffer.
	 *
	 * @param prefix the prefix decoded from this same body
	 * @param body the buffer, positioned at {@link Prefix#payloadOffset()}
	 * @return the event as it was stored
	 */
	public static StoredEvent withPayloads ( Prefix prefix, ByteBuffer body ) {
		body.position(prefix.payloadOffset());
		String immutableData = BinaryFormat.getString(body);
		String erasableData = BinaryFormat.getString(body);
		return new StoredEvent(prefix.stream(), prefix.type(), prefix.reference(), immutableData, erasableData,
				prefix.tags(), prefix.timestamp(), prefix.idempotencyKey());
	}

	private static int size ( StoredEvent event, Set<Tag> tags ) {
		int size = Byte.BYTES                       // recordVersion
				+ Short.BYTES                       // tagCount
				+ Long.BYTES                        // position
				+ Long.BYTES                        // tx
				+ Long.BYTES                        // timestamp seconds
				+ Integer.BYTES;                    // timestamp nanos
		size += BinaryFormat.stringSize(event.reference().id().value());
		size += BinaryFormat.stringSize(event.stream().context());
		size += BinaryFormat.stringSize(event.stream().purpose());
		size += BinaryFormat.stringSize(event.type().name());
		size += BinaryFormat.stringSize(event.idempotencyKey());
		for ( Tag tag : tags ) {
			size += BinaryFormat.stringSize(tag.key());
			size += BinaryFormat.stringSize(tag.value());
		}
		size += BinaryFormat.stringSize(event.immutableData());
		size += BinaryFormat.stringSize(event.erasableData());
		return size;
	}

}
