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
package org.sliceworkz.eventstore.benchmark.workload;

/**
 * One measurable operation against the event store.
 *
 * <p>This is the seam the whole suite turns on: the JMH benchmarks and the load runner both call
 * {@link #invoke}, so "a conditional append with five OR-groups" is defined exactly once. Without
 * that, the two halves of the suite would drift -- and two numbers for the same named operation, one
 * from each half, is worse than having only one of them.
 *
 * <p><b>A read workload must fully consume what it reads, and return the result.</b> This is not
 * style; it is the difference between a real number and a fictional one. {@code query()} hands back a
 * {@code Stream} whose rows storage has already fetched, but whose <em>deserialization is lazy</em> --
 * it happens in the caller's terminal operation. A workload that returned the stream unconsumed would
 * have the caller's blackhole swallow it whole, and the benchmark would time the SQL while skipping
 * the serde entirely. Returning an already-materialised result makes that mistake impossible to make
 * in a caller.
 */
public interface Workload {

	/**
	 * The name a profile refers to this by, in kebab case: {@code append-type-and-tag},
	 * {@code query-by-tag-needle}.
	 */
	String name ( );

	/** One sentence on what this measures and why it is worth measuring. */
	String description ( );

	/** What this workload needs from the corpus and the store, so an unusable pairing is caught early. */
	WorkloadRequirement requirement ( );

	/**
	 * Performs one unit of work.
	 *
	 * @return the result, already materialised. Never a lazy {@code Stream}: see the class comment.
	 */
	Object invoke ( WorkloadContext context );

	/**
	 * Called once per thread before measurement begins, for a workload that has per-thread state to
	 * establish -- a consistency-boundary reference to start from, a slice of entities to work on.
	 *
	 * <p>Doing this lazily inside {@link #invoke} instead would fold a one-off query into the first
	 * measured invocation, which is exactly the kind of outlier that widens an error bar and gets
	 * explained away as noise.
	 */
	default void prepare ( WorkloadContext context ) {
		// most workloads need nothing
	}
}
