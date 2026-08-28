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
import org.sliceworkz.eventstore.benchmark.domain.CrmEvent;
import org.sliceworkz.eventstore.benchmark.domain.InventoryEvent;
import org.sliceworkz.eventstore.benchmark.domain.SalesEvent;
import org.sliceworkz.eventstore.benchmark.domain.WebshopContext;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventFilter;
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
		 * Every thread writes to one stream while their boundaries stay disjoint: on Postgres every
		 * conditional append serialises on the same advisory lock and none of them logically conflicts,
		 * so the gap to {@link #SPREAD} is the lock and nothing else.
		 *
		 * <p><b>Only {@code PER_ENTITY} can express this, and the arrangement is deliberately
		 * artificial.</b> Threads draw the same rotation of entities {@code SPREAD} does -- so the
		 * boundaries, the filters and the tags are identical -- while every append is written into the
		 * <em>hot entity's</em> stream. That puts one entity's events in another's stream, which no
		 * application would do; it is the only way to hold the boundary distribution fixed and vary
		 * nothing but the lock. Under a design with one stream per context there is only ever one
		 * stream, so this mode degenerates into {@code SPREAD} and
		 * {@link WorkloadContext#collisionCaveat} says so.
		 */
		ONE_STREAM,
		/**
		 * Every thread writes at the same consistency boundary. Most appends lose and raise
		 * {@code OptimisticLockingException}; the conflict rate and the cost of retrying are the
		 * measurement, not the throughput.
		 *
		 * <p>Under {@code PER_ENTITY} one boundary is also one stream, so this is the lock <em>and</em>
		 * the conflicts; {@code ONE_STREAM} is what isolates the first half.
		 */
		ONE_BOUNDARY;

		/** The spelling a profile uses, so a report naming a mode names it the way the YAML does. */
		public String label ( ) {
			return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
		}

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

	/** Opened on first use; see {@link #crm()} for why this one cannot be eager. */
	private EventStream<CrmEvent> crm;

	/** Entity-scoped inventory streams, opened on demand; see {@link #inventoryFor}. */
	private final Map<String, EventStream<InventoryEvent>> scopedInventory = new HashMap<>();

	/**
	 * The last reference this thread knows for a given consistency boundary.
	 *
	 * <p>Without it an append-with-criteria benchmark conflicts with itself: the first append succeeds
	 * and moves the boundary, and every one after it presents a stale reference and raises. Caching
	 * the reference returned by the append is also what a real decider does, so this is realistic
	 * rather than a benchmark trick -- and under {@code ONE_BOUNDARY} it is deliberately not enough,
	 * because there the other threads move the boundary too and the conflicts are the point.
	 *
	 * <p><b>Keyed on the filter, not on the entity.</b> This is the whole correctness of the scheme and
	 * it was originally wrong: keying on {@code workload|sku} assumes a boundary belongs to one entity,
	 * which is true for {@code append-type-and-tag} and false for {@code append-types}, whose filter
	 * carries no tag at all and whose boundary therefore moves on <em>every</em> stock append anywhere in
	 * the store. A per-entity key held one stale reference per entity and produced an unmistakable
	 * signature: the first rotation through the entity slice succeeded and cached, the second conflicted
	 * on every single invocation, the third re-read and succeeded, and so on -- alternating rotations,
	 * with successful appends pinned at exactly one entity-slice per iteration and everything else
	 * counted as a conflict. What was published as the cost of a types-only DCB check was mostly the
	 * cost of failing one. Two filters that are equal share a cache entry, which is exactly the
	 * condition under which they share a boundary.
	 */
	private final Map<BoundaryKey, EventReference> boundaryCache = new HashMap<>();

	/** Identifies a consistency boundary by what actually defines it: the workload and its filter. */
	public record BoundaryKey ( String workload, EventFilter filter ) { }

	/** Rotates through this thread's slice, so successive invocations do not hit one cached row. */
	private int rotation;

	/** Hands out ids nothing has ever used; see {@link #freshEntity()}. */
	private long freshCounter;

	/**
	 * How many entities are reserved as read-only companions, at most.
	 *
	 * <p>{@code append-or-groups-N} needs N-1 further entities to scope its extra filter items to, and
	 * they have to be entities this workload never writes -- otherwise appending to one of them moves
	 * the boundary of every other entity's cached reference, and the workload conflicts with itself in
	 * a way no per-entity cache can fix. Sixteen covers the widest OR-group the suite measures with
	 * room to spare.
	 */
	private static final int MAX_COMPANION_ENTITIES = 16;

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
	 *
	 * <p><b>On Postgres a wildcard purpose is not free, and the profiles have to say which question
	 * they are asking.</b> {@code stream_purpose} is the second column of both
	 * {@code idx_events_stream_position} and {@code idx_events_stream_tags}, so leaving it unbound
	 * takes the equality off column two: an ordered read can no longer descend to a start condition
	 * and a {@code LIMIT} cannot be pushed into the scan. That is a real cost of reading across
	 * entities and belongs in the measurement -- but it is the wrong thing to charge a read that
	 * names one entity, which under this design would address that entity's own stream. Use
	 * {@link #inventoryFor} for those; see {@link #streamScopesEntity}.
	 */
	public EventStream<InventoryEvent> inventory ( ) {
		return inventory;
	}

	/**
	 * The inventory stream to read one entity's history through, honouring the corpus's design.
	 *
	 * <p>Under {@code PER_ENTITY} that is the entity's own stream, so the read is scoped by
	 * {@code stream_purpose} and needs no tag filter at all. Under every other design it is the one
	 * context-wide stream, and scoping to an entity remains a tag filter the caller applies.
	 *
	 * <p>Cached per entity because a stream is a handle rather than a connection and the workloads
	 * ask for two of them; nothing here subscribes, so nothing here owns anything to close.
	 */
	public EventStream<InventoryEvent> inventoryFor ( String entityId ) {
		if ( !streamScopesEntity() || entityId == null ) {
			return inventory;
		}
		return scopedInventory.computeIfAbsent(entityId, id -> target.store().getEventStream(
				EventStreamId.forContext(WebshopContext.INVENTORY.streamContext()).withPurpose(id),
				InventoryEvent.class));
	}

	/**
	 * Whether the stream {@link #inventoryFor} hands back already scopes to the entity.
	 *
	 * <p>A workload reading one entity branches on this rather than always filtering by tag: under
	 * {@code PER_ENTITY} the stream <em>is</em> the entity, so a tag filter on top would add a GIN
	 * predicate that selects nothing further and would charge the design for work its users would
	 * never do.
	 */
	public boolean streamScopesEntity ( ) {
		return spec.streamDesign() == StreamDesign.PER_ENTITY;
	}

	public EventStream<SalesEvent> sales ( ) {
		return sales;
	}

	/**
	 * A stream over the crm context, opened on first use.
	 *
	 * <p>Lazy where the other two are eager, and not for tidiness: {@code CrmEvent} declares a
	 * {@code Shreddable}, so registering it against a store with no codec configured throws at
	 * {@code getEventStream}. Opening it in the constructor would therefore make every workload fail
	 * on every store without shredding, including the twenty-odd that have nothing to do with it.
	 */
	public EventStream<CrmEvent> crm ( ) {
		if ( crm == null ) {
			crm = target.store().getEventStream(streamIdFor(WebshopContext.CRM, null), CrmEvent.class);
		}
		return crm;
	}

	/**
	 * The stream id to write to for a given entity, honouring both the corpus's stream design and the
	 * collision mode.
	 *
	 * <p><b>This is the only place the two contention modes differ</b>, and it is where the difference
	 * belongs: {@link #nextEntity()} decides which boundary an append is checked against, this decides
	 * which stream it lands in, and on Postgres it is the stream that keys the advisory lock. Under
	 * {@link Collision#ONE_STREAM} every writer is aimed at the hot entity's stream while keeping the
	 * boundary it drew, so the lock is shared and no two appends conflict.
	 */
	public EventStreamId streamIdFor ( WebshopContext context, String entityId ) {
		if ( spec.streamDesign() != StreamDesign.PER_ENTITY ) {
			// one stream per context: every writer is on it, and so is every advisory lock, regardless of
			// collision mode -- which is why ONE_STREAM cannot mean anything here
			return EventStreamId.forContext(context.streamContext());
		}
		if ( entityId == null ) {
			// reading across every entity
			return EventStreamId.forContext(context.streamContext()).anyPurpose();
		}
		String purpose = collision == Collision.ONE_STREAM ? facts.hotEntity() : entityId;
		return EventStreamId.forContext(context.streamContext()).withPurpose(purpose);
	}

	/**
	 * Why this pairing of collision mode and stream design measures less than it claims, if it does.
	 *
	 * <p>A collision mode is a statement about where writers meet, and under a design with one stream
	 * per context they meet everywhere: there is a single advisory lock for the whole context, so
	 * {@link Collision#SPREAD} already serialises every conditional append and {@link
	 * Collision#ONE_STREAM} adds nothing to it. That is not a small distortion -- it is a profile pair
	 * whose difference is zero by construction, which reads as "contention is free" rather than as "this
	 * profile cannot ask that question". Reported at the start of a run rather than left in the numbers.
	 */
	public static Optional<String> collisionCaveat ( Collision collision, StreamDesign design ) {
		if ( collision == Collision.ONE_STREAM && design != StreamDesign.PER_ENTITY ) {
			return Optional.of(
					"collision=one-stream needs streamDesign=PER_ENTITY to mean anything: with one stream per "
							+ "context every writer already shares that stream's advisory lock, so this run "
							+ "measures what collision=spread measures");
		}
		return Optional.empty();
	}

	/**
	 * The next SKU this thread should work on.
	 *
	 * <p>Under {@link Collision#SPREAD} each thread gets a disjoint slice, so threads never share a
	 * boundary. {@link Collision#ONE_STREAM} draws from the <em>same</em> rotation, because what it
	 * varies is the stream the append lands in and not the boundary it is checked against -- see
	 * {@link #streamIdFor}. Only {@link Collision#ONE_BOUNDARY} pins every thread to one entity, and it
	 * picks the hot one, since a contention measurement against an entity holding three events would be
	 * measuring nothing.
	 *
	 * <p>This used to answer {@code facts.hotEntity()} for {@code ONE_STREAM} as well, which made the
	 * two contention modes the same measurement under a different name: identical throughput, identical
	 * conflict counts, and a pair of profiles whose descriptions promised a distinction the numbers
	 * could not contain.
	 */
	public String nextEntity ( ) {
		return switch ( collision ) {
			case ONE_BOUNDARY -> facts.hotEntity();
			case SPREAD, ONE_STREAM -> {
				// stride by thread count so the slices interleave rather than sitting in disjoint
				// position ranges, which would give each thread a different part of the index
				int raw = ( rotation++ * threadCount + threadIndex ) % writableEntityCount();
				// step over the reserved band rather than round it, so the writable entities stay a
				// contiguous walk of the distribution with a hole in the middle
				int entity = raw >= companionStart() ? raw + companionCount() : raw;
				yield "SKU-%06d".formatted(entity);
			}
		};
	}

	/** How many entities appends may target -- everything but the reserved companions. */
	public int writableEntityCount ( ) {
		return Math.max(spec.entityCount() - companionCount(), 1);
	}

	private int companionCount ( ) {
		return companionCapacity(spec.entityCount());
	}

	/**
	 * How many <em>distinct</em> companion entities a corpus of this size can offer.
	 *
	 * <p>Never more than a quarter of the entity space: on a toy corpus there is nothing to hold back,
	 * and reserving most of it would starve the rotation the appends draw from.
	 *
	 * <p><b>Ask this before widening a filter, and refuse rather than wrap.</b> {@link #companionEntity}
	 * takes its index modulo this number, so a workload asking for more companions than exist gets the
	 * same entity back twice and builds a filter with a duplicated disjunct. Nothing throws and nothing
	 * is logged: {@code append-or-groups-10} on a 20-entity corpus (capacity 5) builds ten disjuncts
	 * over six distinct SKUs and is reported as a ten-fact decision. That is a different measurement
	 * wearing the same name -- the statement is as long as it claims while the predicate is not as wide
	 * -- so {@link WorkloadRequirement#distinctCompanions()} declares the need and the pairing is
	 * rejected at setup.
	 */
	public static int companionCapacity ( int entityCount ) {
		return Math.min(MAX_COMPANION_ENTITIES, entityCount / 4);
	}

	/** The smallest {@code entityCount} whose companion band holds this many distinct entities. */
	public static int entityCountFor ( int distinctCompanions ) {
		return distinctCompanions * 4;
	}

	/** Whether an entity is one of the reserved companions, so nothing writes to it by accident. */
	public boolean isCompanion ( String entity ) {
		int count = companionCount();
		for ( int i = 0; i < count; i++ ) {
			if ( companionEntity(i).equals(entity) ) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Where the reserved band starts: an eighth of the way into the distribution, not at either end.
	 *
	 * <p>The tail is the obvious place to put entities nothing writes to, and it is the wrong one. The
	 * corpus is Zipf-distributed, so the coldest entities hold a handful of events at 10^5 and quite
	 * possibly none at 10^3 -- an OR-ed item scoped to one of those is a disjunct over a tag value that
	 * matches nothing, which is a different question from the one the workload is asking. An eighth in
	 * is warm enough to match real events at every tier and cold enough not to be the hot entity the
	 * contention modes aim at.
	 */
	private int companionStart ( ) {
		return Math.max(1, spec.entityCount() / 8);
	}

	/**
	 * The i-th reserved companion entity: real, present in the corpus, and never appended to.
	 *
	 * <p>Used to widen a filter without widening what moves it. An OR-ed item scoped to a companion
	 * contributes a genuine disjunct over a tag value that genuinely matches corpus events -- so the
	 * selectivity is honest -- while staying fixed for the whole run.
	 */
	public String companionEntity ( int i ) {
		return companionEntity(i, spec.entityCount());
	}

	/**
	 * The same computation without a context, so the report can build the very filters the append
	 * workloads build when it captures their query plans.
	 */
	public static String companionEntity ( int i, int entityCount ) {
		int count = companionCapacity(entityCount);
		if ( count <= 0 ) {
			// nothing to reserve: fall back to an ordinary entity and accept the self-conflict
			return "SKU-%06d".formatted(Math.floorMod(i, Math.max(entityCount, 1)));
		}
		return "SKU-%06d".formatted(Math.max(1, entityCount / 8) + Math.floorMod(i, count));
	}

	/**
	 * A fresh, unused entity id, for appends that must not collide with existing history.
	 *
	 * <p><b>A counter, not a random draw.</b> This used to be {@code random.nextInt(100_000)}, which is
	 * not "fresh" in any sense that survives the birthday paradox: a few thousand draws from a
	 * hundred-thousand space collide with near-certainty, and {@code append-empty-boundary} then does
	 * exactly what it is designed to do -- raise, because the stream it decided was empty is not. That
	 * killed the fork rather than being counted, so the profile died part-way through.
	 *
	 * <p>Deterministic on purpose. Restarting at zero in each fork is safe because the corpus is put
	 * back to its baseline between them, and if that ever stops being true the drift guard is the thing
	 * that should say so -- not a nondeterministic id quietly papering over it.
	 */
	public String freshEntity ( ) {
		return "SKU-N%d-%d".formatted(threadIndex, freshCounter++);
	}

	/** The same guarantee for a data subject: a customer nobody, including this run, has seen. */
	public String freshCustomer ( ) {
		return "CUST-N%d-%d".formatted(threadIndex, freshCounter++);
	}

	public int threadIndex ( ) {
		return threadIndex;
	}

	/** The reference this thread last saw for a boundary, if it has one. */
	public Optional<EventReference> cachedBoundary ( BoundaryKey key ) {
		return Optional.ofNullable(boundaryCache.get(key));
	}

	/** Records the reference an append returned, so the next append at this boundary is not stale. */
	public void rememberBoundary ( BoundaryKey key, EventReference reference ) {
		if ( reference != null ) {
			boundaryCache.put(key, reference);
		}
	}

	/** Drops a cached boundary, so the next invocation re-reads it -- used after a conflict. */
	public void forgetBoundary ( BoundaryKey key ) {
		boundaryCache.remove(key);
	}
}
