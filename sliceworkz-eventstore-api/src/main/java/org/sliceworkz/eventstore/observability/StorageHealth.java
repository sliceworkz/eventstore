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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A storage's health at one moment: what {@link org.sliceworkz.eventstore.spi.EventStorage#health()}
 * answers, for a health endpoint to render.
 * <p>
 * A list of {@link HealthCheck}s, one per aspect the backend can judge, and a {@link #status()} that is
 * derived from them — the worst — rather than stated beside them, so the two can never disagree. A
 * storage that checked nothing is {@link HealthStatus#UNKNOWN}.
 * <p>
 * It is a snapshot taken when asked, never a cache: a caller probing every few seconds asks the database
 * every few seconds, which is what makes the answer worth having. Pacing and caching belong to the
 * caller, the same as mapping a status onto an HTTP code.
 *
 * @param storage the storage's {@link org.sliceworkz.eventstore.spi.EventStorage#name() name}
 * @param checks what was checked, in the order the backend checked it
 */
public record StorageHealth ( String storage, List<HealthCheck> checks ) {

	/**
	 * @throws IllegalArgumentException for two checks with one name
	 */
	public StorageHealth {
		Objects.requireNonNull(storage, "storage must not be null");
		checks = List.copyOf(Objects.requireNonNull(checks, "checks must not be null"));
		if ( checks.stream().map(HealthCheck::name).distinct().count() != checks.size() ) {
			throw new IllegalArgumentException("storage '%s' reports two health checks with one name: %s"
				.formatted(storage, checks.stream().map(HealthCheck::name).toList()));
		}
	}

	/**
	 * The storage's status: the worst of its checks, {@link HealthStatus#UNKNOWN} when there are none.
	 *
	 * @return the status
	 */
	public HealthStatus status ( ) {
		return HealthStatus.worst(checks.stream().map(HealthCheck::status).toList());
	}

	/**
	 * One check by name.
	 *
	 * @param name the check's name
	 * @return the check, or empty when this storage did not report it
	 */
	public Optional<HealthCheck> check ( String name ) {
		return checks.stream().filter(c -> c.name().equals(name)).findFirst();
	}

}
