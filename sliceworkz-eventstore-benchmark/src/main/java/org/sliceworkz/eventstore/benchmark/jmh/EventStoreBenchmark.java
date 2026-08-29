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

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The one benchmark method the whole suite runs.
 *
 * <p>One method rather than twenty-seven, because the operation is a {@code @Param}. That keeps the
 * matrix in the profile -- a file -- instead of spread across annotations, and it means adding a
 * workload never means adding a benchmark class. JMH still reports each parameter value as its own
 * row, so nothing is lost in the output.
 *
 * <p><b>The result is consumed, and that is not a formality.</b> A read workload returns an
 * already-materialised list precisely so this blackhole has something real to swallow: the store's
 * {@code query()} defers deserialization to the caller's terminal operation, so handing JMH an
 * unconsumed {@code Stream} would time the SQL and skip the serde entirely. The discipline lives in
 * the workloads; this is where it pays off.
 *
 * <p>The annotations here are defaults for someone running a class directly. A real run overrides
 * every one of them from the profile, through {@link JmhRunner} -- so changing an iteration count
 * means editing YAML, not Java.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
public class EventStoreBenchmark {

	/**
	 * Invokes the workload once.
	 *
	 * <p>Note what is <em>not</em> here: a try/catch. An optimistic locking conflict is classified by
	 * {@link Outcomes} from the workload's return value rather than thrown, because under contention it
	 * is the expected outcome and JMH would treat a thrown exception as a benchmark error and abort.
	 * Anything that does escape is a real failure and should stop the run.
	 */
	@Benchmark
	public void operation ( CorpusState corpus, ThreadContext thread, Outcomes outcomes, Blackhole blackhole ) {
		Object result = corpus.workload().invoke(thread.context());
		outcomes.record(result, corpus.workload().requirement().mutatesStore());
		blackhole.consume(result);
	}
}
