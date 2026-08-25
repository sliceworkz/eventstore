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
package org.sliceworkz.eventstore.benchmark.report;

import java.util.List;
import java.util.Map;

/**
 * One measured operation, flattened out of JMH's JSON into the shape a report actually needs.
 *
 * <p>JMH's own format is faithful and deeply nested -- every iteration of every fork, with the raw
 * samples. A report wants one row per (workload, threads) with the score, the error and the aux
 * counters beside it, so this is that projection and nothing more. The original JSON is kept
 * alongside, because it is the record and this is only a view of it.
 *
 * @param target which store this was measured against, e.g. {@code postgres:18/metrics=off}. Not in
 *        JMH's output at all -- it comes from the launcher, which knows which result file belongs to
 *        which target. It has to be here: a profile measuring one corpus through two stores produces
 *        two rows per workload, and without this they are indistinguishable, so every derived table
 *        would silently report whichever came first and a baseline diff would line the wrong pair up
 * @param workload the operation, e.g. {@code append-type-and-tag}
 * @param threads how many threads were running
 * @param mode JMH's mode, {@code thrpt} or {@code sample}
 * @param score the primary metric
 * @param error the half-width of its 99.9% confidence interval, or NaN when a single fork gave none
 * @param unit the score's unit
 * @param secondary the aux counters -- successes, conflicts, deduplicated
 */
public record BenchmarkRow (
		String target,
		String workload,
		int threads,
		String mode,
		double score,
		double error,
		String unit,
		Map<String, Double> secondary ) {

	public BenchmarkRow {
		target = target == null || target.isBlank() ? "(unknown target)" : target;
		secondary = secondary == null ? Map.of() : Map.copyOf(secondary);
	}

	public double conflicts ( ) {
		return secondary.getOrDefault("conflicts", 0.0d);
	}

	public double successes ( ) {
		return secondary.getOrDefault("ok", 0.0d);
	}

	public double deduplicated ( ) {
		return secondary.getOrDefault("deduplicated", 0.0d);
	}

	/**
	 * What fraction of attempts did no work.
	 *
	 * <p>The number that keeps a throughput figure honest under contention: a run can raise its
	 * operations per second while lowering the work done, and only this says so.
	 */
	public double conflictRate ( ) {
		double total = successes() + conflicts();
		return total <= 0 ? 0 : conflicts() / total;
	}

	/**
	 * The score as operations per second, or NaN where the score is not a throughput.
	 *
	 * <p>JMH reports whatever unit the run asked for -- {@code ops/ms} here -- and a report that prints
	 * a per-second figure has to do the conversion rather than assume it. A {@code sample} row's score
	 * is a duration, which has no reading as a rate at all, so it comes back NaN and callers leave the
	 * cell empty instead of publishing a number that means nothing.
	 */
	public double operationsPerSecond ( ) {
		return switch ( unit == null ? "" : unit ) {
			case "ops/ns" -> score * 1_000_000_000d;
			case "ops/us" -> score * 1_000_000d;
			case "ops/ms" -> score * 1_000d;
			case "ops/s" -> score;
			case "ops/min" -> score / 60d;
			default -> Double.NaN;
		};
	}

	/**
	 * Operations per second that did work, conflicts excluded.
	 *
	 * <p>The column that keeps a throughput honest under contention, and it has to be a <em>rate</em>
	 * to do that. It was previously rendered from {@link #successes()}, which is JMH's {@code ok}
	 * counter summed over every iteration of the trial -- a count of operations, printed under a header
	 * saying per second. At one thread and no conflicts the two disagree by whatever the trial length
	 * happened to be: 3,921 events over 40 measured seconds read as "3921/s" beside a score of 0.098
	 * ops/ms, which is 98/s. Forty times out, and in the direction that flatters the store.
	 */
	public double usefulOperationsPerSecond ( ) {
		return operationsPerSecond() * ( 1 - conflictRate() );
	}

	/** The score with its error, or just the score when a single fork produced no interval. */
	public String scoreWithError ( ) {
		if ( Double.isNaN(error) ) {
			return "%.3f".formatted(score);
		}
		return "%.3f ± %.3f".formatted(score, error);
	}

	/** How wide the confidence interval is relative to the score, as a readability signal. */
	public double relativeError ( ) {
		return Double.isNaN(error) || score == 0 ? Double.NaN : Math.abs(error / score);
	}

	/** A key identifying this measurement across runs, for a baseline diff. */
	public String key ( ) {
		return "%s|%s|%s|%dt".formatted(target, workload, mode, threads);
	}

	/** Sorts rows the way a reader wants them: by target, then workload, then thread count. */
	public static List<BenchmarkRow> sorted ( List<BenchmarkRow> rows ) {
		return rows.stream()
				.sorted(java.util.Comparator.comparing(BenchmarkRow::target)
						.thenComparing(BenchmarkRow::workload)
						.thenComparing(BenchmarkRow::threads)
						.thenComparing(BenchmarkRow::mode))
				.toList();
	}
}
