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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.load.LoadResult;

/**
 * Renders a run as Markdown a person can read.
 *
 * <p>The raw table of scores is the least useful part and comes last. What goes first is the
 * <b>derived comparisons</b> -- what the DCB check costs over an unconditional append, how that grows
 * with the number of OR-ed facts, what happens as threads are added -- because those are the questions
 * the suite exists to answer and a reader should not have to do the division themselves.
 *
 * <p>A derived section that cannot be computed from this run's rows is <b>omitted</b>, never estimated.
 * A profile measuring only reads has nothing to say about append cost, and saying so with a blank or a
 * zero would be worse than saying nothing.
 */
final class MarkdownRenderer {

	private final RunReport report;

	MarkdownRenderer ( RunReport report ) {
		this.report = report;
	}

	String render ( ) {
		StringBuilder out = new StringBuilder();
		header(out);
		environment(out);
		corpus(out);
		derived(out);
		load(out);
		plans(out);
		allResults(out);
		return out.toString();
	}

	private void header ( StringBuilder out ) {
		RunManifest manifest = report.manifest();
		out.append("# Benchmark run: %s\n\n".formatted(manifest.profileName()));
		if ( manifest.profileDescription() != null && !manifest.profileDescription().isBlank() ) {
			out.append(manifest.profileDescription().strip()).append("\n\n");
		}
		out.append("| | |\n|---|---|\n");
		out.append("| suite version | %s |\n".formatted(manifest.suiteVersion()));
		out.append("| started | %s |\n".formatted(manifest.startedAt()));
		out.append("| finished | %s |\n".formatted(manifest.finishedAt()));
		out.append("| targets | %s |\n".formatted(String.join(", ", manifest.targets())));
		out.append("| corpus restore | %s |\n".formatted(manifest.restorePolicy()));
		if ( manifest.driftFraction() > 0 ) {
			out.append("| store drift | %.2f%% during the run |\n".formatted(manifest.driftFraction() * 100));
		}
		out.append('\n');

		List<String> notPublishable = manifest.reasonsNotPublishable();
		if ( !notPublishable.isEmpty() ) {
			out.append("> **Not suitable as a published baseline.**\n>\n");
			notPublishable.forEach(reason -> out.append("> - %s\n".formatted(reason)));
			out.append('\n');
		}
	}

	private void environment ( StringBuilder out ) {
		out.append("## Environment\n\n");
		out.append("These are the settings the numbers below depend on. Two runs whose environments "
				+ "differ are not comparable, and the comparator refuses rather than reporting a "
				+ "difference in hardware as a change in the store.\n\n");

		appendKeyValues(out, "JVM", report.manifest().environment().jvm());
		appendKeyValues(out, "Host", report.manifest().environment().host());
		if ( report.manifest().environment().postgres().isEmpty() ) {
			out.append("No PostgreSQL settings recorded: this run measured an in-memory store.\n\n");
		} else {
			appendKeyValues(out, "PostgreSQL", report.manifest().environment().postgres());
		}
	}

	private void appendKeyValues ( StringBuilder out, String title, Map<String, String> values ) {
		if ( values.isEmpty() ) {
			return;
		}
		out.append("### %s\n\n| setting | value |\n|---|---|\n".formatted(title));
		new TreeMap<>(values).forEach(( key, value ) -> out.append("| %s | %s |\n".formatted(key, value)));
		out.append('\n');
	}

	private void corpus ( StringBuilder out ) {
		CorpusFacts facts = report.manifest().facts();
		out.append("## Corpus\n\n");
		out.append("| | |\n|---|---|\n");
		out.append("| fingerprint | `%s` |\n".formatted(report.manifest().corpusFingerprint()));
		out.append("| volume | %,d events under test |\n".formatted(report.manifest().corpus().volume()));
		out.append("| stream design | %s |\n".formatted(report.manifest().corpus().streamDesign()));
		out.append("| composition | %s |\n".formatted(report.manifest().corpus().composition()));
		out.append("| payload | %s |\n".formatted(report.manifest().corpus().payload()));
		out.append("| entities | %,d |\n".formatted(report.manifest().corpus().entityCount()));

		if ( facts != null ) {
			out.append("| hot entity | `%s`, %,d events |\n".formatted(
					facts.hotEntity(), facts.count(CorpusFacts.COUNT_HOT_ENTITY)));
			out.append("| cold entity | `%s`, %,d events |\n".formatted(
					facts.coldEntity(), facts.count(CorpusFacts.COUNT_COLD_ENTITY)));
			out.append("| needle tag | %,d matches |\n".formatted(facts.count(CorpusFacts.COUNT_NEEDLE)));
			out.append("| swathe tag | %,d matches |\n".formatted(facts.count(CorpusFacts.COUNT_SWATHE)));
			if ( facts.meanPayloadBytes() != null ) {
				out.append("| mean payload | %.0f bytes (sales) |\n".formatted(facts.meanPayloadBytes()));
			}
		}
		out.append('\n');
	}

	/* ------------------------------------------------------- the questions the suite exists to answer */

	private void derived ( StringBuilder out ) {
		StringBuilder body = new StringBuilder();
		dcbCost(body);
		orGroupScaling(body);
		threadScaling(body);
		batchCost(body);

		if ( body.isEmpty() ) {
			return;
		}
		out.append("## What this run says\n\n").append(body);
	}

	/** What a consistency check costs over an append that does not make one. */
	private void dcbCost ( StringBuilder out ) {
		Optional<BenchmarkRow> baseline = throughputRow("append-none", 1);
		Optional<BenchmarkRow> checked = throughputRow("append-type-and-tag", 1);
		if ( baseline.isEmpty() || checked.isEmpty() ) {
			return;
		}

		double ratio = baseline.get().score() / checked.get().score();
		out.append("### What the DCB check costs\n\n");
		out.append("| append | throughput | relative |\n|---|---|---|\n");
		out.append("| no criteria | %s %s | 1.00x |\n".formatted(
				baseline.get().scoreWithError(), baseline.get().unit()));
		// The wording follows the data rather than the expectation.  A hard-coded "slower" reads as
		// "0.61x slower" when the conditional append happens to come out faster, which is both wrong and
		// exactly the sort of thing that makes a reader stop trusting the rest of the document.
		out.append("| one type set and one tag | %s %s | %s |\n\n".formatted(
				checked.get().scoreWithError(), checked.get().unit(),
				ratio >= 1
						? "%.2fx slower".formatted(ratio)
						: "%.2fx faster".formatted(1 / ratio)));
		out.append("On PostgreSQL the unconditional append is also the only one that takes no advisory "
				+ "lock, so this gap is the whole DCB mechanism rather than just the extra predicate.\n\n");

		if ( ratio < 1.05 ) {
			out.append("> The check appears to cost nothing here. Against the in-memory backend that is "
					+ "expected -- both appends take the same monitor and there is no lock to contend for. "
					+ "Against PostgreSQL it would mean the measurement is wrong, most likely too few "
					+ "iterations to separate the two.\n\n");
		}
	}

	/** How the check grows with the number of facts a decision rests on. */
	private void orGroupScaling ( StringBuilder out ) {
		List<BenchmarkRow> rows = List.of(2, 5, 10).stream()
				.map(groups -> throughputRow("append-or-groups-" + groups, 1))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.toList();
		if ( rows.size() < 2 ) {
			return;
		}

		Optional<BenchmarkRow> single = throughputRow("append-type-and-tag", 1);
		out.append("### How a multi-fact decision scales\n\n");
		out.append("| OR-ed filter items | throughput | relative to one |\n|---|---|---|\n");
		single.ifPresent(row -> out.append("| 1 | %s %s | 1.00x |\n".formatted(row.scoreWithError(), row.unit())));
		for ( BenchmarkRow row : rows ) {
			String relative = single
					.map(one -> "%.2fx".formatted(one.score() / row.score()))
					.orElse("--");
			out.append("| %s | %s %s | %s |\n".formatted(
					row.workload().replace("append-or-groups-", ""), row.scoreWithError(), row.unit(), relative));
		}
		out.append("\nThe generated SQL gains a disjunct per item, so this is whether a decision resting "
				+ "on ten facts costs ten times one or barely more than it.\n\n");
	}

	/**
	 * What happens as writers are added -- with the conflict rate beside it, because throughput alone
	 * can rise while the useful work falls.
	 */
	private void threadScaling ( StringBuilder out ) {
		Map<String, List<BenchmarkRow>> byWorkload = new TreeMap<>();
		for ( BenchmarkRow row : report.benchmarks() ) {
			if ( "thrpt".equals(row.mode()) ) {
				byWorkload.computeIfAbsent(row.workload(), key -> new java.util.ArrayList<>()).add(row);
			}
		}
		byWorkload.values().removeIf(rows -> rows.size() < 2);
		if ( byWorkload.isEmpty() ) {
			return;
		}

		out.append("### What happens as threads are added\n\n");
		out.append("| workload | threads | throughput | useful/s | conflicts |\n|---|---|---|---|---|\n");
		byWorkload.forEach(( workload, rows ) -> rows.stream()
				.sorted(Comparator.comparingInt(BenchmarkRow::threads))
				.forEach(row -> out.append("| %s | %d | %s %s | %.0f | %.1f%% |\n".formatted(
						workload, row.threads(), row.scoreWithError(), row.unit(),
						row.successes(), row.conflictRate() * 100))));
		out.append("\nA rising throughput with a rising conflict rate is a store spending more of its "
				+ "capacity losing races, not doing more work. The useful column is the one to read.\n\n");
	}

	/** Per-call overhead against per-event cost. */
	private void batchCost ( StringBuilder out ) {
		Optional<BenchmarkRow> one = throughputRow("append-none", 1);
		Optional<BenchmarkRow> ten = throughputRow("append-batch-10", 1);
		Optional<BenchmarkRow> hundred = throughputRow("append-batch-100", 1);
		if ( one.isEmpty() || ( ten.isEmpty() && hundred.isEmpty() ) ) {
			return;
		}

		out.append("### What a round trip costs\n\n");
		out.append("| events per call | calls/s | events/s |\n|---|---|---|\n");
		out.append("| 1 | %.3f | %.0f |\n".formatted(one.get().score(), one.get().score()));
		ten.ifPresent(row -> out.append("| 10 | %.3f | %.0f |\n".formatted(row.score(), row.score() * 10)));
		hundred.ifPresent(row -> out.append("| 100 | %.3f | %.0f |\n".formatted(row.score(), row.score() * 100)));
		out.append("\nThe events-per-second column laid against the single-event row is the per-call "
				+ "overhead made visible.\n\n");
	}

	/* ------------------------------------------------------------------------------ the load results */

	private void load ( StringBuilder out ) {
		if ( report.load().isEmpty() ) {
			return;
		}
		out.append("## Sustained load\n\n");
		out.append("Measured against a store that grew throughout, unlike the operation benchmarks above "
				+ "which restore the corpus between iterations.\n\n");

		for ( LoadResult result : report.load() ) {
			out.append("### %s\n\n".formatted(result.scenario().profileName()));
			out.append("| | |\n|---|---|\n");
			out.append("| duration | %.1fs |\n".formatted(result.duration().toMillis() / 1000.0));
			out.append("| attempted | %,d (%.0f/s) |\n".formatted(result.operations(), result.operationsPerSecond()));
			out.append("| useful | %,d (%.0f/s) |\n".formatted(result.successes(), result.usefulOperationsPerSecond()));
			if ( result.conflicts() > 0 ) {
				out.append("| conflicts | %,d (%.1f%%) |\n".formatted(result.conflicts(), result.conflictRate() * 100));
			}
			if ( result.storeGrewBy() >= 0 ) {
				out.append("| store grew by | %,d events |\n".formatted(result.storeGrewBy()));
			}
			out.append('\n');

			out.append("| measurement | n | p50 | p90 | p99 | p99.9 | max |\n|---|---|---|---|---|---|---|\n");
			result.latencies().forEach(summary -> out.append(
					"| %s | %,d | %.3f | %.3f | %.3f | %.3f | %.3f |\n".formatted(
							summary.name(), summary.count(), summary.p50Ms(), summary.p90Ms(),
							summary.p99Ms(), summary.p999Ms(), summary.maxMs())));
			out.append("\nAll in milliseconds.\n\n");

			out.append("| check | result |\n|---|---|\n");
			result.correctness().forEach(check -> out.append("| %s | %s -- %s |\n".formatted(
					check.name(), check.passed() ? "pass" : "**FAIL**", check.detail())));
			out.append('\n');
		}
	}

	private void plans ( StringBuilder out ) {
		if ( report.plans().isEmpty() ) {
			return;
		}
		out.append("## Query plans\n\n");
		out.append("Representative statements matching the shapes the store issues, not the statements "
				+ "themselves -- the backend builds its SQL internally and does not expose it. Enough to "
				+ "answer whether the planner used an index or scanned the table, and no substitute for "
				+ "the real thing if the query builder changes.\n\n");

		for ( QueryPlans.Plan plan : report.plans() ) {
			out.append("### %s%s\n\n".formatted(plan.shape(),
					QueryPlans.isSequentialScan(plan) ? " — **sequential scan**" : ""));
			out.append("```\n").append(plan.explain()).append("\n```\n\n");
		}
	}

	private void allResults ( StringBuilder out ) {
		if ( report.benchmarks().isEmpty() ) {
			return;
		}
		out.append("## Every measurement\n\n");
		out.append("| workload | mode | threads | score | unit | error | useful/s | conflicts/s |\n");
		out.append("|---|---|---|---|---|---|---|---|\n");

		for ( BenchmarkRow row : BenchmarkRow.sorted(report.benchmarks()) ) {
			String relativeError = Double.isNaN(row.relativeError())
					? "--"
					: "%.1f%%".formatted(row.relativeError() * 100);
			out.append("| %s | %s | %d | %.3f | %s | %s | %.0f | %.0f |\n".formatted(
					row.workload(), row.mode(), row.threads(), row.score(), row.unit(),
					relativeError, row.successes(), row.conflicts()));
		}
		out.append("\nA relative error above about 10% means the measurement is too noisy to compare "
				+ "against anything; raise the iteration count or quieten the machine.\n");
	}

	private Optional<BenchmarkRow> throughputRow ( String workload, int threads ) {
		return report.benchmarks().stream()
				.filter(row -> row.workload().equals(workload) && row.threads() == threads
						&& "thrpt".equals(row.mode()))
				.findFirst();
	}
}
