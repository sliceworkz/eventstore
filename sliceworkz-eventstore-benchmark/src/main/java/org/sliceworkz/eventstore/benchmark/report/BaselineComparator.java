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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Diffs a run against a committed baseline, and refuses when the two are not comparable.
 *
 * <p>The refusal is the feature. A percentage difference between two runs is only a statement about
 * the store if everything else was the same, and nothing about two numbers says whether it was. A
 * comparator that always answers will eventually report a faster laptop as a performance improvement
 * and a busier CI machine as a regression -- and both readings are worse than no answer, because they
 * come with a number attached.
 *
 * <p>Where the two runs <em>are</em> comparable, a difference is only called out when it exceeds the
 * measurements' own error bars. JMH reports a confidence interval per score; a change smaller than the
 * sum of two intervals is not distinguishable from noise, and treating it as a result is how a
 * benchmark suite trains people to ignore it.
 */
public final class BaselineComparator {

	/**
	 * A change worth mentioning even when the error bars overlap, because a difference this large is
	 * almost certainly real however wide the intervals.
	 */
	private static final double ALWAYS_INTERESTING = 0.25d;

	private BaselineComparator ( ) { }

	/** How one measurement moved. */
	public record Change ( String key, double baselineScore, double currentScore, String unit,
			boolean significant, String note ) {

		/** Positive means the current run scored higher. For throughput, higher is better. */
		public double relativeChange ( ) {
			return baselineScore == 0 ? Double.NaN : ( currentScore - baselineScore ) / baselineScore;
		}

		public String toLine ( ) {
			return "%-40s %10.3f -> %10.3f %-10s %+7.1f%%  %s".formatted(
					key, baselineScore, currentScore, unit, relativeChange() * 100,
					significant ? note : "within noise");
		}
	}

	/** The outcome of a comparison, which may be a refusal. */
	public sealed interface Result {

		/** The two runs are not comparable, and here is why. */
		record Refused ( List<String> differences ) implements Result {

			public String explain ( ) {
				StringBuilder out = new StringBuilder(
						"these two runs are not comparable, so no difference is reported:\n");
				differences.forEach(difference -> out.append("  - ").append(difference).append('\n'));
				out.append("\nA percentage between runs measured in different circumstances is not a "
						+ "statement about the store. Re-run the baseline's profile in this environment, "
						+ "or compare against a baseline taken here.");
				return out.toString();
			}
		}

		/** The two runs are comparable; these are the measurements that moved. */
		record Compared ( List<Change> changes, int measurementsInBoth, int onlyInBaseline, int onlyInCurrent )
				implements Result {

			public List<Change> significant ( ) {
				return changes.stream().filter(Change::significant).toList();
			}
		}
	}

	/** Compares a run against a baseline. */
	public static Result compare ( RunReport baseline, RunReport current ) {
		if ( !current.manifest().comparableTo(baseline.manifest()) ) {
			return new Result.Refused(current.manifest().differencesFrom(baseline.manifest()));
		}

		List<Change> changes = new ArrayList<>();
		int inBoth = 0;
		int onlyInBaseline = 0;

		for ( BenchmarkRow baselineRow : baseline.benchmarks() ) {
			Optional<BenchmarkRow> currentRow = current.row(baselineRow.key());
			if ( currentRow.isEmpty() ) {
				onlyInBaseline++;
				continue;
			}
			inBoth++;
			changes.add(changeBetween(baselineRow, currentRow.get()));
		}

		long onlyInCurrent = current.benchmarks().stream()
				.filter(row -> baseline.row(row.key()).isEmpty())
				.count();

		changes.sort(( a, b ) -> Double.compare(Math.abs(b.relativeChange()), Math.abs(a.relativeChange())));
		return new Result.Compared(List.copyOf(changes), inBoth, onlyInBaseline, (int) onlyInCurrent);
	}

	/**
	 * Whether a difference is bigger than the two measurements' own uncertainty.
	 *
	 * <p>Comparing the gap against the sum of both error bars is the conservative reading, and the
	 * right one here: this suite's job is to characterise behaviour, so a false "no change" costs a
	 * follow-up run while a false "regression" costs somebody an afternoon.
	 */
	private static Change changeBetween ( BenchmarkRow baseline, BenchmarkRow current ) {
		double gap = Math.abs(current.score() - baseline.score());
		double relative = baseline.score() == 0 ? 0 : gap / baseline.score();

		// A single-fork run produces no confidence interval at all, and treating a missing one as zero
		// would make every difference "outside both error bars" -- including a 0.9% wobble, which is
		// how a comparator teaches people that its findings mean nothing. With no intervals there is
		// nothing to be outside of, so only a difference large enough to survive any plausible noise
		// gets called.
		boolean haveErrorBars = !Double.isNaN(baseline.error()) || !Double.isNaN(current.error());

		if ( !haveErrorBars ) {
			boolean large = relative > ALWAYS_INTERESTING;
			return new Change(baseline.key(), baseline.score(), current.score(), baseline.unit(), large,
					large
							? "large, but this run has no error bars: re-run with more forks to confirm"
							: "no error bars, and too small to call");
		}

		double combinedError = errorOf(baseline) + errorOf(current);
		boolean significant = gap > combinedError || relative > ALWAYS_INTERESTING;
		String note = significant
				? ( gap > combinedError
						? "outside both error bars"
						: "large enough to matter despite wide error bars" )
				: "within noise";

		return new Change(baseline.key(), baseline.score(), current.score(), baseline.unit(), significant, note);
	}

	/** A missing error bar counts as zero once the other run has one, which is the stricter reading. */
	private static double errorOf ( BenchmarkRow row ) {
		return Double.isNaN(row.error()) ? 0 : row.error();
	}
}
