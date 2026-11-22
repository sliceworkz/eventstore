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
package org.sliceworkz.eventstore.infra.postgres;

import java.util.Locale;

/**
 * <b>Experimental, off by default, not public.</b> How many round trips a conditional append spends
 * while it holds its stream's advisory lock.
 *
 * <p>A conditional append is the lock statement, the {@code INSERT} carrying the DCB check, and the
 * commit. Shipped, they are three round trips, and the lock is held from the first one's answer to the
 * third one's: about two round trips plus the work. On a stream every writer appends to, that hold is
 * the ceiling on throughput, and on a network it is mostly waiting for the wire.
 *
 * <p>{@link #SINGLE_ROUND_TRIP} sends all three as one pipelined statement string, which pgjdbc writes
 * as one batch of extended-protocol messages behind one sync. The server still runs them as separate
 * statements in order — so the {@code INSERT} still takes its snapshot after the lock is granted, which
 * is what the check rests on — and the lock is released by the {@code COMMIT} at the end of the same
 * batch. No statement, no snapshot and no answer changes; only the wire between them goes.
 *
 * <p>Selected with the system property {@value #SYSTEM_PROPERTY} when a storage is built
 * ({@code off} or {@code single-round-trip}), and by the package's tests through
 * {@code PostgresEventStorageImpl#appendPipelining(AppendPipelining)}. Deliberately not a builder
 * method: a builder setting is public API, and this one has not earned it.
 */
enum AppendPipelining {

	/** The shipped behaviour: lock, {@code INSERT} and commit are a round trip each. */
	OFF,

	/** Lock, {@code INSERT} and {@code COMMIT} in one round trip. */
	SINGLE_ROUND_TRIP;

	/** The system property a storage reads at build time to select a mode. */
	static final String SYSTEM_PROPERTY = "eventstore.postgres.experimental.appendPipelining";

	/**
	 * The mode {@link #SYSTEM_PROPERTY} names, {@link #OFF} when it is unset.
	 *
	 * @throws IllegalArgumentException for a value naming no mode, so a typo cannot silently run the
	 *         other mode than the one a benchmark believes it is measuring
	 */
	static AppendPipelining fromSystemProperty ( ) {
		return parse(System.getProperty(SYSTEM_PROPERTY));
	}

	static AppendPipelining parse ( String value ) {
		if ( value == null || value.isBlank() ) {
			return OFF;
		}
		String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
		for ( AppendPipelining mode : values() ) {
			if ( mode.name().equals(normalized) ) {
				return mode;
			}
		}
		throw new IllegalArgumentException("%s=%s names no append pipelining mode; use off or single-round-trip"
				.formatted(SYSTEM_PROPERTY, value));
	}

	/**
	 * One conditional append, for the experiment's benchmarks and tests, in nanoseconds on the caller's
	 * clock.
	 *
	 * @param lockWaitNanos acquiring the lock, when it is a round trip of its own; 0 when pipelined
	 * @param lockHoldNanos from holding the lock to the commit's answer, when the lock is a round trip
	 *        of its own; 0 when pipelined, where the hold is not observable from the client
	 * @param totalNanos the whole of {@code append}, connection checkout included
	 * @param conflicted whether the append raised an optimistic-locking conflict
	 */
	record Timing ( long lockWaitNanos, long lockHoldNanos, long totalNanos, boolean conflicted ) { }

	/** Told the {@link Timing} of every conditional append that reached the database; absent by default. */
	@FunctionalInterface
	interface Recorder {
		void record ( Timing timing );
	}
}
