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
package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * Tests for multi-event upcasting: upcast-to-zero, upcast-to-one, upcast-to-many,
 * combined with forward/backward queries, getEventById, and index verification.
 */
class UpcastMultiTest {

	abstract static class Tests {

		EventStorage eventStorage;
		EventStore eventStore;
		EventStreamId streamId = EventStreamId.forContext("unittest");

		abstract EventStorage createEventStorage();

		void destroyEventStorage(EventStorage storage) {}

		@BeforeEach
		public void setUp() {
			this.eventStorage = createEventStorage();
			this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
		}

		@AfterEach
		public void tearDown() {
			destroyEventStorage(eventStorage);
		}


		// =========================================================================
		// Helper: store original events and return the upcasted stream
		// =========================================================================

		private EventStream<CurrentEvent> storeOriginalAndGetUpcastedStream() {
			EventStream<OriginalEvent> originalStream = eventStore.getEventStream(streamId, OriginalEvent.class);

			// Position 1: will split into 2 events (index 0,1)
			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerRegisteredWithAddress("John", "123 Main St", "Springfield"),
				Tags.of("customer", "1")));

			// Position 2: will be filtered out (0 events)
			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerLegacyAuditLog("some audit message"),
				Tags.of("customer", "1")));

			// Position 3: will upcast 1-to-1
			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerNameChanged("Jane"),
				Tags.of("customer", "1")));

			EventStream<CurrentEvent> stream = eventStore.getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

			// Position 4: a real current event, not upcasted — creates a mix of legacy and current events
			stream.append(AppendCriteria.none(), Event.of(
				new CurrentEvent.CustomerChurned(),
				Tags.of("customer", "1")));

			return stream;
		}


		// =========================================================================
		// Tests: forward queries
		// =========================================================================

		@Test
		void testForwardQueryMatchAll() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll()).toList();

			// Position 1 → 2 events, Position 2 → 0 events, Position 3 → 1 event, Position 4 → 1 event = 4 total
			assertEquals(4, events.size());

			// First: CustomerRegistered (from split, index 0)
			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(0).data().getClass());
			assertEquals("John", ((CurrentEvent.CustomerRegistered) events.get(0).data()).name());

			// Second: AddressRecorded (from split, index 1)
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(1).data().getClass());
			assertEquals("123 Main St", ((CurrentEvent.AddressRecorded) events.get(1).data()).street());
			assertEquals("Springfield", ((CurrentEvent.AddressRecorded) events.get(1).data()).city());

			// Third: CustomerRenamed (from 1-to-1 upcast)
			assertEquals(CurrentEvent.CustomerRenamed.class, events.get(2).data().getClass());
			assertEquals("Jane", ((CurrentEvent.CustomerRenamed) events.get(2).data()).name());

			// Fourth: CustomerChurned (current event, not upcasted)
			assertEquals(CurrentEvent.CustomerChurned.class, events.get(3).data().getClass());
		}

		@Test
		void testForwardQueryFilteredEventTypesIncludeUpcasted() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// Query specifically for CustomerRegistered — should find it from the split
			List<Event<CurrentEvent>> registered = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(CurrentEvent.CustomerRegistered.class), Tags.none())
			).toList();
			assertEquals(1, registered.size());
			assertEquals("John", ((CurrentEvent.CustomerRegistered) registered.get(0).data()).name());

			// Query specifically for AddressRecorded — should find it from the split
			List<Event<CurrentEvent>> addresses = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(CurrentEvent.AddressRecorded.class), Tags.none())
			).toList();
			assertEquals(1, addresses.size());
			assertEquals("Springfield", ((CurrentEvent.AddressRecorded) addresses.get(0).data()).city());

			// Query for CustomerRenamed — should find the 1-to-1 upcast
			List<Event<CurrentEvent>> renamed = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(CurrentEvent.CustomerRenamed.class), Tags.none())
			).toList();
			assertEquals(1, renamed.size());
			assertEquals("Jane", ((CurrentEvent.CustomerRenamed) renamed.get(0).data()).name());
		}


		// =========================================================================
		// Tests: index verification on references
		// =========================================================================

		@Test
		void testSplitEventsHaveDistinctIndices() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll()).toList();

			// Events from position 1 (split): should have index 0 and 1
			assertEquals(0, events.get(0).reference().index());
			assertEquals(1, events.get(1).reference().index());

			// They share the same id, position, and tx
			assertEquals(events.get(0).reference().id(), events.get(1).reference().id());
			assertEquals(events.get(0).reference().position(), events.get(1).reference().position());
			assertEquals(events.get(0).reference().tx(), events.get(1).reference().tx());

			// Single-event results have index 0
			assertEquals(0, events.get(2).reference().index()); // CustomerRenamed
			assertEquals(0, events.get(3).reference().index()); // CustomerChurned
		}

		@Test
		void testSplitEventsOrderCorrectlyViaHappenedBefore() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll()).toList();

			// index 0 happened before index 1
			assertTrue(events.get(0).reference().happenedBefore(events.get(1).reference()));

			// index 1 happened before position 3 events
			assertTrue(events.get(1).reference().happenedBefore(events.get(2).reference()));

			// overall ordering is consistent
			for ( int i = 0; i < events.size() - 1; i++ ) {
				assertTrue(events.get(i).reference().happenedBefore(events.get(i+1).reference()),
					"event %d should happenBefore event %d".formatted(i, i+1));
			}
		}


		// =========================================================================
		// Tests: backward queries
		// =========================================================================

		@Test
		void testBackwardQueryMatchAll() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().backwards()).toList();

			// Same 4 events, reversed
			assertEquals(4, events.size());

			// First (most recent): CustomerChurned
			assertEquals(CurrentEvent.CustomerChurned.class, events.get(0).data().getClass());

			// Second: CustomerRenamed
			assertEquals(CurrentEvent.CustomerRenamed.class, events.get(1).data().getClass());

			// Third: AddressRecorded (index 1, from split — descending within the split)
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(2).data().getClass());
			assertEquals(1, events.get(2).reference().index());

			// Fourth: CustomerRegistered (index 0, from split)
			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(3).data().getClass());
			assertEquals(0, events.get(3).reference().index());
		}

		// =========================================================================
		// Tests: limit applies to stored events, not upcasted events
		// =========================================================================
		//
		// The limit parameter restricts how many *stored* events are fetched from
		// storage, before upcasting expands (or filters) them. This means:
		//
		//   Stored events (backward from position 4):
		//     Position 4: CustomerChurned                → 1 upcasted event
		//     Position 3: CustomerNameChanged             → 1 upcasted event  (renamed)
		//     Position 2: CustomerLegacyAuditLog          → 0 upcasted events (filtered)
		//     Position 1: CustomerRegisteredWithAddress   → 2 upcasted events (split)
		//
		//   Stored events (forward from position 1):
		//     Position 1: CustomerRegisteredWithAddress   → 2 upcasted events (split)
		//     Position 2: CustomerLegacyAuditLog          → 0 upcasted events (filtered)
		//     Position 3: CustomerNameChanged             → 1 upcasted event  (renamed)
		//     Position 4: CustomerChurned                → 1 upcasted event

		@Test
		void testBackwardQueryWithLimit1() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(1) → 1 stored event (position 4: CustomerChurned) → 1 upcasted event
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().backwards().limit(1)).toList();

			assertEquals(1, events.size());
			assertEquals(CurrentEvent.CustomerChurned.class, events.get(0).data().getClass());
		}

		@Test
		void testBackwardQueryWithLimit2() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(2) → 2 stored events (position 4 + 3) → 2 upcasted events
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().backwards().limit(2)).toList();

			assertEquals(2, events.size());
			assertEquals(CurrentEvent.CustomerChurned.class, events.get(0).data().getClass());
			assertEquals(CurrentEvent.CustomerRenamed.class, events.get(1).data().getClass());
		}

		@Test
		void testBackwardQueryWithLimit3() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(3) → 3 stored events (position 4 + 3 + 2)
			// position 2 is the filtered audit log → 0 upcasted events
			// so we still get only 2 upcasted events despite fetching 3 stored events
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().backwards().limit(3)).toList();

			assertEquals(2, events.size());
			assertEquals(CurrentEvent.CustomerChurned.class, events.get(0).data().getClass());
			assertEquals(CurrentEvent.CustomerRenamed.class, events.get(1).data().getClass());
		}

		@Test
		void testBackwardQueryWithLimit4() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(4) → all 4 stored events
			// position 1 splits into 2 → we get 4 upcasted events total
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().backwards().limit(4)).toList();

			assertEquals(4, events.size());
			assertEquals(CurrentEvent.CustomerChurned.class, events.get(0).data().getClass());
			assertEquals(CurrentEvent.CustomerRenamed.class, events.get(1).data().getClass());
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(2).data().getClass());
			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(3).data().getClass());
		}

		@Test
		void testBackwardQueryWithLimit5() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(5) → exceeds the 4 stored events, same result as limit(4)
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().backwards().limit(5)).toList();

			assertEquals(4, events.size());
			assertEquals(CurrentEvent.CustomerChurned.class, events.get(0).data().getClass());
			assertEquals(CurrentEvent.CustomerRenamed.class, events.get(1).data().getClass());
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(2).data().getClass());
			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(3).data().getClass());
		}

		@Test
		void testForwardQueryWithLimit1() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(1) → 1 stored event (position 1: split) → 2 upcasted events
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().limit(1)).toList();

			assertEquals(2, events.size());
			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(0).data().getClass());
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(1).data().getClass());
		}

		@Test
		void testForwardQueryWithLimit2() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(2) → 2 stored events (position 1 + 2)
			// position 2 is the filtered audit log → 0 upcasted events
			// so we still get only 2 upcasted events from the split
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().limit(2)).toList();

			assertEquals(2, events.size());
			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(0).data().getClass());
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(1).data().getClass());
		}

		@Test
		void testForwardQueryWithLimit3() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(3) → 3 stored events (position 1 + 2 + 3) → 3 upcasted events (2 from split + 0 filtered + 1 renamed)
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().limit(3)).toList();

			assertEquals(3, events.size());
			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(0).data().getClass());
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(1).data().getClass());
			assertEquals(CurrentEvent.CustomerRenamed.class, events.get(2).data().getClass());
		}

		@Test
		void testForwardQueryWithLimit4() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(4) → all 4 stored events → 4 upcasted events
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().limit(4)).toList();

			assertEquals(4, events.size());
			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(0).data().getClass());
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(1).data().getClass());
			assertEquals(CurrentEvent.CustomerRenamed.class, events.get(2).data().getClass());
			assertEquals(CurrentEvent.CustomerChurned.class, events.get(3).data().getClass());
		}

		@Test
		void testForwardQueryWithLimit5() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// limit(5) → exceeds the 4 stored events, same result as limit(4)
			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().limit(5)).toList();

			assertEquals(4, events.size());
		}

		@Test
		void testBackwardQueryIndicesStillCorrect() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().backwards()).toList();

			// The split events (at end of list in backward order) should still have their
			// canonical indices: AddressRecorded=1, CustomerRegistered=0
			Event<CurrentEvent> addressEvent = events.stream()
				.filter(e -> e.data() instanceof CurrentEvent.AddressRecorded)
				.findFirst().orElseThrow();
			Event<CurrentEvent> registeredEvent = events.stream()
				.filter(e -> e.data() instanceof CurrentEvent.CustomerRegistered)
				.findFirst().orElseThrow();

			assertEquals(1, addressEvent.reference().index());
			assertEquals(0, registeredEvent.reference().index());

			// AddressRecorded (index 1) happened after CustomerRegistered (index 0)
			assertTrue(registeredEvent.reference().happenedBefore(addressEvent.reference()));
		}


		// =========================================================================
		// Tests: getEventById
		// =========================================================================

		@Test
		void testGetEventByIdForSplitEvent() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// Get the EventId of the split event (position 1)
			List<Event<CurrentEvent>> allEvents = stream.query(EventQuery.matchAll()).toList();
			EventId splitEventId = allEvents.get(0).reference().id();

			// getEventById should return both sub-events
			List<Event<CurrentEvent>> retrieved = stream.getEventById(splitEventId);
			assertEquals(2, retrieved.size());
			assertEquals(CurrentEvent.CustomerRegistered.class, retrieved.get(0).data().getClass());
			assertEquals(CurrentEvent.AddressRecorded.class, retrieved.get(1).data().getClass());
			assertEquals(0, retrieved.get(0).reference().index());
			assertEquals(1, retrieved.get(1).reference().index());
		}

		@Test
		void testGetEventByIdForFilteredEvent() {
			// Store events including one that gets filtered out
			EventStream<OriginalEvent> originalStream = eventStore.getEventStream(streamId, OriginalEvent.class);

			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerLegacyAuditLog("audit"),
				Tags.of("customer", "1")));

			// Get the EventId from the original stream
			EventId auditEventId = originalStream.query(EventQuery.matchAll()).toList().get(0).reference().id();

			// Now read via the upcasted stream
			EventStream<CurrentEvent> upcastedStream = eventStore.getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

			// getEventById should return empty list for a filtered event
			List<Event<CurrentEvent>> retrieved = upcastedStream.getEventById(auditEventId);
			assertEquals(0, retrieved.size());
		}

		@Test
		void testGetEventByIdForOneToOneUpcast() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// Find the CustomerRenamed event
			Event<CurrentEvent> renamedEvent = stream.query(EventQuery.matchAll()).toList().stream()
				.filter(e -> e.data() instanceof CurrentEvent.CustomerRenamed)
				.findFirst().orElseThrow();

			List<Event<CurrentEvent>> retrieved = stream.getEventById(renamedEvent.reference().id());
			assertEquals(1, retrieved.size());
			assertEquals(CurrentEvent.CustomerRenamed.class, retrieved.get(0).data().getClass());
			assertEquals(0, retrieved.get(0).reference().index());
		}

		@Test
		void testGetEventByIdForCurrentEvent() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			// Find the CustomerChurned event (appended as a current event, not upcasted)
			Event<CurrentEvent> churnedEvent = stream.query(EventQuery.matchAll()).toList().stream()
				.filter(e -> e.data() instanceof CurrentEvent.CustomerChurned)
				.findFirst().orElseThrow();

			List<Event<CurrentEvent>> retrieved = stream.getEventById(churnedEvent.reference().id());
			assertEquals(1, retrieved.size());
			assertEquals(CurrentEvent.CustomerChurned.class, retrieved.get(0).data().getClass());
			assertEquals(0, retrieved.get(0).reference().index());
		}

		@Test
		void testGetEventByIdNonExistent() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			List<Event<CurrentEvent>> retrieved = stream.getEventById(EventId.create());
			assertEquals(0, retrieved.size());
		}


		// =========================================================================
		// Tests: upcast-to-zero is properly invisible in queries
		// =========================================================================

		@Test
		void testFilteredEventsAreInvisibleInForwardQuery() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll()).toList();

			// The audit log event (position 2) should be completely absent
			for ( Event<CurrentEvent> event : events ) {
				assertTrue(event.reference().position() != 2,
					"position 2 (filtered audit log) should not appear in results");
			}
		}

		@Test
		void testFilteredEventsAreInvisibleInBackwardQuery() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().backwards()).toList();

			for ( Event<CurrentEvent> event : events ) {
				assertTrue(event.reference().position() != 2,
					"position 2 (filtered audit log) should not appear in backward results");
			}
		}


		// =========================================================================
		// Tests: storedType vs type for upcasted events
		// =========================================================================

		@Test
		void testStoredTypePreservedOnSplitEvents() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll()).toList();

			// Split events: stored type is the original, type is the upcasted
			assertEquals(EventType.ofType("CustomerRegisteredWithAddress"), events.get(0).storedType());
			assertEquals(EventType.ofType("CustomerRegistered"), events.get(0).type());

			assertEquals(EventType.ofType("CustomerRegisteredWithAddress"), events.get(1).storedType());
			assertEquals(EventType.ofType("AddressRecorded"), events.get(1).type());
		}

		@Test
		void testStoredTypePreservedOnOneToOneUpcast() {
			EventStream<CurrentEvent> stream = storeOriginalAndGetUpcastedStream();

			Event<CurrentEvent> renamed = stream.query(EventQuery.matchAll()).toList().stream()
				.filter(e -> e.data() instanceof CurrentEvent.CustomerRenamed)
				.findFirst().orElseThrow();

			assertEquals(EventType.ofType("CustomerNameChanged"), renamed.storedType());
			assertEquals(EventType.ofType("CustomerRenamed"), renamed.type());
		}


		// =========================================================================
		// Tests: multiple split events in the stream
		// =========================================================================

		@Test
		void testMultipleSplitEventsInStream() {
			EventStream<OriginalEvent> originalStream = eventStore.getEventStream(streamId, OriginalEvent.class);

			// Two split events in sequence
			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerRegisteredWithAddress("Alice", "1 A St", "CityA"),
				Tags.of("customer", "1")));
			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerRegisteredWithAddress("Bob", "2 B St", "CityB"),
				Tags.of("customer", "2")));

			EventStream<CurrentEvent> stream = eventStore.getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll()).toList();
			assertEquals(4, events.size()); // 2 splits × 2 events each

			// First split (position 1): Alice
			assertEquals(0, events.get(0).reference().index());
			assertEquals(1, events.get(1).reference().index());
			assertEquals("Alice", ((CurrentEvent.CustomerRegistered) events.get(0).data()).name());
			assertEquals("CityA", ((CurrentEvent.AddressRecorded) events.get(1).data()).city());

			// Second split (position 2): Bob
			assertEquals(0, events.get(2).reference().index());
			assertEquals(1, events.get(3).reference().index());
			assertEquals("Bob", ((CurrentEvent.CustomerRegistered) events.get(2).data()).name());
			assertEquals("CityB", ((CurrentEvent.AddressRecorded) events.get(3).data()).city());

			// Each pair has a different EventId
			assertTrue(!events.get(0).reference().id().equals(events.get(2).reference().id()));
		}

		@Test
		void testMultipleSplitEventsBackward() {
			EventStream<OriginalEvent> originalStream = eventStore.getEventStream(streamId, OriginalEvent.class);

			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerRegisteredWithAddress("Alice", "1 A St", "CityA"),
				Tags.of("customer", "1")));
			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerRegisteredWithAddress("Bob", "2 B St", "CityB"),
				Tags.of("customer", "2")));

			EventStream<CurrentEvent> stream = eventStore.getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll().backwards()).toList();
			assertEquals(4, events.size());

			// Backward: Bob's split comes first (reversed within split too)
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(0).data().getClass());
			assertEquals("CityB", ((CurrentEvent.AddressRecorded) events.get(0).data()).city());
			assertEquals(1, events.get(0).reference().index()); // index 1 first in backward

			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(1).data().getClass());
			assertEquals("Bob", ((CurrentEvent.CustomerRegistered) events.get(1).data()).name());
			assertEquals(0, events.get(1).reference().index());

			// Then Alice's split
			assertEquals(CurrentEvent.AddressRecorded.class, events.get(2).data().getClass());
			assertEquals("CityA", ((CurrentEvent.AddressRecorded) events.get(2).data()).city());
			assertEquals(1, events.get(2).reference().index());

			assertEquals(CurrentEvent.CustomerRegistered.class, events.get(3).data().getClass());
			assertEquals("Alice", ((CurrentEvent.CustomerRegistered) events.get(3).data()).name());
			assertEquals(0, events.get(3).reference().index());
		}


		// =========================================================================
		// Tests: only filtered events in stream (edge case)
		// =========================================================================

		@Test
		void testStreamWithOnlyFilteredEvents() {
			EventStream<OriginalEvent> originalStream = eventStore.getEventStream(streamId, OriginalEvent.class);

			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerLegacyAuditLog("audit 1"), Tags.of("customer", "1")));
			originalStream.append(AppendCriteria.none(), Event.of(
				new OriginalEvent.CustomerLegacyAuditLog("audit 2"), Tags.of("customer", "1")));

			EventStream<CurrentEvent> stream = eventStore.getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

			List<Event<CurrentEvent>> events = stream.query(EventQuery.matchAll()).toList();
			assertEquals(0, events.size());

			List<Event<CurrentEvent>> eventsBackward = stream.query(EventQuery.matchAll().backwards()).toList();
			assertEquals(0, eventsBackward.size());
		}

	}

	@Nested
	class OnInMem extends Tests {

		@Override
		EventStorage createEventStorage() {
			return InMemoryEventStorage.newBuilder().build();
		}

	}

	@Nested
	class OnPostgres17 extends Tests {

		@BeforeAll
		static void startContainer() {
			PostgresContainer.start(PostgresContainer.IMAGE_PG17);
		}

		@AfterAll
		static void stopContainer() {
			PostgresContainer.stop(PostgresContainer.IMAGE_PG17);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17);
		}

		@Override
		EventStorage createEventStorage() {
			return PostgresEventStorage.newBuilder()
				.name("unit-test")
				.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG17))
				.initializeDatabase()
				.build();
		}

		@Override
		void destroyEventStorage(EventStorage storage) {
			((PostgresEventStorageImpl) storage).stop();
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG17);
		}

	}

	@Nested
	class OnPostgres18 extends Tests {

		@BeforeAll
		static void startContainer() {
			PostgresContainer.start(PostgresContainer.IMAGE_PG18);
		}

		@AfterAll
		static void stopContainer() {
			PostgresContainer.stop(PostgresContainer.IMAGE_PG18);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18);
		}

		@Override
		EventStorage createEventStorage() {
			return PostgresEventStorage.newBuilder()
				.name("unit-test")
				.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18))
				.initializeDatabase()
				.build();
		}

		@Override
		void destroyEventStorage(EventStorage storage) {
			((PostgresEventStorageImpl) storage).stop();
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}

	}


	// =========================================================================
	// Domain model: original events as stored
	// =========================================================================

	sealed interface OriginalEvent {
		/** A combined event that will be split into two events on upcast */
		record CustomerRegisteredWithAddress ( String name, String street, String city ) implements OriginalEvent { }
		/** An obsolete event that will be filtered out (upcast to 0) */
		record CustomerLegacyAuditLog ( String message ) implements OriginalEvent { }
		/** A simple rename upcast (1-to-1) */
		record CustomerNameChanged ( String name ) implements OriginalEvent { }
	}

	// =========================================================================
	// Domain model: current events
	// =========================================================================

	sealed interface CurrentEvent {
		record CustomerRegistered ( String name ) implements CurrentEvent { }
		record AddressRecorded ( String street, String city ) implements CurrentEvent { }
		record CustomerRenamed ( String name ) implements CurrentEvent { }
		record CustomerChurned ( ) implements CurrentEvent { }
	}

	// =========================================================================
	// Legacy event types for upcasting
	// =========================================================================

	sealed interface LegacyEvents {

		@LegacyEvent(upcast=SplitRegistrationUpcaster.class)
		record CustomerRegisteredWithAddress ( String name, String street, String city ) implements LegacyEvents { }

		@LegacyEvent(upcast=FilterAuditLogUpcaster.class)
		record CustomerLegacyAuditLog ( String message ) implements LegacyEvents { }

		@LegacyEvent(upcast=RenameNameChangedUpcaster.class)
		record CustomerNameChanged ( String name ) implements LegacyEvents { }
	}

	// =========================================================================
	// Upcasters
	// =========================================================================

	/** Upcast-to-many: splits a combined event into CustomerRegistered + AddressRecorded */
	public static class SplitRegistrationUpcaster implements Upcast<LegacyEvents.CustomerRegisteredWithAddress, CurrentEvent> {

		@Override
		public List<CurrentEvent> upcast ( LegacyEvents.CustomerRegisteredWithAddress historicalEvent ) {
			return List.of(
				new CurrentEvent.CustomerRegistered(historicalEvent.name()),
				new CurrentEvent.AddressRecorded(historicalEvent.street(), historicalEvent.city())
			);
		}

		@Override
		public Set<Class<? extends CurrentEvent>> targetTypes ( ) {
			return Set.of(CurrentEvent.CustomerRegistered.class, CurrentEvent.AddressRecorded.class);
		}
	}

	/** Upcast-to-zero: filters out obsolete audit log events */
	public static class FilterAuditLogUpcaster implements Upcast<LegacyEvents.CustomerLegacyAuditLog, CurrentEvent> {

		@Override
		public List<CurrentEvent> upcast ( LegacyEvents.CustomerLegacyAuditLog historicalEvent ) {
			return List.of(); // filtered out
		}

		@Override
		public Set<Class<? extends CurrentEvent>> targetTypes ( ) {
			return Set.of(); // produces no target types
		}
	}

	/** Upcast-to-one: simple rename */
	public static class RenameNameChangedUpcaster implements Upcast<LegacyEvents.CustomerNameChanged, CurrentEvent.CustomerRenamed> {

		@Override
		public List<CurrentEvent.CustomerRenamed> upcast ( LegacyEvents.CustomerNameChanged historicalEvent ) {
			return List.of(new CurrentEvent.CustomerRenamed(historicalEvent.name()));
		}

		@Override
		public Set<Class<? extends CurrentEvent.CustomerRenamed>> targetTypes ( ) {
			return Set.of(CurrentEvent.CustomerRenamed.class);
		}
	}

}
