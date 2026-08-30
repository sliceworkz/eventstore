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
package org.sliceworkz.eventstore.benchmark;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.openjdk.jmh.runner.RunnerException;

import org.sliceworkz.eventstore.benchmark.config.BenchmarkProfile;
import org.sliceworkz.eventstore.benchmark.config.Profiles;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusFingerprint;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusProvisioner;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.benchmark.env.EnvironmentReport;
import org.sliceworkz.eventstore.benchmark.env.TargetFactory;
import org.sliceworkz.eventstore.benchmark.env.TargetSpec;
import org.sliceworkz.eventstore.benchmark.jmh.JmhRunner;
import org.sliceworkz.eventstore.benchmark.load.LoadResult;
import org.sliceworkz.eventstore.benchmark.load.LoadRunner;
import org.sliceworkz.eventstore.benchmark.report.AppendPlanCapture;
import org.sliceworkz.eventstore.benchmark.report.AutoExplain;
import org.sliceworkz.eventstore.benchmark.report.BaselineComparator;
import org.sliceworkz.eventstore.benchmark.report.BenchmarkRow;
import org.sliceworkz.eventstore.benchmark.report.JmhResults;
import org.sliceworkz.eventstore.benchmark.report.QueryPlans;
import org.sliceworkz.eventstore.benchmark.report.ReadPlanCapture;
import org.sliceworkz.eventstore.benchmark.report.Reports;
import org.sliceworkz.eventstore.benchmark.report.RunManifest;
import org.sliceworkz.eventstore.benchmark.report.RunReport;
import org.sliceworkz.eventstore.benchmark.workload.Workload;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadDryRun;
import org.sliceworkz.eventstore.benchmark.workload.Workloads;

/**
 * Entry point of the benchmark suite.
 *
 * <p>The suite is a capacity-characterisation harness, not a regression gate: it exists to answer
 * "how does this store behave at N events, with M concurrent writers, under these query shapes, in a
 * store that also holds other domains" with numbers that can be published and reproduced.
 *
 * <p>Work is split over subcommands because their costs differ by orders of magnitude:
 *
 * <dl>
 *   <dt>{@code provision}</dt>
 *   <dd>builds the corpora a profile needs, or reports that they are already there. Ten million
 *       events is minutes of bulk import; a corpus is content-addressed and reused across runs, so
 *       this is paid once rather than per measurement.</dd>
 *   <dt>{@code jmh}</dt>
 *   <dd>operation-level measurement against a provisioned corpus.</dd>
 *   <dt>{@code load}</dt>
 *   <dd>sustained load against a <em>growing</em> store, which JMH cannot host: offered rate, latency
 *       percentiles, conflict rates, and the two live-latency scenarios.</dd>
 *   <dt>{@code report}</dt>
 *   <dd>renders a run, and diffs it against a committed baseline.</dd>
 *   <dt>{@code list} / {@code doctor}</dt>
 *   <dd>what profiles exist, and whether this machine can actually run one.</dd>
 * </dl>
 */
public final class Main {

	private Main ( ) { }

	public static void main ( String[] args ) {
		if ( args.length == 0 ) {
			usage();
			System.exit(2);
			return;
		}

		Map<String, String> options = parseOptions(args);
		int exitCode;
		try {
			exitCode = dispatch(args[0], options);
		} catch ( RuntimeException e ) {
			// A mistyped profile name or a malformed profile is the most likely way to invoke this
			// wrongly, and those already carry messages that say exactly what is wrong.  A stack trace
			// buries that message under forty frames of Jackson, so print the message and keep the trace
			// behind --verbose.
			System.err.println(e.getMessage());
			if ( options.containsKey("verbose") ) {
				e.printStackTrace();
			} else {
				System.err.println("(pass --verbose for the stack trace)");
			}
			exitCode = 1;
		}
		System.exit(exitCode);
	}

	private static int dispatch ( String command, Map<String, String> options ) {
		return switch ( command ) {
			case "list" -> list();
			case "doctor" -> doctor(options.get("profile"));
			case "help", "--help", "-h" -> {
				usage();
				yield 0;
			}
			case "provision" -> provision(options);
			case "workloads" -> workloads();
			case "dry-run" -> dryRun(options);
			case "jmh" -> jmh(options);
			case "load" -> load(options);
			case "report" -> report(options);
			case "compare" -> compare(options);
			default -> {
				System.err.println("unknown subcommand '%s'".formatted(command));
				usage();
				yield 2;
			}
		};
	}

	/** Parses {@code --key=value} and {@code --flag} arguments; the subcommand itself is args[0]. */
	private static Map<String, String> parseOptions ( String[] args ) {
		Map<String, String> options = new LinkedHashMap<>();
		for ( int i = 1; i < args.length; i++ ) {
			String argument = args[i];
			if ( !argument.startsWith("--") ) {
				continue;
			}
			int equals = argument.indexOf('=');
			if ( equals < 0 ) {
				options.put(argument.substring(2), "true");
			} else {
				options.put(argument.substring(2, equals), argument.substring(equals + 1));
			}
		}
		return options;
	}

	private static int list ( ) {
		List<BenchmarkProfile> profiles;
		try {
			profiles = Profiles.loadAll();
		} catch ( RuntimeException e ) {
			// loadAll parses every profile, so a typo in any one of them surfaces here rather than
			// three hours into the run that happens to use it
			System.err.println("a profile failed to load: " + e.getMessage());
			return 1;
		}

		if ( profiles.isEmpty() ) {
			System.out.println("no profiles found on the classpath");
			return 0;
		}

		System.out.println("%-22s %-10s %-9s %-8s %s".formatted("PROFILE", "EVENTS", "ESTIMATE", "DOCKER", "CORPUS"));
		List<String> broken = new ArrayList<>();

		for ( BenchmarkProfile profile : profiles ) {
			System.out.println("%-22s %-10s %-9s %-8s %s".formatted(
					profile.name(),
					compactCount(profile.corpus().totalEventsInOwnStore()),
					humanDuration(profile.estimatedDuration()),
					profile.requiresDocker() ? "yes" : "no",
					CorpusFingerprint.prefixFor(profile.corpus())));
			if ( !profile.description().isBlank() ) {
				System.out.println("    " + profile.description().strip().replace("\n", "\n    "));
			}
			workloadProblemIn(profile).ifPresent(problem -> {
				System.out.println("    !!  " + problem);
				broken.add(profile.name());
			});
		}

		if ( !broken.isEmpty() ) {
			System.err.println();
			System.err.println("%d profile(s) name a workload their corpus cannot support: %s"
					.formatted(broken.size(), String.join(", ", broken)));
			return 1;
		}
		return 0;
	}

	/**
	 * Whether a profile's workload names resolve against its own corpus.
	 *
	 * <p>Checked here because {@code list} is the cheapest command there is, and the alternative is
	 * finding out three hours into a run -- or, worse, not finding out: a workload that needs a legacy
	 * corpus and gets a current one reads current events and reports an upcasting cost of zero, which
	 * looks like good news. The resolver rejects that pairing; this is what makes the rejection cheap
	 * to discover.
	 */
	private static java.util.Optional<String> workloadProblemIn ( BenchmarkProfile profile ) {
		if ( profile.jmh() == null ) {
			return java.util.Optional.empty();
		}
		try {
			Workloads.resolve(profile.jmh().workloads(), profile.corpus());
			return java.util.Optional.empty();
		} catch ( IllegalArgumentException e ) {
			return java.util.Optional.of(e.getMessage());
		}
	}

	/**
	 * Runs a profile's JMH benchmarks.
	 *
	 * <p>Deliberately does <em>not</em> provision first. Building a ten-million event corpus inside a
	 * measurement command would hide minutes of setup inside something whose whole job is timing, and
	 * the corpus is reused across runs anyway -- {@code provision} is its own step for that reason. If
	 * the corpus is missing, the trial setup builds it and says so.
	 */
	private static int jmh ( Map<String, String> options ) {
		String profileName = options.get("profile");
		if ( profileName == null ) {
			System.err.println("jmh needs --profile=<name>");
			return 2;
		}
		BenchmarkProfile profile = Profiles.resolve(profileName);

		// Scoped by profile, like the load runner's. A shared default would have two profiles run back to
		// back overwrite each other's report -- which is precisely the `compare` workflow, and would lose
		// the first run silently rather than refusing.
		Path output = Path.of(options.getOrDefault("out", Reports.scratchDirectoryFor(profile).toString()));

		System.out.println("profile   : %s".formatted(profile.name()));
		System.out.println("targets   : %d".formatted(profile.targets().size()));
		System.out.println("estimate  : %s".formatted(humanDuration(profile.estimatedDuration())));
		System.out.println("output    : %s".formatted(output.toAbsolutePath()));
		System.out.println();

		// taken before the run, because the manifest is only assembled after it -- see writeReport
		java.time.Instant startedAt = java.time.Instant.now();
		try {
			JmhRunner.RunOutcome outcome = JmhRunner.run(profileName, profile, output, options.containsKey("yes"));
			System.out.println();
			System.out.println("ran %d benchmark(s)".formatted(outcome.benchmarksRun()));
			outcome.resultFiles().forEach(file -> System.out.println("  results  %-24s %s".formatted(
					file.target(), file.path())));

			// A run without its manifest is a number nobody can attribute, which is how this project's
			// existing documented figures came to be unreproducible.  Writing it is not optional.
			RunReport written = writeReport(profile, output, startedAt,
					JmhResults.readAll(outcome.resultFiles()), List.of(),
					outcome.worstDrift().orElse(0));
			outcome.worstDrift().ifPresent(drift -> System.out.println(
					"  drift    %.2f%% (worst of any trial)".formatted(drift * 100)));
			// Printed beside the drift and not folded into it. Under a restore-per-iteration policy the
			// drift is zero by construction, so a summary carrying only that reads "0.00%" for a run
			// whose log warned twenty times that the store grew twenty-five-fold inside each iteration.
			// Both are true; only together do they say whether the numbers describe the corpus named.
			outcome.worstIterationGrowth()
					.stream().filter(growth -> growth > 0.25d)
					.forEach(growth -> System.out.println(
							"  growth   %.0f%% within one iteration (worst): these numbers describe a store larger than the corpus"
									.formatted(growth * 100)));
			System.out.println("  report   %s".formatted(output.resolve("report.md")));
			written.reasonsNotPublishable().forEach(
					reason -> System.out.println("  note     not publishable: %s".formatted(reason)));
			return 0;
		} catch ( RunnerException e ) {
			System.err.println("the benchmark run failed: " + rootCauseOf(e));
			return 1;
		}
	}

	/**
	 * Runs a profile's load scenario.
	 *
	 * <p>Unlike {@code jmh} this measures a store that is <em>growing</em> throughout, which is the
	 * point: the benchmark layer restores the corpus between iterations, correctly, and in doing so
	 * hides everything an ingest actually experiences.
	 */
	private static int load ( Map<String, String> options ) {
		String profileName = options.get("profile");
		if ( profileName == null ) {
			System.err.println("load needs --profile=<name>");
			return 2;
		}
		BenchmarkProfile profile = Profiles.resolve(profileName);
		if ( profile.load().isEmpty() ) {
			System.err.println("profile '%s' has no 'load' section".formatted(profile.name()));
			return 2;
		}

		System.out.println("profile   : %s".formatted(profile.name()));
		System.out.println("scenarios : %d".formatted(profile.load().size()));

		Path output = Path.of(options.getOrDefault("out", Reports.scratchDirectoryFor(profile).toString()));
		int unsound = 0;
		List<LoadResult> results = new ArrayList<>();

		// taken before the scenarios run, because the manifest is only assembled after them
		java.time.Instant startedAt = java.time.Instant.now();
		for ( TargetSpec target : profile.targets() ) {
			for ( BenchmarkProfile.LoadSettings scenario : profile.load() ) {
				System.out.println();
				System.out.println("  %s -- %s (%d writer(s), %d reader(s), %s, %s)".formatted(
						target.describe(), scenario.scenario(), scenario.writers(), scenario.readers(),
						scenario.collision(),
						scenario.isFixedRate() ? scenario.targetRatePerSecond() + "/s offered" : "saturate"));

				LoadResult result = LoadRunner.run(profile, scenario, target);
				results.add(result);
				printLoadResult(result);
				if ( !result.isSound() ) {
					unsound++;
				}
			}
		}

		// A load run measures a store that is deliberately growing, so "drift" as the benchmark layer
		// means it -- a store that moved away from the corpus it names -- does not apply. The growth is
		// reported per scenario instead, as storeGrewBy.
		writeReport(profile, output, startedAt, List.of(), results, 0);
		System.out.println();
		System.out.println("report    %s".formatted(output.resolve("report.md")));
		if ( unsound > 0 ) {
			System.out.println("%d run(s) failed a correctness check: their numbers describe work that did not happen"
					.formatted(unsound));
		}
		return unsound == 0 ? 0 : 1;
	}

	private static void printLoadResult ( LoadResult result ) {
		System.out.println("      duration      %s".formatted(humanDuration(result.duration())));
		System.out.println("      operations    %,d (%.0f/s attempted, %.0f/s useful)".formatted(
				result.operations(), result.operationsPerSecond(), result.usefulOperationsPerSecond()));
		if ( result.conflicts() > 0 ) {
			System.out.println("      conflicts     %,d (%.1f%% of attempts did no work)".formatted(
					result.conflicts(), result.conflictRate() * 100));
		}
		if ( result.deduplicated() > 0 ) {
			System.out.println("      deduplicated  %,d".formatted(result.deduplicated()));
		}
		if ( result.failures() > 0 ) {
			System.out.println("      failures      %,d".formatted(result.failures()));
		}
		if ( result.storeGrewBy() >= 0 ) {
			System.out.println("      store grew by %,d events".formatted(result.storeGrewBy()));
		}
		System.out.println("      latency");
		result.latencies().forEach(summary -> System.out.println("        " + summary.toLine()));
		System.out.println("      correctness");
		result.correctness().forEach(check -> System.out.println("        " + check.toLine()));
	}

	/**
	 * Assembles and writes a run's report.
	 *
	 * <p>Opens the target once more to read the environment and capture query plans. That costs a
	 * second and buys the difference between a number and an attributable one -- and doing it after the
	 * measurement rather than during means {@code EXPLAIN ANALYZE}, which executes the query, cannot
	 * perturb what it is describing.
	 *
	 * <p><b>Which target it opens is load-bearing, and it used to be the first one.</b> Neither the
	 * environment nor a query plan exists for an in-memory store: {@code EnvironmentReport} finds no
	 * server to read settings from and {@code QueryPlans} returns an empty list on a target with no
	 * {@code DataSource}. Nearly every profile lists {@code inmem} first, as the zero-IO control, so
	 * nearly every report came out with no plans at all and an environment section reading "this run
	 * measured an in-memory store" -- for a run that had just spent seventeen minutes on PostgreSQL.
	 * Both omissions are quiet: an absent plan looks like a plan that could not be captured, and the
	 * publish guard's "the PostgreSQL settings could not be read" reads as an unreachable server rather
	 * than as the report having asked the wrong store.
	 */
	private static RunReport writeReport ( BenchmarkProfile profile, Path output, java.time.Instant startedAt,
			List<BenchmarkRow> benchmarks, List<LoadResult> loadResults, double drift ) {
		CorpusProvisioner provisioner = new CorpusProvisioner(profile.corpus());

		try ( CorpusProvisioner.Prepared prepared = provisioner.open(targetToDescribe(profile), false, null) ) {
			RunManifest manifest = RunManifest.starting(startedAt,
					profile.name(), profile.description(), Profiles.toJson(profile),
					profile.corpus(), prepared.outcome().facts(),
					profile.targets().stream().map(TargetSpec::describe).toList(),
					EnvironmentReport.capture(prepared.target()),
					describeRestorePolicy(profile), profile.jmh().maxDrift());

			List<QueryPlans.Plan> plans = new ArrayList<>(QueryPlans.capture(
					prepared.target(), provisioner.prefix(), profile.corpus(), prepared.outcome().facts(),
					appends(profile)));
			plans.addAll(captureIssuedPlans(profile, provisioner, prepared));

			RunReport report = new RunReport(manifest.finished(drift), benchmarks, loadResults, plans);
			report.writeTo(output);
			return report;
		}
	}

	/** Whether this profile's JMH half runs anything that appends, which decides two things below. */
	private static boolean appends ( BenchmarkProfile profile ) {
		if ( profile.jmh() == null ) {
			return false;
		}
		return Workloads.resolve(profile.jmh().workloads(), profile.corpus()).stream()
				.anyMatch(workload -> workload.name().startsWith("append-")
						|| workload.name().equals("decide-then-append"));
	}

	/**
	 * Plans for the conditional appends and the reads alike, captured from the statements the store
	 * itself issued.
	 *
	 * <p>{@code auto_explain} is switched on for the database and then the pool's idle connections are
	 * retired, so the store the rest of the report was built from starts explaining without being
	 * rebuilt -- see {@link AutoExplain} for why opening a second store would not have helped.
	 *
	 * <p>Only for a Testcontainers PostgreSQL, because the plans come back through the container's log
	 * and this process has no way to read an external server's. Everything else about the report is
	 * unchanged on such a target; it simply carries the hand-written plans alone.
	 *
	 * <p>Both halves run inside the one {@code auto_explain} window rather than each opening its own:
	 * enabling it retires the pool's connections, and doing that twice would cost the second capture a
	 * cold pool for no gain.
	 */
	private static List<QueryPlans.Plan> captureIssuedPlans ( BenchmarkProfile profile,
			CorpusProvisioner provisioner, CorpusProvisioner.Prepared prepared ) {
		if ( profile.jmh() == null ) {
			return List.of();
		}
		List<Workload> workloads = Workloads.resolve(profile.jmh().workloads(), profile.corpus());
		List<QueryPlans.Plan> plans = new ArrayList<>();

		// Every PostgreSQL target, not just the first. A profile measuring one configuration against
		// another -- which is how the suite answers whether a setting is worth having -- differs in
		// exactly the thing a plan would explain, so explaining only one half of the pair leaves the
		// interesting half unaccounted for. The first target reuses the store the report was built
		// from; the rest open their own, which is a corpus reuse and a few seconds.
		for ( TargetSpec spec : profile.targets() ) {
			if ( !canCapturePlansOn(spec) ) {
				continue;
			}
			if ( spec.equals(targetToDescribe(profile)) ) {
				plans.addAll(captureIssuedPlansOn(prepared, spec, provisioner, profile, workloads));
			} else {
				try ( CorpusProvisioner.Prepared other = provisioner.open(spec, false, null) ) {
					plans.addAll(captureIssuedPlansOn(other, spec, provisioner, profile, workloads));
				}
			}
		}
		return plans;
	}

	/** Whether this process can read back the plans a target's server logs. */
	private static boolean canCapturePlansOn ( TargetSpec spec ) {
		return spec.backend() == TargetSpec.Backend.POSTGRES
				&& spec.server() == TargetSpec.PostgresServer.TESTCONTAINERS;
	}

	private static List<QueryPlans.Plan> captureIssuedPlansOn ( CorpusProvisioner.Prepared prepared,
			TargetSpec spec, CorpusProvisioner provisioner, BenchmarkProfile profile,
			List<Workload> workloads ) {
		if ( prepared.target().dataSource().isEmpty() ) {
			return List.of();
		}
		DataSource dataSource = prepared.target().dataSource().get();
		if ( !AutoExplain.enable(dataSource) ) {
			return List.of();
		}
		try {
			// Appends first, then reads, because the renderer introduces the captured plans once at the
			// first of them and the introduction it writes depends on an append being among them.
			List<QueryPlans.Plan> plans = new ArrayList<>(
					AppendPlanCapture.capture(prepared.target(), spec.image(), provisioner.prefix(),
							profile.corpus(), prepared.outcome().facts(), workloads, spec.describe(),
							profile.collision()));
			plans.addAll(ReadPlanCapture.capture(prepared.target(), spec.image(), provisioner.prefix(),
					profile.corpus(), prepared.outcome().facts(), workloads, spec.describe()));
			return plans;
		} finally {
			AutoExplain.disable(dataSource);
		}
	}

	/**
	 * The target the report's environment and query plans should describe: the first SQL-backed one.
	 *
	 * <p>An in-memory target has no settings to record and no plans to capture, so opening one produces
	 * a report that is silent about both. Where a profile measures several targets the manifest holds
	 * one environment, and the one worth recording is the database's -- the JVM and host halves are
	 * shared by every target anyway. A profile with no SQL target at all falls back to its first, which
	 * is then correctly reported as an in-memory run.
	 */
	private static TargetSpec targetToDescribe ( BenchmarkProfile profile ) {
		return profile.targets().stream()
				.filter(target -> target.backend() == TargetSpec.Backend.POSTGRES)
				.findFirst()
				.orElseGet(() -> profile.targets().getFirst());
	}

	private static String describeRestorePolicy ( BenchmarkProfile profile ) {
		if ( profile.jmh() == null ) {
			return "not applicable: this run applied load rather than benchmarking operations";
		}
		List<Workload> selected = Workloads.resolve(profile.jmh().workloads(), profile.corpus());
		if ( !Workloads.anyMutates(selected) ) {
			return "no restore needed: every workload in this run is read-only";
		}
		return profile.corpus().volume() >= 1_000_000
				? "restored once per trial; intra-trial drift measured"
				: "restored before every iteration";
	}

	/**
	 * Renders a run, and optionally diffs it against a committed baseline or publishes it.
	 *
	 * <p>The diff refuses when the two runs are not comparable, which is the whole reason it exists: a
	 * percentage between runs measured on different machines is not a statement about the store, and
	 * nothing about the two numbers says so.
	 */
	private static int report ( Map<String, String> options ) {
		Path runDirectory = Path.of(options.getOrDefault("run",
				options.containsKey("profile")
						? Reports.scratchDirectoryFor(Profiles.resolve(options.get("profile"))).toString()
						: Reports.SCRATCH_ROOT.toString()));

		RunReport current = RunReport.read(runDirectory);
		// Re-render before anything else looks at it. The JSON is the record and the Markdown a view of
		// it, so a run measured before a change to how it is presented should not have to be measured
		// again -- and publishing a directory whose Markdown was rendered by an older version of the
		// renderer commits a baseline that disagrees with its own data.
		current.writeMarkdownTo(runDirectory);

		System.out.println("run       : %s".formatted(runDirectory));
		System.out.println("profile   : %s".formatted(current.manifest().profileName()));
		System.out.println("version   : %s".formatted(current.manifest().suiteVersion()));
		System.out.println("corpus    : %s".formatted(current.manifest().corpusFingerprint()));
		System.out.println("markdown  : %s (re-rendered)".formatted(runDirectory.resolve("report.md")));

		if ( options.containsKey("publish") ) {
			Path published = Reports.publish(runDirectory, options.containsKey("force"));
			System.out.println("published : %s".formatted(published));
			return 0;
		}

		String baselineOption = options.get("baseline");
		Path baselinePath;
		if ( baselineOption != null ) {
			baselinePath = Path.of(baselineOption);
		} else {
			java.util.Optional<Path> latest = Reports.latestBaselineFor(current.manifest().profileName());
			if ( latest.isEmpty() ) {
				System.out.println();
				System.out.println("no committed baseline for this profile; nothing to compare against");
				return 0;
			}
			baselinePath = latest.get();
		}

		System.out.println("baseline  : %s".formatted(baselinePath));
		System.out.println();
		return printComparison(BaselineComparator.compare(RunReport.read(baselinePath), current));
	}

	/**
	 * Diffs two <em>configurations</em> measured here, which is a different question from a baseline diff
	 * and the suite's most useful one.
	 *
	 * <p>"Which stream design should I pick" and "what does a crowded database cost my queries" are both
	 * two runs that differ in exactly one corpus property. {@code report --baseline} is required to
	 * decline that comparison -- it refuses when the corpus differs, which here is the entire experiment
	 * -- so this inverts the rule and refuses on the environment instead.
	 */
	private static int compare ( Map<String, String> options ) {
		String a = options.get("a");
		String b = options.get("b");
		if ( a == null || b == null ) {
			System.err.println("compare needs --a=<run dir> and --b=<run dir>");
			return 2;
		}

		RunReport first = RunReport.read(Path.of(a));
		RunReport second = RunReport.read(Path.of(b));

		System.out.println("a         : %s (%s, corpus %s)".formatted(
				first.manifest().profileName(), String.join(", ", first.manifest().targets()),
				first.manifest().corpusFingerprint()));
		System.out.println("b         : %s (%s, corpus %s)".formatted(
				second.manifest().profileName(), String.join(", ", second.manifest().targets()),
				second.manifest().corpusFingerprint()));
		System.out.println();
		System.out.println(describeExperiment(first, second));
		System.out.println();
		return printComparison(BaselineComparator.compareConfigurations(first, second));
	}

	/**
	 * Names what actually differs between two configurations, so the reader knows what the percentages
	 * are being attributed to.
	 *
	 * <p>A diff of two runs differing in one property is an experiment; a diff of two runs differing in
	 * five is a shrug with numbers attached. Printing the list is not a check -- several differences are
	 * legitimate -- but it puts the confound in front of whoever reads the result.
	 */
	private static String describeExperiment ( RunReport a, RunReport b ) {
		List<String> differences = new ArrayList<>();
		if ( !a.manifest().corpusFingerprint().equals(b.manifest().corpusFingerprint()) ) {
			differences.addAll(a.manifest().corpus().differencesFrom(b.manifest().corpus()));
		}
		if ( !a.manifest().targets().equals(b.manifest().targets()) ) {
			differences.add("targets: %s vs %s".formatted(a.manifest().targets(), b.manifest().targets()));
		}
		// Two profiles necessarily have two names whenever anything differs, so a name difference is not
		// itself a variable and is not counted as one. It matters only as a reminder that a profile also
		// carries how the run was driven -- the collision mode, the thread sweep -- which the manifest
		// does not summarise and this cannot enumerate.
		String profileNote = a.manifest().profileName().equals(b.manifest().profileName())
				? ""
				: "\n(profiles '%s' and '%s'; the corpus and targets are listed above, how each was driven is in its own report)"
						.formatted(a.manifest().profileName(), b.manifest().profileName());

		if ( differences.isEmpty() ) {
			return "the corpus and the targets are identical." + profileNote;
		}
		StringBuilder out = new StringBuilder(differences.size() == 1
				? "the one difference between them:\n"
				: "%d differences between them, so a change cannot be attributed to any single one:\n"
						.formatted(differences.size()));
		differences.forEach(difference -> out.append("  - ").append(difference).append('\n'));
		return out.toString().stripTrailing() + profileNote;
	}

	private static int printComparison ( BaselineComparator.Result result ) {
		if ( result instanceof BaselineComparator.Result.Refused refused ) {
			System.out.println(refused.explain());
			return 1;
		}

		BaselineComparator.Result.Compared compared = (BaselineComparator.Result.Compared) result;
		System.out.println("%d measurement(s) in both runs".formatted(compared.measurementsInBoth()));
		if ( compared.onlyInBaseline() > 0 ) {
			System.out.println("%d only in the baseline".formatted(compared.onlyInBaseline()));
		}
		if ( compared.onlyInCurrent() > 0 ) {
			System.out.println("%d only in this run".formatted(compared.onlyInCurrent()));
		}
		System.out.println();

		List<BaselineComparator.Change> significant = compared.significant();
		if ( significant.isEmpty() ) {
			System.out.println("nothing moved beyond the measurements' own error bars");
			return 0;
		}
		System.out.println("changes outside the error bars:");
		significant.forEach(change -> System.out.println("  " + change.toLine()));
		return 0;
	}

	/** Lists the workload catalogue, which is the vocabulary a profile's {@code workloads:} draws on. */
	private static int workloads ( ) {
		System.out.println("%-30s %-6s %s".formatted("WORKLOAD", "WRITES", "MEASURES"));
		for ( Workload workload : Workloads.all() ) {
			System.out.println("%-30s %-6s %s".formatted(
					workload.name(),
					workload.requirement().mutatesStore() ? "yes" : "no",
					workload.description()));
		}
		return 0;
	}

	/**
	 * Invokes every workload of a profile once, and checks each did something.
	 *
	 * <p>Worth its own subcommand because of what it catches: a query matching nothing is fast, so a
	 * workload aimed at a tag the corpus does not hold reports an excellent number and no error. This
	 * is the only place that distinction gets made.
	 */
	private static int dryRun ( Map<String, String> options ) {
		String profileName = options.get("profile");
		if ( profileName == null ) {
			System.err.println("dry-run needs --profile=<name>");
			return 2;
		}
		BenchmarkProfile profile = Profiles.resolve(profileName);
		List<Workload> selected = Workloads.resolve(
				profile.jmh() == null ? List.of() : profile.jmh().workloads(), profile.corpus());

		System.out.println("profile   : %s".formatted(profile.name()));
		System.out.println("workloads : %d".formatted(selected.size()));

		CorpusProvisioner provisioner = new CorpusProvisioner(profile.corpus());
		int failures = 0;

		for ( TargetSpec targetSpec : distinctDataHomes(profile) ) {
			System.out.println();
			System.out.println("  %s".formatted(targetSpec.describe()));

			// One open target for both provisioning and the run.  Opening a second one would work
			// against Postgres and silently fail against the in-memory backend, whose corpus lives only
			// as long as the store that holds it -- which is exactly how this first went wrong.
			try ( CorpusProvisioner.Prepared prepared = provisioner.open(targetSpec, false, null) ) {
				List<WorkloadDryRun.Result> results = WorkloadDryRun.run(prepared.target(), profile.corpus(),
						prepared.outcome().facts(), selected);
				for ( WorkloadDryRun.Result result : results ) {
					System.out.println("    %-4s %-30s %-9s %s".formatted(
							result.ok() ? "OK" : "FAIL",
							result.workload(),
							humanDuration(result.took()),
							result.detail()));
					if ( !result.ok() ) {
						failures++;
					}
				}
			}
		}

		System.out.println();
		System.out.println(failures == 0
				? "every workload produced a non-degenerate result"
				: "%d workload invocation(s) produced nothing measurable".formatted(failures));
		return failures == 0 ? 0 : 1;
	}

	/**
	 * Builds the corpora a profile needs, or reports that they are already there.
	 *
	 * <p>Separate from the measurement subcommands because the costs are nothing alike: provisioning
	 * the large tier is minutes of bulk import, and the whole point of addressing a corpus by its
	 * content is that the next twenty runs skip it.
	 */
	private static int provision ( Map<String, String> options ) {
		String profileName = options.get("profile");
		if ( profileName == null ) {
			System.err.println("provision needs --profile=<name>");
			return 2;
		}
		BenchmarkProfile profile = Profiles.resolve(profileName);
		boolean force = options.containsKey("force");

		CorpusProvisioner provisioner = new CorpusProvisioner(profile.corpus());
		System.out.println("profile   : %s".formatted(profile.name()));
		System.out.println("corpus    : %s".formatted(provisioner.prefix()));
		System.out.println("events    : ~%d".formatted(profile.corpus().totalEventsInOwnStore()));
		if ( force ) {
			System.out.println("force     : rebuilding even if a usable corpus exists");
		}

		int failures = 0;
		// Every target of a profile shares one corpus, and provisioning is about the *data* rather than
		// about how a store is configured over it -- so a Postgres corpus is built once no matter how
		// many targets read it.  In-memory targets are a different matter: nothing persists, so there
		// is nothing to provision ahead of time and the JMH layer generates as part of its setup.
		for ( TargetSpec target : distinctDataHomes(profile) ) {
			try {
				CorpusProvisioner.Outcome outcome = provisioner.ensure(target, force, new ProgressPrinter());
				System.out.println();
				System.out.println("  %-40s %s".formatted(target.describe(),
						outcome.rebuilt() ? "built" : "reused"));
				System.out.println("      events  %d".formatted(outcome.eventCount()));
				System.out.println("      took    %s".formatted(humanDuration(outcome.took())));
				System.out.println("      reason  %s".formatted(outcome.reason()));
				if ( !target.requiresDocker() && target.backend() == TargetSpec.Backend.INMEM ) {
					System.out.println("      note    nothing persists: an in-memory corpus is rebuilt by whatever "
							+ "measures it, so provisioning one ahead of time achieves nothing");
				}
				printFacts(outcome.facts());
			} catch ( RuntimeException e ) {
				failures++;
				System.out.println("  FAIL %-40s %s".formatted(target.describe(), rootCauseOf(e)));
			}
		}
		return failures == 0 ? 0 : 1;
	}

	/**
	 * The distinct places a profile's corpus has to physically exist. Targets differing only in how the
	 * store is configured -- metrics, result limit -- share one set of tables, so provisioning them
	 * separately would rebuild the same data several times.
	 */
	private static List<TargetSpec> distinctDataHomes ( BenchmarkProfile profile ) {
		LinkedHashMap<String, TargetSpec> homes = new LinkedHashMap<>();
		for ( TargetSpec target : profile.targets() ) {
			String key = "%s|%s|%s".formatted(target.backend(), target.server(), target.image());
			homes.putIfAbsent(key, target);
		}
		return List.copyOf(homes.values());
	}

	private static void printFacts ( CorpusFacts facts ) {
		System.out.println("      facts");
		System.out.println("        hot entity    %-16s %d events".formatted(
				facts.hotEntity(), facts.count(CorpusFacts.COUNT_HOT_ENTITY)));
		System.out.println("        cold entity   %-16s %d events".formatted(
				facts.coldEntity(), facts.count(CorpusFacts.COUNT_COLD_ENTITY)));
		System.out.println("        needle tag    %-16s %d events".formatted(
				facts.needleTagValue(), facts.count(CorpusFacts.COUNT_NEEDLE)));
		System.out.println("        swathe tag    %-16s %d events".formatted(
				facts.swatheTagValue(), facts.count(CorpusFacts.COUNT_SWATHE)));
		System.out.println("        mid cursor    %s".formatted(facts.midCursorRef()));
		System.out.println("        replay until  %s".formatted(facts.replayUntilRef()));
		if ( facts.meanPayloadBytes() != null ) {
			System.out.println("        payload       %.0f bytes mean (sales)".formatted(facts.meanPayloadBytes()));
		}
		if ( !facts.streamPurposes().isEmpty() ) {
			System.out.println("        purposes      %d recorded".formatted(facts.streamPurposes().size()));
		}
	}

	/** Prints a running count during a long provisioning run, on one rewritten line. */
	private static final class ProgressPrinter implements java.util.function.LongConsumer {

		private long lastPrintedAt;

		@Override
		public void accept ( long events ) {
			long now = System.nanoTime();
			if ( now - lastPrintedAt < 1_000_000_000L ) {
				return;
			}
			lastPrintedAt = now;
			System.out.print("\r      generated %,d events".formatted(events));
			System.out.flush();
		}
	}

	/**
	 * Reports whether this machine can run a profile, by actually opening each of its targets rather
	 * than by inspecting configuration.
	 *
	 * <p>Opening a store is the step that fails for environmental reasons -- no Docker daemon, no
	 * {@code db.properties}, a database missing {@code btree_gin}, a monitoring datasource behind a
	 * pooler so LISTEN/NOTIFY never registers -- and every one of those failures is far cheaper to see
	 * here than after a corpus has been built.
	 */
	private static int doctor ( String profileName ) {
		System.out.println("java     : %s (%s)".formatted(
				System.getProperty("java.version"), System.getProperty("java.vm.name")));
		System.out.println("cpus     : %d".formatted(Runtime.getRuntime().availableProcessors()));
		System.out.println("max heap : %d MB".formatted(Runtime.getRuntime().maxMemory() / ( 1024 * 1024 )));
		System.out.println();

		List<TargetSpec> targets = new ArrayList<>();
		String label;
		if ( profileName == null ) {
			label = "the in-memory baseline (pass --profile=<name> to check a profile's own targets)";
			targets.add(TargetSpec.inmem());
		} else {
			BenchmarkProfile profile = Profiles.resolve(profileName);
			label = "profile '%s'".formatted(profile.name());
			targets.addAll(profile.targets());
		}
		System.out.println("checking " + label);

		int failures = 0;
		for ( TargetSpec spec : targets ) {
			// a throwaway prefix: doctor must not touch a real corpus, and ENSURE on an unused prefix
			// creates an empty schema that costs nothing and proves the privileges are there
			String prefix = CorpusFingerprint.PREFIX_NAMESPACE + "doctor_";
			long startedAt = System.nanoTime();
			try ( BenchmarkTarget target = TargetFactory.open(spec, prefix) ) {
				Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
				System.out.println("  OK   %-40s opened in %s".formatted(spec.describe(), humanDuration(took)));

				EnvironmentReport report = EnvironmentReport.capture(target);
				report.postgres().forEach(( key, value ) -> {
					if ( !"version".equals(key) ) {
						System.out.println("         %-32s %s".formatted(key, value));
					}
				});
			} catch ( RuntimeException e ) {
				failures++;
				System.out.println("  FAIL %-40s %s".formatted(spec.describe(), rootCauseOf(e)));
			}
		}

		System.out.println();
		System.out.println(failures == 0
				? "all %d target(s) opened".formatted(targets.size())
				: "%d of %d target(s) could not be opened".formatted(failures, targets.size()));
		return failures == 0 ? 0 : 1;
	}

	private static String rootCauseOf ( Throwable throwable ) {
		Throwable cause = throwable;
		while ( cause.getCause() != null && cause.getCause() != cause ) {
			cause = cause.getCause();
		}
		return "%s: %s".formatted(cause.getClass().getSimpleName(), cause.getMessage());
	}

	private static String compactCount ( long count ) {
		if ( count >= 1_000_000 ) {
			return "%.1fM".formatted(count / 1_000_000.0);
		}
		if ( count >= 1_000 ) {
			return "%.0fk".formatted(count / 1_000.0);
		}
		return String.valueOf(count);
	}

	private static String humanDuration ( Duration duration ) {
		long seconds = duration.toSeconds();
		if ( seconds < 60 ) {
			return duration.toMillis() < 1000 ? duration.toMillis() + "ms" : seconds + "s";
		}
		if ( seconds < 3600 ) {
			return "%dm%02ds".formatted(seconds / 60, seconds % 60);
		}
		return "%dh%02dm".formatted(seconds / 3600, ( seconds % 3600 ) / 60);
	}

	private static void usage ( ) {
		System.out.println("""
			sliceworkz eventstore benchmark suite

			  list                           the available profiles, their size and rough runtime
			  doctor    [--profile=<name>]   open each target and report whether this machine can run it
			  provision --profile=<name>     build (or reuse) the corpora a profile needs
			            [--force]            rebuild even when a usable corpus is already there
			  jmh       --profile=<name>     run the profile's JMH benchmarks
			            [--out=<dir>]        where to write results (default target/benchmark/<profile>)
			            [--yes]              required for a run estimated at over an hour
			  load      --profile=<name>     run the profile's load scenario against a growing store
			  report    [--run=<dir>]        re-render a run from its stored data; diffs the latest baseline
			            [--baseline=<path>]  diff a particular baseline instead
			            [--publish]          copy the run into the committed results
			            [--force]            publish despite the run not meeting the conditions
			  compare   --a=<dir> --b=<dir>  diff two configurations measured here (stream design,
			                                 composition, volume); refuses across environments
			  workloads                      the workload catalogue a profile's 'workloads:' draws on
			  dry-run   --profile=<name>     invoke each workload once and check it measures something

			A profile is a YAML file: pass its name to use one that ships on the classpath, or a path
			to use one of your own.
			""");
	}
}
