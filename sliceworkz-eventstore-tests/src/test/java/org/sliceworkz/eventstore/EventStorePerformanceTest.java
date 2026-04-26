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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

class EventStorePerformanceTest {

	abstract static class Tests {

		private static final String UNITTEST_BOUNDEDCONTEXT = "pertest";

		EventStorage eventStorage;

		@BeforeEach
		public void setUp ( ) {
			this.eventStorage = createEventStorage();
		}

		@AfterEach
		public void tearDown ( ) {
			destroyEventStorage(eventStorage);
		}

		abstract EventStorage createEventStorage ( );

		void destroyEventStorage ( EventStorage storage ) {
		}

		@Test
		void testAppendPerformance ( ) {
			EventStream<MockDomainEvent> eventStream = createEventStream();

			List<? extends Event<? extends MockDomainEvent>> result = null;

			AtomicInteger counter = new AtomicInteger();

			eventStream.subscribe(new EventStreamConsistentAppendListener<MockDomainEvent>() {

				@Override
				public void eventsAppended(List<? extends Event<MockDomainEvent>> events) {
					counter.incrementAndGet();
				}
			});

			System.out.println("starting");

			Instant start = Instant.now();

			int COUNT = 10_000;

			for ( int i = 0; i < COUNT; i++ ) {
				// append with no criteria (should always succeed)
				EphemeralEvent<FirstDomainEvent> event = Event.of(new FirstDomainEvent("test1"), Tags.none());
				result = eventStream.append(AppendCriteria.none(), Collections.singletonList(event));
			}

			Instant stop = Instant.now();
			long ms = stop.toEpochMilli() - start.toEpochMilli();
			double eventsPerSecond = (COUNT*1000/(double)ms);

			assertEquals(COUNT, result.iterator().next().reference().position());

			System.out.println("ms                : " + ms);
			System.out.println("counter (async)   : " + counter.get());
			System.out.println("appended event/sec: " + eventsPerSecond);
		}

		private EventStream<MockDomainEvent> createEventStream() {
			return EventStoreFactory.get().eventStore(eventStorage).getEventStream(EventStreamId.forContext(UNITTEST_BOUNDEDCONTEXT), MockDomainEvent.class);
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
