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

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.ThreadParams;

import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext;

/**
 * One thread's view of the store under test.
 *
 * <p>Per-thread rather than shared, for the reason set out on {@link WorkloadContext}: the boundary
 * cache that keeps a conditional append from conflicting with itself only works if it belongs to one
 * thread, and an {@code EventStream} is a stateful handle that two threads should not be closing on
 * each other.
 *
 * <p>The thread index and count come from JMH's {@link ThreadParams}, which is what lets
 * {@code SPREAD} hand each thread a disjoint slice of entities. Without them every thread would work
 * on the same entities and a "no contention" measurement would quietly be a contended one.
 */
@State(Scope.Thread)
public class ThreadContext {

	private WorkloadContext context;

	@Setup(Level.Iteration)
	public void setUp ( CorpusState corpus, ThreadParams threads ) {
		context = new WorkloadContext(
				corpus.target(),
				corpus.spec(),
				corpus.facts(),
				corpus.collision(),
				threads.getThreadIndex(),
				threads.getThreadCount(),
				corpus.spec().seed());

		// Any one-off setup a workload needs happens here rather than inside the first measured
		// invocation, where it would show up as an outlier and get explained away as noise.
		corpus.workload().prepare(context);
	}

	public WorkloadContext context ( ) {
		return context;
	}
}
