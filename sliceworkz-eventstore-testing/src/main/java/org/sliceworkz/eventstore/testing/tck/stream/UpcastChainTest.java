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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastChainTest.CustomerEvent.CustomerRegisteredV3;

/**
 * Upcasting follows a chain: an upcaster's target may itself be a {@code @LegacyEvent} on the same
 * stream, whose upcaster is then applied in turn, so a history written under three versions of an
 * event reads through the two upcasters written when each version arrived. A query or a consistency
 * boundary over the current type fetches every legacy type on the chain, and what an upcaster
 * declares is checked at stream creation: a target this stream does not register, or a cycle, is an
 * {@link IllegalArgumentException} like the other registration checks. What an upcaster produces is
 * checked on the read: an event outside its declared targets is an
 * {@link EventDeserializationException} naming the upcaster.
 */
public class UpcastChainTest extends AbstractEventStoreTest {

	private final EventStreamId streamId = EventStreamId.forContext("upcast-chain");
	private final Tags customer = Tags.of("customer", "123");

	// --- history as it was written: the first two versions, each stored under its own name -----------

	sealed interface AsWritten {
		record CustomerRegistered ( String name ) implements AsWritten { }
		record CustomerRegisteredV2 ( String name, String email ) implements AsWritten { }
	}

	// --- the current version ---------------------------------------------------------------------

	sealed interface CustomerEvent {
		record CustomerRegisteredV3 ( String name, String email, String channel ) implements CustomerEvent { }
		record CustomerChurned ( ) implements CustomerEvent { }
	}

	// --- the legacy versions as the reading side declares them: V1 upcasts to V2, V2 to V3 ----------

	sealed interface CustomerHistoricalEvent {
		@LegacyEvent(upcast = V1ToV2.class)
		record CustomerRegistered ( String name ) implements CustomerHistoricalEvent { }
		@LegacyEvent(upcast = V2ToV3.class)
		record CustomerRegisteredV2 ( String name, String email ) implements CustomerHistoricalEvent { }
	}

	public static class V1ToV2 implements Upcast<CustomerHistoricalEvent.CustomerRegistered, CustomerHistoricalEvent.CustomerRegisteredV2> {
		@Override
		public List<CustomerHistoricalEvent.CustomerRegisteredV2> upcast ( CustomerHistoricalEvent.CustomerRegistered historicalEvent ) {
			return List.of(new CustomerHistoricalEvent.CustomerRegisteredV2(historicalEvent.name(), "unknown"));
		}
		@Override
		public Set<Class<? extends CustomerHistoricalEvent.CustomerRegisteredV2>> targetTypes ( ) {
			return Set.of(CustomerHistoricalEvent.CustomerRegisteredV2.class);
		}
	}

	public static class V2ToV3 implements Upcast<CustomerHistoricalEvent.CustomerRegisteredV2, CustomerRegisteredV3> {
		@Override
		public List<CustomerRegisteredV3> upcast ( CustomerHistoricalEvent.CustomerRegisteredV2 historicalEvent ) {
			return List.of(new CustomerRegisteredV3(historicalEvent.name(), historicalEvent.email(), "legacy"));
		}
		@Override
		public Set<Class<? extends CustomerRegisteredV3>> targetTypes ( ) {
			return Set.of(CustomerRegisteredV3.class);
		}
	}

	private void writeOneOfEachVersion ( ) {
		EventStream<AsWritten> asWritten = eventStore().getEventStream(streamId, AsWritten.class);
		asWritten.append(AppendCriteria.none(), Event.of(new AsWritten.CustomerRegistered("John"), customer));
		asWritten.append(AppendCriteria.none(), Event.of(new AsWritten.CustomerRegisteredV2("Jane", "jane@example.org"), customer));
		EventStream<CustomerEvent> current = eventStore().getEventStream(streamId, CustomerEvent.class, CustomerHistoricalEvent.class);
		current.append(AppendCriteria.none(), Event.of(new CustomerRegisteredV3("Joe", "joe@example.org", "web"), customer));
	}

	@ForEachBackend
	void aLegacyEventUpcastsThroughEveryHopToTheCurrentType ( ) {
		writeOneOfEachVersion();
		EventStream<CustomerEvent> current = eventStore().getEventStream(streamId, CustomerEvent.class, CustomerHistoricalEvent.class);

		List<Event<CustomerEvent>> events = current.query(EventQuery.matchAll()).toList();

		assertEquals(3, events.size());
		// every event is a current one, whichever version it was stored as: an exhaustive switch
		// over CustomerEvent is what a caller writes, and it must not meet a legacy class
		for ( Event<CustomerEvent> event : events ) {
			assertEquals(CustomerRegisteredV3.class, event.data().getClass());
			assertEquals(EventType.ofType("CustomerRegisteredV3"), event.type());
		}
		assertEquals(new CustomerRegisteredV3("John", "unknown", "legacy"), events.get(0).data());
		assertEquals(EventType.ofType("CustomerRegistered"), events.get(0).storedType());
		assertEquals(new CustomerRegisteredV3("Jane", "jane@example.org", "legacy"), events.get(1).data());
		assertEquals(EventType.ofType("CustomerRegisteredV2"), events.get(1).storedType());
		assertEquals(new CustomerRegisteredV3("Joe", "joe@example.org", "web"), events.get(2).data());
		assertEquals(EventType.ofType("CustomerRegisteredV3"), events.get(2).storedType());
	}

	@ForEachBackend
	void aQueryForTheCurrentTypeFetchesEveryLegacyTypeOnTheChain ( ) {
		writeOneOfEachVersion();
		EventStream<CustomerEvent> current = eventStore().getEventStream(streamId, CustomerEvent.class, CustomerHistoricalEvent.class);
		EventQuery registrations = EventQuery.forEvents(EventTypesFilter.of(CustomerRegisteredV3.class), customer);

		assertEquals(List.of("John", "Jane", "Joe"),
				current.query(registrations).map(e -> ((CustomerRegisteredV3) e.data()).name()).toList());
		assertEquals(List.of("Joe", "Jane", "John"),
				current.query(registrations.backwards()).map(e -> ((CustomerRegisteredV3) e.data()).name()).toList());
		// the one two hops behind is the one a single-hop trace-back would miss
		assertEquals(List.of("John"),
				current.query(registrations.limit(1)).map(e -> ((CustomerRegisteredV3) e.data()).name()).toList());
	}

	@ForEachBackend
	void aBoundaryOverTheCurrentTypeCountsAnEventTwoHopsBehindIt ( ) {
		EventStream<CustomerEvent> current = eventStore().getEventStream(streamId, CustomerEvent.class, CustomerHistoricalEvent.class);
		EventStream<AsWritten> asWritten = eventStore().getEventStream(streamId, AsWritten.class);
		current.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerChurned(), customer));

		// decided on the registrations of this customer; a first-version registration lands meanwhile
		EventReference head = current.head().orElseThrow();
		AppendCriteria decidedOnRegistrations = AppendCriteria.of(EventQuery.forEvents(EventTypesFilter.of(CustomerRegisteredV3.class), customer), head);
		asWritten.append(AppendCriteria.none(), Event.of(new AsWritten.CustomerRegistered("John"), customer));

		OptimisticLockingException e = assertThrows(OptimisticLockingException.class,
				() -> current.append(decidedOnRegistrations, Event.of(new CustomerRegisteredV3("John", "john@example.org", "web"), customer)));
		assertEquals(decidedOnRegistrations.eventFilter(), e.getFilter());
	}

	// --- misconfiguration: fails at getEventStream, before anything is read or written --------------

	sealed interface Elsewhere {
		record CustomerMoved ( String name ) implements Elsewhere { }
	}

	@LegacyEvent(upcast = ToAnUnregisteredType.class)
	record CustomerRelocated ( String name ) { }

	public static class ToAnUnregisteredType implements Upcast<CustomerRelocated, Elsewhere.CustomerMoved> {
		@Override
		public List<Elsewhere.CustomerMoved> upcast ( CustomerRelocated historicalEvent ) {
			return List.of(new Elsewhere.CustomerMoved(historicalEvent.name()));
		}
		@Override
		public Set<Class<? extends Elsewhere.CustomerMoved>> targetTypes ( ) {
			return Set.of(Elsewhere.CustomerMoved.class);
		}
	}

	@ForEachBackend
	void anUpcasterNamingATargetThisStreamDoesNotRegisterIsRejectedAtStreamCreation ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(streamId, CustomerEvent.class, CustomerRelocated.class));

		assertTrue(e.getMessage().contains(ToAnUnregisteredType.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(Elsewhere.CustomerMoved.class.getName()), e.getMessage());

		// with the target's root registered the same stream opens, whichever root set it sits in
		eventStore().getEventStream(streamId, Set.of(CustomerEvent.class, Elsewhere.class), Set.of(CustomerRelocated.class));
	}

	sealed interface Cyclic {
		@LegacyEvent(upcast = AToB.class)
		record A ( String name ) implements Cyclic { }
		@LegacyEvent(upcast = BToA.class)
		record B ( String name ) implements Cyclic { }
	}

	public static class AToB implements Upcast<Cyclic.A, Cyclic.B> {
		@Override public List<Cyclic.B> upcast ( Cyclic.A historicalEvent ) { return List.of(new Cyclic.B(historicalEvent.name())); }
		@Override public Set<Class<? extends Cyclic.B>> targetTypes ( ) { return Set.of(Cyclic.B.class); }
	}

	public static class BToA implements Upcast<Cyclic.B, Cyclic.A> {
		@Override public List<Cyclic.A> upcast ( Cyclic.B historicalEvent ) { return List.of(new Cyclic.A(historicalEvent.name())); }
		@Override public Set<Class<? extends Cyclic.A>> targetTypes ( ) { return Set.of(Cyclic.A.class); }
	}

	@ForEachBackend
	void upcastersFormingACycleAreRejectedAtStreamCreation ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(streamId, CustomerEvent.class, Cyclic.class));

		assertTrue(e.getMessage().contains("cycle"), e.getMessage());
		assertTrue(e.getMessage().contains(Cyclic.A.class.getName()), e.getMessage());
	}

	// --- what an upcaster produces is checked against what it declared -------------------------------

	/** The same stored name as the legacy class below, unannotated, so it can be appended. */
	interface Written {
		record CustomerNoteAdded ( String note ) { }
	}

	@LegacyEvent(upcast = DeclaresNothingProducesSomething.class)
	record CustomerNoteAdded ( String note ) { }

	public static class DeclaresNothingProducesSomething implements Upcast<CustomerNoteAdded, CustomerEvent> {
		@Override
		public List<CustomerEvent> upcast ( CustomerNoteAdded historicalEvent ) {
			return List.of(new CustomerEvent.CustomerChurned());
		}
		@Override
		public Set<Class<? extends CustomerEvent>> targetTypes ( ) {
			return Set.of();
		}
	}

	@ForEachBackend
	void anUpcasterProducingATypeItDidNotDeclareFailsTheReadNamingIt ( ) {
		eventStore().getEventStream(streamId, Written.CustomerNoteAdded.class)
				.append(AppendCriteria.none(), Event.of(new Written.CustomerNoteAdded("hello"), customer));
		EventStream<CustomerEvent> current = eventStore().getEventStream(streamId, CustomerEvent.class, CustomerNoteAdded.class);

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> current.query(EventQuery.matchAll()).toList());

		assertEquals(EventType.ofType("CustomerNoteAdded"), e.getEventType());
		assertTrue(e.getReference().isPresent(), "the stored event that failed should be named");
		assertTrue(e.getMessage().contains(DeclaresNothingProducesSomething.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(CustomerEvent.CustomerChurned.class.getName()), e.getMessage());
	}

}
