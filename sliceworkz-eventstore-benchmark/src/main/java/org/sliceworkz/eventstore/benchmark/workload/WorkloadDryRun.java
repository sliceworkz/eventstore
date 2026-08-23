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
package org.sliceworkz.eventstore.benchmark.workload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;

/**
 * Invokes every workload once and checks it actually did something.
 *
 * <p>This is the cheapest defence the suite has against its worst failure mode. A query that matches
 * nothing is <em>fast</em>, so a workload aimed at a tag that is not in the corpus, or at an entity
 * that does not exist, reports an excellent number and no error whatsoever. Nothing downstream can
 * tell that apart from a genuinely quick query -- not JMH, not the report, not a person reading it.
 *
 * <p>So before a run of any length, each workload is invoked once and its result inspected: a
 * collection must be non-empty, a count must be positive. Two seconds here against hours of
 * measurement that would otherwise be describing nothing.
 *
 * <p>Appends are exempt from the emptiness rule in one specific case -- a swallowed idempotency
 * duplicate legitimately returns nothing, and a conflict legitimately returns -1. Those are the
 * outcomes being measured rather than signs of a broken fixture.
 */
public final class WorkloadDryRun {

	/** What one workload's trial invocation produced. */
	public record Result ( String workload, boolean ok, String detail, Duration took ) { }

	private WorkloadDryRun ( ) { }

	/**
	 * Runs each workload once against the target.
	 *
	 * <p>Note this <em>writes</em> for the mutating workloads, so it should run against a corpus that
	 * is about to be measured (and restored) rather than one being preserved.
	 */
	public static List<Result> run ( BenchmarkTarget target, CorpusSpec spec, CorpusFacts facts,
			List<Workload> workloads ) {
		List<Result> results = new ArrayList<>(workloads.size());

		for ( Workload workload : workloads ) {
			WorkloadContext context = new WorkloadContext(target, spec, facts,
					WorkloadContext.Collision.SPREAD, 0, 1, spec.seed());
			long started = System.nanoTime();
			try {
				workload.prepare(context);
				Object produced = workload.invoke(context);
				Duration took = Duration.ofNanos(System.nanoTime() - started);
				results.add(judge(workload, produced, took));
			} catch ( RuntimeException e ) {
				results.add(new Result(workload.name(), false,
						"threw %s: %s".formatted(e.getClass().getSimpleName(), e.getMessage()),
						Duration.ofNanos(System.nanoTime() - started)));
			}
		}
		return results;
	}

	private static Result judge ( Workload workload, Object produced, Duration took ) {
		if ( produced == null ) {
			return new Result(workload.name(), false, "returned null, so nothing was consumed", took);
		}

		boolean mutating = workload.requirement().mutatesStore();

		if ( produced instanceof Collection<?> collection ) {
			if ( collection.isEmpty() && !mutating ) {
				return new Result(workload.name(), false,
						"returned no events -- a query matching nothing is fast, so this would have "
								+ "reported a flattering number rather than an error",
						took);
			}
			return new Result(workload.name(), true, "%d events".formatted(collection.size()), took);
		}

		if ( produced instanceof Number number ) {
			long value = number.longValue();
			if ( value <= 0 && !mutating ) {
				return new Result(workload.name(), false,
						"produced %d, so it processed nothing".formatted(value), took);
			}
			return new Result(workload.name(), true, describeNumber(workload, value), took);
		}

		return new Result(workload.name(), true, produced.getClass().getSimpleName(), took);
	}

	private static String describeNumber ( Workload workload, long value ) {
		if ( value < 0 ) {
			return "optimistic locking conflict (expected under contention)";
		}
		if ( value == 0 && workload.name().contains("idempotent-duplicate") ) {
			return "0 events written -- the duplicate was swallowed, as intended";
		}
		return String.valueOf(value);
	}

	/** Whether every workload passed. */
	public static boolean allPassed ( List<Result> results ) {
		return results.stream().allMatch(Result::ok);
	}
}
