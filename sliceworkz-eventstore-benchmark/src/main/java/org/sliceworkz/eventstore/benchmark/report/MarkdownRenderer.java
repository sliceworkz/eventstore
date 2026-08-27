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
		// The targets side by side comes first, because for a profile whose targets differ in one
		// setting it is the whole question and every per-target table below answers a different one.
		targetComparison(body);
		// Every derived table is computed per target and says which one it is about. Averaging an
		// in-memory store and a PostgreSQL one would produce a number describing neither, and comparing
		// the two is a job for `compare`, not for a table that quietly picked whichever came first.
		for ( String target : distinctTargets() ) {
			dcbCost(body, target);
			orGroupScaling(body, target);
			threadScaling(body, target);
			batchCost(body, target);
		}

		if ( body.isEmpty() ) {
			return;
		}
		out.append("## What this run says\n\n").append(body);
	}

	/** The targets this run measured, in the order their rows appear. */
	private List<String> distinctTargets ( ) {
		return report.benchmarks().stream().map(BenchmarkRow::target).distinct().toList();
	}

	/**
	 * Every workload against every target, with the first target as the reference.
	 *
	 * <p>A profile whose targets differ in one setting -- metrics-cost, dcb-plan-cache -- exists to
	 * ask what that setting costs, and the per-target tables below cannot answer it: each describes
	 * one target, so the reader is left doing the division by hand off the "every measurement" table.
	 * Both profiles' descriptions promise the comparison needs no second report, and until this
	 * section they were promising something the report did not render.
	 *
	 * <p><b>The reference is the first target, and target order is itself a confound.</b> The corpus
	 * is generated inside the first fork of the first target, so on a fresh container that target is
	 * measured against a colder server than the ones after it -- see {@code JmhRunner.warmServer}.
	 * That is a difference between targets owing nothing to the setting under test, and it moves the
	 * ratios in this table by several percent in a direction the setting cannot explain. Hence the
	 * note below the table rather than a silent percentage: a ratio worth acting on should survive
	 * re-running the profile with its targets in the opposite order.
	 */
	private void targetComparison ( StringBuilder out ) {
		List<String> targets = distinctTargets();
		if ( targets.size() < 2 ) {
			return;
		}
		String reference = targets.getFirst();

		// Only workloads every target actually measured: a partial row would invite a comparison
		// between a number and a blank, which is the kind of table people read a ratio off anyway.
		List<String> workloads = report.benchmarks().stream()
				.map(BenchmarkRow::workload)
				.distinct()
				.filter(workload -> targets.stream().allMatch(t -> rowFor(t, workload) != null))
				.toList();
		if ( workloads.isEmpty() ) {
			return;
		}

		out.append("### The targets side by side\n\n");
		out.append("| workload | threads | ").append(String.join(" | ", targets)).append(" |\n");
		out.append("|---".repeat(targets.size() + 2)).append("|\n");

		for ( String workload : workloads ) {
			BenchmarkRow base = rowFor(reference, workload);
			out.append("| %s | %d ".formatted(workload, base.threads()));
			for ( String target : targets ) {
				BenchmarkRow row = rowFor(target, workload);
				out.append(target.equals(reference)
						? "| %s %s ".formatted(row.scoreWithError(), row.unit())
						: "| %s %s (%.2fx) ".formatted(row.scoreWithError(), row.unit(),
								base.score() == 0.0d ? Double.NaN : row.score() / base.score()));
			}
			out.append("|\n");
		}

		out.append("\nRelative to **%s**, higher is better. ".formatted(reference));
		out.append("A ratio is only about the setting these targets differ in if it is larger than ");
		out.append("both error bars and survives running the profile with the targets in the opposite ");
		out.append("order: the first target is measured against a server the later ones then inherit ");
		out.append("warm, which is worth a few percent on its own.\n\n");
	}

	/** The row for one target and workload, or null where that pair was not measured. */
	private BenchmarkRow rowFor ( String target, String workload ) {
		return report.benchmarks().stream()
				.filter(row -> row.target().equals(target) && row.workload().equals(workload))
				.findFirst()
				.orElse(null);
	}

	/** A section heading, carrying the target when this run measured more than one. */
	private String heading ( String title, String target ) {
		return distinctTargets().size() > 1
				? "### %s — %s\n\n".formatted(title, target)
				: "### %s\n\n".formatted(title);
	}

	/** What a consistency check costs over an append that does not make one. */
	private void dcbCost ( StringBuilder out, String target ) {
		Optional<BenchmarkRow> baseline = throughputRow(target, "append-none", 1);
		Optional<BenchmarkRow> checked = throughputRow(target, "append-type-and-tag", 1);
		if ( baseline.isEmpty() || checked.isEmpty() ) {
			return;
		}

		double ratio = baseline.get().score() / checked.get().score();
		out.append(heading("What the DCB check costs", target));
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

		// Three readings, and they need different sentences.  A conditional append coming out *faster*
		// is not "the check is free": nothing in the store makes an extra predicate cheaper than no
		// predicate, so it is the measurement talking, whatever the backend.
		if ( ratio < 0.95 ) {
			out.append("> The conditional append came out **faster** than the unconditional one, which "
					+ "nothing in the store can explain -- it does strictly more work. This is the "
					+ "measurement, not a result: too few iterations, too short an iteration, or a busy "
					+ "machine. Do not quote this table.\n\n");
		} else if ( ratio < 1.05 ) {
			out.append(target.startsWith("inmem")
					? "> The check costs nothing here, which is what the in-memory backend should say: both "
							+ "appends take the same monitor and there is no lock to contend for. Read the "
							+ "PostgreSQL figure for the cost of the mechanism.\n\n"
					: "> The check appears to cost nothing, which against PostgreSQL means the measurement is "
							+ "wrong -- most likely too few iterations to separate the two, since a conditional "
							+ "append additionally takes an advisory lock and runs a NOT EXISTS predicate.\n\n");
		}
	}

	/**
	 * How the check grows with the number of facts a decision rests on.
	 *
	 * <p>Every {@code append-or-groups-N} the run measured, in numeric order, rather than a fixed set
	 * of steps. The profile decides which widths are worth measuring, and the interesting ones are
	 * whichever bracket a step in the curve -- this table used to be hard-coded to 2, 5 and 10, so
	 * adding 3 and 4 to bisect an eleven-fold cliff between two and five left the cliff invisible in
	 * the very section written to describe it.
	 */
	private void orGroupScaling ( StringBuilder out, String target ) {
		List<BenchmarkRow> rows = report.benchmarks().stream()
				.filter(row -> row.target().equals(target))
				.filter(row -> row.threads() == 1)
				.filter(row -> row.workload().startsWith("append-or-groups-"))
				.filter(row -> orGroupsOf(row) > 0)
				.sorted(Comparator.comparingInt(MarkdownRenderer::orGroupsOf))
				.toList();
		if ( rows.size() < 2 ) {
			return;
		}

		Optional<BenchmarkRow> single = throughputRow(target, "append-type-and-tag", 1);
		out.append(heading("How a multi-fact decision scales", target));
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

	/** The N in {@code append-or-groups-N}, or 0 for a name that does not end in one. */
	private static int orGroupsOf ( BenchmarkRow row ) {
		try {
			return Integer.parseInt(row.workload().substring("append-or-groups-".length()));
		} catch ( NumberFormatException e ) {
			return 0;
		}
	}

	/**
	 * What happens as writers are added -- with the conflict rate beside it, because throughput alone
	 * can rise while the useful work falls.
	 */
	private void threadScaling ( StringBuilder out, String target ) {
		Map<String, List<BenchmarkRow>> byWorkload = new TreeMap<>();
		for ( BenchmarkRow row : report.benchmarks() ) {
			if ( "thrpt".equals(row.mode()) && row.target().equals(target) ) {
				byWorkload.computeIfAbsent(row.workload(), key -> new java.util.ArrayList<>()).add(row);
			}
		}
		byWorkload.values().removeIf(rows -> rows.size() < 2);
		if ( byWorkload.isEmpty() ) {
			return;
		}

		out.append(heading("What happens as threads are added", target));
		out.append("| workload | threads | throughput | useful ops/s | conflicts |\n|---|---|---|---|---|\n");
		byWorkload.forEach(( workload, rows ) -> rows.stream()
				.sorted(Comparator.comparingInt(BenchmarkRow::threads))
				.forEach(row -> out.append("| %s | %d | %s %s | %s | %.1f%% |\n".formatted(
						workload, row.threads(), row.scoreWithError(), row.unit(),
						rate(row.usefulOperationsPerSecond()), row.conflictRate() * 100))));
		out.append("\nA rising throughput with a rising conflict rate is a store spending more of its "
				+ "capacity losing races, not doing more work. The useful column is the one to read.\n\n");
	}

	/** Per-call overhead against per-event cost. */
	private void batchCost ( StringBuilder out, String target ) {
		Optional<BenchmarkRow> one = throughputRow(target, "append-none", 1);
		Optional<BenchmarkRow> ten = throughputRow(target, "append-batch-10", 1);
		Optional<BenchmarkRow> hundred = throughputRow(target, "append-batch-100", 1);
		if ( one.isEmpty() || ( ten.isEmpty() && hundred.isEmpty() ) ) {
			return;
		}

		out.append(heading("What a round trip costs", target));
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
		// Whether anything captured the store's own statements decides how the reconstructions may be
		// described: pointing at captured plans that are not in this report sends the reader looking for
		// a section that does not exist. A load run has no captured plans at all -- nothing runs under
		// auto_explain there -- so the reconstructions are all the report has, and they have to be
		// introduced as such rather than as the lesser half of a pair.
		boolean hasCaptured = report.plans().stream()
				.anyMatch(plan -> plan.shape().startsWith(QueryPlans.CAPTURED_SHAPE_PREFIX));

		out.append("The reconstructed statements below describe the run's first PostgreSQL target.");
		out.append(hasCaptured
				? " The captured ones name the target they came from, since a plan is a property of one "
						+ "store's configuration and a profile measuring a setting against itself explains "
						+ "both halves; the ms/op beside each is that same target's.\n\n"
				: " This run captured none of the store's own statements, so every plan here is a "
						+ "reconstruction.\n\n");

		boolean warned = false;
		boolean introduced = false;
		for ( QueryPlans.Plan plan : report.plans() ) {
			if ( plan.shape().startsWith(QueryPlans.CAPTURED_SHAPE_PREFIX) ) {
				if ( !introduced ) {
					out.append(CAPTURED_PLAN_NOTE);
					introduced = true;
				}
			} else if ( !warned && plan.shape().startsWith(QueryPlans.APPEND_SHAPE_PREFIX) ) {
				out.append(APPEND_PLAN_CAVEAT);
				out.append(hasCaptured ? CAPTURED_PLAN_POINTER : NO_CAPTURED_PLAN_NOTE);
				warned = true;
			}
			out.append("### %s%s%s\n\n".formatted(plan.shape(), measured(plan),
					QueryPlans.isSequentialScan(plan) ? " — **sequential scan**" : ""));
			out.append("```\n").append(plan.explain()).append("\n```\n\n");
		}
	}

	/**
	 * What the operation this plan belongs to actually cost, next to the plan's own timings.
	 *
	 * <p>The comparison that catches a capture describing something other than what was measured, which
	 * is a failure mode this section has had twice and which is invisible without doing this division by
	 * hand. A plan whose own execution time is an order of magnitude under the measured cost of the
	 * operation containing it is not the plan that operation was running.
	 */
	private String measured ( QueryPlans.Plan plan ) {
		if ( !plan.shape().startsWith(QueryPlans.CAPTURED_SHAPE_PREFIX) ) {
			return "";
		}
		String remainder = plan.shape().substring(QueryPlans.CAPTURED_SHAPE_PREFIX.length());
		int suffix = remainder.indexOf(" (");
		String subject = suffix < 0 ? remainder : remainder.substring(0, suffix);

		int separator = subject.indexOf(QueryPlans.SHAPE_TARGET_SEPARATOR);
		String workload = separator < 0 ? subject : subject.substring(0, separator);
		// A plan from before the target was recorded in its shape falls back to the first PostgreSQL
		// row, which is what such a plan described. Reading a two-target run's plans that way would be
		// wrong, but a two-target run could not have produced a shape without a target in it.
		String target = separator < 0 ? null
				: subject.substring(separator + QueryPlans.SHAPE_TARGET_SEPARATOR.length());

		return report.benchmarks().stream()
				.filter(row -> row.workload().equals(workload))
				.filter(row -> target == null ? row.target().startsWith("postgres") : row.target().equals(target))
				.filter(row -> row.operationsPerSecond() > 0)
				.findFirst()
				.map(row -> " — measured %.2f ms/op".formatted(1000 / row.operationsPerSecond()))
				.orElse("");
	}

	/**
	 * The append-predicate plans are known not to describe the store's own execution, and the report
	 * says so rather than letting a reader take them for an explanation of the curve above.
	 *
	 * <p>They invert it: {@code append-types} plans as a sub-millisecond index-only scan and measures
	 * the slowest of the tagged shapes, while {@code append-type-and-tag} plans as an eight-millisecond
	 * sequential scan and measures nearly the fastest. A capture that contradicts the measurement is
	 * describing a different query, and the difference is the parameterisation: the store binds its tag
	 * arrays and its cursor as JDBC parameters and re-uses the statement, so PostgreSQL settles on a
	 * generic plan against default selectivity, while these statements inline the arrays as literals and
	 * are planned from real statistics every time. That is enough to flip index-versus-scan, which is
	 * precisely the question the section exists to answer -- and it is why the captured plans below are
	 * taken with the plan cache pinned rather than left to the server's own switch-over.
	 */
	/**
	 * Introduces the plans read back from the server for the store's own statements -- the ones that
	 * need no qualification, and the ones to believe where they and the reconstructions disagree.
	 */
	private static final String CAPTURED_PLAN_NOTE = """
			> **These are the store's own statements, explained by the server.** Captured by running each\s
			> workload with `auto_explain` on, after the last measurement, so the SQL is the one the backend\s
			> built, the parameters are bound as it binds them, and the plan is the one PostgreSQL chose.\s
			> Where these and the reconstructed plans above disagree, these are the ones that describe what\s
			> was measured.
			>
			> **Generic against custom, and both are shown.** The backend re-uses its prepared statements,\s
			> so PostgreSQL holds two plans for each: a *generic* one planned once against default\s
			> selectivity, and a *custom* one re-planned from the actual parameter values. From the tenth\s
			> execution it compares their **estimated** costs and adopts the generic plan if it looks no\s
			> worse. So neither one is automatically what the throughput above was measured on: match the\s
			> plans by their `cost=` estimates -- the cheaper-looking of the two is the one the server\s
			> chose, and its actual time should be near the measured ms/op in the heading. Where the two\s
			> are the same plan only one is shown.
			>
			> That comparison is on estimates, and a DCB check is exactly the shape that defeats it: the\s
			> expected result is *no rows*, while the planner prices a `NOT EXISTS` by how soon it expects\s
			> to find one. A wider filter makes it expect a match sooner, so the generic plan's estimate\s
			> **falls** as facts are added while the custom plan's rises -- and once it drops below, the\s
			> server switches to a plan that scans the whole table for a row that is not there.

			""";

	private static final String APPEND_PLAN_CAVEAT = """
			> **The plans below do not describe the store's own execution.** They inline the tag arrays\s
			> and the cursor as literals, which is what PostgreSQL sees when it builds a *custom* plan;\s
			> the store binds them as JDBC parameters and re-uses the statement, so what it actually runs\s
			> is whichever of the custom and generic plans the server settled on -- and for several of\s
			> these shapes that is the generic one, which is a different plan entirely. Read these as the\s
			> shape of the predicate.""";

	/** Where the run captured the store's own statements too, they are the ones to believe. */
	private static final String CAPTURED_PLAN_POINTER = """
			 The captured plans further down are the ones to read against the\s
			> measurements.

			""";

	/**
	 * Where it did not, saying so is the point: the caveat above explains why a reconstruction may
	 * differ from what ran, and without this it would trail off into a pointer to a section that is not
	 * in this report. A load run is the case -- nothing there executes under {@code auto_explain}.
	 */
	private static final String NO_CAPTURED_PLAN_NOTE = """
			 This run captured none of the store's own\s
			> statements, so there is nothing here to check them against: read the shapes, and take the\s
			> plan a measurement actually ran on from a `jmh` run over the same corpus.

			""";

	/**
	 * A per-second figure, or an empty cell where the score is not a throughput.
	 *
	 * <p>A {@code sample} row's score is a duration; there is no rate to print, and printing one anyway
	 * is how the column got into trouble in the first place.
	 */
	private static String rate ( double perSecond ) {
		return Double.isNaN(perSecond) ? "--" : "%,.0f".formatted(perSecond);
	}

	private void allResults ( StringBuilder out ) {
		if ( report.benchmarks().isEmpty() ) {
			return;
		}
		out.append("## Every measurement\n\n");
		// "ok" and "conflicts" are totals over the whole trial, which is what JMH's aux counters are --
		// they used to be headed "useful/s" and "conflicts/s", turning a count of operations into a rate
		// nobody had computed. The rate is next to them now, derived from the score.
		out.append("| target | workload | mode | threads | score | unit | error | useful ops/s | ok | conflicts |\n");
		out.append("|---|---|---|---|---|---|---|---|---|---|\n");

		for ( BenchmarkRow row : BenchmarkRow.sorted(report.benchmarks()) ) {
			String relativeError = Double.isNaN(row.relativeError())
					? "--"
					: "%.1f%%".formatted(row.relativeError() * 100);
			out.append("| %s | %s | %s | %d | %.3f | %s | %s | %s | %,.0f | %,.0f |\n".formatted(
					row.target(), row.workload(), row.mode(), row.threads(), row.score(), row.unit(),
					relativeError, rate(row.usefulOperationsPerSecond()), row.successes(), row.conflicts()));
		}
		out.append("\nA relative error above about 10% means the measurement is too noisy to compare "
				+ "against anything; raise the iteration count or quieten the machine.\n");
	}

	private Optional<BenchmarkRow> throughputRow ( String target, String workload, int threads ) {
		return report.benchmarks().stream()
				.filter(row -> row.target().equals(target) && row.workload().equals(workload)
						&& row.threads() == threads && "thrpt".equals(row.mode()))
				.findFirst();
	}
}
