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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;

/**
 * Reports how much CPU the database server spent per second of each measured iteration, beside the
 * throughput, as the secondary metric {@value #METRIC}.
 *
 * <p>Divided by the score it is the server's CPU per operation, which is what tells a change that
 * makes the server do less work apart from one that only makes the client wait less: a throughput
 * figure alone cannot, since both raise it.
 *
 * <p>PostgreSQL has no SQL view of its own processes' CPU, so this reads the server's control group
 * from the filesystem of the machine the forks run on -- a container's on a Docker host, a service's
 * under systemd. It is therefore opt-in: the launcher adds it only when
 * {@code -D}{@value #PATH_PROPERTY}{@code =<file>} names the group's accounting file, either a cgroup v2
 * {@code cpu.stat} ({@code usage_usec}) or a cgroup v1 {@code cpuacct.usage} (nanoseconds). Everything
 * else in that group is counted too, so the group should hold the server and nothing else.
 *
 * <p>The window is a measured iteration with its setup taken out. JMH calls
 * {@link #beforeIteration} before the iteration's {@code @Setup(Level.Iteration)} methods, which for an
 * append workload restore the corpus -- a copy of the whole table, far more server CPU than the
 * iteration's appends. So the window starts when the setup calls {@link #iterationStarts()}, and only
 * falls back to {@link #beforeIteration} where nothing did. What the restore leaves the server to do
 * in the background (writing out the copied pages, analysing the table) still lands in the window,
 * the same for every run of a profile.
 */
public class ServerCpuProfiler implements InternalProfiler {

	/** the system property naming the accounting file; the launcher passes it on as the profiler's options */
	public static final String PATH_PROPERTY = "benchmark.server.cpu";

	/** the secondary metric's name in JMH's results */
	public static final String METRIC = "server-cpu";

	/** the profiler of this fork, when there is one, for {@link #iterationStarts()} */
	private static volatile ServerCpuProfiler active;

	private final Path accountingFile;
	private volatile long startMicros;
	private volatile long startNanos;

	public ServerCpuProfiler ( String options ) {
		if ( options == null || options.isBlank() ) {
			throw new IllegalArgumentException("ServerCpuProfiler needs the path of a cgroup cpu.stat or cpuacct.usage file");
		}
		this.accountingFile = Path.of(options.strip());
		usageMicros(); // fail at start-up, not after the first iteration
		active = this;
	}

	/**
	 * Starts the window afresh: called at the end of an iteration's setup, so the corpus restore it
	 * did is not counted. Does nothing in a fork that runs without this profiler.
	 */
	public static void iterationStarts ( ) {
		ServerCpuProfiler profiler = active;
		if ( profiler != null ) {
			profiler.mark();
		}
	}

	private void mark ( ) {
		startNanos = System.nanoTime();
		startMicros = usageMicros();
	}

	@Override
	public String getDescription ( ) {
		return "CPU spent by the database server's control group, in microseconds per second of iteration";
	}

	@Override
	public void beforeIteration ( BenchmarkParams benchmarkParams, IterationParams iterationParams ) {
		mark();
	}

	@Override
	public Collection<? extends Result> afterIteration ( BenchmarkParams benchmarkParams,
			IterationParams iterationParams, IterationResult result ) {
		long usedMicros = usageMicros() - startMicros;
		double seconds = ( System.nanoTime() - startNanos ) / 1e9d;
		return List.of(new ScalarResult(METRIC, usedMicros / seconds, "us/s", AggregationPolicy.AVG));
	}

	private long usageMicros ( ) {
		try {
			String content = Files.readString(accountingFile).strip();
			for ( String line : content.split("\n") ) {
				if ( line.startsWith("usage_usec ") ) {
					return Long.parseLong(line.substring("usage_usec ".length()).strip());
				}
			}
			return Long.parseLong(content) / 1_000L; // cpuacct.usage: nanoseconds
		} catch ( IOException | NumberFormatException e ) {
			throw new IllegalStateException("cannot read the server's CPU usage from " + accountingFile
					+ ": expected a cgroup v2 cpu.stat or a cgroup v1 cpuacct.usage", e);
		}
	}
}
