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

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Thrown when an import cannot proceed because the target storage already holds a conflicting event.
 * <p>
 * Two distinct conflicts are reported through this exception, distinguished by {@link #kind()}:
 * <ul>
 *   <li>{@link Kind#DUPLICATE_EVENT_ID} — an event with the same {@link EventId} already exists. Raised in
 *       {@link EventStorage.ImportMode#FAIL_ON_EXISTING_ID}; under
 *       {@link EventStorage.ImportMode#SKIP_EXISTING_ID} such an event is skipped instead.</li>
 *   <li>{@link Kind#DUPLICATE_IDEMPOTENCY_KEY} — a <em>different</em> event already holds this idempotency key
 *       on the same stream. Always fatal, in both modes: skipping past it would discard an event that the
 *       target has never seen.</li>
 * </ul>
 * <p>
 * The batch that raised this exception is rolled back in full. Batches committed before it are not — an
 * import is atomic per batch, not overall. Re-running under {@link EventStorage.ImportMode#SKIP_EXISTING_ID}
 * is the supported way to continue after resolving the conflict.
 *
 * @see EventStorage#importEvents(java.util.List, EventStorage.ImportMode)
 */
public class EventImportConflictException extends EventStorageException {

	private final Kind kind;
	private final EventId eventId;
	private final EventStreamId stream;
	private final String idempotencyKey;

	/**
	 * The nature of the conflict that stopped the import.
	 */
	public enum Kind {
		/** An event with the same identifier already exists in the target storage. */
		DUPLICATE_EVENT_ID,
		/** A different event already holds this idempotency key on the same stream. */
		DUPLICATE_IDEMPOTENCY_KEY
	}

	/**
	 * Constructs a conflict for an event identifier that already exists in the target.
	 *
	 * @param eventId the conflicting event identifier, or null if the backend could not determine it
	 * @param cause the underlying storage exception, or null
	 * @return a new EventImportConflictException of kind {@link Kind#DUPLICATE_EVENT_ID}
	 */
	public static EventImportConflictException duplicateEventId ( EventId eventId, Throwable cause ) {
		return new EventImportConflictException(
				Kind.DUPLICATE_EVENT_ID,
				eventId,
				null,
				null,
				"an event with id %s already exists in the target storage".formatted(eventId == null ? "<unknown>" : eventId.value()),
				cause);
	}

	/**
	 * Constructs a conflict for an idempotency key already in use on the target stream.
	 *
	 * @param stream the stream the key collides on, or null if the backend could not determine it
	 * @param idempotencyKey the conflicting idempotency key, or null if the backend could not determine it
	 * @param cause the underlying storage exception, or null
	 * @return a new EventImportConflictException of kind {@link Kind#DUPLICATE_IDEMPOTENCY_KEY}
	 */
	public static EventImportConflictException duplicateIdempotencyKey ( EventStreamId stream, String idempotencyKey, Throwable cause ) {
		return new EventImportConflictException(
				Kind.DUPLICATE_IDEMPOTENCY_KEY,
				null,
				stream,
				idempotencyKey,
				"idempotency key %s is already in use on stream %s by a different event".formatted(
						idempotencyKey == null ? "<unknown>" : idempotencyKey,
						stream == null ? "<unknown>" : stream.toString()),
				cause);
	}

	private EventImportConflictException ( Kind kind, EventId eventId, EventStreamId stream, String idempotencyKey, String message, Throwable cause ) {
		super(message, cause);
		this.kind = kind;
		this.eventId = eventId;
		this.stream = stream;
		this.idempotencyKey = idempotencyKey;
	}

	/**
	 * Returns what kind of conflict stopped the import.
	 *
	 * @return the conflict kind, never null
	 */
	public Kind kind ( ) {
		return kind;
	}

	/**
	 * Returns the conflicting event identifier for a {@link Kind#DUPLICATE_EVENT_ID} conflict.
	 *
	 * @return the event identifier, or null for other kinds or when the backend could not determine it
	 */
	public EventId eventId ( ) {
		return eventId;
	}

	/**
	 * Returns the stream a {@link Kind#DUPLICATE_IDEMPOTENCY_KEY} conflict occurred on.
	 *
	 * @return the stream, or null for other kinds or when the backend could not determine it
	 */
	public EventStreamId stream ( ) {
		return stream;
	}

	/**
	 * Returns the conflicting idempotency key for a {@link Kind#DUPLICATE_IDEMPOTENCY_KEY} conflict.
	 *
	 * @return the idempotency key, or null for other kinds or when the backend could not determine it
	 */
	public String idempotencyKey ( ) {
		return idempotencyKey;
	}

}
