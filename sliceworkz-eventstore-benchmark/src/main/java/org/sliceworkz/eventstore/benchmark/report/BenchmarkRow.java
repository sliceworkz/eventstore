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
 * @param workload the operation, e.g. {@code append-type-and-tag}
 * @param threads how many threads were running
 * @param mode JMH's mode, {@code thrpt} or {@code sample}
 * @param score the primary metric
 * @param error the half-width of its 99.9% confidence interval, or NaN when a single fork gave none
 * @param unit the score's unit
 * @param secondary the aux counters -- successes, conflicts, deduplicated
 */
public record BenchmarkRow (
		String workload,
		int threads,
		String mode,
		double score,
		double error,
		String unit,
		Map<String, Double> secondary ) {

	public BenchmarkRow {
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
		return "%s|%s|%dt".formatted(workload, mode, threads);
	}

	/** Sorts rows the way a reader wants them: by workload, then by thread count. */
	public static List<BenchmarkRow> sorted ( List<BenchmarkRow> rows ) {
		return rows.stream()
				.sorted(java.util.Comparator.comparing(BenchmarkRow::workload)
						.thenComparing(BenchmarkRow::threads)
						.thenComparing(BenchmarkRow::mode))
				.toList();
	}
}
