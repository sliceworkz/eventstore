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
package org.sliceworkz.eventstore.benchmark.load;

/**
 * What kind of load a run applies.
 *
 * <p>These are the measurements JMH cannot host, and each is here for a specific reason rather than
 * as a variation on the others.
 */
public enum LoadScenario {

	/**
	 * Writers as fast as they can go, or at a fixed offered rate.
	 *
	 * <p>Unlike a JMH append benchmark this runs against a store that is <em>growing</em> the whole
	 * time, which is what a real ingest looks like: the index deepens, the table outgrows the cache,
	 * autovacuum wakes up. A benchmark that restores the corpus between iterations deliberately hides
	 * all of that, and should -- but then something has to measure it.
	 */
	WRITE_SATURATION,

	/**
	 * Readers and writers together.
	 *
	 * <p>Worth its own scenario because contention between them is not the sum of measuring each
	 * alone: readers sit behind the {@code pg_snapshot_xmin} barrier that writers advance, and on
	 * Postgres a conditional append holds an advisory lock that a reader never takes but a checkpoint
	 * triggered by the writes will still make it wait for.
	 */
	MIXED,

	/**
	 * Append returns, then the subscriber's callback fires. Nothing else in between.
	 *
	 * <p>Isolates notification delivery -- LISTEN/NOTIFY on Postgres, a direct call in memory, and the
	 * optimizing decorator in both cases -- from whatever a projection then does with it. Without this
	 * half, a disappointing end-to-end figure has no attribution: it could be the store's plumbing or
	 * the read model's own work, and those get fixed in entirely different places.
	 */
	NOTIFY_LATENCY,

	/**
	 * Append returns, then a subscribed projector has committed the event to a read model.
	 *
	 * <p>The number a user actually feels: how long after a write does the thing they are looking at
	 * change. Measured against {@link #NOTIFY_LATENCY} it says how much of the wait is delivery and how
	 * much is projection.
	 */
	END_TO_END_LATENCY;

	public static LoadScenario parse ( String value ) {
		if ( value == null || value.isBlank() ) {
			throw new IllegalArgumentException("a load profile needs a scenario");
		}
		String normalised = value.strip().toLowerCase().replace('-', '_');
		for ( LoadScenario scenario : values() ) {
			if ( scenario.name().toLowerCase().equals(normalised) ) {
				return scenario;
			}
		}
		throw new IllegalArgumentException("unknown load scenario '%s'; expected one of %s"
				.formatted(value, java.util.Arrays.stream(values())
						.map(s -> s.name().toLowerCase().replace('_', '-'))
						.reduce(( a, b ) -> a + ", " + b).orElse("")));
	}

	/** The name a profile writes. */
	public String profileName ( ) {
		return name().toLowerCase().replace('_', '-');
	}

	/** Whether this scenario needs a subscriber, and so a store whose notifications are working. */
	public boolean needsSubscription ( ) {
		return this == NOTIFY_LATENCY || this == END_TO_END_LATENCY;
	}
}
