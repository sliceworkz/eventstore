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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One aspect of a storage's health: whether its database answers, whether a notification channel is
 * listening, whether reads are held behind the visibility barrier.
 * <p>
 * The details are what an operator reads next to the status — a round trip in milliseconds, a pool's
 * connection counts — and hold JSON scalars only ({@code String}, {@code Number}, {@code Boolean}), so a
 * health endpoint renders them as they are. They keep the order they were given in. The names a storage
 * uses are constants on the backend that reports them; {@link #OPEN} is the one every backend reports.
 *
 * @param name what was checked, a camelCase name stable across releases
 * @param status how it is
 * @param message what is wrong and what it costs, in words; empty when there is nothing to say
 * @param details facts behind the status, in the order given
 */
public record HealthCheck ( String name, HealthStatus status, String message, Map<String, Object> details ) {

	/** Whether the storage is open: {@link HealthStatus#DOWN} once it is closed, for good. */
	public static final String OPEN = "open";

	/**
	 * @throws IllegalArgumentException for a blank name, or a detail that is null or not a JSON scalar
	 */
	public HealthCheck {
		Objects.requireNonNull(name, "name must not be null");
		if ( name.isBlank() ) {
			throw new IllegalArgumentException("name must not be blank");
		}
		Objects.requireNonNull(status, "status must not be null");
		message = message == null ? "" : message;
		Objects.requireNonNull(details, "details must not be null (use Map.of() for none)");
		LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
		details.forEach(( key, value ) -> {
			if ( key == null || value == null ) {
				throw new IllegalArgumentException("health check '%s' has a null detail: %s=%s".formatted(name, key, value));
			}
			if ( !(value instanceof String || value instanceof Number || value instanceof Boolean) ) {
				throw new IllegalArgumentException("health check '%s' detail '%s' is a %s; details are JSON scalars (String, Number, Boolean)"
					.formatted(name, key, value.getClass().getName()));
			}
			copy.put(key, value);
		});
		details = Collections.unmodifiableMap(copy);
	}

	/**
	 * The {@link #OPEN} check, the one every backend reports.
	 *
	 * @param open whether the storage is open
	 * @return {@link HealthStatus#UP} while open, {@link HealthStatus#DOWN} once closed
	 */
	public static HealthCheck open ( boolean open ) {
		return open
			? up(OPEN, Map.of())
			: of(OPEN, HealthStatus.DOWN, "the storage is closed: every operation on it throws, and it cannot be reopened", Map.of());
	}

	/**
	 * A check that found nothing wrong.
	 *
	 * @param name what was checked
	 * @param details facts behind it
	 * @return the check
	 */
	public static HealthCheck up ( String name, Map<String, Object> details ) {
		return new HealthCheck(name, HealthStatus.UP, "", details);
	}

	/**
	 * A check that found something wrong, or could not be made.
	 *
	 * @param name what was checked
	 * @param status how it is
	 * @param message what is wrong and what it costs
	 * @param details facts behind it
	 * @return the check
	 */
	public static HealthCheck of ( String name, HealthStatus status, String message, Map<String, Object> details ) {
		return new HealthCheck(name, status, message, details);
	}

}
