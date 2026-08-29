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

import java.time.Duration;
import java.util.List;

/**
 * What a load run produced.
 *
 * <p>Throughput is deliberately not the headline. Under contention a store can raise its operations
 * per second while doing less useful work -- most of the extra operations being conflicts that write
 * nothing -- so a figure without {@link #conflicts()} beside it can move in the wrong direction and
 * read as an improvement.
 *
 * @param scenario what was run
 * @param duration how long the measured window lasted, ramp-up excluded
 * @param operations operations attempted during the measured window
 * @param successes operations that did the work they set out to
 * @param conflicts appends refused because another writer moved the boundary first
 * @param deduplicated appends the store accepted and silently swallowed as duplicates
 * @param failures operations that threw something other than an optimistic locking conflict
 * @param latencies one distribution per thing measured -- service time, and for the live scenarios
 *        the delivery and end-to-end delays as well
 * @param correctness what the run checked about its own results
 * @param storeGrewBy how many events the store gained, which is the point of running against a
 *        growing store rather than a restored one
 */
public record LoadResult (
		LoadScenario scenario,
		Duration duration,
		long operations,
		long successes,
		long conflicts,
		long deduplicated,
		long failures,
		List<LatencyRecorder.Summary> latencies,
		List<LoadCorrectness.Check> correctness,
		long storeGrewBy ) {

	public LoadResult {
		latencies = latencies == null ? List.of() : List.copyOf(latencies);
		correctness = correctness == null ? List.of() : List.copyOf(correctness);
	}

	/** Operations attempted per second, conflicts included. */
	public double operationsPerSecond ( ) {
		double seconds = duration.toNanos() / 1_000_000_000.0d;
		return seconds <= 0 ? 0 : operations / seconds;
	}

	/**
	 * Operations that accomplished something, per second.
	 *
	 * <p>The figure worth quoting under contention. A run whose {@link #operationsPerSecond()} is high
	 * and whose useful rate is low is a store spending its capacity losing races.
	 */
	public double usefulOperationsPerSecond ( ) {
		double seconds = duration.toNanos() / 1_000_000_000.0d;
		return seconds <= 0 ? 0 : successes / seconds;
	}

	/** What fraction of attempts lost their optimistic locking check. */
	public double conflictRate ( ) {
		return operations == 0 ? 0 : conflicts / (double) operations;
	}

	/** Whether every correctness check passed. */
	public boolean isSound ( ) {
		return correctness.stream().allMatch(LoadCorrectness.Check::passed) && failures == 0;
	}
}
