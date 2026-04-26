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
package org.sliceworkz.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Collections;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.mock.MockDomainEvent;
import org.sliceworkz.eventstore.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.mock.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

class EventStoreBasicTest {

	abstract static class Tests {

		private EventStorage eventStorage;
		private EventStore eventStore;

		@BeforeEach
		public void setUp ( ) {
			this.eventStorage = createEventStorage();
			this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
		}

		@AfterEach
		public void tearDown ( ) {
			destroyEventStorage(eventStorage);
		}

		abstract EventStorage createEventStorage ( );

		void destroyEventStorage ( EventStorage storage ) {
		}

		private void storeEvent ( EventStreamId eventStreamId, MockDomainEvent event, Tags tags ) {
			EventStream<MockDomainEvent> eventStream = eventStore.getEventStream(eventStreamId, MockDomainEvent.class);
			EphemeralEvent<MockDomainEvent> e = Event.of(event, tags);
			eventStream.append(AppendCriteria.none(), Collections.singletonList((e)));
		}

		private void storeTestEvent ( EventStreamId eventStreamId ) {
			storeEvent(eventStreamId, new FirstDomainEvent("test"), Tags.parse("a:1", "b:2", "c:3"));
		}

		@Test
		void testAppendToStorageWithNonSpecifiedPurpose ( ) {
			try {
				storeTestEvent(EventStreamId.forContext("a").anyPurpose());
				fail("exception expected");
			} catch (RuntimeException e) {
				// OK
			}
		}

		@Test
		void testAppendToStorageWithNonSpecifiedContext ( ) {
			try {
				storeTestEvent(EventStreamId.anyContext().withPurpose("p"));
				fail("exception expected");
			} catch (RuntimeException e) {
				// OK
			}
		}

		@Test
		void testQueryEmptyStorageAll ( ) {
			assertEquals(0, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQueryEmptyStorageDomain ( ) {
			assertEquals(0, eventStore.getEventStream(EventStreamId.forContext("a").withPurpose("domain")).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQueryOneEvent ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.forContext("a").withPurpose("domain")).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQueryAnyStreamAnyPurpose ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQueryEmptyStream ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(0, eventStore.getEventStream(EventStreamId.forContext("b").anyPurpose()).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQuerySpecificStreamSpecificPurposeNoMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(0, eventStore.getEventStream(EventStreamId.forContext("a").withPurpose("p")).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQuerySpecificStreamSpecificPurposeMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("p"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.forContext("a").withPurpose("p")).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQuerySpecificStreamAnyPurpose ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("p"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.forContext("a").anyPurpose()).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQuerySpecificStreamAnyPurposeOnApplication ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.forContext("a").anyPurpose()).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQueryAnyStreamSpecificPurpose ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("p"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.anyContext().withPurpose("p")).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQuerySpecificStreamApplicationPurpose ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.forContext("a").withPurpose("domain")).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQueryAnyStreamApplicationPurpose ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.anyContext().withPurpose("domain")).query(EventQuery.matchAll()).count());
		}

		@Test
		void testQueryByEventTypeMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(
					EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())
			).count());
		}

		@Test
		void testQueryByEventTypeNoMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(0, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(
					EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none())
			).count());
		}

		@Test
		void testQueryBySingleTagNoMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(0, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(
					EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("a:2"))
			).count());
		}

		@Test
		void testQueryBySingleTagMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(
					EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("b:2"))
			).count());
		}

		@Test
		void testQueryByMultipleTagsNoMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(0, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(
					EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("a:1", "b:2", "c:4"))
			).count());
		}

		@Test
		void testQueryByMultipleTagsMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(
					EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("a:1", "c:3"))
			).count());
		}

		@Test
		void testQueryByAllTagsMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(
					EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("a:1", "b:2", "c:3"))
			).count());
		}

		@Test
		void testQueryByEventTypeAndAllTagsMatch ( ) {
			storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
			assertEquals(1, eventStore.getEventStream(EventStreamId.anyContext().anyPurpose()).query(
					EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.parse("a:1", "b:2", "c:3"))
			).count());
		}

	}

	@Nested
	class OnInMem extends Tests {
		@Override
		EventStorage createEventStorage ( ) {
			return InMemoryEventStorage.newBuilder().build();
		}
	}

	@Nested
	class OnPostgres17 extends Tests {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG17); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG17); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17); }

		@Override
		EventStorage createEventStorage ( ) {
			return PostgresEventStorage.newBuilder()
					.name("unit-test")
					.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG17))
					.initializeDatabase()
					.build();
		}

		@Override
		void destroyEventStorage ( EventStorage storage ) {
			((PostgresEventStorageImpl)storage).stop();
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG17);
		}
	}

	@Nested
	class OnPostgres18 extends Tests {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

		@Override
		EventStorage createEventStorage ( ) {
			return PostgresEventStorage.newBuilder()
					.name("unit-test")
					.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18))
					.initializeDatabase()
					.build();
		}

		@Override
		void destroyEventStorage ( EventStorage storage ) {
			((PostgresEventStorageImpl)storage).stop();
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}

}
