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
package org.sliceworkz.eventstore.benchmark.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Metrics;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.MeterOptions;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.inmem.shredding.InMemoryShreddingKeyStore;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Writes one of every event type in the domain to an in-memory store, reads them back, and prints
 * what was stored.
 *
 * <p>This is not a benchmark and measures nothing. It is here because two of this domain's
 * properties are load-bearing for the whole suite and neither fails at compile time:
 *
 * <ol>
 *   <li><b>Every event type simple name is unique across all seven hierarchies.</b> Names are global
 *       to a storage, and the {@code multi-domain} composition deliberately puts six contexts in one
 *       table -- so a collision would have one context silently reading another's payloads. Across
 *       streams nothing catches that, at registration or at write time. This check does.</li>
 *   <li><b>Every payload round-trips.</b> A record that serializes but cannot be read back fails
 *       inside {@code append} (which deserializes what it just wrote in order to return it), with the
 *       event already stored. Better to find that here than three hours into a provisioning run.</li>
 * </ol>
 *
 * <p>Run it with {@code java -cp <jar> org.sliceworkz.eventstore.benchmark.domain.DomainSelfCheck}.
 * It exits non-zero if either property is violated.
 */
public final class DomainSelfCheck {

	private DomainSelfCheck ( ) { }

	public static void main ( String[] args ) {
		int problems = reportDuplicateEventTypeNames();
		problems += roundTripEveryContext();

		if ( problems > 0 ) {
			System.err.println();
			System.err.println("%d problem(s) found".formatted(problems));
			System.exit(1);
		}
		System.out.println();
		System.out.println("domain self-check passed");
	}

	/**
	 * Every sealed hierarchy in the domain, including the legacy one. The legacy hierarchy is part of
	 * the uniqueness check even though it is never registered alongside its current counterpart on the
	 * same stream, because it shares the same storage and therefore the same {@code event_type} space.
	 */
	private static List<Class<?>> allHierarchies ( ) {
		return List.of(InventoryEvent.class, SalesEvent.class, PaymentEvent.class,
				ShippingEvent.class, CrmEvent.class, CatalogEvent.class, LegacySalesEvent.class);
	}

	private static int reportDuplicateEventTypeNames ( ) {
		Map<String, List<String>> byName = new TreeMap<>();
		for ( Class<?> hierarchy : allHierarchies() ) {
			for ( Class<?> permitted : hierarchy.getPermittedSubclasses() ) {
				byName.computeIfAbsent(EventType.of(permitted).name(), k -> new java.util.ArrayList<>())
						.add(permitted.getName());
			}
		}

		System.out.println("%d event types across %d hierarchies".formatted(byName.size(), allHierarchies().size()));
		byName.forEach(( name, classes ) -> System.out.println("  %-24s %s".formatted(name, classes.getFirst())));

		List<Map.Entry<String, List<String>>> clashes = byName.entrySet().stream()
				.filter(e -> e.getValue().size() > 1)
				.toList();

		for ( Map.Entry<String, List<String>> clash : clashes ) {
			System.err.println("DUPLICATE event type name '%s' shared by %s".formatted(clash.getKey(),
					clash.getValue().stream().collect(Collectors.joining(", "))));
		}
		return clashes.size();
	}

	private static int roundTripEveryContext ( ) {
		int problems = 0;
		System.out.println();
		System.out.println("round-tripping one event of every type through an in-memory store");

		// The storage and the store are built separately because the legacy pass needs the storage
		// handle for importEvents.  Note the codec goes to the *factory*, not to the storage builder:
		// shredding is a property of the EventStore, and a store assembled without it rejects every
		// CrmEvent at getEventStream.  (buildStore() does exactly this, and hands back no storage.)
		ShreddingCodec codec = AesGcmShreddingCodec.over(new InMemoryShreddingKeyStore());
		try ( EventStorage storage = InMemoryEventStorage.newBuilder().build();
				EventStore store = EventStoreFactory.get()
						.eventStore(storage, Metrics.globalRegistry, MeterOptions.defaults(), codec) ) {

			problems += roundTrip(store, WebshopContext.INVENTORY, InventoryEvent.class, inventorySamples());
			problems += roundTrip(store, WebshopContext.SALES, SalesEvent.class, salesSamples());
			problems += roundTrip(store, WebshopContext.PAYMENTS, PaymentEvent.class, paymentSamples());
			problems += roundTrip(store, WebshopContext.SHIPPING, ShippingEvent.class, shippingSamples());
			problems += roundTrip(store, WebshopContext.CRM, CrmEvent.class, crmSamples());
			problems += roundTrip(store, WebshopContext.CATALOG, CatalogEvent.class, catalogSamples());

			problems += roundTripLegacy(store, storage);
		}
		return problems;
	}

	private static <T> int roundTrip ( EventStore store, WebshopContext context, Class<T> root, List<T> samples ) {
		EventStreamId id = EventStreamId.forContext(context.streamContext());
		EventStream<T> stream = store.getEventStream(id, root);

		for ( T sample : samples ) {
			stream.append(AppendCriteria.none(), Event.of(sample, Tags.of(context.entityTagKey(), "self-check")));
		}

		List<Event<T>> read = stream.query(EventQuery.matchAll()).toList();
		System.out.println("  %-10s wrote %d, read back %d".formatted(context.streamContext(), samples.size(), read.size()));
		read.forEach(e -> System.out.println("      %-24s %s".formatted(e.type().name(), e.data())));

		if ( read.size() != samples.size() ) {
			System.err.println("  %s: wrote %d but read back %d".formatted(context, samples.size(), read.size()));
			return 1;
		}
		return 0;
	}

	/**
	 * The legacy pass, which has to go in through the SPI.
	 *
	 * <p>There is no way to <em>write</em> a legacy event through a stream, and both refusals are
	 * deliberate. Registering {@code LegacySalesEvent} as a current root throws ("should not be
	 * annotated as a {@code @LegacyEvent}") because legacy types are a read path only; and a raw
	 * stream, which registers no types at all, rejects the append too ("cannot append event type
	 * 'OrderPlacedV1' via this stream"). {@code importEvents} is the only door, which is why the
	 * corpus generator produces legacy events that way rather than by appending them.
	 *
	 * <p>The payloads are hand-written JSON for the same reason: an import carries opaque JSON and a
	 * type name, needing no domain classes on the classpath. Spelling the JSON out here also pins the
	 * stored shape, so a change to the legacy records that would break reads of real history shows up
	 * as a failing self-check rather than as an unreadable store.
	 */
	private static int roundTripLegacy ( EventStore store, EventStorage storage ) {
		EventStreamId id = EventStreamId.forContext("sales").withPurpose("legacy");
		LocalDateTime when = LocalDateTime.of(2024, 1, 1, 12, 0);

		storage.importEvents(List.of(
				new EventToImport(id, EventType.of(LegacySalesEvent.OrderPlacedV1.class), EventId.create(),
						"""
						{"orderId":"o-1","basketId":"b-1","customerId":"c-1","totalCents":4200}""",
						null, Tags.of(TagKeys.ORDER, "o-1"), when, null),
				new EventToImport(id, EventType.of(LegacySalesEvent.BasketCheckedOut.class), EventId.create(),
						"""
						{"orderId":"o-2","basketId":"b-2","customerId":"c-2","totalCents":3300,\
						"couponCode":"SUMMER","discountCents":300}""",
						null, Tags.of(TagKeys.ORDER, "o-2"), when.plusSeconds(1), null)),
				EventStorage.ImportMode.FAIL_ON_EXISTING_ID);

		EventStream<SalesEvent> reader = store.getEventStream(id, SalesEvent.class, LegacySalesEvent.class);
		List<Event<SalesEvent>> upcasted = reader.query(EventQuery.matchAll()).toList();

		System.out.println("  %-10s imported 2 legacy events, read back %d upcasted".formatted("sales/legacy", upcasted.size()));
		upcasted.forEach(e -> System.out.println("      %-24s %s".formatted(e.type().name(), e.data())));

		// one stored event upcasts 1:1 and the other 1:many, so two stored events must yield three
		if ( upcasted.size() != 3 ) {
			System.err.println("  legacy: expected 2 stored events to upcast into 3, got %d".formatted(upcasted.size()));
			return 1;
		}
		return 0;
	}

	/* ---- one of every type, with values that exercise nesting, collections and sealed values ---- */

	private static List<InventoryEvent> inventorySamples ( ) {
		return List.of(
				new InventoryEvent.StockReceived("SKU-1", 100, "WH-1", Money.euro(250)),
				new InventoryEvent.StockReserved("SKU-1", 2, "o-1"),
				new InventoryEvent.StockReleased("SKU-1", 1, "o-1", "basket expired"),
				new InventoryEvent.StockPicked("SKU-1", 1, "o-1", "WH-1"),
				new InventoryEvent.StockCounted("SKU-1", 98, "WH-1"));
	}

	private static List<SalesEvent> salesSamples ( ) {
		return List.of(
				new SalesEvent.BasketStarted("b-1", "c-1", "web"),
				new SalesEvent.ItemAddedToBasket("b-1", "SKU-1", 2, Money.euro(499)),
				new SalesEvent.ItemRemovedFromBasket("b-1", "SKU-1", 1),
				new SalesEvent.CouponApplied("b-1", "SUMMER", Money.euro(-100)),
				new SalesEvent.OrderPlaced("o-1", "b-1", "c-1",
						List.of(new OrderLine("SKU-1", 1, Money.euro(499)), new OrderLine("SKU-2", 3, Money.euro(1250))),
						Money.euro(4249)),
				new SalesEvent.OrderCancelled("o-1", "customer changed their mind"));
	}

	private static List<PaymentEvent> paymentSamples ( ) {
		return List.of(
				new PaymentEvent.PaymentAuthorized("p-1", "o-1", Money.euro(4249), "card"),
				new PaymentEvent.PaymentCaptured("p-1", "o-1", Money.euro(4249)),
				new PaymentEvent.PaymentRefunded("p-1", "o-1", Money.euro(-4249), "order cancelled"),
				new PaymentEvent.PaymentFailed("p-2", "o-2", "insufficient funds"));
	}

	private static List<ShippingEvent> shippingSamples ( ) {
		return List.of(
				new ShippingEvent.ShipmentPlanned("s-1", "o-1", "PostNL", "Antwerpen", "BE"),
				new ShippingEvent.ShipmentDispatched("s-1", "3STBJG123456789"),
				new ShippingEvent.ShipmentDelivered("s-1", "neighbour at 44"));
	}

	private static List<CrmEvent> crmSamples ( ) {
		DataSubject subject = DataSubject.of(TagKeys.CUSTOMER, "c-1");
		Address address = new Address("Grote Markt", "1", "2000", "Antwerpen", "BE");
		return List.of(
				new CrmEvent.CustomerRegistered("c-1",
						Shreddable.of(new ContactDetails("Jan Peeters", "jan@example.invalid", "+3231234567", address), subject),
						"loyal"),
				new CrmEvent.CustomerAddressChanged("c-1", Shreddable.of(address, subject)),
				new CrmEvent.NewsletterSubscribed("c-1", "offers"),
				new CrmEvent.NewsletterUnsubscribed("c-1", "offers", "too frequent"));
	}

	private static List<CatalogEvent> catalogSamples ( ) {
		return List.of(
				new CatalogEvent.ProductListed("SKU-1", "Widget", "tools"),
				new CatalogEvent.PriceChanged("SKU-1", Money.euro(499)),
				new CatalogEvent.ProductDelisted("SKU-1", "discontinued"));
	}
}
