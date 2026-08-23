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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;

import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec.StreamDesign;
import org.sliceworkz.eventstore.benchmark.domain.InventoryEvent;
import org.sliceworkz.eventstore.benchmark.domain.SalesEvent;
import org.sliceworkz.eventstore.benchmark.domain.WebshopContext;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Everything one thread needs to run a workload: the store, what is in it, and where this thread is
 * allowed to write.
 *
 * <p><b>One context per thread, never shared.</b> Two reasons, and the second is the load-bearing
 * one. An {@code EventStream} is a cheap handle but a stateful one, so sharing it across threads
 * would make one thread's {@code close()} another's problem. More importantly, the consistency
 * boundary cache below is per-thread by design: it is what lets an uncontended append benchmark
 * append repeatedly without conflicting with itself.
 *
 * <p><b>The collision mode is what makes the concurrency dimension mean something.</b> Handing every
 * thread its own slice of entities measures a store with no contention; pointing them all at one
 * entity measures the advisory lock and the retry loop. Those are different questions and the
 * difference between them is entirely in which entity {@link #nextEntity()} returns.
 */
public final class WorkloadContext {

	/** Where concurrent writers are aimed, which decides what a multi-threaded run measures. */
	public enum Collision {
		/**
		 * Each thread works on its own slice of entities. No two threads share a consistency boundary,
		 * and on Postgres no two conditional appends contend for the same advisory lock. This is the
		 * throughput ceiling.
		 */
		SPREAD,
		/**
		 * Every thread writes to one stream. Under {@code PER_ENTITY} that means one purpose, so on
		 * Postgres every conditional append serialises on the same advisory lock -- this measures the
		 * lock, not the store.
		 */
		ONE_STREAM,
		/**
		 * Every thread writes at the same consistency boundary. Most appends lose and raise
		 * {@code OptimisticLockingException}; the conflict rate and the cost of retrying are the
		 * measurement, not the throughput.
		 */
		ONE_BOUNDARY;

		public static Collision parse ( String value ) {
			if ( value == null || value.isBlank() ) {
				return SPREAD;
			}
			return switch ( value.strip().toLowerCase().replace('_', '-') ) {
				case "spread" -> SPREAD;
				case "one-stream" -> ONE_STREAM;
				case "one-boundary" -> ONE_BOUNDARY;
				default -> throw new IllegalArgumentException(
						"unknown collision mode '%s'; expected spread, one-stream or one-boundary".formatted(value));
			};
		}
	}

	private final BenchmarkTarget target;
	private final CorpusSpec spec;
	private final CorpusFacts facts;
	private final Collision collision;
	private final int threadIndex;
	private final int threadCount;
	private final SplittableRandom random;

	private final EventStream<InventoryEvent> inventory;
	private final EventStream<SalesEvent> sales;

	/**
	 * The last reference this thread knows for a given consistency boundary.
	 *
	 * <p>Without it an append-with-criteria benchmark conflicts with itself: the first append succeeds
	 * and moves the boundary, and every one after it presents a stale reference and raises. Caching
	 * the reference returned by the append is also what a real decider does, so this is realistic
	 * rather than a benchmark trick -- and under {@code ONE_BOUNDARY} it is deliberately not enough,
	 * because there the other threads move the boundary too and the conflicts are the point.
	 */
	private final Map<String, EventReference> boundaryCache = new HashMap<>();

	/** Rotates through this thread's slice, so successive invocations do not hit one cached row. */
	private int rotation;

	public WorkloadContext ( BenchmarkTarget target, CorpusSpec spec, CorpusFacts facts, Collision collision,
			int threadIndex, int threadCount, long seed ) {
		this.target = target;
		this.spec = spec;
		this.facts = facts;
		this.collision = collision;
		this.threadIndex = threadIndex;
		this.threadCount = Math.max(threadCount, 1);
		this.random = new SplittableRandom(seed + threadIndex);

		this.inventory = target.store().getEventStream(
				streamIdFor(WebshopContext.INVENTORY, null), InventoryEvent.class);
		this.sales = target.store().getEventStream(
				streamIdFor(WebshopContext.SALES, null), SalesEvent.class);
	}

	public BenchmarkTarget target ( ) {
		return target;
	}

	public CorpusSpec spec ( ) {
		return spec;
	}

	public CorpusFacts facts ( ) {
		return facts;
	}

	public Collision collision ( ) {
		return collision;
	}

	public SplittableRandom random ( ) {
		return random;
	}

	/**
	 * A read stream over the inventory context. Under {@code PER_ENTITY} this reads across every
	 * purpose, which is what a query filtering by tag rather than by stream has to do.
	 */
	public EventStream<InventoryEvent> inventory ( ) {
		return inventory;
	}

	public EventStream<SalesEvent> sales ( ) {
		return sales;
	}

	/**
	 * The stream id to write to for a given entity, honouring both the corpus's stream design and the
	 * collision mode.
	 */
	public EventStreamId streamIdFor ( WebshopContext context, String entityId ) {
		if ( spec.streamDesign() != StreamDesign.PER_ENTITY ) {
			// one stream per context: every writer is on it regardless of collision mode
			return EventStreamId.forContext(context.streamContext());
		}
		if ( entityId == null ) {
			// reading across every entity
			return EventStreamId.forContext(context.streamContext()).anyPurpose();
		}
		return EventStreamId.forContext(context.streamContext()).withPurpose(entityId);
	}

	/**
	 * The next SKU this thread should work on.
	 *
	 * <p>Under {@link Collision#SPREAD} each thread gets a disjoint slice, so threads never share a
	 * boundary. The other two modes deliberately return the same entity for every thread -- the hot
	 * one, since a contention measurement against an entity with three events would be measuring
	 * nothing.
	 */
	public String nextEntity ( ) {
		return switch ( collision ) {
			case ONE_STREAM, ONE_BOUNDARY -> facts.hotEntity();
			case SPREAD -> {
				// stride by thread count so the slices interleave rather than sitting in disjoint
				// position ranges, which would give each thread a different part of the index
				int entity = ( rotation++ * threadCount + threadIndex ) % spec.entityCount();
				yield "SKU-%06d".formatted(entity);
			}
		};
	}

	/** A fresh, unused entity id, for appends that must not collide with existing history. */
	public String freshEntity ( ) {
		return "SKU-N%05d-%d".formatted(random.nextInt(100_000), threadIndex);
	}

	public int threadIndex ( ) {
		return threadIndex;
	}

	/** The reference this thread last saw for a boundary, if it has one. */
	public Optional<EventReference> cachedBoundary ( String key ) {
		return Optional.ofNullable(boundaryCache.get(key));
	}

	/** Records the reference an append returned, so the next append at this boundary is not stale. */
	public void rememberBoundary ( String key, EventReference reference ) {
		if ( reference != null ) {
			boundaryCache.put(key, reference);
		}
	}

	/** Drops a cached boundary, so the next invocation re-reads it -- used after a conflict. */
	public void forgetBoundary ( String key ) {
		boundaryCache.remove(key);
	}
}
