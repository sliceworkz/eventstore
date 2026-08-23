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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sliceworkz.eventstore.benchmark.config.BenchmarkProfile;
import org.sliceworkz.eventstore.benchmark.config.Profiles;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusFingerprint;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusProvisioner;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.benchmark.env.EnvironmentReport;
import org.sliceworkz.eventstore.benchmark.env.TargetFactory;
import org.sliceworkz.eventstore.benchmark.env.TargetSpec;
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
			case "jmh", "load", "report" -> {
				System.err.println("'%s' is not implemented yet".formatted(command));
				yield 3;
			}
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
		}
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
			  load      --profile=<name>     run the profile's load scenarios
			  report    [--baseline=<path>]  render a run, optionally diffing a baseline
			  workloads                      the workload catalogue a profile's 'workloads:' draws on
			  dry-run   --profile=<name>     invoke each workload once and check it measures something

			A profile is a YAML file: pass its name to use one that ships on the classpath, or a path
			to use one of your own.
			""");
	}
}
