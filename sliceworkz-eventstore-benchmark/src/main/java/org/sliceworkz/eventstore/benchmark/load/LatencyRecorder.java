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

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.HdrHistogram.Histogram;

/**
 * Records how long operations took, at a resolution that survives percentiles.
 *
 * <p>A mean tells you almost nothing about a store: the interesting behaviour -- lock waits, a
 * checkpoint, an autovacuum pass, a connection pool running dry -- lives in the top percentile or
 * two, and an average buries all of it. HdrHistogram keeps the whole distribution in bounded memory
 * at constant cost, so p99.9 is as cheap to ask for as the mean.
 *
 * <p><b>One histogram per thread, merged at the end.</b> A shared histogram would put a contended
 * write on the measurement path, which is precisely the sort of thing that makes a load test measure
 * itself.
 *
 * <h2>Coordinated omission</h2>
 *
 * <p>This is the trap that makes most home-grown load tests optimistic, often by an order of
 * magnitude at the high percentiles. Under a fixed offered rate, a load generator that times only
 * {@code finish - start} misses the queueing it caused: when the store stalls for a second, the
 * generator stalls with it, the requests that <em>should</em> have been issued during that second are
 * never issued, and the one slow sample gets recorded while the hundred late ones are silently
 * skipped. The result flatters exactly the case it was built to expose.
 *
 * <p>So under a fixed rate this records against the time an operation was <em>due</em>, not when it
 * actually started. A request scheduled for t+100ms that begins at t+900ms because the store was busy
 * is recorded as having taken 800ms plus its own service time, which is what the caller waiting on it
 * experienced.
 *
 * <p>Under saturation there is no schedule to be late against -- every thread issues the next request
 * the instant the last one returns -- so service time is the honest measure and is what gets
 * recorded.
 */
public final class LatencyRecorder {

	/** Nanoseconds. The upper bound has to cover a pathological stall, not a typical operation. */
	private static final long MAX_TRACKABLE_NANOS = TimeUnit.MINUTES.toNanos(5);

	/** Three significant digits: enough to separate 1.00ms from 1.01ms, and cheap. */
	private static final int SIGNIFICANT_DIGITS = 3;

	private final Histogram histogram = new Histogram(MAX_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);
	private final String name;

	public LatencyRecorder ( String name ) {
		this.name = name;
	}

	public String name ( ) {
		return name;
	}

	/**
	 * Records one observation.
	 *
	 * @param nanos how long it took, measured from whichever start the mode calls for
	 */
	public void record ( long nanos ) {
		// clamping rather than throwing: a stall past the ceiling is a real observation and losing the
		// whole run over it would be worse than recording it as "at least this bad"
		histogram.recordValue(Math.min(Math.max(nanos, 0), MAX_TRACKABLE_NANOS));
	}

	/** Folds another thread's histogram into this one. */
	public void mergeFrom ( LatencyRecorder other ) {
		histogram.add(other.histogram);
	}

	public long count ( ) {
		return histogram.getTotalCount();
	}

	/** A summary in milliseconds, which is the unit these numbers get read in. */
	public Summary summarise ( ) {
		return new Summary(
				name,
				histogram.getTotalCount(),
				toMillis(histogram.getMinValue()),
				toMillis((long) histogram.getMean()),
				toMillis(histogram.getValueAtPercentile(50)),
				toMillis(histogram.getValueAtPercentile(90)),
				toMillis(histogram.getValueAtPercentile(99)),
				toMillis(histogram.getValueAtPercentile(99.9)),
				toMillis(histogram.getMaxValue()));
	}

	private static double toMillis ( long nanos ) {
		return nanos / 1_000_000.0d;
	}

	/**
	 * The distribution, in milliseconds.
	 *
	 * <p>p99.9 is included because at any real throughput it is not a rare event: a store handling a
	 * thousand operations a second crosses it once a second, and a user meets it several times an hour.
	 */
	public record Summary (
			String name, long count,
			double minMs, double meanMs,
			double p50Ms, double p90Ms, double p99Ms, double p999Ms, double maxMs ) {

		/** A single line for a terminal, aligned so several read as a table. */
		public String toLine ( ) {
			return "%-22s n=%-9d min=%-8.3f p50=%-8.3f p90=%-8.3f p99=%-8.3f p99.9=%-8.3f max=%-8.3f (ms)"
					.formatted(name, count, minMs, p50Ms, p90Ms, p99Ms, p999Ms, maxMs);
		}
	}

	/** Merges a set of per-thread recorders into one. */
	public static LatencyRecorder merge ( String name, List<LatencyRecorder> recorders ) {
		LatencyRecorder merged = new LatencyRecorder(name);
		recorders.forEach(merged::mergeFrom);
		return merged;
	}
}
