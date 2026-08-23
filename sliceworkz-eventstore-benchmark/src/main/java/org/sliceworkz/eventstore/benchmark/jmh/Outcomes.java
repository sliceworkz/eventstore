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
package org.sliceworkz.eventstore.benchmark.jmh;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Counts what each invocation actually did, alongside the timing.
 *
 * <p>This exists because of {@code ONE_BOUNDARY}. There, most appends are <em>supposed</em> to fail:
 * every thread writes at the same consistency boundary, one wins and the rest raise
 * {@code OptimisticLockingException}. The conflict rate and what a retry costs are the measurement --
 * but a throughput figure alone cannot distinguish "forty thousand successful appends a second" from
 * "thirty-nine thousand conflicts and a thousand appends", and the second is a very different store.
 * Without these counters the contention benchmarks would report their best numbers precisely where the
 * store was doing the least useful work.
 *
 * <p>Letting the exception escape instead is not an option: JMH would count it as a benchmark error
 * and abort the run, when it is in fact the outcome under study.
 *
 * <p>{@link AuxCounters.Type#EVENTS} means JMH reports these as per-second rates next to the primary
 * score, and resets them each iteration.
 */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
public class Outcomes {

	/** Invocations that wrote or read what they set out to. */
	public long ok;

	/** Appends refused because another writer moved the boundary first. */
	public long conflicts;

	/** Appends the store accepted but silently swallowed as an idempotency duplicate. */
	public long deduplicated;

	@Setup(Level.Iteration)
	public void reset ( ) {
		ok = 0;
		conflicts = 0;
		deduplicated = 0;
	}

	/**
	 * Classifies what a workload returned.
	 *
	 * <p>The encoding is the workloads' own: a negative count means the append lost its optimistic
	 * locking check, and zero from an append means the store swallowed a duplicate. Both are real
	 * outcomes of a real call rather than failures.
	 */
	public void record ( Object result, boolean mutating ) {
		if ( result instanceof Number number ) {
			long value = number.longValue();
			if ( value < 0 ) {
				conflicts++;
				return;
			}
			if ( value == 0 && mutating ) {
				deduplicated++;
				return;
			}
		}
		ok++;
	}
}
