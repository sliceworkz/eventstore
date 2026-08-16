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
package org.sliceworkz.eventstore.shredding;

import java.time.Instant;
import java.util.List;

/**
 * What an erasure actually destroyed.
 * <p>
 * Returned by {@link org.sliceworkz.eventstore.EventStore#erase}. It reports keys, not events: nothing
 * in the events table was touched, so there is no row count to give. Every value sealed under a key
 * named here became unreadable at the moment the key went, wherever that ciphertext had already spread
 * — the events table, WAL, replicas, last night's backup.
 *
 * <h2>Finding the events, if you need to</h2>
 * The key ids are carried on each event as {@code dek:} tags, so the affected events are an ordinary
 * tag query rather than a table scan:
 * <pre>{@code
 * ErasureReport report = eventStore.erase(alice, ErasureReason.of("art.17 request #4711"));
 *
 * for ( KeyId key : report.shreddedKeys() ) {
 *     stream.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("dek", key.value())))
 *           .forEach(…);
 * }
 * }</pre>
 * The count is deliberately not computed for you: it reads every matching event, which is a surprising
 * cost to bury inside an erasure call that is otherwise a single key-store write.
 *
 * <h2>An empty report is not an error</h2>
 * {@link #isNoop()} means the subject held no keys — usually because nothing was ever appended for
 * them, or because they were erased already. Erasure is idempotent, so re-running it is safe and says
 * so rather than failing.
 *
 * @param subject      whose data was erased
 * @param reason       the recorded authority for the erasure
 * @param shreddedKeys the keys destroyed, in no particular order; empty if the subject held none
 * @param shreddedAt   when the key store performed the erasure
 *
 * @see org.sliceworkz.eventstore.EventStore#erase(DataSubject, ErasureReason)
 */
public record ErasureReport ( DataSubject subject, ErasureReason reason, List<KeyId> shreddedKeys, Instant shreddedAt ) {

	/**
	 * Defensively copies the key list so a report cannot be altered after the fact.
	 *
	 * @throws IllegalArgumentException if any component is null
	 */
	public ErasureReport {
		if ( subject == null ) {
			throw new IllegalArgumentException("ErasureReport subject must not be null");
		}
		if ( reason == null ) {
			throw new IllegalArgumentException("ErasureReport reason must not be null");
		}
		if ( shreddedKeys == null ) {
			throw new IllegalArgumentException("ErasureReport shreddedKeys must not be null; use an empty list when nothing was erased");
		}
		if ( shreddedAt == null ) {
			throw new IllegalArgumentException("ErasureReport shreddedAt must not be null");
		}
		shreddedKeys = List.copyOf(shreddedKeys);
	}

	/**
	 * @return how many keys were destroyed
	 */
	public int keysShredded ( ) {
		return shreddedKeys.size();
	}

	/**
	 * @return true if the subject held no keys, so nothing was erased
	 */
	public boolean isNoop ( ) {
		return shreddedKeys.isEmpty();
	}

	@Override
	public String toString ( ) {
		return "erased %s: %d key(s) shredded at %s (%s)".formatted(subject, keysShredded(), shreddedAt, reason);
	}

}
