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
package org.sliceworkz.eventstore.events;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A named lease held by a single owner, used to elect one processor among several instances.
 * <p>
 * Leases are produced by {@code requestLease(...)} on the
 * {@link org.sliceworkz.eventstore.spi.EventStorage} SPI and surfaced as a snapshot list via
 * {@code getLeases()}. A lease is <em>live</em> while its {@link #heartbeatAt()} is younger than the
 * time-to-live it was requested with, measured on the <b>storage's clock</b> — never on any
 * contender's clock. An expired lease is not removed; it merely becomes acquirable, and the next
 * successful acquisition overwrites it with a higher {@link #fencingToken()}.
 * <p>
 * The fencing token increases strictly on every acquisition — every request that finds the lease
 * absent, expired or released, whoever held it last — and is stable across renewals of a live lease
 * by its owner, so work stamped with an older token can be recognised as coming from a superseded
 * leader. That includes a leader superseded by itself: an owner re-acquiring its own expired lease
 * gets a new token, since the lease was acquirable in between and anything its paused earlier self
 * still stamps carries the old one.
 *
 * @param leaseName    the globally unique name of the lease (storage-wide, like a bookmark reader)
 * @param owner        the identifier of the owner currently holding (or last holding) the lease
 * @param priority     the priority the owner requested the lease with; a live contender with a
 *                     strictly higher priority makes the storage ask the owner to step down
 * @param fencingToken monotonically increasing per acquisition, starting at 1 for the first owner
 * @param acquiredAt   the storage-clock instant of the acquisition the current owner holds the lease
 *                     by; a renewal keeps it, a re-acquisition after expiry or release resets it
 * @param heartbeatAt  the storage-clock instant of the owner's most recent successful renewal
 * @param ttl          the time-to-live the owner requested; the lease expires when
 *                     {@link #heartbeatAt()} is older than this on the storage's clock
 */
public record Lease ( String leaseName, String owner, long priority, long fencingToken, Instant acquiredAt, Instant heartbeatAt, Duration ttl ) {

	public Lease {
		Objects.requireNonNull(leaseName, "leaseName must not be null");
		Objects.requireNonNull(owner, "owner must not be null");
		Objects.requireNonNull(acquiredAt, "acquiredAt must not be null");
		Objects.requireNonNull(heartbeatAt, "heartbeatAt must not be null");
		Objects.requireNonNull(ttl, "ttl must not be null");
	}

	/**
	 * Whether this lease is expired — and so acquirable by any contender — at the given instant.
	 * The instant must come from the same clock that produced {@link #heartbeatAt()}, i.e. the
	 * storage's clock; comparing against a contender's clock is exactly what leases exist to avoid.
	 *
	 * @param now the current instant on the storage's clock
	 * @return true when the last heartbeat is older than the lease's time-to-live
	 */
	public boolean isExpiredAt ( Instant now ) {
		return heartbeatAt.plus(ttl).isBefore(now);
	}

}
