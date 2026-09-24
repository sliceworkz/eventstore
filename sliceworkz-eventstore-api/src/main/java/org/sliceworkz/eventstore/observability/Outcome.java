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
package org.sliceworkz.eventstore.observability;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.query.EventFilter;

/**
 * What an operation answered, reported through {@link Observation.Scope#completed(Outcome)}.
 * <p>
 * The outcomes of the operations performed through a stream carry a {@code storageTime}: the part of the
 * operation spent inside the {@link org.sliceworkz.eventstore.spi.EventStorage}. The scope itself spans
 * the whole call, so what remains is the store's own work — serializing, deserializing, upcasting,
 * sealing and unsealing — which on an ordinary page is most of the wait. A projector batch and an
 * erasure have no such share: a batch's storage work is its child observations', and an erasure's is all
 * key-store work.
 *
 * @see Observation
 */
public sealed interface Outcome {

	/**
	 * What an admitted append answers: one of three things, none of them a failure. A batch is either
	 * stored whole or not at all, so these are the only answers — there is no partial one.
	 * <p>
	 * The consistency boundary is checked first: an append that is both stale and a retry answers
	 * {@link Conflicted}, as the caller is told.
	 */
	sealed interface AppendResult extends Outcome { }

	/**
	 * The batch was stored.
	 *
	 * @param storageTime the time spent in the storage's append
	 * @param stored how many events were stored
	 * @param storedPerType how many of each type, by stored name
	 * @param last the reference of the last event stored
	 */
	record Appended ( Duration storageTime, int stored, Map<EventType, Integer> storedPerType, EventReference last ) implements AppendResult {
		public Appended {
			storedPerType = Map.copyOf(storedPerType);
		}
	}

	/**
	 * New facts matched the consistency boundary after the reference the caller decided on: nothing was
	 * stored, and the caller receives an {@link org.sliceworkz.eventstore.stream.OptimisticLockingException}
	 * to re-decide on. The DCB answer to a stale decision, reported as an answer rather than a failure.
	 *
	 * @param storageTime the time spent in the storage's append
	 * @param boundary the boundary the caller decided on
	 * @param expected the reference the caller decided on, empty for "an empty boundary"
	 */
	record Conflicted ( Duration storageTime, EventFilter boundary, Optional<EventReference> expected ) implements AppendResult { }

	/**
	 * Every idempotency key in the batch was stored before: a retry, swallowed whole. Nothing was stored
	 * and nobody is notified; the caller receives an empty list.
	 *
	 * @param storageTime the time spent in the storage's append
	 * @param events how many events the batch carried, every one of them swallowed
	 */
	record Duplicated ( Duration storageTime, int events ) implements AppendResult { }

	/**
	 * A read completed.
	 *
	 * @param storageTime the time spent in the storage's query
	 * @param storedEventsRead how many stored events the storage returned
	 * @param readPerStoredType how many of each stored type — the type in storage, before upcasting
	 * @param eventsReturned how many events the caller received, which upcasting and the re-check against
	 *                       the caller's filter can make differ from the stored events read
	 */
	record Read ( Duration storageTime, int storedEventsRead, Map<EventType, Integer> readPerStoredType, int eventsReturned ) implements Outcome {
		public Read {
			readPerStoredType = Map.copyOf(readPerStoredType);
		}
	}

	/**
	 * A head lookup completed.
	 *
	 * @param storageTime the time spent in the storage's lookup
	 * @param head the head, empty for an empty stream
	 */
	record HeadRead ( Duration storageTime, Optional<EventReference> head ) implements Outcome { }

	/**
	 * A lookup of one thing completed.
	 *
	 * @param storageTime the time spent in the storage's lookup
	 * @param found whether it was there
	 */
	record Found ( Duration storageTime, boolean found ) implements Outcome { }

	/**
	 * A listing completed.
	 *
	 * @param storageTime the time spent in the storage's listing
	 * @param count how many were listed
	 */
	record Counted ( Duration storageTime, int count ) implements Outcome { }

	/**
	 * A write with nothing to report but its completion.
	 *
	 * @param storageTime the time spent in the storage's write
	 */
	record Done ( Duration storageTime ) implements Outcome { }

	/**
	 * A projector batch completed: committed and, for a bookmarked projector, bookmarked.
	 *
	 * @param storedEventsRead how many stored events the batch read
	 * @param eventsHandled how many events were handed to the projection
	 * @param last where the projector stands after the batch, if anywhere
	 * @param bookmarked whether the batch placed the bookmark
	 */
	record Projected ( int storedEventsRead, int eventsHandled, Optional<EventReference> last, boolean bookmarked ) implements Outcome { }

	/**
	 * An erasure completed.
	 *
	 * @param keysShredded how many keys were destroyed
	 * @param categories the categories that held live keys, and so were erased
	 */
	record Erased ( int keysShredded, List<String> categories ) implements Outcome {
		public Erased {
			categories = List.copyOf(categories);
		}
	}

}
