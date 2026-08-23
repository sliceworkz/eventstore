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
package org.sliceworkz.eventstore.benchmark.config;

import java.util.List;

import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.env.TargetSpec;

/**
 * One named benchmark configuration: which corpora to build, which stores to open over them, and
 * what to measure.
 *
 * <p>A profile is a file rather than a class so that asking a new question -- "a small store in a
 * database that also holds three big ones" -- is an edit rather than a code change and a rebuild.
 * That was the point of the configuration model: the dimension matrix is large enough that the
 * interesting runs are combinations nobody anticipated, and a suite where each of those needs a Java
 * class is a suite where they do not get run.
 *
 * <p>A profile names <em>several</em> targets over <em>one</em> corpus deliberately. Building the
 * corpus is the expensive half and opening a store over it is free, so "the same ten million events,
 * measured with metrics off and then with an unlimited purpose cap" should cost one provisioning.
 *
 * @param name the profile's identifier, matching its file name
 * @param description what question this profile answers, shown by {@code list} and copied into reports
 * @param corpus what the store under test must contain
 * @param targets the stores to open over that corpus; each is measured separately
 * @param jmh how to run the operation-level benchmarks, or {@code null} if this profile has none
 * @param load how to run the sustained-load scenarios, or {@code null} if this profile has none
 */
public record BenchmarkProfile (
		String name,
		String description,
		CorpusSpec corpus,
		List<TargetSpec> targets,
		JmhSettings jmh,
		LoadSettings load ) {

	/**
	 * How the JMH half of a profile runs.
	 *
	 * @param workloads the workload names to measure; empty means every workload the corpus supports
	 * @param threads the writer/reader counts to sweep, which is the concurrency dimension
	 * @param forks how many JVMs each benchmark is measured in
	 * @param warmupIterations warmup iterations per fork
	 * @param measurementIterations measured iterations per fork
	 * @param iterationSeconds how long one iteration runs
	 */
	public record JmhSettings (
			List<String> workloads,
			List<Integer> threads,
			int forks,
			int warmupIterations,
			int measurementIterations,
			int iterationSeconds ) {

		public JmhSettings {
			workloads = workloads == null ? List.of() : List.copyOf(workloads);
			threads = threads == null || threads.isEmpty() ? List.of(1) : List.copyOf(threads);
			if ( threads.stream().anyMatch(t -> t == null || t <= 0) ) {
				throw new IllegalArgumentException("thread counts must all be positive");
			}
			forks = forks <= 0 ? 3 : forks;
			warmupIterations = warmupIterations <= 0 ? 5 : warmupIterations;
			measurementIterations = measurementIterations <= 0 ? 10 : measurementIterations;
			iterationSeconds = iterationSeconds <= 0 ? 5 : iterationSeconds;
		}

		/**
		 * Roughly how long this will take, ignoring JVM startup and corpus restore.
		 *
		 * <p>Approximate on purpose -- its job is to let the runner say "this is a four-hour run" before
		 * starting one, not to be accurate. A profile whose estimate surprises the person launching it
		 * has already earned its keep.
		 */
		public java.time.Duration estimatedDurationPerBenchmark ( ) {
			long seconds = (long) forks * ( warmupIterations + measurementIterations ) * iterationSeconds;
			return java.time.Duration.ofSeconds(seconds);
		}
	}

	/**
	 * How the load half of a profile runs.
	 *
	 * @param scenario which load scenario, e.g. {@code write-saturation} or {@code end-to-end-latency}
	 * @param writers concurrent writer threads
	 * @param readers concurrent reader threads
	 * @param collision where the writers collide: {@code spread}, {@code one-stream} or {@code one-boundary}
	 * @param targetRatePerSecond offered load; {@code null} or non-positive means saturate instead
	 * @param durationSeconds how long to hold the load after ramp-up
	 * @param rampUpSeconds how long to spend reaching full load
	 */
	public record LoadSettings (
			String scenario,
			int writers,
			int readers,
			String collision,
			Integer targetRatePerSecond,
			int durationSeconds,
			int rampUpSeconds ) {

		public LoadSettings {
			if ( scenario == null || scenario.isBlank() ) {
				throw new IllegalArgumentException("a load profile needs a scenario");
			}
			writers = Math.max(writers, 0);
			readers = Math.max(readers, 0);
			if ( writers == 0 && readers == 0 ) {
				throw new IllegalArgumentException("a load profile needs at least one writer or reader");
			}
			collision = collision == null || collision.isBlank() ? "spread" : collision;
			durationSeconds = durationSeconds <= 0 ? 60 : durationSeconds;
			rampUpSeconds = Math.max(rampUpSeconds, 0);
		}

		/** Whether latency must be recorded against intended start times rather than service time. */
		public boolean isFixedRate ( ) {
			return targetRatePerSecond != null && targetRatePerSecond > 0;
		}

		public java.time.Duration estimatedDuration ( ) {
			return java.time.Duration.ofSeconds(durationSeconds + rampUpSeconds);
		}
	}

	public BenchmarkProfile {
		if ( name == null || name.isBlank() ) {
			throw new IllegalArgumentException("a profile needs a name");
		}
		if ( corpus == null ) {
			throw new IllegalArgumentException("profile '%s' has no corpus".formatted(name));
		}
		targets = targets == null || targets.isEmpty() ? List.of(TargetSpec.inmem()) : List.copyOf(targets);
		description = description == null ? "" : description;

		if ( jmh == null && load == null ) {
			throw new IllegalArgumentException(
					"profile '%s' measures nothing: it needs a 'jmh' section, a 'load' section, or both"
							.formatted(name));
		}
		// A shredded corpus can only be read through a store that has a codec, and the failure lands at
		// getEventStream rather than on the append -- far from the cause.  Catch it while it is still a
		// configuration mistake.
		if ( corpus.requiresShredding() ) {
			List<TargetSpec> unshredded = targets.stream().filter(t -> !t.shredding()).toList();
			if ( !unshredded.isEmpty() ) {
				throw new IllegalArgumentException(
						"profile '%s' has a SHREDDED corpus but %d of its targets have shredding off; those stores would reject every crm event at stream creation"
								.formatted(name, unshredded.size()));
			}
		}
	}

	/** Whether running this profile needs a Docker daemon. */
	public boolean requiresDocker ( ) {
		return targets.stream().anyMatch(TargetSpec::requiresDocker);
	}

	/** A rough total runtime, for the estimate printed before a run starts. */
	public java.time.Duration estimatedDuration ( ) {
		java.time.Duration total = java.time.Duration.ZERO;
		if ( jmh != null ) {
			// one benchmark per workload per thread count, per target
			long benchmarks = (long) Math.max(jmh.workloads().size(), 1) * jmh.threads().size() * targets.size();
			total = total.plus(jmh.estimatedDurationPerBenchmark().multipliedBy(benchmarks));
		}
		if ( load != null ) {
			total = total.plus(load.estimatedDuration().multipliedBy(targets.size()));
		}
		return total;
	}
}
