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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec.PayloadProfile;
import org.sliceworkz.eventstore.benchmark.domain.Address;
import org.sliceworkz.eventstore.benchmark.domain.CrmEvent;
import org.sliceworkz.eventstore.benchmark.domain.InventoryEvent;
import org.sliceworkz.eventstore.benchmark.domain.TagKeys;
import org.sliceworkz.eventstore.benchmark.domain.WebshopContext;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventFilterItem;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * The write side of the workload catalogue: every append shape the suite measures.
 *
 * <p>The shapes are not arbitrary. Each one is a filter a real consistency check produces, and the
 * interesting comparisons between them are the questions the suite exists to answer -- what a DCB
 * check costs over an unconditional append, how that grows with the number of OR-ed filter items, and
 * how much of the ceiling under concurrency is the advisory lock rather than the work.
 *
 * <p><b>A conditional append benchmark has to avoid conflicting with itself.</b> An append that
 * succeeds moves the boundary it was checked against, so a workload holding one reference and reusing
 * it would succeed once and then raise {@code OptimisticLockingException} on every subsequent
 * invocation -- measuring the failure path while appearing to measure the success path. Each of these
 * therefore threads the reference forward from the append's own return value, which is what a real
 * decider does anyway. Under {@link WorkloadContext.Collision#ONE_BOUNDARY} that deliberately is not
 * enough, because the other threads move the boundary too, and there the conflicts are the point.
 */
public final class AppendWorkloads {

	private AppendWorkloads ( ) { }

	public static List<Workload> all ( ) {
		return List.of(
				unconditional(),
				batch(10),
				batch(100),
				byTypesOnly(),
				typeAndTag(),
				multiTag(),
				orGroups(2),
				// three and four bisect the cliff. Two OR-ed items keep the check on the tag index and
				// cost 1.4ms; five make PostgreSQL scan the table instead and cost 15ms, and ten cost the
				// same fifteen because by then the scan is the whole cost. The step is somewhere in here,
				// and where exactly is the difference between a rule of thumb and a number.
				orGroups(3),
				orGroups(4),
				orGroups(5),
				orGroups(10),
				emptyBoundary(),
				staleBoundary(),
				idempotentFresh(),
				idempotentDuplicate(),
				decideThenAppend(),
				shreddedAppend(false),
				shreddedAppend(true));
	}

	/**
	 * An unconditional append of one crm event carrying a sealed value.
	 *
	 * <p>Two variants, and the pair is the measurement. The <em>known subject</em> one appends for a
	 * customer the corpus already holds, so the key store has that key and probably has it cached; the
	 * <em>new subject</em> one appends for a customer nobody has seen, so a key has to be created and
	 * stored first. The gap between them is what a first-ever event for a data subject costs, which is
	 * the part of shredding that scales with customers rather than with events.
	 *
	 * <p>Against {@code append-none} in the same run, either one is the absolute cost of sealing a
	 * value on the write path. That comparison crosses two contexts and two payload shapes, so it is a
	 * figure rather than a ratio -- the tight ratio lives on the read side, where {@code query-crm-raw}
	 * reads the very same bytes without decrypting them.
	 */
	private static Workload shreddedAppend ( boolean newSubject ) {
		return new Workload() {

			@Override
			public String name ( ) {
				return newSubject ? "append-crm-new-subject" : "append-crm-shredded";
			}

			@Override
			public String description ( ) {
				return newSubject
						? "a sealed append for a customer nobody has seen -- includes minting and storing a key"
						: "a sealed append for a customer the corpus already holds -- the steady-state cost";
			}

			@Override
			public WorkloadRequirement requirement ( ) {
				return new WorkloadRequirement(true, java.util.Set.of(PayloadProfile.SHREDDED), false, 0);
			}

			@Override
			public Object invoke ( WorkloadContext context ) {
				// A counter, not a random draw: a "new subject" that collides with one already in the
				// store is silently a known subject, so the workload would go on reporting the
				// key-minting cost while no longer paying it -- and the pair this profile exists for
				// would converge for a reason invisible in the numbers.
				String customerId = newSubject
						? context.freshCustomer()
						: "CUST-%06d".formatted(context.random().nextInt(context.spec().entityCount()));
				DataSubject subject = DataSubject.of("customer", customerId);

				Address address = new Address("Meir", String.valueOf(context.random().nextInt(1, 400)),
						"2000", "Antwerpen", "BE");
				CrmEvent event = new CrmEvent.CustomerAddressChanged(customerId,
						Shreddable.of(address, subject));

				return context.crm().append(AppendCriteria.none(),
						List.of(Event.of(event, Tags.of(TagKeys.CUSTOMER, customerId))),
						context.streamIdFor(WebshopContext.CRM, customerId));
			}
		};
	}

	/**
	 * No criteria at all. The floor, and on Postgres the only append that takes no advisory lock --
	 * which makes the gap between this and {@link #typeAndTag()} the cost of the whole DCB mechanism.
	 */
	private static Workload unconditional ( ) {
		return new AbstractAppend("append-none",
				"a single event with no criteria -- the floor, and the only append taking no advisory lock") {

			@Override
			Object append ( WorkloadContext context, String sku ) {
				return context.inventory().append(AppendCriteria.none(), reservation(context, sku),
						context.streamIdFor(WebshopContext.INVENTORY, sku));
			}
		};
	}

	/**
	 * Several events in one call, unconditional. Bulk ingestion, and the measurement that separates
	 * per-call overhead from per-event cost -- a per-event figure from a batch of 100 against one from
	 * a batch of 1 is the round trip laid bare.
	 */
	private static Workload batch ( int size ) {
		return new AbstractAppend("append-batch-" + size,
				"%d events in one unconditional call -- separates per-call overhead from per-event cost"
						.formatted(size)) {

			@Override
			Object append ( WorkloadContext context, String sku ) {
				List<EphemeralEvent<? extends InventoryEvent>> events = new ArrayList<>(size);
				for ( int i = 0; i < size; i++ ) {
					events.add(reservationEvent(context, sku));
				}
				return context.inventory().append(AppendCriteria.none(), events,
						context.streamIdFor(WebshopContext.INVENTORY, sku));
			}
		};
	}

	/** A criteria filtering on event types alone: the cheapest real consistency check. */
	private static Workload byTypesOnly ( ) {
		return new AbstractConditionalAppend("append-types",
				"a DCB check filtering on event types only, with no tags") {

			@Override
			EventFilter filterFor ( WorkloadContext context, String sku ) {
				return EventFilter.forEvents(
						EventTypesFilter.of(InventoryEvent.StockReserved.class, InventoryEvent.StockPicked.class),
						Tags.none());
			}
		};
	}

	/**
	 * One type set and one tag: "this SKU is never oversold", and the canonical DCB check. The single
	 * most important number the suite produces, because it is what every decider pays.
	 */
	private static Workload typeAndTag ( ) {
		return new AbstractConditionalAppend("append-type-and-tag",
				"the canonical DCB check: four stock types scoped to one SKU") {

			@Override
			EventFilter filterFor ( WorkloadContext context, String sku ) {
				return EventFilter.forEvents(stockTypes(), Tags.of(TagKeys.SKU, sku));
			}
		};
	}

	/** Three tags AND-ed in one item, which on Postgres is one wider containment test. */
	private static Workload multiTag ( ) {
		return new AbstractConditionalAppend("append-multi-tag",
				"a DCB check whose single filter item carries three AND-ed tags") {

			@Override
			EventFilter filterFor ( WorkloadContext context, String sku ) {
				return EventFilter.forEvents(stockTypes(), Tags.of(
						org.sliceworkz.eventstore.events.Tag.of(TagKeys.SKU, sku),
						org.sliceworkz.eventstore.events.Tag.of(TagKeys.CHANNEL, "web"),
						org.sliceworkz.eventstore.events.Tag.of(TagKeys.WAREHOUSE, "WH-1")));
			}
		};
	}

	/**
	 * N OR-ed filter items in one criteria: "placing this order needs stock for each of these SKUs".
	 *
	 * <p>Measured at two, five and ten because the shape of the growth is the interesting part. The
	 * generated SQL gains a disjunct per item, so this asks whether a multi-fact decision costs a
	 * multiple of a single-fact one or barely more than it.
	 *
	 * <p><b>The extra items scope to reserved companion entities, which nothing ever appends to.</b>
	 * They used to scope to {@code SKU-000001..N-1}, which are ordinary entities in the writable
	 * rotation -- so an append to one of them moved the boundary for every other entity's cached
	 * reference and the workload conflicted with itself in bulk. The disjuncts are still real, over tag
	 * values that really match corpus events, so the selectivity the planner sees is unchanged; what
	 * changed is that only the entity being appended to can move the boundary.
	 */
	private static Workload orGroups ( int groups ) {
		return new AbstractConditionalAppend("append-or-groups-" + groups,
				"a DCB check over %d OR-ed filter items -- a decision resting on %d separate facts"
						.formatted(groups, groups)) {

			@Override
			public WorkloadRequirement requirement ( ) {
				// the first item is scoped to the entity being appended to; the rest need one distinct
				// companion each. Without this the companion index wraps on a small corpus and the
				// filter repeats a tag -- ten disjuncts over six facts, reported as ten facts.
				return WorkloadRequirement.mutatingOverCompanions(groups - 1);
			}

			@Override
			EventFilter filterFor ( WorkloadContext context, String sku ) {
				List<EventFilterItem> items = new ArrayList<>(groups);
				items.add(new EventFilterItem(stockTypes(), Tags.of(TagKeys.SKU, sku)));
				for ( int i = 1; i < groups; i++ ) {
					items.add(new EventFilterItem(stockTypes(),
							Tags.of(TagKeys.SKU, context.companionEntity(i))));
				}
				return new EventFilter(items, null);
			}
		};
	}

	/**
	 * A real filter with an <em>absent</em> expected reference: "I decided on an empty stream".
	 *
	 * <p>Distinct from {@code AppendCriteria.none()} and worth its own measurement, because it is a
	 * genuine consistency boundary -- any matching event must make it fail. It appends against a fresh
	 * entity every time so the check passes and the success path is what gets timed.
	 */
	private static Workload emptyBoundary ( ) {
		return new AbstractAppend("append-empty-boundary",
				"a real filter with no expected reference -- the 'I decided on an empty stream' boundary") {

			@Override
			Object append ( WorkloadContext context, String ignored ) {
				String fresh = context.freshEntity();
				AppendCriteria criteria = AppendCriteria.of(
						EventFilter.forEvents(stockTypes(), Tags.of(TagKeys.SKU, fresh)), null);
				try {
					return context.inventory().append(criteria, reservation(context, fresh),
							context.streamIdFor(WebshopContext.INVENTORY, fresh)).size();
				} catch ( OptimisticLockingException e ) {
					// Should not happen now that freshEntity() counts rather than draws at random, and
					// that is exactly why it is caught: a conflict here means the entity was not fresh,
					// which is a harness fault worth seeing in the conflict count rather than a dead
					// fork. The JMH layer counts it; it does not treat it as an error.
					return -1;
				}
			}
		};
	}

	/**
	 * A DCB check against a <b>deliberately stale</b> cursor: the corpus midpoint, roughly half the
	 * stream back. The other append workloads read their boundary at append time, so their cursors sit
	 * wherever the entity's last event does -- fresh for the hot entities the Zipf walk favours. This
	 * one pins the cursor's age instead, because cursor age is exactly what the ordered probe pays
	 * for: every stream event after the cursor is a row it walks past to prove absence, while the tag
	 * path the no-cursor branch takes does not care how old a cursor is.
	 *
	 * <p><b>The filter matches nothing, on purpose, and the appended events do not match it.</b> The
	 * check's expected result is "no new relevant facts", and proving that absence is the cost under
	 * measurement -- a filter that found a match would let the forward walk stop early and measure the
	 * cheap path. The probe tag names a SKU that does not exist and is never appended, so the walk runs
	 * its full length every invocation, no invocation ever conflicts, and the appended reservation
	 * (an ordinary one, for the walked entity) keeps the store growing like the other append workloads.
	 *
	 * <p>Read it beside {@code append-type-and-tag} (fresh-ish cursors) and
	 * {@code append-empty-boundary} (no cursor at all, routed to the tag path): the three are the
	 * staleness curve the criteria-shaped check was designed against, and this row is its one
	 * accepted cost -- linear in the walk, and avoided by re-reading the boundary before appending.
	 */
	private static Workload staleBoundary ( ) {
		return new AbstractAppend("append-stale-boundary",
				"the canonical DCB check against a cursor half the stream old, proving absence every time") {

			@Override
			Object append ( WorkloadContext context, String sku ) {
				EventReference midCursor = context.facts().midCursor().orElseThrow(
						() -> new IllegalStateException("this corpus's facts carry no midCursor;"
								+ " re-provision it before measuring boundary staleness"));
				AppendCriteria criteria = AppendCriteria.of(
						EventFilter.forEvents(stockTypes(), Tags.of(TagKeys.SKU, "SKU-STALE-PROBE")),
						midCursor);
				return context.inventory().append(criteria, reservation(context, sku),
						context.streamIdFor(WebshopContext.INVENTORY, sku)).size();
			}
		};
	}

	/**
	 * An append carrying a fresh idempotency key. Single-event only: the store rejects a multi-event
	 * append where any event carries one.
	 */
	private static Workload idempotentFresh ( ) {
		return new AbstractAppend("append-idempotent-fresh",
				"a single event with an unused idempotency key -- the partial unique index on the write path") {

			@Override
			Object append ( WorkloadContext context, String sku ) {
				EphemeralEvent<? extends InventoryEvent> event = reservationEvent(context, sku)
						.withIdempotencyKey("k-%d-%d".formatted(context.threadIndex(), counter++));
				return context.inventory().append(AppendCriteria.none(), List.of(event),
						context.streamIdFor(WebshopContext.INVENTORY, sku));
			}

			private long counter;
		};
	}

	/**
	 * An append whose key was already used, which storage silently swallows.
	 *
	 * <p>Worth measuring separately because the cost is not the same as a successful write and the
	 * outcome is invisible: the call reports success having written nothing, and the only signal is
	 * that the returned list is shorter than the one submitted. A deduplicating ingest path pays this
	 * on every replayed message, so it is a real steady state rather than an error case.
	 */
	private static Workload idempotentDuplicate ( ) {
		return new Workload() {

			private String key;

			@Override
			public String name ( ) {
				return "append-idempotent-duplicate";
			}

			@Override
			public String description ( ) {
				return "an append whose idempotency key was already used -- the silently swallowed path";
			}

			@Override
			public WorkloadRequirement requirement ( ) {
				return WorkloadRequirement.mutating();
			}

			@Override
			public void prepare ( WorkloadContext context ) {
				// write the key once, so every measured invocation is a duplicate rather than the first
				key = "dup-%d-%d".formatted(context.threadIndex(), System.identityHashCode(this));
				String sku = context.facts().hotEntity();
				context.inventory().append(AppendCriteria.none(),
						List.of(reservationEvent(context, sku).withIdempotencyKey(key)),
						context.streamIdFor(WebshopContext.INVENTORY, sku));
			}

			@Override
			public Object invoke ( WorkloadContext context ) {
				String sku = context.facts().hotEntity();
				List<Event<InventoryEvent>> written = context.inventory().append(AppendCriteria.none(),
						List.of(reservationEvent(context, sku).withIdempotencyKey(key)),
						context.streamIdFor(WebshopContext.INVENTORY, sku));
				// empty is the expected outcome: the duplicate was swallowed
				return written.size();
			}
		};
	}

	/**
	 * Query the boundary, then conditionally append against what it returned -- what an application
	 * actually does.
	 *
	 * <p>The most honest write number the suite produces. Timing the append alone understates a
	 * decision by however long the read took, and for a contended entity with thousands of events the
	 * read is most of it.
	 */
	private static Workload decideThenAppend ( ) {
		return new Workload() {

			@Override
			public String name ( ) {
				return "decide-then-append";
			}

			@Override
			public String description ( ) {
				return "read the boundary, then append against it -- one whole decision, as an application makes it";
			}

			@Override
			public WorkloadRequirement requirement ( ) {
				return WorkloadRequirement.mutating();
			}

			@Override
			public Object invoke ( WorkloadContext context ) {
				String sku = context.nextEntity();
				EventQuery boundary = EventQuery.forEvents(stockTypes(), Tags.of(TagKeys.SKU, sku));

				List<Event<InventoryEvent>> history = context.inventory().query(boundary).toList();
				EventReference last = history.isEmpty() ? null : history.getLast().reference();

				try {
					return context.inventory().append(
							AppendCriteria.of(boundary.filter(), last),
							reservation(context, sku),
							context.streamIdFor(WebshopContext.INVENTORY, sku)).size();
				} catch ( OptimisticLockingException e ) {
					// under ONE_BOUNDARY this is the expected outcome for most threads; the JMH layer
					// counts them separately rather than treating them as errors
					return -1;
				}
			}
		};
	}

	/* ---------------------------------------------------------------- helpers */

	static EventTypesFilter stockTypes ( ) {
		return EventTypesFilter.of(InventoryEvent.StockReceived.class, InventoryEvent.StockReserved.class,
				InventoryEvent.StockReleased.class, InventoryEvent.StockPicked.class);
	}

	static EphemeralEvent<? extends InventoryEvent> reservationEvent ( WorkloadContext context, String sku ) {
		return Event.of(new InventoryEvent.StockReserved(sku, 1, "ORD-benchmark"),
				Tags.of(
						org.sliceworkz.eventstore.events.Tag.of(TagKeys.SKU, sku),
						org.sliceworkz.eventstore.events.Tag.of(TagKeys.CHANNEL, "web"),
						org.sliceworkz.eventstore.events.Tag.of(TagKeys.WAREHOUSE, "WH-1")));
	}

	static List<EphemeralEvent<? extends InventoryEvent>> reservation ( WorkloadContext context, String sku ) {
		return List.of(reservationEvent(context, sku));
	}

	/** Shared shape for an append that needs no boundary bookkeeping. */
	private abstract static class AbstractAppend implements Workload {

		private final String name;
		private final String description;

		AbstractAppend ( String name, String description ) {
			this.name = name;
			this.description = description;
		}

		abstract Object append ( WorkloadContext context, String sku );

		@Override
		public final String name ( ) {
			return name;
		}

		@Override
		public final String description ( ) {
			return description;
		}

		@Override
		public final WorkloadRequirement requirement ( ) {
			return WorkloadRequirement.mutating();
		}

		@Override
		public final Object invoke ( WorkloadContext context ) {
			return append(context, context.nextEntity());
		}
	}

	/**
	 * Shared shape for a conditional append, including the boundary bookkeeping that keeps it from
	 * conflicting with itself.
	 *
	 * <p>The first invocation for an entity reads the boundary; every one after it uses the reference
	 * the previous append returned. A conflict drops the cached reference so the next invocation
	 * re-reads -- which is what a retry loop does, and what keeps a contended run making progress
	 * instead of failing forever against one stale reference.
	 */
	private abstract static class AbstractConditionalAppend implements Workload {

		private final String name;
		private final String description;

		AbstractConditionalAppend ( String name, String description ) {
			this.name = name;
			this.description = description;
		}

		abstract EventFilter filterFor ( WorkloadContext context, String sku );

		@Override
		public final String name ( ) {
			return name;
		}

		@Override
		public final String description ( ) {
			return description;
		}

		@Override
		public WorkloadRequirement requirement ( ) {
			return WorkloadRequirement.mutating();
		}

		@Override
		public final Object invoke ( WorkloadContext context ) {
			String sku = context.nextEntity();
			EventFilter filter = filterFor(context, sku);
			// keyed on the filter, because that is what defines the boundary -- see WorkloadContext
			WorkloadContext.BoundaryKey cacheKey = new WorkloadContext.BoundaryKey(name, filter);

			EventReference expected = context.cachedBoundary(cacheKey)
					.orElseGet(() -> readBoundary(context, filter));

			try {
				List<Event<InventoryEvent>> written = context.inventory().append(
						AppendCriteria.of(filter, expected),
						reservation(context, sku),
						context.streamIdFor(WebshopContext.INVENTORY, sku));
				if ( !written.isEmpty() ) {
					context.rememberBoundary(cacheKey, written.getLast().reference());
				}
				return written.size();
			} catch ( OptimisticLockingException e ) {
				context.forgetBoundary(cacheKey);
				return -1;
			}
		}

		private EventReference readBoundary ( WorkloadContext context, EventFilter filter ) {
			return context.inventory()
					.query(new EventQuery(filter, EventQuery.Direction.BACKWARD,
							org.sliceworkz.eventstore.query.Limit.to(1)))
					.map(Event::reference)
					.findFirst()
					.orElse(null);
		}
	}

	/** Kept for callers that want the optional form the API exposes. */
	static Optional<EventReference> asOptional ( EventReference reference ) {
		return Optional.ofNullable(reference);
	}
}
