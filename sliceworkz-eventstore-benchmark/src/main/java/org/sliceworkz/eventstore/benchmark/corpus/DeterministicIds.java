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
package org.sliceworkz.eventstore.benchmark.corpus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.sliceworkz.eventstore.events.EventId;

/**
 * Event ids and timestamps derived from the corpus seed, so that provisioning the same spec twice
 * produces byte-identical stores.
 *
 * <p>This is what makes a reusable corpus defensible rather than a hope. A corpus lives in a database
 * for weeks and is measured against repeatedly; "is the data still what the spec describes?" has to
 * be answerable by regenerating it and comparing, and that only works if nothing about it is random
 * in the ordinary sense. {@code UUID.randomUUID()} and {@code LocalDateTime.now()} are therefore both
 * out.
 *
 * <p><b>The ids are UUIDv7-shaped on purpose.</b> A v7 id carries a millisecond timestamp in its
 * leading bits, so ids sort in roughly the order they were created -- which is what the store's own
 * {@code uuidv7()} produces and what a real index therefore sees. Filling the same field with random
 * bytes would give the index a uniformly scattered key and measure a B-tree behaving in a way it
 * never does in production.
 *
 * <p>One caveat the generator has to respect: {@code importEvents} <b>reassigns</b> {@code position}
 * and {@code tx}. The timestamps here influence nothing about physical order, so BRIN correlation on
 * {@code event_position} follows <em>import order</em>. Events must be generated in the order they
 * should be stored.
 */
final class DeterministicIds {

	/** Where a corpus's history starts. Fixed, so the timestamps are a property of the spec alone. */
	static final LocalDateTime EPOCH = LocalDateTime.of(2024, 1, 1, 0, 0, 0);

	/**
	 * Mean gap between consecutive events. At 40ms a ten-million event corpus spans about five days,
	 * which keeps timestamps plausible without making the range so wide that date-bucketed reporting
	 * over one is meaningless.
	 */
	private static final long MEAN_GAP_MILLIS = 40;

	private final long seed;

	DeterministicIds ( long seed ) {
		this.seed = seed;
	}

	/**
	 * The timestamp of the n-th event. Monotonic in {@code sequence} so that import order, physical
	 * order and timestamp order all agree -- an inversion here would show up as decorrelated BRIN and
	 * be read as a property of the store rather than of the fixture.
	 */
	LocalDateTime timestampOf ( long sequence ) {
		return EPOCH.plusNanos(sequence * MEAN_GAP_MILLIS * 1_000_000L);
	}

	/**
	 * The id of the n-th event: a v7-shaped UUID whose timestamp bits come from
	 * {@link #timestampOf(long)} and whose remaining bits are a hash of the seed and the sequence.
	 */
	EventId idOf ( long sequence ) {
		long millis = timestampOf(sequence).toInstant(ZoneOffset.UTC).toEpochMilli();

		// mix the seed and the sequence into two well-distributed words; SplittableRandom's finaliser
		// is a convenient, stable avalanche function
		long hiRandom = mix(seed * 0x9E3779B97F4A7C15L + sequence);
		long loRandom = mix(hiRandom ^ ( seed + 0x632BE59BD9B4E019L ));

		// v7 layout: 48 bits of millisecond timestamp, 4 bits of version, 12 bits random,
		// 2 bits of variant, 62 bits random
		long msb = ( millis & 0xFFFF_FFFF_FFFFL ) << 16
				| 0x7000L
				| ( hiRandom & 0x0FFFL );
		long lsb = ( loRandom & 0x3FFF_FFFF_FFFF_FFFFL ) | 0x8000_0000_0000_0000L;

		return EventId.of(new UUID(msb, lsb).toString());
	}

	/** A per-sequence seed, so a generator thread can draw values without sharing mutable state. */
	long streamSeedFor ( long sequence ) {
		return mix(seed ^ ( sequence * 0xBF58476D1CE4E5B9L ));
	}

	private static long mix ( long value ) {
		long z = value + 0x9E3779B97F4A7C15L;
		z = ( z ^ ( z >>> 30 ) ) * 0xBF58476D1CE4E5B9L;
		z = ( z ^ ( z >>> 27 ) ) * 0x94D049BB133111EBL;
		return z ^ ( z >>> 31 );
	}
}
