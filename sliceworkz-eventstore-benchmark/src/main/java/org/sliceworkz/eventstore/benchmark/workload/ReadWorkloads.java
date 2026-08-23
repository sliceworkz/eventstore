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

import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusGenerator;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec.PayloadProfile;
import org.sliceworkz.eventstore.benchmark.domain.CrmEvent;
import org.sliceworkz.eventstore.benchmark.domain.InventoryEvent;
import org.sliceworkz.eventstore.benchmark.domain.LegacySalesEvent;
import org.sliceworkz.eventstore.benchmark.domain.SalesEvent;
import org.sliceworkz.eventstore.benchmark.domain.TagKeys;
import org.sliceworkz.eventstore.benchmark.domain.WebshopContext;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventFilterItem;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The read side of the workload catalogue.
 *
 * <p>Every one of these consumes what it reads and returns the materialised result. That is the whole
 * discipline of this file: {@code query()} defers deserialization to the caller's terminal operation,
 * so a workload handing back an unconsumed {@code Stream} would time the SQL and skip the serde --
 * reporting perhaps a fifth of the real cost, with nothing to suggest anything was wrong.
 *
 * <p>Selectivity is treated as part of the workload rather than as a separate axis. A "tag query"
 * hitting ten events out of ten million and one hitting a hundred thousand are different questions
 * with different plans, and averaging them into one number would describe neither.
 */
public final class ReadWorkloads {

	/** A page of a stream: what a projector or a paging reader actually asks for. */
	public static final int PAGE_SIZE = 500;

	/** How many pages the cursor walk covers. Enough to leave the first page's cache behind. */
	private static final int CURSOR_WALK_PAGES = 5;

	private ReadWorkloads ( ) { }

	public static List<Workload> all ( ) {
		return List.of(
				streamPage(),
				byType(),
				byTagNeedle(),
				byTagSwathe(),
				byEntityHot(),
				byEntityCold(),
				byMultiTag(),
				byOrGroups(),
				lastEvent(),
				cursorWalk(),
				byId(),
				wildcard(),
				replay(),
				upcastingReplay(),
				shreddedPage(),
				sealedPageRaw());
	}

	/**
	 * A page of crm events read through a typed stream, so every sealed value is unsealed.
	 *
	 * <p>Paired with {@link #sealedPageRaw()}, which reads the very same events without decrypting
	 * them. That pairing is what makes the shredding cost measurable at all: the two differ in the
	 * unseal and in binding the result to a record, over identical bytes on an identical store, which
	 * is a far tighter comparison than any two corpora could give.
	 */
	private static Workload shreddedPage ( ) {
		return new Workload() {

			@Override
			public String name ( ) {
				return "query-crm-shredded";
			}

			@Override
			public String description ( ) {
				return "a page of crm events read typed, unsealing every protected value";
			}

			@Override
			public WorkloadRequirement requirement ( ) {
				return WorkloadRequirement.readOnlyOn(PayloadProfile.SHREDDED);
			}

			@Override
			public Object invoke ( WorkloadContext context ) {
				List<Event<CrmEvent>> page = context.crm()
						.query(EventQuery.matchAll().limit(PAGE_SIZE))
						.toList();

				// Touching the value is not ceremony. A Shreddable holds its plaintext once unsealed, and
				// the unseal happens during deserialization -- but reading it here is what stops a future
				// lazy implementation from turning this benchmark into a measurement of nothing.
				int seen = 0;
				for ( Event<CrmEvent> event : page ) {
					seen += switch ( event.data() ) {
						case CrmEvent.CustomerRegistered registered ->
							registered.details().toOptional().map(details -> details.fullName().length()).orElse(0);
						case CrmEvent.CustomerAddressChanged changed ->
							changed.address().toOptional().map(address -> address.city().length()).orElse(0);
						default -> 0;
					};
				}
				return seen;
			}
		};
	}

	/**
	 * The same page read raw, which by design does not decrypt.
	 *
	 * <p>The control for {@link #shreddedPage()}. Raw mode hands back the sealed envelope as stored, so
	 * the gap between the two is the unseal plus the record binding -- an upper bound on the unseal
	 * alone, and the honest way to state it.
	 */
	private static Workload sealedPageRaw ( ) {
		return new Workload() {

			@Override
			public String name ( ) {
				return "query-crm-raw";
			}

			@Override
			public String description ( ) {
				return "the same crm page read raw, which does not decrypt -- the control for the unseal cost";
			}

			@Override
			public WorkloadRequirement requirement ( ) {
				return WorkloadRequirement.readOnlyOn(PayloadProfile.SHREDDED);
			}

			@Override
			public Object invoke ( WorkloadContext context ) {
				return context.target().store()
						.getEventStream(EventStreamId.forContext(WebshopContext.CRM.streamContext()).anyPurpose())
						.query(EventQuery.matchAll().limit(PAGE_SIZE))
						.toList();
			}
		};
	}

	/** A plain page of the inventory stream: the cheapest possible read, and the baseline. */
	private static Workload streamPage ( ) {
		return simple("query-stream-page",
				"one page of 500 events from the inventory stream, unfiltered -- the read baseline",
				context -> context.inventory().query(EventQuery.matchAll().limit(PAGE_SIZE)).toList());
	}

	/**
	 * Filtered by event type only. On Postgres this can use the
	 * {@code (context, purpose, type, tx, position)} btree, so it is the case where the btree rather
	 * than the GIN index carries the query.
	 */
	private static Workload byType ( ) {
		return simple("query-by-type",
				"500 events of one type from inventory -- the btree path, no tags involved",
				context -> context.inventory()
						.query(EventQuery.forEvents(EventTypesFilter.of(InventoryEvent.StockReserved.class),
								Tags.none()).limit(PAGE_SIZE))
						.toList());
	}

	/**
	 * A tag matching about ten events in the whole store. An index scan is the only sensible plan, so
	 * this is close to the best case for tag filtering.
	 */
	private static Workload byTagNeedle ( ) {
		return simple("query-by-tag-needle",
				"a tag matching ~10 events store-wide -- the selective end of tag filtering",
				context -> context.inventory()
						.query(EventQuery.forEvents(EventTypesFilter.any(),
								Tags.of(CorpusGenerator.MARKER_TAG_KEY, context.facts().needleTagValue())))
						.toList());
	}

	/**
	 * A tag matching about one percent of the store -- where the planner starts weighing a sequential
	 * scan against the index, and where a single "tag query" number would be most misleading.
	 */
	private static Workload byTagSwathe ( ) {
		return simple("query-by-tag-swathe",
				"a tag matching ~1% of the store -- where the planner starts preferring a seq scan",
				context -> context.inventory()
						.query(EventQuery.forEvents(EventTypesFilter.any(),
								Tags.of(CorpusGenerator.MARKER_TAG_KEY, context.facts().swatheTagValue()))
								.limit(PAGE_SIZE))
						.toList());
	}

	/** One entity's whole history, for the busiest entity: the realistic worst case for a decider. */
	private static Workload byEntityHot ( ) {
		return simple("query-by-entity-hot",
				"the full history of the busiest SKU -- what loading a contended decision costs",
				context -> context.inventory()
						.query(EventQuery.forEvents(EventTypesFilter.any(),
								Tags.of(TagKeys.SKU, context.facts().hotEntity())))
						.toList());
	}

	/** The same read for an entity out in the tail, which is what most reads actually look like. */
	private static Workload byEntityCold ( ) {
		return simple("query-by-entity-cold",
				"the full history of a long-tail SKU -- what loading a typical decision costs",
				context -> context.inventory()
						.query(EventQuery.forEvents(EventTypesFilter.any(),
								Tags.of(TagKeys.SKU, context.facts().coldEntity())))
						.toList());
	}

	/**
	 * Several tags in one filter item, which is a single {@code @>} containment test rather than
	 * several -- so this measures how tag <em>count</em> affects one index probe.
	 */
	private static Workload byMultiTag ( ) {
		return simple("query-by-multi-tag",
				"three tags AND-ed in one filter item -- one containment test over a wider array",
				context -> context.inventory()
						.query(EventQuery.forEvents(EventTypesFilter.any(),
								Tags.of(
										org.sliceworkz.eventstore.events.Tag.of(TagKeys.SKU, context.facts().hotEntity()),
										org.sliceworkz.eventstore.events.Tag.of(TagKeys.CHANNEL, "web"),
										org.sliceworkz.eventstore.events.Tag.of(TagKeys.COUNTRY, "BE")))
								.limit(PAGE_SIZE))
						.toList());
	}

	/**
	 * Five OR-ed filter items, which is the read-side shape of the multi-fact consistency check. Worth
	 * measuring on its own, because the generated SQL grows a disjunction per item and the planner has
	 * to decide whether one index scan per branch beats a single scan.
	 */
	private static Workload byOrGroups ( ) {
		return new Workload() {

			@Override
			public String name ( ) {
				return "query-by-or-groups";
			}

			@Override
			public String description ( ) {
				return "five OR-ed filter items -- the read shape of a multi-fact DCB decision";
			}

			@Override
			public WorkloadRequirement requirement ( ) {
				return WorkloadRequirement.readOnly();
			}

			@Override
			public Object invoke ( WorkloadContext context ) {
				return context.inventory().query(orGroupQuery(context, 5).limit(PAGE_SIZE)).toList();
			}
		};
	}

	/**
	 * A backwards query with limit 1: the savepoint probe, and the single most common read in an
	 * event-sourced application after a page. Cheap in principle, so it is a good detector of a store
	 * whose ordering cannot use an index.
	 */
	private static Workload lastEvent ( ) {
		return simple("query-last-event",
				"the most recent event for one SKU, backwards with limit 1 -- the savepoint probe",
				context -> context.inventory()
						.query(EventQuery.forEvents(EventTypesFilter.of(InventoryEvent.StockCounted.class),
								Tags.of(TagKeys.SKU, context.facts().hotEntity())).backwards().limit(1))
						.toList());
	}

	/**
	 * Five successive pages carrying a cursor, starting from the corpus's recorded midpoint.
	 *
	 * <p>Starting midway rather than at the beginning is deliberate: the first pages of a table are
	 * the ones every other benchmark has already pulled into cache, so a walk from position zero
	 * measures a warm buffer rather than a cursor.
	 */
	private static Workload cursorWalk ( ) {
		return simple("query-cursor-walk",
				"five successive cursor-carried pages from the corpus midpoint",
				context -> {
					EventReference cursor = startCursor(context);
					List<Event<InventoryEvent>> lastPage = List.of();
					int walked = 0;
					for ( int page = 0; page < CURSOR_WALK_PAGES; page++ ) {
						lastPage = context.inventory()
								.query(EventQuery.matchAll().limit(PAGE_SIZE), cursor)
								.toList();
						if ( lastPage.isEmpty() ) {
							break;
						}
						walked += lastPage.size();
						cursor = lastPage.getLast().reference();
					}
					return walked;
				});
	}

	/** Fetch by id: the one eager read on the interface, and a pure primary-key lookup. */
	private static Workload byId ( ) {
		return simple("query-by-id",
				"a single event fetched by id -- a primary key lookup, eager rather than lazy",
				context -> context.inventory().getEventById(EventId.of(context.facts().knownEventId())));
	}

	/**
	 * A page read through a wildcard stream, which drops the {@code stream_context} predicate
	 * entirely. Against a {@code MULTI_DOMAIN} corpus this is what a store-wide projection pays.
	 */
	private static Workload wildcard ( ) {
		return simple("query-wildcard",
				"a page from a stream scoped to no context -- what a store-wide reader pays",
				context -> {
					EventStream<Object> raw = context.target().store()
							.getEventStream(EventStreamId.anyContext().anyPurpose());
					return raw.query(EventQuery.matchAll().limit(PAGE_SIZE)).toList();
				});
	}

	/**
	 * A bounded projector run: what a read-model rebuild costs per batch.
	 *
	 * <p>Bounded rather than a full replay, because a JMH invocation has to be comparable to the ones
	 * around it and "replay the whole corpus" is a different duration at every volume. The full-replay
	 * number belongs to the load runner, which can afford it.
	 */
	private static Workload replay ( ) {
		return simple("replay-batches",
				"a projector run over ten batches of 500 -- the per-batch cost of a read-model rebuild",
				context -> {
					CountingProjection projection = new CountingProjection();
					Projector<InventoryEvent> projector = Projector.<InventoryEvent>newBuilder()
							.from(context.inventory())
							.towards(projection)
							.inBatchesOf(PAGE_SIZE)
							.build();
					// runUntil, not run(): a full replay at the large tier is minutes, which is not an
					// invocation. The cursor bounds it to a fixed, comparable amount of work.
					EventReference until = boundedReplayLimit(context);
					if ( until == null ) {
						throw new IllegalStateException(
								"the corpus records no replay bound, so this run would replay everything");
					}
					projector.runUntil(until);
					return projection.handled;
				});
	}

	/**
	 * The same read through upcasters. Only meaningful against a LEGACY corpus -- against a current
	 * one it would read current events and report an upcasting cost of zero, which reads as good news.
	 */
	private static Workload upcastingReplay ( ) {
		return new Workload() {

			@Override
			public String name ( ) {
				return "query-upcasting";
			}

			@Override
			public String description ( ) {
				return "a page of legacy sales events read through their upcasters";
			}

			@Override
			public WorkloadRequirement requirement ( ) {
				return WorkloadRequirement.readOnlyOn(PayloadProfile.LEGACY);
			}

			@Override
			public Object invoke ( WorkloadContext context ) {
				EventStream<SalesEvent> stream = context.target().store().getEventStream(
						EventStreamId.forContext(WebshopContext.SALES.streamContext()).anyPurpose(),
						SalesEvent.class, LegacySalesEvent.class);
				// note: limit counts *stored* events, and a BasketCheckedOut upcasts into two, so this
				// returns more than PAGE_SIZE. That is the documented behaviour, not a miscount.
				return stream.query(EventQuery.matchAll().limit(PAGE_SIZE)).toList();
			}
		};
	}

	/* ---------------------------------------------------------------- helpers */

	/** An {@code EventQuery} of n OR-ed filter items, each naming a different entity. */
	static EventQuery orGroupQuery ( WorkloadContext context, int groups ) {
		List<EventFilterItem> items = new ArrayList<>(groups);
		for ( int i = 0; i < groups; i++ ) {
			items.add(new EventFilterItem(
					EventTypesFilter.of(InventoryEvent.StockReserved.class, InventoryEvent.StockPicked.class),
					Tags.of(TagKeys.SKU, "SKU-%06d".formatted(i % context.spec().entityCount()))));
		}
		return new EventQuery(new EventFilter(items, null), EventQuery.Direction.FORWARD,
				org.sliceworkz.eventstore.query.Limit.none());
	}

	/**
	 * Where the cursor walk starts: a real reference recorded by the provisioner at the midpoint of the
	 * inventory context.
	 *
	 * <p>It has to be a real one. Boundaries compare the whole {@code (tx, position)} tuple, so a
	 * reference fabricated from a position and a zero {@code tx} sorts before every stored event and
	 * the walk quietly starts at the beginning -- over exactly the pages every other read workload has
	 * already pulled into cache, which is the one thing this workload exists to avoid.
	 */
	private static EventReference startCursor ( WorkloadContext context ) {
		return context.facts().midCursor().orElse(null);
	}

	/**
	 * Where the bounded replay stops.
	 *
	 * <p>Also a real reference, for the mirror-image reason: a synthetic one carrying
	 * {@code tx = Long.MAX_VALUE} is greater than every stored event, so it bounds nothing and the
	 * "ten batches" replay walks the whole corpus. Measured on a 20k corpus it processed 11121 events
	 * instead of 5000 -- which at the large tier would be minutes inside a single JMH invocation.
	 */
	private static EventReference boundedReplayLimit ( WorkloadContext context ) {
		return context.facts().replayUntil().orElse(null);
	}

	private static Workload simple ( String name, String description,
			java.util.function.Function<WorkloadContext, Object> body ) {
		return new Workload() {

			@Override
			public String name ( ) {
				return name;
			}

			@Override
			public String description ( ) {
				return description;
			}

			@Override
			public WorkloadRequirement requirement ( ) {
				return WorkloadRequirement.readOnly();
			}

			@Override
			public Object invoke ( WorkloadContext context ) {
				return body.apply(context);
			}
		};
	}

	/** A projection that does nothing but count, so a replay measures the store rather than the model. */
	private static final class CountingProjection implements Projection<InventoryEvent> {

		private long handled;

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		public void when ( Event<InventoryEvent> event ) {
			handled++;
		}
	}
}
