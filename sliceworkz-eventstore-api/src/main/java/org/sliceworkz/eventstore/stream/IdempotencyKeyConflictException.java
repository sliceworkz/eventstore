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
package org.sliceworkz.eventstore.stream;

import java.util.Set;

/**
 * Thrown when a batch of events carries idempotency keys of which some are already stored on the
 * stream and others are not, so that the batch can neither be stored nor be a retry of one that was.
 * <p>
 * An append is de-duplicated only when <em>every</em> key it carries was stored before, which is the
 * one shape a retry of an atomically stored batch can have. A batch mixing stored and new keys is
 * something else: one of its events collides with a different event that holds its key, and the rest
 * are unknown to the store. Storing the unknown ones would leave the caller believing the colliding
 * fact landed too; swallowing the batch would lose the unknown ones with nothing to say so. So nothing
 * is stored and this is raised, naming both sets of keys.
 * <p>
 * Never worth retrying: the keys are a property of the batch and the stream's history, identical on
 * the next attempt. The realistic cause is a key derived from a business fact that an earlier command
 * already recorded, or a key reused across commands. It deliberately does not extend
 * {@link org.sliceworkz.eventstore.spi.EventStorageException}, which a caller retries with backoff.
 * <p>
 * Serializable, like every exception here: the stream is carried by its rendering and the keys as
 * plain strings, so the report arrives intact across a process boundary.
 *
 * @see EventSink#append(AppendCriteria, java.util.List)
 * @see org.sliceworkz.eventstore.events.EphemeralEvent#withIdempotencyKey(String)
 */
public class IdempotencyKeyConflictException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String stream;
	private final Set<String> storedKeys;
	private final Set<String> newKeys;

	/**
	 * Constructs the exception for a batch whose keys are partly stored on the stream.
	 *
	 * @param stream the stream the batch was appended to
	 * @param storedKeys the keys of the batch already held by events on the stream (at least one)
	 * @param newKeys the keys of the batch the stream does not hold (at least one)
	 */
	public IdempotencyKeyConflictException ( EventStreamId stream, Set<String> storedKeys, Set<String> newKeys ) {
		super("batch on stream %s mixes idempotency keys already stored %s with keys not stored %s: it is not a retry of a stored batch, and nothing of it was stored"
				.formatted(stream, storedKeys, newKeys));
		this.stream = String.valueOf(stream);
		this.storedKeys = Set.copyOf(storedKeys);
		this.newKeys = Set.copyOf(newKeys);
	}

	/**
	 * Returns the stream the batch was appended to, as rendered by {@link EventStreamId#toString()}.
	 *
	 * @return the stream, never null
	 */
	public String stream ( ) {
		return stream;
	}

	/**
	 * Returns the keys of the batch that events on the stream already hold.
	 *
	 * @return the stored keys, never empty
	 */
	public Set<String> storedKeys ( ) {
		return storedKeys;
	}

	/**
	 * Returns the keys of the batch that the stream does not hold.
	 *
	 * @return the new keys, never empty
	 */
	public Set<String> newKeys ( ) {
		return newKeys;
	}
}
