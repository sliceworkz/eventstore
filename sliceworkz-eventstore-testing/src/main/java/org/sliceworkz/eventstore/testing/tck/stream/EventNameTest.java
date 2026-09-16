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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventName;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * An event class annotated {@link EventName} is stored, matched and read back under the declared name,
 * and its class name plays no part on the wire.
 * <p>
 * The stored name is the one string a storage keeps about an event's type, and a class's simple name
 * is that string unless the class says otherwise. Two things follow from a class name being wire
 * format, and the annotation exists to take both away: renaming an event class strands its history
 * (the class no longer claims the name the events were written under), and two contexts in one store
 * cannot both own a {@code Created}. These scenarios pin, per backend, that the declared name is what
 * goes through every path a class name goes through — the append, the type mappings a typed stream
 * reads with, the filter a query is built from, the raw view of the stored row, and a
 * {@link LegacyEvent}'s registration — so that a class annotated with the name of its history reads
 * that history with nothing else changed.
 */
public class EventNameTest extends AbstractEventStoreTest {

	// =========================================================================
	// Generation 1: the class name is the stored name
	// =========================================================================

	public sealed interface CustomerEventV1 {
		record CustomerRegistered ( String id, String name ) implements CustomerEventV1 { }
	}

	// =========================================================================
	// Generation 2: the class is renamed, and declares the name its history was stored under
	// =========================================================================

	public sealed interface CustomerEvent {
		@EventName("CustomerRegistered")
		record CustomerSignedUp ( String id, String name ) implements CustomerEvent { }
		record CustomerChurned ( String id ) implements CustomerEvent { }
	}

	// =========================================================================
	// Generation 3: the shape changed too, so the history is upcast — from a legacy class that
	// carries the stored name in its annotation rather than in its class name
	// =========================================================================

	public sealed interface CustomerEventV3 {
		record CustomerRegisteredV3 ( String id, String name, String email ) implements CustomerEventV3 { }
	}

	public sealed interface CustomerLegacyEvent {
		@EventName("CustomerRegistered")
		@LegacyEvent(upcast = OldRegistrationUpcaster.class)
		record OldRegistration ( String id, String name ) implements CustomerLegacyEvent { }
	}

	public static class OldRegistrationUpcaster implements Upcast<CustomerLegacyEvent.OldRegistration, CustomerEventV3> {
		@Override
		public List<CustomerEventV3> upcast ( CustomerLegacyEvent.OldRegistration legacy ) {
			return List.of(new CustomerEventV3.CustomerRegisteredV3(legacy.id(), legacy.name(), "unknown"));
		}

		@Override
		public Set<Class<? extends CustomerEventV3>> targetTypes ( ) {
			return Set.of(CustomerEventV3.CustomerRegisteredV3.class);
		}
	}

	// =========================================================================
	// Two contexts, one simple name
	// =========================================================================

	public sealed interface OrderEvent {
		@EventName("OrderCreated")
		record Created ( String orderId, int amount ) implements OrderEvent { }
	}

	public sealed interface VacancyEvent {
		record Created ( String vacancyId, String title ) implements VacancyEvent { }
	}

	public sealed interface BrokenEvent {
		@EventName(" ")
		record Blank ( String id ) implements BrokenEvent { }
	}

	private final EventStreamId customers = EventStreamId.forContext("customer");

	private void writeHistoryUnderTheOldClassName ( ) {
		EventStream<CustomerEventV1> stream = eventStore().getEventStream(customers, CustomerEventV1.class);
		stream.append(AppendCriteria.none(), Event.of(new CustomerEventV1.CustomerRegistered("c1", "Alice"), Tags.of("customer", "c1")));
	}

	@ForEachBackend
	void anAnnotatedClassIsStoredUnderItsDeclaredName ( ) {
		EventStream<CustomerEvent> stream = eventStore().getEventStream(customers, CustomerEvent.class);

		List<Event<CustomerEvent>> appended = stream.append(AppendCriteria.none(),
				Event.of(new CustomerEvent.CustomerSignedUp("c1", "Alice"), Tags.of("customer", "c1")));

		// the events append returns already carry the declared name
		assertEquals(1, appended.size());
		assertEquals("CustomerRegistered", appended.getFirst().type().name());
		assertEquals("CustomerRegistered", appended.getFirst().storedType().name());

		// and so does the stored row, seen without any type mapping at all
		EventSource<Object> raw = eventStore().getRawEventStream(customers);
		List<Event<Object>> stored = raw.query(EventQuery.matchAll()).toList();
		assertEquals(1, stored.size());
		assertEquals("CustomerRegistered", stored.getFirst().type().name());
		assertEquals("CustomerRegistered", stored.getFirst().storedType().name());
	}

	@ForEachBackend
	void aRenamedClassReadsTheHistoryWrittenUnderItsOldName ( ) {
		writeHistoryUnderTheOldClassName();

		EventStream<CustomerEvent> stream = eventStore().getEventStream(customers, CustomerEvent.class);
		List<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll()).toList();

		assertEquals(1, events.size());
		CustomerEvent.CustomerSignedUp signedUp = assertInstanceOf(CustomerEvent.CustomerSignedUp.class, events.getFirst().data());
		assertEquals("Alice", signedUp.name());
		// no upcasting happened: the current type and the stored type are the same name
		assertEquals("CustomerRegistered", events.getFirst().type().name());
		assertEquals("CustomerRegistered", events.getFirst().storedType().name());

		// and getEventById, which is eager where query is lazy, agrees
		List<Event<CustomerEvent>> byId = stream.getEventById(events.getFirst().reference().id());
		assertEquals(1, byId.size());
		assertInstanceOf(CustomerEvent.CustomerSignedUp.class, byId.getFirst().data());
	}

	@ForEachBackend
	void aFilterBuiltFromTheClassMatchesTheDeclaredName ( ) {
		writeHistoryUnderTheOldClassName();
		EventStream<CustomerEvent> stream = eventStore().getEventStream(customers, CustomerEvent.class);
		stream.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerChurned("c1"), Tags.of("customer", "c1")));

		// by class: the filter is built from EventType.of(Class), so it carries the declared name
		List<Event<CustomerEvent>> byClass = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerSignedUp.class), Tags.of("customer", "c1"))).toList();
		assertEquals(1, byClass.size());
		assertInstanceOf(CustomerEvent.CustomerSignedUp.class, byClass.getFirst().data());

		// by name: the declared name is the stored name
		List<Event<CustomerEvent>> byName = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(Set.of(EventType.ofType("CustomerRegistered"))), Tags.none())).toList();
		assertEquals(1, byName.size());

		// the class's simple name is not a stored name of anything
		List<Event<CustomerEvent>> bySimpleName = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(Set.of(EventType.ofType("CustomerSignedUp"))), Tags.none())).toList();
		assertTrue(bySimpleName.isEmpty());

		// and the filter is honoured by the lock check as well as by the read
		Event<CustomerEvent> last = byClass.getFirst();
		stream.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerSignedUp("c2", "Bob"), Tags.of("customer", "c1")));
		assertThrows(org.sliceworkz.eventstore.stream.OptimisticLockingException.class, () -> stream.append(
				AppendCriteria.of(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerSignedUp.class), Tags.of("customer", "c1")), last.reference()),
				Event.of(new CustomerEvent.CustomerChurned("c1"), Tags.of("customer", "c1"))));
	}

	@ForEachBackend
	void aLegacyClassCarriesTheStoredNameOfTheHistoryItUpcasts ( ) {
		writeHistoryUnderTheOldClassName();

		EventStream<CustomerEventV3> stream = eventStore().getEventStream(customers, CustomerEventV3.class, CustomerLegacyEvent.class);
		List<Event<CustomerEventV3>> events = stream.query(EventQuery.matchAll()).toList();

		assertEquals(1, events.size());
		CustomerEventV3.CustomerRegisteredV3 upcast = assertInstanceOf(CustomerEventV3.CustomerRegisteredV3.class, events.getFirst().data());
		assertEquals("Alice", upcast.name());
		assertEquals("unknown", upcast.email());
		assertEquals("CustomerRegisteredV3", events.getFirst().type().name());
		assertEquals("CustomerRegistered", events.getFirst().storedType().name());

		// a filter on the current type reaches the legacy name through the upcaster's mapping
		List<Event<CustomerEventV3>> byCurrentType = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(CustomerEventV3.CustomerRegisteredV3.class), Tags.none())).toList();
		assertEquals(1, byCurrentType.size());
	}

	@ForEachBackend
	void twoContextsShareASimpleNameWhenOneDeclaresItsStoredName ( ) {
		EventStreamId orders = EventStreamId.forContext("order");
		EventStreamId vacancies = EventStreamId.forContext("vacancy");
		eventStore().getEventStream(orders, OrderEvent.class)
				.append(AppendCriteria.none(), Event.of(new OrderEvent.Created("o1", 42), Tags.none()));
		eventStore().getEventStream(vacancies, VacancyEvent.class)
				.append(AppendCriteria.none(), Event.of(new VacancyEvent.Created("v1", "developer"), Tags.none()));

		// both hierarchies register on one stream: no duplicate name, since only one of them stores 'Created'
		EventStream<Object> everything = eventStore().getEventStream(EventStreamId.anyContext(), Set.of(OrderEvent.class, VacancyEvent.class));
		List<Event<Object>> events = everything.query(EventQuery.matchAll()).toList();

		assertEquals(2, events.size());
		Event<Object> order = events.stream().filter(e -> e.stream().context().equals("order")).findFirst().orElseThrow();
		Event<Object> vacancy = events.stream().filter(e -> e.stream().context().equals("vacancy")).findFirst().orElseThrow();
		assertInstanceOf(OrderEvent.Created.class, order.data());
		assertEquals("OrderCreated", order.type().name());
		assertInstanceOf(VacancyEvent.Created.class, vacancy.data());
		assertEquals("Created", vacancy.type().name());

		// each read through its own class finds its own context's events, and only those
		assertEquals(1, everything.query(EventQuery.forEvents(EventTypesFilter.of(OrderEvent.Created.class), Tags.none())).count());
		assertEquals(1, everything.query(EventQuery.forEvents(EventTypesFilter.of(VacancyEvent.Created.class), Tags.none())).count());
	}

	@ForEachBackend
	void aBlankDeclaredNameFailsAtStreamCreation ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(customers, BrokenEvent.class));
		assertTrue(e.getMessage().contains("@EventName"), e.getMessage());
		assertTrue(e.getMessage().contains(BrokenEvent.Blank.class.getName()), e.getMessage());
	}

}
