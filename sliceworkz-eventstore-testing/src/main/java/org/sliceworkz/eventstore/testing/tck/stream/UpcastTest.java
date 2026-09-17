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
import java.util.Optional;
import java.util.Set;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastTest.CustomerEvent.CustomerRegisteredV2;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastTest.CustomerEvent.CustomerRenamed;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastTest.CustomerEvent.Name;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

public class UpcastTest extends AbstractEventStoreTest {

	EventStreamId streamId = EventStreamId.forContext("unittest");

	@ForEachBackend
	void testUpcasting() {
		EventStream<OriginalEvent> originalStream = eventStore().getEventStream(streamId, OriginalEvent.class);
		originalStream.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerRegistered("John"), Tags.of("customer", "123")));
		originalStream.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerNameChanged("Jane"), Tags.of("customer", "123")));
		originalStream.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerChurned(), Tags.of("customer", "123")));

		List<Event<OriginalEvent>> originalEvents = originalStream.query(EventQuery.matchAll());

		assertEquals(3, originalEvents.size());

		EventStream<CustomerEvent> streamWithUpcasts = eventStore().getEventStream(streamId, CustomerEvent.class, CustomerHistoricalEvent.class);
		streamWithUpcasts.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerRegisteredV2(Name.of("Superman")), Tags.of("customer", "234")));
		streamWithUpcasts.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerRenamed(Name.of("Batman")), Tags.of("customer", "234")));
		streamWithUpcasts.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerChurned(), Tags.of("customer", "234")));

		List<Event<CustomerEvent>> newEvents = streamWithUpcasts.query(EventQuery.matchAll());
		assertEquals(6, newEvents.size());

		// make sure we can query both old and new events on the new (potentially upcasted) type
		assertEquals(2, streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerRegisteredV2.class), Tags.none())).size());
		assertEquals(2, streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerRenamed.class), Tags.none())).size());
		assertEquals(2, streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerChurned.class), Tags.none())).size());

		// verify data on the Register events
		List<Event<CustomerEvent>> registers = streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerRegisteredV2.class), Tags.none()));
		assertEquals(EventType.ofType("CustomerRegisteredV2"), registers.get(0).type());
		assertEquals(EventType.ofType("CustomerRegistered"), registers.get(0).storedType());
		assertEquals("John", ((CustomerRegisteredV2)(registers.get(0).data())).name().value());
		assertEquals(1, registers.get(0).reference().position());
		assertEquals(CustomerRegisteredV2.class, registers.get(0).data().getClass());
		assertEquals(EventType.ofType("CustomerRegisteredV2"), registers.get(1).type());
		assertEquals(EventType.ofType("CustomerRegisteredV2"), registers.get(1).storedType());
		assertEquals(CustomerRegisteredV2.class, registers.get(1).data().getClass());
		assertEquals("Superman", ((CustomerRegisteredV2)(registers.get(1).data())).name().value());
		assertEquals(4, registers.get(1).reference().position());

		// verify data on the Rename events
		List<Event<CustomerEvent>> renames = streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerRenamed.class), Tags.none()));
		assertEquals(EventType.ofType("CustomerRenamed"), renames.get(0).type());
		assertEquals(EventType.ofType("CustomerNameChanged"), renames.get(0).storedType());
		assertEquals("Jane", ((CustomerRenamed)(renames.get(0).data())).name().value());
		assertEquals(2, renames.get(0).reference().position());
		assertEquals(CustomerRenamed.class, renames.get(0).data().getClass());
		assertEquals(EventType.ofType("CustomerRenamed"), renames.get(1).type());
		assertEquals(EventType.ofType("CustomerRenamed"), renames.get(1).storedType());
		assertEquals(CustomerRenamed.class, renames.get(1).data().getClass());
		assertEquals("Batman", ((CustomerRenamed)(renames.get(1).data())).name().value());
		assertEquals(5, renames.get(1).reference().position());

		// check that references are not changed during upcasting
		assertEquals(originalEvents.get(0).reference(), newEvents.get(0).reference());
		assertEquals(originalEvents.get(1).reference(), newEvents.get(1).reference());
		assertEquals(originalEvents.get(2).reference(), newEvents.get(2).reference());

		// verify reading the raw stream still shows all historical details
		EventSource<String> rawStream = eventStore().getRawEventStream(streamId);
		List<Event<String>> rawEvents = rawStream.query(EventQuery.matchAll());

		assertEquals(6, rawEvents.size());

		assertEquals(EventType.ofType("CustomerRegistered"), rawEvents.get(0).type());
		assertEquals(EventType.ofType("CustomerRegistered"), rawEvents.get(0).storedType());
		assertTrue(rawEvents.get(0).data().contains("John"));
		assertEquals(1, rawEvents.get(0).reference().position());

		assertEquals(EventType.ofType("CustomerNameChanged"), rawEvents.get(1).type());
		assertEquals(EventType.ofType("CustomerNameChanged"), rawEvents.get(1).storedType());
		assertTrue(rawEvents.get(1).data().contains("Jane"));
		assertEquals(2, rawEvents.get(1).reference().position());

		assertEquals(EventType.ofType("CustomerChurned"), rawEvents.get(2).type());
		assertEquals(EventType.ofType("CustomerChurned"), rawEvents.get(2).storedType());
		assertEquals(3, rawEvents.get(2).reference().position());

		assertEquals(EventType.ofType("CustomerRegisteredV2"), rawEvents.get(3).type());
		assertEquals(EventType.ofType("CustomerRegisteredV2"), rawEvents.get(3).storedType());
		assertTrue(rawEvents.get(3).data().toString().contains("Superman"));
		assertEquals(4, rawEvents.get(3).reference().position());

		assertEquals(EventType.ofType("CustomerRenamed"), rawEvents.get(4).type());
		assertEquals(EventType.ofType("CustomerRenamed"), rawEvents.get(4).storedType());
		assertTrue(rawEvents.get(4).data().toString().contains("Batman"));
		assertEquals(5, rawEvents.get(4).reference().position());

		assertEquals(EventType.ofType("CustomerChurned"), rawEvents.get(5).type());
		assertEquals(EventType.ofType("CustomerChurned"), rawEvents.get(5).storedType());
		assertEquals(6, rawEvents.get(5).reference().position());
	}

	/**
	 * A consistency boundary over a current type counts the legacy events that upcast into it, exactly
	 * as a query for that type returns them: the two are answered over the same stored names. A legacy
	 * event upcasting into a type <em>outside</em> the boundary is not a relevant fact for it. The
	 * exception names the boundary the caller decided on, not the stored names it was checked with.
	 */
	@ForEachBackend
	void aBoundaryOverACurrentTypeCountsTheLegacyEventsUpcastIntoIt() {
		Tags customer = Tags.of("customer", "123");
		EventStream<CustomerEvent> current = eventStore().getEventStream(streamId, CustomerEvent.class, CustomerHistoricalEvent.class);
		// history lands the way it was written, under its legacy names
		EventStream<OriginalEvent> asWritten = eventStore().getEventStream(streamId, OriginalEvent.class);

		current.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerRegisteredV2(Name.of("Superman")), customer));

		// decided on the renames of this customer; a legacy registration is not one of them, so it is no
		// new relevant fact and the append is admitted
		EventReference head = current.head().orElseThrow();
		AppendCriteria decidedOnRenames = AppendCriteria.of(EventQuery.forEvents(EventTypesFilter.of(CustomerRenamed.class), customer), head);
		asWritten.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerRegistered("John"), customer));
		current.append(decidedOnRenames, Event.of(new CustomerEvent.CustomerRenamed(Name.of("Robin")), customer));

		// a legacy rename is one of them
		EventReference laterHead = current.head().orElseThrow();
		AppendCriteria decidedOnRenamesAgain = AppendCriteria.of(EventQuery.forEvents(EventTypesFilter.of(CustomerRenamed.class), customer), laterHead);
		asWritten.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerNameChanged("Batman"), customer));
		OptimisticLockingException e = assertThrows(OptimisticLockingException.class,
				() -> current.append(decidedOnRenamesAgain, Event.of(new CustomerEvent.CustomerRenamed(Name.of("Joker")), customer)));

		assertEquals(decidedOnRenamesAgain.eventFilter(), e.getFilter());
		assertEquals(Optional.of(laterHead), e.getExpectedLastEventReference());

		// which is what the query path says too: the legacy rename is a rename
		assertEquals(2, current.query(EventQuery.forEvents(EventTypesFilter.of(CustomerRenamed.class), customer)).size());
	}

	/**
	 * A filter on a typed stream names current types, and is refused when it names a legacy one -- for
	 * a query and for a consistency boundary alike, since neither could be answered: storage would
	 * fetch the legacy events and the read would upcast them into a type the filter does not name, so
	 * a query would return nothing while the same filter as a boundary counted the very same events.
	 * The message names the legacy type and the current type it is read as. A raw stream registers no
	 * legacy types and reads the name as stored; so does a typed stream on which the name is current.
	 */
	@ForEachBackend
	void aQueryOrABoundaryNamingALegacyTypeIsRefused() {
		Tags customer = Tags.of("customer", "123");
		EventStream<OriginalEvent> asWritten = eventStore().getEventStream(streamId, OriginalEvent.class);
		asWritten.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerNameChanged("Jane"), customer));
		EventStream<CustomerEvent> current = eventStore().getEventStream(streamId, CustomerEvent.class, CustomerHistoricalEvent.class);

		// by class and by the stored name alike: the filter carries the name either way
		EventQuery byClass = EventQuery.forEvents(EventTypesFilter.of(CustomerHistoricalEvent.CustomerNameChanged.class), customer);
		EventQuery byName = EventQuery.forEvents(EventTypesFilter.of(Set.of(EventType.ofType("CustomerNameChanged"))), customer);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> current.query(byClass));
		assertEquals("a query or a consistency boundary on this stream names the current event types, and it returns and counts the legacy events that upcast into them; it cannot name a legacy type: 'CustomerNameChanged' (a legacy type, read as 'CustomerRenamed')", e.getMessage());
		assertEquals(e.getMessage(), assertThrows(IllegalArgumentException.class, () -> current.query(byName)).getMessage());
		// beside a current type it is refused just the same: the filter as a whole cannot be answered
		assertEquals(e.getMessage(), assertThrows(IllegalArgumentException.class,
				() -> current.query(EventQuery.forEvents(EventTypesFilter.of(CustomerHistoricalEvent.CustomerNameChanged.class, CustomerRenamed.class), customer))).getMessage());

		// refused as a boundary too, with nothing stored
		EventReference head = current.head().orElseThrow();
		IllegalArgumentException asBoundary = assertThrows(IllegalArgumentException.class,
				() -> current.append(AppendCriteria.of(byClass, head), Event.of(new CustomerEvent.CustomerRenamed(Name.of("Batman")), customer)));
		assertEquals(e.getMessage(), asBoundary.getMessage());
		assertEquals(1, current.query(EventQuery.matchAll()).size());

		// the current type is what the legacy rename is read as, and a filter over it returns it
		assertEquals(1, current.query(EventQuery.forEvents(EventTypesFilter.of(CustomerRenamed.class), customer)).size());

		// a raw stream registers no legacy types: the stored name is read as stored
		EventSource<?> raw = eventStore().getRawEventStream(streamId);
		assertEquals(1, raw.query(EventQuery.forEvents(EventTypesFilter.of(Set.of(EventType.ofType("CustomerNameChanged"))), customer)).size());
		// and on a typed stream where the name is a current type, the filter is an ordinary one
		assertEquals(1, asWritten.query(EventQuery.forEvents(EventTypesFilter.of(OriginalEvent.CustomerNameChanged.class), customer)).size());
	}

	@ForEachBackend
	void testUpcastAnnotationNotAllowedOnCurrentEventVersions() {
		// IllegalArgumentException, like the two neighbouring registration checks (duplicate event name,
		// non-sealed interface): the argument passed to getEventStream is what is wrong.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,()->eventStore().getEventStream(streamId, CustomerHistoricalEvent.CustomerNameChanged.class));
		assertEquals("Event type class org.sliceworkz.eventstore.testing.tck.stream.UpcastTest$CustomerHistoricalEvent$CustomerNameChanged should not be annotated as a @LegacyEvent, or moved to the legacy Event types", e.getMessage());
	}

	@ForEachBackend
	void testUpcastRequiredOnHistoricalEventVersions() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,()->eventStore().getEventStream(streamId, OriginalEvent.CustomerRegistered.class, CustomerEvent.CustomerRenamed.class));
		assertEquals("legacy Event type class org.sliceworkz.eventstore.testing.tck.stream.UpcastTest$CustomerEvent$CustomerRenamed should be annotated as a @LegacyEvent and configured with an Upcaster", e.getMessage());
	}

	/*
	 * The original events appended to the store
	 */
	sealed interface OriginalEvent {

		public record CustomerRegistered ( String name ) implements OriginalEvent { }

		public record CustomerNameChanged (String name ) implements OriginalEvent { }

		public record CustomerChurned ( ) implements OriginalEvent { }

	}

	/*
	 * Our latest and brightest event definitions
	 */
	sealed interface CustomerEvent {

		// this one changes to much that we consider it a new version
		public record CustomerRegisteredV2 ( Name name ) implements CustomerEvent { }

		// this one is renamed from "CustomerNameChanged"
		public record CustomerRenamed ( Name name ) implements CustomerEvent { }

		public record CustomerChurned ( ) implements CustomerEvent { }

		public record Name ( String value ) {

			public Name ( String value ) {
				if ( value == null || value.strip().length() == 0 ) {
					throw new IllegalArgumentException();
				}
				this.value = value;
			}

			public static Name of ( String value ) {
				if ( value != null ) {
					if ( value.length() < 3 || value.length() > 20 ) {
						throw new IllegalArgumentException("name length must be between 3 and 20");
					}
				}
				return new Name(value);
			}
		}

	}

	/*
	 * Deprecated historical event definitions, needed to deserialization, but will be upcasted
	 */
	sealed interface CustomerHistoricalEvent {

		@LegacyEvent(upcast=CustomerRegisteredUpcaster.class)
		public record CustomerRegistered ( String name ) implements CustomerHistoricalEvent { }

		@LegacyEvent(upcast=CustomerNameChangedUpcaster.class)
		public record CustomerNameChanged (String name ) implements CustomerHistoricalEvent { }

	}

	/*
	 * Our upcasters that transform the legacy events to current event definitions
	 */

	public static class CustomerRegisteredUpcaster implements Upcast<CustomerHistoricalEvent.CustomerRegistered, CustomerEvent.CustomerRegisteredV2> {

		@Override
		public List<CustomerEvent.CustomerRegisteredV2> upcast(CustomerHistoricalEvent.CustomerRegistered historicalEvent) {
			// using the constructor, not the "of" utility method to allow historical values that don't adhere to the new length business rules
			return List.of(new CustomerEvent.CustomerRegisteredV2(new CustomerEvent.Name(historicalEvent.name())));
		}

		@Override
		public Set<Class<? extends CustomerRegisteredV2>> targetTypes() {
			return Set.of(CustomerRegisteredV2.class);
		}

	}

	public static class CustomerNameChangedUpcaster implements Upcast<CustomerHistoricalEvent.CustomerNameChanged, CustomerEvent.CustomerRenamed> {

		@Override
		public List<CustomerEvent.CustomerRenamed> upcast(CustomerHistoricalEvent.CustomerNameChanged historicalEvent) {
			// using the constructor, not the "of" utility method to allow historical values that don't adhere to the new length business rules
			return List.of(new CustomerEvent.CustomerRenamed(new CustomerEvent.Name(historicalEvent.name())));
		}

		@Override
		public Set<Class<? extends CustomerRenamed>> targetTypes() {
			return Set.of(CustomerRenamed.class);
		}

	}

}
