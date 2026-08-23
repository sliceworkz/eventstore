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
package org.sliceworkz.eventstore.benchmark.jmh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.config.BenchmarkProfile;
import org.sliceworkz.eventstore.benchmark.env.TargetFactory;
import org.sliceworkz.eventstore.benchmark.env.TargetSpec;
import org.sliceworkz.eventstore.benchmark.workload.Workload;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext.Collision;
import org.sliceworkz.eventstore.benchmark.workload.Workloads;
import org.sliceworkz.eventstore.testing.backend.PostgresContainer;

/**
 * Turns a profile into a JMH run.
 *
 * <p>Two things here are load-bearing and neither is obvious from JMH's API.
 *
 * <p><b>The container is started here, in the launcher, and its URL passed down.</b> Every JMH fork is
 * a fresh JVM, so a benchmark that called Testcontainers itself would start one container per fork --
 * each with an empty schema, none of them holding the corpus, and the whole run measuring provisioning
 * instead of the store. The launcher starts it once and hands the URL to the forks as a system
 * property.
 *
 * <p><b>The first fork is systematically colder than the rest.</b> JMH's warmup settles the JVM, but
 * what actually dominates a JDBC benchmark is the server's own cache, and that lives in PostgreSQL
 * rather than in the forked JVM -- it survives the fork boundary, so fork 1 pays for warming shared
 * buffers and forks 2 and 3 inherit a warm server. The runner therefore warms the server explicitly
 * before starting, rather than leaving the difference to be discovered as inter-fork variance.
 */
public final class JmhRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(JmhRunner.class);

	/** Beyond this, a run is long enough that starting it by accident is worth preventing. */
	private static final java.time.Duration CONFIRMATION_THRESHOLD = java.time.Duration.ofHours(1);

	private JmhRunner ( ) { }

	/** What a run produced, so the caller can report where the output went. */
	public record RunOutcome ( List<Path> resultFiles, int benchmarksRun ) { }

	/**
	 * Runs a profile's JMH benchmarks.
	 *
	 * @param profileRef the {@code --profile} value exactly as it was given -- a classpath name or a
	 *        path. It is passed through to the forks verbatim rather than being replaced by
	 *        {@code profile.name()}, because a fork resolves it again from scratch and a profile loaded
	 *        from a file is not on the classpath under its name. Getting this wrong fails every fork
	 *        with "no profile named ...", which is at least loud.
	 * @param acknowledgeLongRun required for a run estimated at over an hour
	 */
	public static RunOutcome run ( String profileRef, BenchmarkProfile profile, Path outputDirectory,
			boolean acknowledgeLongRun ) throws RunnerException {
		// a fork inherits no working directory guarantee, so a relative path has to be made absolute
		// before it is handed over
		String resolvedRef = Files.exists(Path.of(profileRef))
				? Path.of(profileRef).toAbsolutePath().toString()
				: profileRef;

		if ( profile.jmh() == null ) {
			throw new IllegalArgumentException(
					"profile '%s' has no 'jmh' section, so there is nothing for this subcommand to run"
							.formatted(profile.name()));
		}

		List<Workload> workloads = Workloads.resolve(profile.jmh().workloads(), profile.corpus());
		if ( workloads.isEmpty() ) {
			throw new IllegalArgumentException(
					"profile '%s' selects no workloads its corpus supports".formatted(profile.name()));
		}

		java.time.Duration estimate = profile.estimatedDuration();
		if ( estimate.compareTo(CONFIRMATION_THRESHOLD) > 0 && !acknowledgeLongRun ) {
			throw new IllegalArgumentException(
					"this run is estimated at %dh%02dm. Pass --yes to start it, or narrow the profile's workloads, threads or iteration counts."
							.formatted(estimate.toHours(), estimate.toMinutesPart()));
		}

		try {
			Files.createDirectories(outputDirectory);
		} catch ( java.io.IOException e ) {
			throw new IllegalStateException("could not create the output directory " + outputDirectory, e);
		}

		int benchmarks = 0;
		List<Path> resultFiles = new ArrayList<>();

		for ( int targetIndex = 0; targetIndex < profile.targets().size(); targetIndex++ ) {
			TargetSpec target = profile.targets().get(targetIndex);
			List<String> forkArgs = forkArgumentsFor(target, resolvedRef, profile, targetIndex);

			for ( int threads : profile.jmh().threads() ) {
				// One file per (target, threads) combination.  JMH's result() truncates rather than
				// appends, so a single shared path would leave only the last combination's numbers --
				// and a profile sweeping four thread counts would silently report one of them.
				Path resultsFile = outputDirectory.resolve(
						"%s-t%d-x%dt.json".formatted(profile.name(), targetIndex, threads));

				Options options = optionsFor(profile, workloads, threads, forkArgs, resultsFile);
				LOGGER.info("running {} workload(s) against {} with {} thread(s)",
						workloads.size(), target.describe(), threads);
				new Runner(options).run();

				resultFiles.add(resultsFile);
				benchmarks += workloads.size();
			}
		}

		return new RunOutcome(List.copyOf(resultFiles), benchmarks);
	}

	private static Options optionsFor ( BenchmarkProfile profile, List<Workload> workloads,
			int threads, List<String> forkArgs, Path resultsFile ) {
		BenchmarkProfile.JmhSettings settings = profile.jmh();

		return new OptionsBuilder()
				.include(EventStoreBenchmark.class.getSimpleName())
				.forks(settings.forks())
				.threads(threads)
				.warmupIterations(settings.warmupIterations())
				.warmupTime(TimeValue.seconds(settings.iterationSeconds()))
				.measurementIterations(settings.measurementIterations())
				.measurementTime(TimeValue.seconds(settings.iterationSeconds()))
				.timeUnit(TimeUnit.MILLISECONDS)
				.jvmArgsAppend(forkArgs.toArray(new String[0]))
				.resultFormat(ResultFormatType.JSON)
				.result(resultsFile.toString())
				.shouldFailOnError(true)
				// the workload names become the @Param values, which is what turns one benchmark method
				// into a row per operation in the output
				.param("workload", workloads.stream().map(Workload::name).toArray(String[]::new))
				.build();
	}

	/**
	 * The system properties a fork needs, including the JDBC URL of a container the launcher started.
	 */
	private static List<String> forkArgumentsFor ( TargetSpec target, String profileRef, BenchmarkProfile profile,
			int targetIndex ) {
		List<String> arguments = new ArrayList<>(List.of(
				BenchmarkConfig.jvmArgsFor(profileRef, targetIndex, collisionOf(profile))));

		if ( target.requiresDocker() ) {
			// started once, here; see the class comment on why a fork must not start its own
			PostgresContainer.start(target.image());
			DataSource dataSource = PostgresContainer.dataSource(target.image());
			String jdbcUrl = jdbcUrlOf(dataSource);
			arguments.add("-D%s=%s".formatted(TargetFactory.INHERITED_JDBC_URL_PROPERTY, jdbcUrl));
			LOGGER.info("forks will attach to the container already started at {}", jdbcUrl);

			warmServer(dataSource);
		}
		return arguments;
	}

	private static Collision collisionOf ( BenchmarkProfile profile ) {
		return profile.load() == null ? Collision.SPREAD : Collision.parse(profile.load().collision());
	}

	private static String jdbcUrlOf ( DataSource dataSource ) {
		if ( dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari ) {
			return hikari.getJdbcUrl();
		}
		throw new IllegalStateException(
				"expected the container harness to hand back a HikariDataSource, whose URL a fork can be pointed at; got "
						+ dataSource.getClass().getName());
	}

	/**
	 * Touches the events table so the server's cache is warm before fork 1 starts.
	 *
	 * <p>Without this, fork 1 measures a cold PostgreSQL and forks 2 and 3 measure a warm one, and the
	 * difference shows up as inter-fork variance that looks like noise and is not.
	 */
	private static void warmServer ( DataSource dataSource ) {
		try ( var connection = dataSource.getConnection();
				var statement = connection.createStatement() ) {
			statement.execute("SELECT 1");
		} catch ( Exception e ) {
			LOGGER.warn("could not warm the server before the run; fork 1 may be measurably colder", e);
		}
	}
}
