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
package org.sliceworkz.eventstore.testing.tck.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.stream.EventTypesFilterHierarchyTest.AuditEvent.AuditNoted;
import org.sliceworkz.eventstore.testing.tck.stream.EventTypesFilterHierarchyTest.ShopEvent.OrderEvent;
import org.sliceworkz.eventstore.testing.tck.stream.EventTypesFilterHierarchyTest.ShopEvent.OrderEvent.OrderPlaced;
import org.sliceworkz.eventstore.testing.tck.stream.EventTypesFilterHierarchyTest.ShopEvent.OrderEvent.OrderShipped;
import org.sliceworkz.eventstore.testing.tck.stream.EventTypesFilterHierarchyTest.ShopEvent.PaymentEvent;
import org.sliceworkz.eventstore.testing.tck.stream.EventTypesFilterHierarchyTest.ShopEvent.PaymentEvent.PaymentReceived;
import org.sliceworkz.eventstore.testing.tck.stream.EventTypesFilterHierarchyTest.ShopEvent.ShopClosed;

/**
 * A sealed interface in a type filter stands for every event type under it, on every path a filter
 * takes: a query, a projection's query, the optimistic-locking check of an append, and a query whose
 * types have legacy events upcasting into them. The root of a hierarchy names all of it and a nested
 * interface names its own branch -- and neither is "any type": an event of another hierarchy on the
 * same stream is outside both.
 * <p>
 * The hierarchy is deliberately mixed: two nested interfaces and one record directly under the root,
 * so that every shape a sealed hierarchy takes is covered.
 */
public class EventTypesFilterHierarchyTest extends AbstractEventStoreTest {

	public sealed interface ShopEvent {

		sealed interface OrderEvent extends ShopEvent {
			record OrderPlaced ( String orderId ) implements OrderEvent { }
			record OrderShipped ( String orderId ) implements OrderEvent { }
		}

		sealed interface PaymentEvent extends ShopEvent {
			record PaymentReceived ( String orderId ) implements PaymentEvent { }
		}

		record ShopClosed ( ) implements ShopEvent { }
	}

	/** A second hierarchy on the same stream, outside every filter built from {@link ShopEvent}. */
	public sealed interface AuditEvent {
		record AuditNoted ( String note ) implements AuditEvent { }
	}

	/** The shape the shop's history was written in: an order was "booked" before it was "placed". */
	public sealed interface OriginalShopEvent {
		record OrderBooked ( String orderId ) implements OriginalShopEvent { }
	}

	/** That history, as the reading side declares it: {@code OrderBooked} upcasts into {@link OrderPlaced}. */
	public sealed interface ShopHistoricalEvent {
		@LegacyEvent(upcast = OrderBookedUpcaster.class)
		record OrderBooked ( String orderId ) implements ShopHistoricalEvent { }
	}

	public static class OrderBookedUpcaster implements Upcast<ShopHistoricalEvent.OrderBooked, OrderPlaced> {
		@Override
		public List<OrderPlaced> upcast ( ShopHistoricalEvent.OrderBooked historicalEvent ) {
			return List.of(new OrderPlaced(historicalEvent.orderId()));
		}

		@Override
		public Set<Class<? extends OrderPlaced>> targetTypes ( ) {
			return Set.of(OrderPlaced.class);
		}
	}

	private final EventStreamId streamId = EventStreamId.forContext("shop");

	private EventStream<Object> stream;

	private static Tags order ( String orderId ) {
		return Tags.of("order", orderId);
	}

	private static List<EventType> typesOf ( List<? extends Event<?>> events ) {
		return events.stream().map(Event::type).toList();
	}

	private static List<EventType> types ( Class<?>... classes ) {
		return List.of(classes).stream().map(EventType::of).toList();
	}

	/** Both hierarchies on one stream, interleaved. */
	private void seedShop ( ) {
		stream = eventStore().getEventStream(streamId, Set.of(ShopEvent.class, AuditEvent.class));
		stream.append(AppendCriteria.none(), Event.of(new OrderPlaced("1"), order("1")));
		stream.append(AppendCriteria.none(), Event.of(new PaymentReceived("1"), order("1")));
		stream.append(AppendCriteria.none(), Event.of(new AuditNoted("checked"), order("1")));
		stream.append(AppendCriteria.none(), Event.of(new OrderPlaced("2"), order("2")));
		stream.append(AppendCriteria.none(), Event.of(new OrderShipped("1"), order("1")));
		stream.append(AppendCriteria.none(), Event.of(new ShopClosed(), Tags.none()));
	}

	@ForEachBackend
	void theRootOfAHierarchyMatchesEveryEventUnderIt ( ) {
		seedShop();

		List<Event<Object>> shop = stream.query(EventQuery.forEvents(EventTypesFilter.of(ShopEvent.class), Tags.none()));
		assertEquals(types(OrderPlaced.class, PaymentReceived.class, OrderPlaced.class, OrderShipped.class, ShopClosed.class), typesOf(shop));

		// and not the other hierarchy's event, which "any type" would have included
		List<Event<Object>> audit = stream.query(EventQuery.forEvents(EventTypesFilter.of(AuditEvent.class), Tags.none()));
		assertEquals(types(AuditNoted.class), typesOf(audit));
	}

	@ForEachBackend
	void aNestedInterfaceMatchesItsOwnBranch ( ) {
		seedShop();

		List<Event<Object>> orders = stream.query(EventQuery.forEvents(EventTypesFilter.of(OrderEvent.class), Tags.none()));
		assertEquals(types(OrderPlaced.class, OrderPlaced.class, OrderShipped.class), typesOf(orders));

		List<Event<Object>> paymentsAndClosing = stream.query(EventQuery.forEvents(EventTypesFilter.of(PaymentEvent.class, ShopClosed.class), Tags.none()));
		assertEquals(types(PaymentReceived.class, ShopClosed.class), typesOf(paymentsAndClosing));

		// tags narrow it exactly as they narrow a filter of records
		List<Event<Object>> order1 = stream.query(EventQuery.forEvents(EventTypesFilter.of(OrderEvent.class), order("1")));
		assertEquals(types(OrderPlaced.class, OrderShipped.class), typesOf(order1));
	}

	@ForEachBackend
	void aProjectionMayQueryByInterface ( ) {
		seedShop();

		List<Event<Object>> seen = new ArrayList<>();
		Projection<Object> orderBook = new Projection<>() {
			@Override
			public EventQuery eventQuery ( ) {
				return EventQuery.forEvents(EventTypesFilter.of(OrderEvent.class), Tags.none());
			}

			@Override
			public void when ( Event<Object> event ) {
				seen.add(event);
			}
		};

		ProjectorMetrics metrics = Projector.from(stream).towards(orderBook).build().run();

		assertEquals(3, metrics.eventsHandled());
		assertEquals(types(OrderPlaced.class, OrderPlaced.class, OrderShipped.class), typesOf(seen));
	}

	@ForEachBackend
	void aRootInterfaceBoundsAConsistencyBoundary ( ) {
		stream = eventStore().getEventStream(streamId, Set.of(ShopEvent.class, AuditEvent.class));
		stream.append(AppendCriteria.none(), Event.of(new OrderPlaced("1"), order("1")));

		EventReference head = stream.head().orElseThrow();
		AppendCriteria everyShopFactAboutOrder1 = AppendCriteria.of(EventQuery.forEvents(EventTypesFilter.of(ShopEvent.class), order("1")), head);

		// an event of the other hierarchy is not a new fact for a boundary over this one -- which is
		// what tells the hierarchy apart from "any type"
		stream.append(AppendCriteria.none(), Event.of(new AuditNoted("checked"), order("1")));
		stream.append(everyShopFactAboutOrder1, Event.of(new PaymentReceived("1"), order("1")));

		// that payment is a shop fact after the boundary, so the same decision is now stale
		assertThrows(OptimisticLockingException.class,
				() -> stream.append(everyShopFactAboutOrder1, Event.of(new OrderShipped("1"), order("1"))));

		// and a boundary over one branch ignores the rest of the hierarchy
		EventReference afterPayment = stream.head().orElseThrow();
		AppendCriteria everyPaymentForOrder1 = AppendCriteria.of(EventQuery.forEvents(EventTypesFilter.of(PaymentEvent.class), order("1")), afterPayment);
		stream.append(AppendCriteria.none(), Event.of(new OrderShipped("1"), order("1")));
		stream.append(everyPaymentForOrder1, Event.of(new ShopClosed(), order("1")));
	}

	@ForEachBackend
	void anInterfaceIncludesTheLegacyEventsUpcastIntoItsTypes ( ) {
		EventStream<OriginalShopEvent> asWritten = eventStore().getEventStream(streamId, OriginalShopEvent.class);
		asWritten.append(AppendCriteria.none(), Event.of(new OriginalShopEvent.OrderBooked("1"), order("1")));

		EventStream<ShopEvent> asRead = eventStore().getEventStream(streamId, ShopEvent.class, ShopHistoricalEvent.class);
		asRead.append(AppendCriteria.none(), Event.of(new OrderPlaced("2"), order("2")));
		asRead.append(AppendCriteria.none(), Event.of(new PaymentReceived("2"), order("2")));

		List<Event<ShopEvent>> orders = asRead.query(EventQuery.forEvents(EventTypesFilter.of(OrderEvent.class), Tags.none()));
		assertEquals(types(OrderPlaced.class, OrderPlaced.class), typesOf(orders));
		assertEquals(EventType.ofType("OrderBooked"), orders.get(0).storedType());
		assertEquals(EventType.ofType("OrderPlaced"), orders.get(1).storedType());

		List<Event<ShopEvent>> everything = asRead.query(EventQuery.forEvents(EventTypesFilter.of(ShopEvent.class), Tags.none()));
		assertEquals(types(OrderPlaced.class, OrderPlaced.class, PaymentReceived.class), typesOf(everything));

		List<Event<ShopEvent>> payments = asRead.query(EventQuery.forEvents(EventTypesFilter.of(PaymentEvent.class), Tags.none()));
		assertEquals(types(PaymentReceived.class), typesOf(payments));

		// and a boundary over the branch counts a legacy event upcasting into it, as the query does
		EventReference head = asRead.head().orElseThrow();
		AppendCriteria everyOrderFact = AppendCriteria.of(EventQuery.forEvents(EventTypesFilter.of(OrderEvent.class), Tags.none()), head);
		asWritten.append(AppendCriteria.none(), Event.of(new OriginalShopEvent.OrderBooked("3"), order("3")));
		assertThrows(OptimisticLockingException.class,
				() -> asRead.append(everyOrderFact, Event.of(new OrderShipped("2"), order("2"))));
	}

}
