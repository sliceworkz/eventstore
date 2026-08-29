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
package org.sliceworkz.eventstore.benchmark.corpus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec.PayloadProfile;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec.StreamDesign;
import org.sliceworkz.eventstore.benchmark.domain.Address;
import org.sliceworkz.eventstore.benchmark.domain.CatalogEvent;
import org.sliceworkz.eventstore.benchmark.domain.ContactDetails;
import org.sliceworkz.eventstore.benchmark.domain.CrmEvent;
import org.sliceworkz.eventstore.benchmark.domain.InventoryEvent;
import org.sliceworkz.eventstore.benchmark.domain.LegacySalesEvent;
import org.sliceworkz.eventstore.benchmark.domain.Money;
import org.sliceworkz.eventstore.benchmark.domain.OrderLine;
import org.sliceworkz.eventstore.benchmark.domain.PaymentEvent;
import org.sliceworkz.eventstore.benchmark.domain.SalesEvent;
import org.sliceworkz.eventstore.benchmark.domain.ShippingEvent;
import org.sliceworkz.eventstore.benchmark.domain.TagKeys;
import org.sliceworkz.eventstore.benchmark.domain.WebshopContext;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the events a {@link CorpusSpec} describes into a store.
 *
 * <p><b>Everything goes in through {@code importEvents}, not {@code append}.</b> Three reasons, in
 * ascending order of how badly the alternative fails:
 *
 * <ol>
 *   <li>It is the bulk path -- the Postgres backend chunks 5000 rows per statement inside one
 *       transaction, which is what makes ten million events minutes rather than hours.</li>
 *   <li>It takes the event id and timestamp explicitly, which is what lets a corpus be
 *       <em>deterministic</em>: the same spec provisioned twice produces the same store, so reusing
 *       one is a checkable claim rather than a hope.</li>
 *   <li>It is the only way to write a legacy event at all. A {@code @LegacyEvent} type cannot be
 *       registered as a current root, and a raw stream rejects the append too.</li>
 * </ol>
 *
 * <p>The one exception is {@link PayloadProfile#SHREDDED}, whose sealed envelopes only the store's
 * own serializer can produce -- hand-writing the ciphertext is not on. That profile goes through
 * {@code append} instead, and pays for it twice: appends are far slower than a bulk import, and the
 * store assigns the ids and timestamps, so a shredded corpus is <b>reproducible in content but not
 * byte-identical</b> across provisionings. Its events are the same events in the same order carrying
 * the same tags; their ids and timestamps are not the same. That is enough for every workload here --
 * none of them depends on a particular id, and the one that needs a known id reads it back -- but it
 * is a real difference from the imported profiles and the reason the large tier is not for this one.
 *
 * <p><b>The payload JSON is produced by an identically configured mapper</b> to the one the store
 * reads with: a plain {@code JsonMapper} with {@code FAIL_ON_UNKNOWN_PROPERTIES} enabled. That is a
 * coupling, and the provisioner guards it by reading a sample of every corpus back through a typed
 * stream before declaring it usable -- a shape mismatch here would otherwise make every read fail
 * long after provisioning finished.
 */
public final class CorpusGenerator {

	private static final Logger LOGGER = LoggerFactory.getLogger(CorpusGenerator.class);

	/** Rows per {@code importEvents} call. Below the backend's own 5000-row statement chunking. */
	private static final int BATCH_SIZE = 2_000;

	/**
	 * How the volume under test is split. Inventory carries more than sales because a single order
	 * moves stock for several lines, which is also what makes the inventory boundary the contended one.
	 */
	private static final double SHARE_INVENTORY = 0.55d;

	/**
	 * How a {@link PayloadProfile#SHREDDED} corpus splits its volume. The crm context joins the two
	 * under test, because it is the only one holding personal data and a shredded corpus with no
	 * shredded values in the contexts anything reads would measure nothing.
	 */
	private static final double SHREDDED_SHARE_INVENTORY = 0.45d;
	private static final double SHREDDED_SHARE_CRM = 0.20d;

	/** How the noise volume is split across the contexts nothing measures. */
	private static final Map<WebshopContext, Double> NOISE_SHARES = Map.of(
			WebshopContext.CATALOG, 0.55d,
			WebshopContext.PAYMENTS, 0.25d,
			WebshopContext.SHIPPING, 0.15d,
			WebshopContext.CRM, 0.05d);

	/**
	 * The same shares with crm's given to catalog, for a shredded corpus.
	 *
	 * <p>Under {@code SHREDDED} the crm context is under test, and letting it also be noise would give
	 * it two populations with different payloads and make its recorded facts describe neither.
	 */
	private static final Map<WebshopContext, Double> SHREDDED_NOISE_SHARES = Map.of(
			WebshopContext.CATALOG, 0.60d,
			WebshopContext.PAYMENTS, 0.25d,
			WebshopContext.SHIPPING, 0.15d);

	/** Events per {@code append} call on the shredded path. Well below any wire-parameter ceiling. */
	private static final int APPEND_BATCH_SIZE = 500;

	/** Marker tag key carrying the needle and swathe values, kept apart from the domain's own tags. */
	public static final String MARKER_TAG_KEY = TagKeys.CAMPAIGN;

	public static final String NEEDLE_VALUE = "needle";
	public static final String SWATHE_VALUE = "swathe";

	private static final List<String> CHANNELS = List.of("web", "app", "phone");
	private static final List<String> COUNTRIES = List.of("BE", "NL", "DE", "FR");
	private static final List<String> WAREHOUSES = List.of("WH-1", "WH-2", "WH-3");

	/**
	 * Order lines on a FAT order. Forty lines of nested records renders to roughly 3.2 KB, and with the
	 * mix biased towards orders the profile measures out at about 2.9 KB mean against 127 bytes for
	 * REALISTIC -- the numbers the provisioner records per corpus rather than ones asserted here.
	 */
	private static final int FAT_ORDER_LINES = 40;

	private final JsonMapper mapper = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	private final CorpusSpec spec;
	private final DeterministicIds ids;
	private final EntityDistribution entities;

	public CorpusGenerator ( CorpusSpec spec ) {
		this.spec = spec;
		this.ids = new DeterministicIds(spec.seed());
		this.entities = new EntityDistribution(spec.entityCount());
	}

	/**
	 * Writes the corpus into {@code storage} and returns what a workload needs to know about it.
	 *
	 * @param progress called with the running event count, for a long provisioning run
	 */
	public CorpusFacts generateInto ( EventStorage storage, LongConsumer progress ) {
		if ( spec.payload() == PayloadProfile.SHREDDED ) {
			throw new UnsupportedOperationException(
					"a SHREDDED corpus cannot be bulk-imported: its sealed envelopes can only be produced by the store's own serializer. Use the EventStore overload of generateInto, which appends.");
		}

		long started = System.nanoTime();
		Counters counters = new Counters();
		List<EventToImport> batch = new ArrayList<>(BATCH_SIZE);
		long sequence = 0;

		long underTest = spec.volume();
		long inventoryEvents = Math.round(underTest * SHARE_INVENTORY);
		long salesEvents = underTest - inventoryEvents;

		// Markers go to the inventory context only, because that is the stream the marker workloads
		// query -- spread across sales too, the recorded counts described the store while the reads
		// saw only inventory's share of them. Sized against the whole under-test volume, so the
		// swathe stays one percent of the *store*; see MarkerPlacement.
		MarkerPlacement markers = MarkerPlacement.over(inventoryEvents, underTest);

		sequence = generateContext(storage, WebshopContext.INVENTORY, inventoryEvents, sequence, batch,
				counters, markers, progress);
		sequence = generateContext(storage, WebshopContext.SALES, salesEvents, sequence, batch,
				counters, MarkerPlacement.none(), progress);

		if ( spec.hasNoiseContexts() ) {
			long noise = underTest * CorpusSpec.NOISE_MULTIPLIER;
			for ( Map.Entry<WebshopContext, Double> entry : orderedNoiseShares() ) {
				long count = Math.round(noise * entry.getValue());
				// noise is never read by a measured workload, so it carries no marker tags: giving it
				// any would dilute the needle and swathe counts the facts promise
				sequence = generateContext(storage, entry.getKey(), count, sequence, batch,
						counters, MarkerPlacement.none(), progress);
			}
		}

		flush(storage, batch, progress, counters);

		Duration took = Duration.ofNanos(System.nanoTime() - started);
		LOGGER.info("generated {} events in {}ms", counters.total, took.toMillis());

		return buildFacts(counters);
	}

	/**
	 * Writes the corpus through {@code append}, which is the only way to produce sealed values.
	 *
	 * <p>Reached only for {@link PayloadProfile#SHREDDED}; every other profile takes the bulk import
	 * path, which is an order of magnitude faster and fully deterministic. See the class comment for
	 * what that costs here.
	 *
	 * @param store the store to append through -- an {@code EventStorage} is not enough, since sealing
	 *        happens in the serde the store owns and needs the codec configured on it
	 */
	public CorpusFacts generateByAppending ( EventStore store, LongConsumer progress ) {
		long started = System.nanoTime();
		Counters counters = new Counters();

		long underTest = spec.volume();
		long inventoryEvents = Math.round(underTest * SHREDDED_SHARE_INVENTORY);
		long crmEvents = Math.round(underTest * SHREDDED_SHARE_CRM);
		long salesEvents = underTest - inventoryEvents - crmEvents;
		// inventory-only, as on the import path -- see the comment there and on MarkerPlacement
		MarkerPlacement markers = MarkerPlacement.over(inventoryEvents, underTest);

		Map<WebshopContext, ContextWriter<?>> writers = new LinkedHashMap<>();
		long sequence = 0;
		sequence = appendContext(store, writers, WebshopContext.INVENTORY, inventoryEvents, sequence,
				counters, markers, progress);
		sequence = appendContext(store, writers, WebshopContext.SALES, salesEvents, sequence,
				counters, MarkerPlacement.none(), progress);
		sequence = appendContext(store, writers, WebshopContext.CRM, crmEvents, sequence,
				counters, MarkerPlacement.none(), progress);

		if ( spec.hasNoiseContexts() ) {
			long noise = underTest * CorpusSpec.NOISE_MULTIPLIER;
			for ( WebshopContext context : List.of(WebshopContext.CATALOG, WebshopContext.PAYMENTS,
					WebshopContext.SHIPPING) ) {
				long count = Math.round(noise * SHREDDED_NOISE_SHARES.get(context));
				sequence = appendContext(store, writers, context, count, sequence, counters,
						MarkerPlacement.none(), progress);
			}
		}

		writers.values().forEach(writer -> drain(writer, counters, progress));

		Duration took = Duration.ofNanos(System.nanoTime() - started);
		LOGGER.info("appended {} events in {}ms", counters.total, took.toMillis());
		return buildFacts(counters);
	}

	private long appendContext ( EventStore store, Map<WebshopContext, ContextWriter<?>> writers,
			WebshopContext context, long count, long startSequence, Counters counters,
			MarkerPlacement markers, LongConsumer progress ) {
		ContextWriter<?> writer = writers.computeIfAbsent(context, ctx -> ContextWriter.open(store, ctx));
		long sequence = startSequence;

		for ( long i = 0; i < count; i++ ) {
			SplittableRandom random = new SplittableRandom(ids.streamSeedFor(sequence));
			String entityId = entityIdFor(context, entities.next(random));

			long markableIndex = counters.markable;
			boolean needle = markers.isNeedle(markableIndex);
			boolean swathe = markers.isSwathe(markableIndex);

			writer.add(streamIdFor(context, entityId), payloadFor(context, entityId, random),
					tagsFor(context, entityId, random, needle, swathe));

			counters.record(context, entityId, needle, swathe, isUnderTest(context));
			sequence++;

			if ( writer.pendingCount() >= APPEND_BATCH_SIZE ) {
				drain(writer, counters, progress);
			}
		}
		return sequence;
	}

	/**
	 * Flushes a writer's pending events and takes the facts only the store can supply.
	 *
	 * <p>The known event id is one of them. On the import path the generator chooses ids, so it knows
	 * one; here the store assigns them, and a fact naming an id nothing stored would send the
	 * {@code query-by-id} workload looking for an event that does not exist -- fast, successful and
	 * measuring nothing.
	 */
	private void drain ( ContextWriter<?> writer, Counters counters, LongConsumer progress ) {
		for ( Event<?> written : writer.flush() ) {
			if ( counters.firstIdUnderTest == null && writer.context == WebshopContext.INVENTORY ) {
				counters.firstIdUnderTest = written.reference().id();
			}
		}
		if ( progress != null ) {
			progress.accept(counters.total);
		}
	}

	/**
	 * One context's typed stream, with the events waiting to go into it.
	 *
	 * <p>Bound to an {@code anyPurpose} stream and appending with an explicit target, so one writer
	 * serves both stream designs: under {@code TAGGED} every event goes to the context's default
	 * purpose, under {@code PER_ENTITY} each goes to its entity's own.
	 *
	 * <p>Events are grouped by target stream because an append call writes to one stream. Under
	 * {@code PER_ENTITY} that makes the batches small -- one per entity -- which is a cost of the
	 * design rather than of this code.
	 */
	private static final class ContextWriter<T> {

		private final WebshopContext context;
		private final Class<T> root;
		private final EventStream<T> stream;
		private final Map<EventStreamId, List<EphemeralEvent<? extends T>>> pending = new LinkedHashMap<>();
		private int pendingCount;

		private ContextWriter ( WebshopContext context, Class<T> root, EventStream<T> stream ) {
			this.context = context;
			this.root = root;
			this.stream = stream;
		}

		static ContextWriter<?> open ( EventStore store, WebshopContext context ) {
			return switch ( context ) {
				case INVENTORY -> of(store, context, InventoryEvent.class);
				case SALES -> of(store, context, SalesEvent.class);
				case PAYMENTS -> of(store, context, PaymentEvent.class);
				case SHIPPING -> of(store, context, ShippingEvent.class);
				case CATALOG -> of(store, context, CatalogEvent.class);
				case CRM -> of(store, context, CrmEvent.class);
			};
		}

		private static <T> ContextWriter<T> of ( EventStore store, WebshopContext context, Class<T> root ) {
			return new ContextWriter<>(context, root,
					store.getEventStream(EventStreamId.forContext(context.streamContext()).anyPurpose(), root));
		}

		void add ( EventStreamId target, Object payload, Tags tags ) {
			pending.computeIfAbsent(target, key -> new ArrayList<>()).add(Event.of(root.cast(payload), tags));
			pendingCount++;
		}

		int pendingCount ( ) {
			return pendingCount;
		}

		List<Event<T>> flush ( ) {
			if ( pending.isEmpty() ) {
				return List.of();
			}
			List<Event<T>> written = new ArrayList<>(pendingCount);
			pending.forEach(( target, events ) ->
					written.addAll(stream.append(AppendCriteria.none(), events, target)));
			pending.clear();
			pendingCount = 0;
			return written;
		}
	}

	/**
	 * Whether a context is one the measured workloads read. Only these carry marker tags, and only
	 * these contribute to the hot/cold entity facts.
	 *
	 * <p>Under {@code SHREDDED} the crm context joins them, because that is where the sealed values
	 * are and a workload has to read them for the profile to measure anything.
	 */
	private boolean isUnderTest ( WebshopContext context ) {
		return context == WebshopContext.INVENTORY || context == WebshopContext.SALES
				|| ( spec.payload() == PayloadProfile.SHREDDED && context == WebshopContext.CRM );
	}

	/** Noise contexts in a fixed order, so generation order -- and thus physical order -- is stable. */
	private List<Map.Entry<WebshopContext, Double>> orderedNoiseShares ( ) {
		List<Map.Entry<WebshopContext, Double>> ordered = new ArrayList<>();
		for ( WebshopContext context : List.of(WebshopContext.CATALOG, WebshopContext.PAYMENTS,
				WebshopContext.SHIPPING, WebshopContext.CRM) ) {
			ordered.add(Map.entry(context, NOISE_SHARES.get(context)));
		}
		return ordered;
	}

	private long generateContext ( EventStorage storage, WebshopContext context, long count, long startSequence,
			List<EventToImport> batch, Counters counters, MarkerPlacement markers, LongConsumer progress ) {
		long sequence = startSequence;
		for ( long i = 0; i < count; i++ ) {
			SplittableRandom random = new SplittableRandom(ids.streamSeedFor(sequence));
			int entityIndex = entities.next(random);
			String entityId = entityIdFor(context, entityIndex);

			long markableIndex = counters.markable;
			boolean needle = markers.isNeedle(markableIndex);
			boolean swathe = markers.isSwathe(markableIndex);

			Object payload = payloadFor(context, entityId, random);
			Tags tags = tagsFor(context, entityId, random, needle, swathe);

			batch.add(new EventToImport(
					streamIdFor(context, entityId),
					EventType.of(payload.getClass()),
					ids.idOf(sequence),
					mapper.writeValueAsString(payload),
					null,
					tags,
					ids.timestampOf(sequence),
					null));

			// on this path the generator chooses the ids, so the id in the batch is the id stored
			if ( counters.firstIdUnderTest == null && context == WebshopContext.INVENTORY ) {
				counters.firstIdUnderTest = ids.idOf(sequence);
			}
			counters.record(context, entityId, needle, swathe, isUnderTest(context));
			sequence++;

			if ( batch.size() >= BATCH_SIZE ) {
				flush(storage, batch, progress, counters);
			}
		}
		return sequence;
	}

	private void flush ( EventStorage storage, List<EventToImport> batch, LongConsumer progress, Counters counters ) {
		if ( batch.isEmpty() ) {
			return;
		}
		storage.importEvents(List.copyOf(batch), EventStorage.ImportMode.FAIL_ON_EXISTING_ID);
		batch.clear();
		if ( progress != null ) {
			progress.accept(counters.total);
		}
	}

	/**
	 * Under {@code TAGGED} every context is one stream and entities are told apart by tags; under
	 * {@code PER_ENTITY} the entity id becomes the stream purpose. This one branch is the whole
	 * difference between the two designs the suite compares.
	 */
	private EventStreamId streamIdFor ( WebshopContext context, String entityId ) {
		return spec.streamDesign() == StreamDesign.PER_ENTITY
				? EventStreamId.forContext(context.streamContext()).withPurpose(entityId)
				: EventStreamId.forContext(context.streamContext());
	}

	private String entityIdFor ( WebshopContext context, int index ) {
		return switch ( context ) {
			case INVENTORY, CATALOG -> "SKU-%06d".formatted(index);
			case SALES -> "ORD-%06d".formatted(index);
			case PAYMENTS -> "PAY-%06d".formatted(index);
			case SHIPPING -> "SHP-%06d".formatted(index);
			case CRM -> "CUST-%06d".formatted(index);
		};
	}

	private Tags tagsFor ( WebshopContext context, String entityId, SplittableRandom random,
			boolean needle, boolean swathe ) {
		List<Tag> tags = new ArrayList<>(12);
		tags.add(Tag.of(context.entityTagKey(), entityId));

		if ( spec.payload() != PayloadProfile.SLIM ) {
			tags.add(Tag.of(TagKeys.CHANNEL, pick(CHANNELS, random)));
			tags.add(Tag.of(TagKeys.COUNTRY, pick(COUNTRIES, random)));
			tags.add(Tag.of(TagKeys.WAREHOUSE, pick(WAREHOUSES, random)));
		}
		if ( spec.payload() == PayloadProfile.WIDE_TAGS ) {
			tags.add(Tag.of(TagKeys.SEGMENT, "seg-%d".formatted(random.nextInt(8))));
			tags.add(Tag.of(TagKeys.EXPERIMENT, "exp-%d".formatted(random.nextInt(12))));
			tags.add(Tag.of(TagKeys.CATEGORY, "cat-%d".formatted(random.nextInt(20))));
			tags.add(Tag.of(TagKeys.CARRIER, "car-%d".formatted(random.nextInt(5))));
			tags.add(Tag.of(TagKeys.COUPON, "cpn-%d".formatted(random.nextInt(50))));
			tags.add(Tag.of(TagKeys.BASKET, "BSK-%06d".formatted(random.nextInt(spec.entityCount()))));
			tags.add(Tag.of(TagKeys.CUSTOMER, "CUST-%06d".formatted(random.nextInt(spec.entityCount()))));
		}
		if ( needle ) {
			tags.add(Tag.of(MARKER_TAG_KEY, NEEDLE_VALUE));
		} else if ( swathe ) {
			tags.add(Tag.of(MARKER_TAG_KEY, SWATHE_VALUE));
		}
		return Tags.of(tags.toArray(new Tag[0]));
	}

	private Object payloadFor ( WebshopContext context, String entityId, SplittableRandom random ) {
		if ( spec.payload() == PayloadProfile.LEGACY && context == WebshopContext.SALES ) {
			// half of each, so both the 1:1 and the 1:many upcaster are exercised by a read
			return random.nextBoolean()
					? new LegacySalesEvent.OrderPlacedV1(entityId, "BSK-" + entityId, "CUST-" + entityId,
							random.nextLong(500, 50_000))
					: new LegacySalesEvent.BasketCheckedOut(entityId, "BSK-" + entityId, "CUST-" + entityId,
							random.nextLong(500, 50_000), "CPN-%d".formatted(random.nextInt(50)),
							random.nextLong(0, 500));
		}

		return switch ( context ) {
			case INVENTORY -> inventoryPayload(entityId, random);
			case SALES -> salesPayload(entityId, random);
			case PAYMENTS -> paymentPayload(entityId, random);
			case SHIPPING -> shippingPayload(entityId, random);
			case CATALOG -> catalogPayload(entityId, random);
			case CRM -> crmPayload(entityId, random);
		};
	}

	/**
	 * Customer events, with personal data only where the store can seal it.
	 *
	 * <p>On the import path the two {@code Shreddable}-carrying types are unreachable: their sealed
	 * envelopes come out of the store's own serializer and this generator writes JSON directly. So an
	 * imported corpus's crm context holds newsletter events, which carry none, and a
	 * {@code SHREDDED} corpus -- which goes through appends -- holds the two that do.
	 */
	private Object crmPayload ( String customerId, SplittableRandom random ) {
		if ( spec.payload() != PayloadProfile.SHREDDED ) {
			return random.nextBoolean()
					? new CrmEvent.NewsletterSubscribed(customerId, "offers")
					: new CrmEvent.NewsletterUnsubscribed(customerId, "offers", "too frequent");
		}

		DataSubject subject = DataSubject.of("customer", customerId);
		Address address = new Address("Meir", String.valueOf(random.nextInt(1, 400)),
				"%04d".formatted(random.nextInt(1000, 9999)), "Antwerpen", pick(COUNTRIES, random));

		// Two thirds registrations, so most reads unseal the larger of the two values. Both types are
		// present because a store holding one shape of sealed value would not exercise the case the
		// design exists for -- a subject's data spread over several events.
		return random.nextInt(3) == 0
				? new CrmEvent.CustomerAddressChanged(customerId, Shreddable.of(address, subject))
				: new CrmEvent.CustomerRegistered(customerId,
						Shreddable.of(new ContactDetails("Customer " + customerId,
								customerId.toLowerCase() + "@example.invalid",
								"+32 3 %03d %02d %02d".formatted(random.nextInt(1000), random.nextInt(100),
										random.nextInt(100)),
								address), subject),
						"seg-%d".formatted(random.nextInt(8)));
	}

	private Object inventoryPayload ( String sku, SplittableRandom random ) {
		int roll = random.nextInt(100);
		if ( roll < 10 ) {
			return new InventoryEvent.StockReceived(sku, random.nextInt(10, 500), pick(WAREHOUSES, random),
					Money.euro(random.nextLong(50, 20_000)));
		}
		if ( roll < 60 ) {
			return new InventoryEvent.StockReserved(sku, random.nextInt(1, 5), "ORD-%06d".formatted(random.nextInt(spec.entityCount())));
		}
		if ( roll < 75 ) {
			return new InventoryEvent.StockReleased(sku, random.nextInt(1, 5),
					"ORD-%06d".formatted(random.nextInt(spec.entityCount())), "basket expired");
		}
		if ( roll < 97 ) {
			return new InventoryEvent.StockPicked(sku, random.nextInt(1, 5),
					"ORD-%06d".formatted(random.nextInt(spec.entityCount())), pick(WAREHOUSES, random));
		}
		// the savepoint event, rare by nature: a physical count happens far less often than a movement
		return new InventoryEvent.StockCounted(sku, random.nextInt(0, 1_000), pick(WAREHOUSES, random));
	}

	private Object salesPayload ( String orderId, SplittableRandom random ) {
		String basketId = "BSK-" + orderId.substring(4);
		String customerId = "CUST-%06d".formatted(random.nextInt(spec.entityCount()));
		int roll = random.nextInt(100);

		// FAT biases the mix towards orders as well as making each one large.  Without the bias the
		// profile barely moved the mean: a forty-line order is ~3.2 KB, but orders were only a quarter
		// of sales traffic, so the average stayed near the small events that dominate it.  Measured, a
		// realistic corpus came out at 127 bytes and a "fat" one at 656 -- a name doing no work.
		if ( spec.payload() == PayloadProfile.FAT ) {
			roll = roll < 85 ? 80 : roll;
		}

		if ( roll < 15 ) {
			return new SalesEvent.BasketStarted(basketId, customerId, pick(CHANNELS, random));
		}
		if ( roll < 55 ) {
			return new SalesEvent.ItemAddedToBasket(basketId, "SKU-%06d".formatted(random.nextInt(spec.entityCount())),
					random.nextInt(1, 4), Money.euro(random.nextLong(100, 15_000)));
		}
		if ( roll < 65 ) {
			return new SalesEvent.ItemRemovedFromBasket(basketId,
					"SKU-%06d".formatted(random.nextInt(spec.entityCount())), 1);
		}
		if ( roll < 72 ) {
			return new SalesEvent.CouponApplied(basketId, "CPN-%d".formatted(random.nextInt(50)),
					Money.euro(-random.nextLong(50, 1_000)));
		}
		if ( roll < 96 ) {
			// FAT is a genuinely large order rather than a padded string: forty lines of nested records
			// is about 4 KB of JSON, which is a shape the serializer and TOAST both see in real life.
			// Padding an identifier would have produced the same byte count and measured nothing real.
			int lineCount = spec.payload() == PayloadProfile.FAT
					? FAT_ORDER_LINES
					: random.nextInt(1, 5);
			List<OrderLine> lines = new ArrayList<>(lineCount);
			for ( int i = 0; i < lineCount; i++ ) {
				lines.add(new OrderLine("SKU-%06d".formatted(random.nextInt(spec.entityCount())),
						random.nextInt(1, 4), Money.euro(random.nextLong(100, 15_000))));
			}
			return new SalesEvent.OrderPlaced(orderId, basketId, customerId, lines,
					Money.euro(random.nextLong(500, 60_000)));
		}
		return new SalesEvent.OrderCancelled(orderId, "customer changed their mind");
	}

	private Object paymentPayload ( String paymentId, SplittableRandom random ) {
		String orderId = "ORD-%06d".formatted(random.nextInt(spec.entityCount()));
		Money amount = Money.euro(random.nextLong(500, 60_000));
		int roll = random.nextInt(100);
		if ( roll < 45 ) {
			return new PaymentEvent.PaymentAuthorized(paymentId, orderId, amount, "card");
		}
		if ( roll < 85 ) {
			return new PaymentEvent.PaymentCaptured(paymentId, orderId, amount);
		}
		if ( roll < 95 ) {
			return new PaymentEvent.PaymentRefunded(paymentId, orderId, amount, "returned");
		}
		return new PaymentEvent.PaymentFailed(paymentId, orderId, "insufficient funds");
	}

	private Object shippingPayload ( String shipmentId, SplittableRandom random ) {
		int roll = random.nextInt(100);
		if ( roll < 40 ) {
			return new ShippingEvent.ShipmentPlanned(shipmentId,
					"ORD-%06d".formatted(random.nextInt(spec.entityCount())), "PostNL",
					"Antwerpen", pick(COUNTRIES, random));
		}
		if ( roll < 75 ) {
			return new ShippingEvent.ShipmentDispatched(shipmentId, "3STBJG%09d".formatted(random.nextInt(1_000_000)));
		}
		return new ShippingEvent.ShipmentDelivered(shipmentId, "neighbour");
	}

	private Object catalogPayload ( String sku, SplittableRandom random ) {
		int roll = random.nextInt(100);
		if ( roll < 10 ) {
			return new CatalogEvent.ProductListed(sku, "Product " + sku, "cat-%d".formatted(random.nextInt(20)));
		}
		if ( roll < 97 ) {
			return new CatalogEvent.PriceChanged(sku, Money.euro(random.nextLong(100, 20_000)));
		}
		return new CatalogEvent.ProductDelisted(sku, "discontinued");
	}

	private static String pick ( List<String> values, SplittableRandom random ) {
		return values.get(random.nextInt(values.size()));
	}

	private CorpusFacts buildFacts ( Counters counters ) {
		Map<String, Long> counts = new LinkedHashMap<>();
		counts.put(CorpusFacts.COUNT_TOTAL, counters.total);
		counts.put(CorpusFacts.COUNT_NEEDLE, counters.needle);
		counts.put(CorpusFacts.COUNT_SWATHE, counters.swathe);

		String hot = counters.busiestUnderTest();
		String cold = counters.quietestUnderTest();
		counts.put(CorpusFacts.COUNT_HOT_ENTITY, counters.perEntity.getOrDefault(hot, 0L));
		counts.put(CorpusFacts.COUNT_COLD_ENTITY, counters.perEntity.getOrDefault(cold, 0L));

		// Positions are assigned by the storage, not by this generator, so the halfway *position* is
		// not knowable here.  The provisioner fills it in by querying the store afterwards; recording
		// a guess would produce a cursor that points at nothing.
		// midCursorPosition and meanPayloadBytes are both left null: positions are assigned by the
		// storage, and the payload size worth reporting is the one measured off what was actually
		// stored.  The provisioner fills both in after the write.
		return new CorpusFacts(hot, cold, NEEDLE_VALUE, SWATHE_VALUE, counts, null, null,
				counters.firstIdUnderTest == null ? null : counters.firstIdUnderTest.value(),
				List.copyOf(counters.purposes), null);
	}

	/** Running totals kept while generating, so the facts need no second pass over the data. */
	private final class Counters {

		long total;
		/** Events eligible to carry a marker, i.e. those in the contexts under test. */
		long markable;
		long needle;
		long swathe;
		EventId firstIdUnderTest;
		final Map<String, Long> perEntity = new HashMap<>();
		final java.util.SequencedSet<String> purposes = new java.util.LinkedHashSet<>();

		void record ( WebshopContext context, String entityId, boolean needleTagged, boolean swatheTagged,
				boolean underTest ) {
			total++;
			if ( underTest ) {
				markable++;
			}
			if ( needleTagged ) {
				needle++;
			}
			if ( swatheTagged ) {
				swathe++;
			}
			if ( context == WebshopContext.INVENTORY ) {
				// the hot and cold facts describe the contended boundary, which is inventory's
				perEntity.merge(entityId, 1L, Long::sum);
			}
			// firstIdUnderTest is deliberately NOT taken here. Which id an event actually got depends
			// on the path: the import path chooses ids itself and records the fact where it builds the
			// event, while on the append path the store assigns them and drain() reads the id off what
			// came back. Taking ids.idOf(sequence) unconditionally -- as this used to -- filled the
			// fact with an id the append path never used, so a SHREDDED corpus sent query-by-id
			// looking for an event that does not exist: fast, successful, and measuring nothing.
			if ( spec.streamDesign() == StreamDesign.PER_ENTITY && purposes.size() < 1_000 ) {
				// capped: a per-entity corpus can have a hundred thousand purposes and the facts are
				// meant to be a handful of values, not a copy of the data
				purposes.add(entityId);
			}
		}

		String busiestUnderTest ( ) {
			return perEntity.entrySet().stream()
					.max(Map.Entry.comparingByValue())
					.map(Map.Entry::getKey)
					.orElse(null);
		}

		String quietestUnderTest ( ) {
			return perEntity.entrySet().stream()
					.min(Map.Entry.comparingByValue())
					.map(Map.Entry::getKey)
					.orElse(null);
		}
	}
}
