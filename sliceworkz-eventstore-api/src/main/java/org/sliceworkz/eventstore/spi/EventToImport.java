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
package org.sliceworkz.eventstore.spi;

import java.time.LocalDateTime;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * An event offered to {@link EventStorage#importEvents(java.util.List, EventStorage.ImportMode)} for
 * identity-preserving insertion into a storage backend.
 * <p>
 * This is a {@link StoredEvent} minus {@code position}, {@code tx} and {@code index}: exactly the fields
 * a caller controls. Position and transaction are always assigned by the target storage — an import never
 * reproduces the source ordering numbers, only the source <em>order</em>. The record shape makes that
 * explicit, so a caller cannot hand-set a position that would be silently ignored.
 * <p>
 * What <em>is</em> preserved, because it is carried on this record:
 * <ul>
 *   <li>{@link #id()} — the event's globally unique {@link EventId}</li>
 *   <li>{@link #timestamp()} — the moment the event was originally stored</li>
 *   <li>{@link #idempotencyKey()} — the key the event was originally appended with</li>
 * </ul>
 *
 * <h2>Import versus append</h2>
 * Importing bypasses {@link EventStorage#append(org.sliceworkz.eventstore.stream.AppendCriteria, java.util.Optional, java.util.List)}
 * entirely: no optimistic locking, no serialization, no upcasting, no re-splitting of erasable data.
 * The payload moves as opaque JSON, so an import needs no domain classes on the classpath and legacy
 * event types survive as legacy event types.
 *
 * <h2>Rewriting during import</h2>
 * Every field has a wither, so an import transformation may remap the stream, retag, rewrite the payload,
 * change the type, mint a new {@link EventId} or restamp the timestamp. That flexibility is deliberate —
 * it supports stream cloning and schema migration — but it means this type offers <em>no</em> fidelity
 * guarantee of its own. What arrives in the target is whatever the caller asked for.
 * <p>
 * Two rewrites deserve particular care:
 * <ul>
 *   <li><b>Rewriting the id</b> makes {@link EventStorage.ImportMode#SKIP_EXISTING_ID} meaningless: there is
 *       no stable identity left to recognise, so a re-run imports everything a second time.</li>
 *   <li><b>Keeping the idempotency key while rewriting the id</b> (for instance when cloning a stream) carries
 *       a key that was minted to deduplicate a different stream. A later legitimate append reusing that key
 *       against the clone will be silently swallowed.</li>
 * </ul>
 *
 * <h2>Constructing from scratch</h2>
 * The canonical constructor is public, so this record can also be used to write synthetic events — with a
 * chosen id and timestamp — directly into a storage backend. That is useful for fixtures and backfills, but
 * note it bypasses {@code append()} and therefore every guarantee that path provides.
 *
 * @param stream the event stream the event belongs to (required)
 * @param type the event type name (required)
 * @param id the globally unique event identifier to persist (required)
 * @param immutableData serialized event data that must be retained permanently (required, must be valid JSON)
 * @param erasableData serialized event data that may be erased for privacy compliance (optional, may be null)
 * @param tags key-value pairs for dynamic event retrieval and consistency boundaries (required, use {@link Tags#none()})
 * @param timestamp the moment the event was stored, always in UTC (required)
 * @param idempotencyKey the idempotency key to persist, or {@code null} for none
 * @see EventStorage#importEvents(java.util.List, EventStorage.ImportMode)
 * @see StoredEvent
 */
public record EventToImport ( EventStreamId stream, EventType type, EventId id, String immutableData, String erasableData, Tags tags, LocalDateTime timestamp, String idempotencyKey ) {

	/**
	 * Constructs an EventToImport, validating everything the storage backends require.
	 * <p>
	 * The timestamp is required even though the underlying column is nullable: binding a null timestamp
	 * overrides the column default rather than falling back to it, and a stored null cannot be read back.
	 * <p>
	 * {@code immutableData} is checked for presence only. Whether it is well-formed JSON is enforced by the
	 * storage backend, since this module carries no JSON parser.
	 *
	 * @param stream the event stream (required)
	 * @param type the event type (required)
	 * @param id the event identifier (required)
	 * @param immutableData the immutable payload (required)
	 * @param erasableData the erasable payload (optional)
	 * @param tags the tags (required, use {@link Tags#none()} if none)
	 * @param timestamp the storage timestamp in UTC (required)
	 * @param idempotencyKey the idempotency key (optional)
	 * @throws IllegalArgumentException if a required field is null or blank
	 */
	public EventToImport {
		if ( stream == null ) {
			throw new IllegalArgumentException("stream is required on an event to import");
		}
		if ( type == null || type.name() == null || type.name().isBlank() ) {
			throw new IllegalArgumentException("type is required on an event to import");
		}
		if ( id == null ) {
			throw new IllegalArgumentException("id is required on an event to import: an import preserves event identity");
		}
		if ( immutableData == null ) {
			throw new IllegalArgumentException("immutableData is required on an event to import");
		}
		if ( tags == null ) {
			throw new IllegalArgumentException("tags is required on an event to import, use Tags.none() if there are none");
		}
		if ( timestamp == null ) {
			throw new IllegalArgumentException("timestamp is required on an event to import: a null timestamp cannot be persisted or read back");
		}
	}

	/**
	 * Creates an EventToImport carrying everything the stored event holds except its position and transaction.
	 * <p>
	 * This is the starting point for an import transformation: take the source event, adjust what needs
	 * adjusting via the withers, and hand the result to the target storage.
	 *
	 * @param storedEvent the event as read from the source storage (required)
	 * @return an EventToImport preserving the stored event's id, timestamp, payload, tags and idempotency key
	 * @throws IllegalArgumentException if the stored event is null or is missing a required field
	 */
	public static EventToImport from ( StoredEvent storedEvent ) {
		if ( storedEvent == null ) {
			throw new IllegalArgumentException("storedEvent is required");
		}
		return new EventToImport(
				storedEvent.stream(),
				storedEvent.type(),
				storedEvent.reference() == null ? null : storedEvent.reference().id(),
				storedEvent.immutableData(),
				storedEvent.erasableData(),
				storedEvent.tags(),
				storedEvent.timestamp(),
				storedEvent.idempotencyKey());
	}

	/**
	 * Converts this event into a {@link StoredEvent} by pairing its preserved id with the position and
	 * transaction the target storage assigned.
	 * <p>
	 * Called by storage implementations once an import statement has committed. Mirrors
	 * {@link EventStorage.EventToStore#positionAt(EventReference, LocalDateTime)}, except the timestamp comes
	 * from the imported event rather than from the storage clock.
	 *
	 * @param position the position assigned by the target storage
	 * @param tx the transaction assigned by the target storage
	 * @return a StoredEvent with the preserved identity and the newly assigned reference
	 */
	public StoredEvent positionAt ( long position, long tx ) {
		return new StoredEvent(stream, type, EventReference.of(id, position, tx), immutableData, erasableData, tags, timestamp, idempotencyKey);
	}

	/**
	 * Creates a copy of this event targeting a different event stream.
	 * <p>
	 * The primary remapping operation: import a source stream into a differently named context or purpose.
	 * Remember that idempotency keys are scoped per stream, so collapsing two source streams onto one target
	 * stream can turn previously disjoint keys into a conflict.
	 *
	 * @param stream the event stream to import into
	 * @return a new EventToImport for the specified stream
	 */
	public EventToImport withStream ( EventStreamId stream ) {
		return new EventToImport(stream, type, id, immutableData, erasableData, tags, timestamp, idempotencyKey);
	}

	/**
	 * Creates a copy of this event with a different event type name.
	 *
	 * @param type the event type to store
	 * @return a new EventToImport with the specified type
	 */
	public EventToImport withType ( EventType type ) {
		return new EventToImport(stream, type, id, immutableData, erasableData, tags, timestamp, idempotencyKey);
	}

	/**
	 * Creates a copy of this event with a different event identifier.
	 * <p>
	 * Use this to clone a stream into a new set of events rather than move it. Doing so forfeits
	 * {@link EventStorage.ImportMode#SKIP_EXISTING_ID}, which has nothing stable left to match on.
	 *
	 * @param id the event identifier to store
	 * @return a new EventToImport with the specified id
	 */
	public EventToImport withId ( EventId id ) {
		return new EventToImport(stream, type, id, immutableData, erasableData, tags, timestamp, idempotencyKey);
	}

	/**
	 * Creates a copy of this event with a different immutable payload.
	 *
	 * @param immutableData the JSON payload to store as immutable data
	 * @return a new EventToImport with the specified immutable payload
	 */
	public EventToImport withImmutableData ( String immutableData ) {
		return new EventToImport(stream, type, id, immutableData, erasableData, tags, timestamp, idempotencyKey);
	}

	/**
	 * Creates a copy of this event with a different erasable payload.
	 *
	 * @param erasableData the JSON payload to store as erasable data, or null for none
	 * @return a new EventToImport with the specified erasable payload
	 */
	public EventToImport withErasableData ( String erasableData ) {
		return new EventToImport(stream, type, id, immutableData, erasableData, tags, timestamp, idempotencyKey);
	}

	/**
	 * Creates a copy of this event with different tags.
	 *
	 * @param tags the tags to attach
	 * @return a new EventToImport with the specified tags
	 */
	public EventToImport withTags ( Tags tags ) {
		return new EventToImport(stream, type, id, immutableData, erasableData, tags, timestamp, idempotencyKey);
	}

	/**
	 * Creates a copy of this event with a different timestamp.
	 * <p>
	 * Preserving the original timestamp is the default and usually the right choice — it is part of the fact.
	 * Restamping is available for cases where the imported event is genuinely a new fact, such as a clone.
	 *
	 * @param timestamp the timestamp to store, in UTC
	 * @return a new EventToImport with the specified timestamp
	 */
	public EventToImport withTimestamp ( LocalDateTime timestamp ) {
		return new EventToImport(stream, type, id, immutableData, erasableData, tags, timestamp, idempotencyKey);
	}

	/**
	 * Creates a copy of this event with a different idempotency key.
	 *
	 * @param idempotencyKey the idempotency key to store, or null for none
	 * @return a new EventToImport with the specified idempotency key
	 */
	public EventToImport withIdempotencyKey ( String idempotencyKey ) {
		return new EventToImport(stream, type, id, immutableData, erasableData, tags, timestamp, idempotencyKey);
	}

}
