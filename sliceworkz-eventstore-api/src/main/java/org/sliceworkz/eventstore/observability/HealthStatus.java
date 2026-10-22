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

import java.util.Collection;

/**
 * How healthy a storage, or one aspect of it, is — part of a {@link StorageHealth}.
 * <p>
 * Declared from best to worst, and {@link #worst(Collection)} picks by that order, so the status of a
 * whole storage is that of its worst check. What a status <em>means</em> for the application around the
 * storage — whether a degraded storage takes an instance out of a load balancer, whether an unknown one
 * pages anyone — is the caller's decision, not the library's: a health endpoint maps these onto its own
 * vocabulary.
 */
public enum HealthStatus {

	/** Working as it should. */
	UP,

	/** The storage does not say: a backend that reports no health, or a check that could not be made. */
	UNKNOWN,

	/**
	 * Reads and writes work, but something the application relies on does not: notifications that wake no
	 * subscriber, reads held behind a cluster-wide visibility barrier, a connection pool with callers
	 * queueing for a connection. Restarting the process fixes none of these.
	 */
	DEGRADED,

	/** The storage cannot serve reads or writes: it is closed, or its database does not answer. */
	DOWN;

	/**
	 * Whether this status is worse than another.
	 *
	 * @param other the status to compare with
	 * @return {@code true} if this one is worse
	 */
	public boolean isWorseThan ( HealthStatus other ) {
		return compareTo(other) > 0;
	}

	/**
	 * The worst of some statuses.
	 *
	 * @param statuses the statuses, possibly none
	 * @return the worst of them, {@link #UNKNOWN} for none: a storage that checked nothing says nothing
	 */
	public static HealthStatus worst ( Collection<HealthStatus> statuses ) {
		return statuses.stream().max(Enum::compareTo).orElse(UNKNOWN);
	}

}
