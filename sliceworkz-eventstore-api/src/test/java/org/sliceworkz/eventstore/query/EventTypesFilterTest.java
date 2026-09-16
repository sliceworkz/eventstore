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
package org.sliceworkz.eventstore.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.query.EventTypesFilterTest.ShopEvent.OrderEvent;
import org.sliceworkz.eventstore.query.EventTypesFilterTest.ShopEvent.OrderEvent.OrderPlaced;
import org.sliceworkz.eventstore.query.EventTypesFilterTest.ShopEvent.OrderEvent.OrderShipped;
import org.sliceworkz.eventstore.query.EventTypesFilterTest.ShopEvent.PaymentEvent.PaymentReceived;
import org.sliceworkz.eventstore.query.EventTypesFilterTest.ShopEvent.ShopClosed;

/**
 * A sealed interface in a type filter stands for every event type under it. An event is stored under
 * the simple name of its record, so an interface is resolved into those names when the filter is
 * built, and the filter holds nothing else: the root of a hierarchy names all of it, a nested interface
 * names its own branch, and the interface's own name -- which no stored event carries -- is not in it.
 */
public class EventTypesFilterTest {

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

	/** Not sealed: its implementations cannot be enumerated. */
	public interface OpenEvent {
		record SomethingHappened ( ) implements OpenEvent { }
	}

	private static Set<EventType> types ( Class<?>... classes ) {
		return Set.copyOf(List.of(classes).stream().map(EventType::of).toList());
	}

	@Test
	void theRootOfAHierarchyNamesEveryEventTypeUnderIt ( ) {
		EventTypesFilter filter = EventTypesFilter.of(ShopEvent.class);

		assertEquals(types(OrderPlaced.class, OrderShipped.class, PaymentReceived.class, ShopClosed.class), filter.eventTypes());
		assertTrue(filter.matches(EventType.of(OrderPlaced.class)));
		assertTrue(filter.matches(EventType.of(ShopClosed.class)));
		assertFalse(filter.matches(EventType.of(OpenEvent.SomethingHappened.class)));
	}

	@Test
	void aNestedInterfaceNamesItsOwnBranch ( ) {
		EventTypesFilter filter = EventTypesFilter.of(OrderEvent.class);

		assertEquals(types(OrderPlaced.class, OrderShipped.class), filter.eventTypes());
		assertTrue(filter.matches(EventType.of(OrderShipped.class)));
		assertFalse(filter.matches(EventType.of(PaymentReceived.class)));
		assertFalse(filter.matches(EventType.of(ShopClosed.class)));
	}

	@Test
	void theInterfaceItselfIsNotAnEventType ( ) {
		// an event is stored under its record's name, so the interface's own name is in no filter built
		// from it -- neither is it the "match any" of an empty set
		EventTypesFilter filter = EventTypesFilter.of(OrderEvent.class);

		assertFalse(filter.matches(EventType.of(OrderEvent.class)));
		assertFalse(filter.matches(EventType.of(ShopEvent.class)));
		assertFalse(filter.eventTypes().isEmpty());
	}

	@Test
	void interfacesAndRecordsCombine ( ) {
		EventTypesFilter filter = EventTypesFilter.of(OrderEvent.class, ShopClosed.class);

		assertEquals(types(OrderPlaced.class, OrderShipped.class, ShopClosed.class), filter.eventTypes());
		assertEquals(filter, EventTypesFilter.of(List.of(OrderPlaced.class, OrderShipped.class, ShopClosed.class)));
	}

	@Test
	void anInterfaceThatIsNotSealedIsRefused ( ) {
		// the same refusal getEventStream gives it as an event root; a literal name would match
		// nothing, silently
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventTypesFilter.of(OpenEvent.class));
		assertEquals("interface " + OpenEvent.class.getName() + " should be sealed to allow Event Type determination", e.getMessage());
	}

	@Test
	void aFilterBuiltFromNamesIsLiteral ( ) {
		// a name says nothing about a hierarchy: this filter names a stored type called "OrderEvent",
		// which no record under that interface is
		EventTypesFilter filter = EventTypesFilter.of(Set.of(EventType.of(OrderEvent.class)));

		assertEquals(Set.of(EventType.ofType("OrderEvent")), filter.eventTypes());
		assertFalse(filter.matches(EventType.of(OrderPlaced.class)));
	}

	@Test
	void noClassesStillMatchesAnyType ( ) {
		assertTrue(EventTypesFilter.any().eventTypes().isEmpty());
		assertTrue(EventTypesFilter.of(List.of()).eventTypes().isEmpty());
		assertTrue(EventTypesFilter.any().matches(EventType.of(OrderPlaced.class)));
	}

}
