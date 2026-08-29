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

import java.time.Instant;
import java.util.List;

import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusFingerprint;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.env.EnvironmentReport;

/**
 * Everything about a run except its numbers.
 *
 * <p>The numbers alone are worthless and slightly dangerous. "Eight thousand appends a second" is not
 * a fact about this library -- it is a fact about this library, on this hardware, against this
 * PostgreSQL with these settings, over a corpus of this size and shape, measured with this many
 * threads. Strip any of that away and the figure survives as something quotable, comparable and
 * wrong.
 *
 * <p>So a run is only ever published together with its manifest, and the comparator refuses to diff
 * two runs whose manifests disagree on anything that moves results. That refusal is the point: a
 * percentage difference between two runs on different machines is not a regression, but nothing about
 * the two percentages says so.
 *
 * @param suiteVersion the library version the suite was built from
 * @param profileName which question was being asked
 * @param profileJson the whole profile, so a report can be read without the file that produced it
 * @param corpusFingerprint the corpus's content address, which is also its table prefix
 * @param corpus the spec that fingerprint came from
 * @param facts what the corpus actually contained, match counts included
 * @param targets how the stores under measurement were configured
 * @param environment the JVM, the host and the PostgreSQL settings that decide the numbers
 * @param restorePolicy how the corpus was kept steady, and whether it was
 * @param driftFraction how far the store grew during the run, as a fraction of the corpus
 * @param startedAt when the run began
 * @param finishedAt when it ended
 */
public record RunManifest (
		String suiteVersion,
		String profileName,
		String profileDescription,
		String profileJson,
		String corpusFingerprint,
		CorpusSpec corpus,
		CorpusFacts facts,
		List<String> targets,
		EnvironmentReport environment,
		String restorePolicy,
		double driftFraction,
		Instant startedAt,
		Instant finishedAt ) {

	public RunManifest {
		targets = targets == null ? List.of() : List.copyOf(targets);
	}

	/**
	 * A manifest for a run; {@link #finishedAt} is filled in when it ends.
	 *
	 * <p>The caller supplies {@code startedAt} rather than this reading the clock, because the
	 * manifest is assembled <em>after</em> the measurement -- the environment is captured and the
	 * plans reconstructed once the numbers are in, so "now" here is when the report was written, not
	 * when the run began. A fifteen-minute run used to get a twenty-millisecond manifest that way,
	 * which answered "when was this measured, and for how long" with neither.
	 */
	public static RunManifest starting ( Instant startedAt, String profileName, String profileDescription,
			String profileJson, CorpusSpec corpus, CorpusFacts facts, List<String> targets,
			EnvironmentReport environment, String restorePolicy ) {
		return new RunManifest(detectSuiteVersion(), profileName, profileDescription, profileJson,
				CorpusFingerprint.prefixFor(corpus), corpus, facts, targets, environment,
				restorePolicy, 0, startedAt == null ? Instant.now() : startedAt, null);
	}

	public RunManifest finished ( double drift ) {
		return new RunManifest(suiteVersion, profileName, profileDescription, profileJson, corpusFingerprint,
				corpus, facts, targets, environment, restorePolicy, drift, startedAt, Instant.now());
	}

	/**
	 * The library version, read from the jar's manifest.
	 *
	 * <p>Falls back to "unknown" rather than guessing: an unversioned report is honest, whereas one
	 * claiming a version it does not have would make a comparison across releases quietly meaningless.
	 */
	private static String detectSuiteVersion ( ) {
		String version = RunManifest.class.getPackage().getImplementationVersion();
		return version == null ? "unknown" : version;
	}

	/**
	 * Whether two runs were measured in circumstances alike enough to be compared.
	 *
	 * <p>Deliberately strict, and deliberately not a judgement call. The failure this prevents -- two
	 * plausible numbers, differing for reasons nobody notices -- costs far more than being told to
	 * re-run on the same machine.
	 */
	public boolean comparableTo ( RunManifest other ) {
		return other != null
				&& profileName.equals(other.profileName)
				&& corpusFingerprint.equals(other.corpusFingerprint)
				&& targets.equals(other.targets)
				&& environment.comparableTo(other.environment);
	}

	/** Everything that differs from another manifest, for explaining a refusal to compare. */
	public List<String> differencesFrom ( RunManifest other ) {
		List<String> differences = new java.util.ArrayList<>();
		if ( !profileName.equals(other.profileName) ) {
			// The corpus and targets are the visible half of a configuration; the profile carries the
			// rest -- collision mode, thread sweep, iteration counts. The three write-contention
			// profiles share one corpus, one target list and one machine and differ in nothing but the
			// collision mode, so without this line a baseline diff between them reported the fourfold
			// contention gap as a store regression.
			differences.add(("profile: %s vs %s -- a baseline diff is the same question asked twice; for two "
					+ "different profiles use `compare`").formatted(profileName, other.profileName));
		}
		if ( !corpusFingerprint.equals(other.corpusFingerprint) ) {
			differences.add("corpus: %s vs %s".formatted(corpusFingerprint, other.corpusFingerprint));
		}
		if ( !targets.equals(other.targets) ) {
			differences.add("targets: %s vs %s".formatted(targets, other.targets));
		}
		if ( !suiteVersion.equals(other.suiteVersion) ) {
			// not by itself a reason to refuse -- comparing releases is the point -- but worth saying
			differences.add("suite version: %s vs %s".formatted(suiteVersion, other.suiteVersion));
		}
		differences.addAll(environment.differencesFrom(other.environment));
		return differences;
	}

	/**
	 * Whether this run is fit to be committed as a baseline.
	 *
	 * <p>Two conditions, both about honesty rather than quality. A run against a container measures
	 * stock PostgreSQL defaults on whatever the host happened to be, which is fine for comparing two
	 * runs on one machine and not fine as a number other people will quote. And a run whose store
	 * drifted materially is not a measurement of the corpus it names.
	 */
	public List<String> reasonsNotPublishable ( ) {
		List<String> reasons = new java.util.ArrayList<>();
		if ( environment.postgres().isEmpty() && targets.stream().anyMatch(t -> t.startsWith("postgres")) ) {
			reasons.add("the PostgreSQL settings could not be read, so these numbers cannot be attributed");
		}
		if ( targets.stream().anyMatch(t -> t.contains("postgres:") && !t.contains("external")) ) {
			reasons.add("measured against a Testcontainers PostgreSQL running stock defaults; publish from an "
					+ "external server whose configuration is deliberate");
		}
		if ( driftFraction > 0.02 ) {
			reasons.add("the store grew by %.1f%% during the run, so these numbers are not about the corpus they name"
					.formatted(driftFraction * 100));
		}
		if ( "unknown".equals(suiteVersion) ) {
			reasons.add("the suite version is unknown, so this baseline could not be attributed to a release");
		}
		return reasons;
	}
}
