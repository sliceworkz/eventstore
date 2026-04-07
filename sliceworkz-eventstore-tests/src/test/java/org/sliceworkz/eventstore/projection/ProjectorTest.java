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
package org.sliceworkz.eventstore.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

class ProjectorTest {

	abstract static class Tests {

		private EventStorage eventStorage;
		private EventStore eventStore;
		private EventStream<MockDomainEvent> es;

		@BeforeEach
		public void setUp ( ) {
			this.eventStorage = createEventStorage();
			this.eventStore = EventStoreFactory.get().eventStore(eventStorage);

			EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");
			es = eventStore.getEventStream(stream, MockDomainEvent.class);

			append(es, new FirstDomainEvent("1"), Tags.of("nr", "one"));
			append(es, new SecondDomainEvent("2"), Tags.of("nr", "two"));
			append(es, new ThirdDomainEvent("3"), Tags.of("nr", "three"));
			append(es, new FirstDomainEvent("4"), Tags.of("nr", "four"));
			append(es, new SecondDomainEvent("5"), Tags.of("nr", "five"));
			append(es, new ThirdDomainEvent("6"), Tags.of("nr", "six"));
		}

		@AfterEach
		public void tearDown ( ) {
			destroyEventStorage(eventStorage);
		}

		abstract EventStorage createEventStorage ( );

		void destroyEventStorage ( EventStorage storage ) {
		}

		public EventStore eventStore ( ) {
			return eventStore;
		}

		@Test
		void testProjector ( ) {
			TestProjection projection = new TestProjection();

			var projector = Projector.from(es).towards(projection).build();

			ProjectorMetrics projectorMetrics = projector.run();
			assertEquals(4, projection.counter()); // SecondDomainEvent type is left out by the query
			assertEquals(1, projectorMetrics.queriesDone()); // 1 batch, stored events < batch limit so no extra query needed
			assertEquals(4,  projectorMetrics.eventsStreamed());
			assertEquals(4,  projectorMetrics.eventsHandled());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(1, accumulatedMetrics.queriesDone());
			assertEquals(4,  accumulatedMetrics.eventsStreamed());
			assertEquals(4,  accumulatedMetrics.eventsHandled());


			BatchAwareTestProjection batchAwareProjection = new BatchAwareTestProjection();
			var batchAwareProjector = Projector.from(es).towards(batchAwareProjection).build();

			projectorMetrics = batchAwareProjector.run();
			assertEquals(4, batchAwareProjection.counter()); // SecondDomainEvent type is left out by the query
			assertEquals(1, batchAwareProjection.beforeTriggered()); // single batch
			assertEquals(1, batchAwareProjection.afterTriggered());  // equal amount expected
			assertEquals(0, batchAwareProjection.cancelTriggered());
		}

		@Test
		void testFailingProjector ( ) {
			FailingBatchAwareTestProjection batchAwareProjection = new FailingBatchAwareTestProjection();
			var batchAwareProjector = Projector.from(es).towards(batchAwareProjection).build();

			ProjectorException e = assertThrows (ProjectorException.class, ()->{
				batchAwareProjector.run();
			});
			assertEquals("UNIT TEST FAKED PROBLEM WITH EVENT PROCESSING", e.getCause().getMessage());

			assertEquals(2, batchAwareProjection.counter()); // SecondDomainEvent type is left out by the query, so 2 processed, third failed
			assertEquals(1, batchAwareProjection.beforeTriggered()); // single batch
			assertEquals(0, batchAwareProjection.afterTriggered());  // failed batches don't call after
			assertEquals(1, batchAwareProjection.cancelTriggered()); // should be called because of exception

			ProjectorMetrics accumulatedMetrics = batchAwareProjector.accumulatedMetrics();
			assertEquals(1, accumulatedMetrics.queriesDone());
			assertEquals(3,  accumulatedMetrics.eventsStreamed());
			assertEquals(2,  accumulatedMetrics.eventsHandled());
			assertNull(accumulatedMetrics.lastEventReference());

			// new re-run to check whether we start over from last batch

			e = assertThrows (ProjectorException.class, ()->{
				batchAwareProjector.run();
			});
			assertEquals("UNIT TEST FAKED PROBLEM WITH EVENT PROCESSING", e.getCause().getMessage());

			assertEquals(4, batchAwareProjection.counter()); // SecondDomainEvent type is left out by the query, so 2 processed, third failed each time
			assertEquals(2, batchAwareProjection.beforeTriggered()); // second run
			assertEquals(0, batchAwareProjection.afterTriggered());  // failed again
			assertEquals(2, batchAwareProjection.cancelTriggered()); // should be called because of exception

			accumulatedMetrics = batchAwareProjector.accumulatedMetrics();
			assertEquals(2, accumulatedMetrics.queriesDone());
			assertEquals(6,  accumulatedMetrics.eventsStreamed());
			assertEquals(4,  accumulatedMetrics.eventsHandled());  // handled but not committed
			assertNull(accumulatedMetrics.lastEventReference());

		}


		@Test
		void testFailingProjectorInBatchesOf2 ( ) {
			FailingBatchAwareTestProjection batchAwareProjection = new FailingBatchAwareTestProjection();
			var batchAwareProjector = Projector.from(es).towards(batchAwareProjection).inBatchesOf(2).build();

			ProjectorException e = assertThrows (ProjectorException.class, ()->{
				batchAwareProjector.run();
			});
			assertEquals("UNIT TEST FAKED PROBLEM WITH EVENT PROCESSING", e.getCause().getMessage());
			assertEquals(4, e.getEventReference().position());

			assertEquals(2, batchAwareProjection.counter());
			assertEquals(2, batchAwareProjection.beforeTriggered());
			assertEquals(1, batchAwareProjection.afterTriggered());
			assertEquals(1, batchAwareProjection.cancelTriggered());

			ProjectorMetrics accumulatedMetrics = batchAwareProjector.accumulatedMetrics();
			assertEquals(2, accumulatedMetrics.queriesDone());
			assertEquals(3,  accumulatedMetrics.eventsStreamed());
			assertEquals(2,  accumulatedMetrics.eventsHandled());
			assertEquals(3, accumulatedMetrics.lastEventReference().position());

			// new re-run to check whether we start over from last batch

			e = assertThrows (ProjectorException.class, ()->{
				batchAwareProjector.run();
			});
			assertEquals("UNIT TEST FAKED PROBLEM WITH EVENT PROCESSING", e.getCause().getMessage());
			assertEquals(4, e.getEventReference().position());

			assertEquals(2, batchAwareProjection.counter());
			assertEquals(3, batchAwareProjection.beforeTriggered());
			assertEquals(1, batchAwareProjection.afterTriggered());
			assertEquals(2, batchAwareProjection.cancelTriggered());

			accumulatedMetrics = batchAwareProjector.accumulatedMetrics();
			assertEquals(3, accumulatedMetrics.queriesDone());
			assertEquals(4,  accumulatedMetrics.eventsStreamed());
			assertEquals(2,  accumulatedMetrics.eventsHandled());
			assertEquals(3, accumulatedMetrics.lastEventReference().position());

		}

		@Test
		void testProjectorWithBookmarkingWithoutReaderName ( ) {
			TestProjection projection = new TestProjection();

			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->Projector.from(es).towards(projection).bookmarkProgress().done().build());
			assertEquals("bookmarking requires a reader name", e.getMessage());
		}

		@Test
		void testProjectorWithBookmarkAtCreation( ) {
			TestProjection projection = new TestProjection();

			EventReference refTwo = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "two"))).findFirst().get().reference();
			EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).findFirst().get().reference();
			EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).findFirst().get().reference();

			es.placeBookmark("someReader", refTwo, Tags.none());

			var projector = Projector.from(es).towards(projection).bookmarkProgress().withReader("someReader").readAtCreationOnly().done().inBatchesOf(1).build();

			es.placeBookmark("someReader", refFour, Tags.none());

			ProjectorMetrics projectorMetrics = projector.runSingleBatch();
			assertEquals(1, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refThree, projectorMetrics.lastEventReference());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(1, accumulatedMetrics.queriesDone());
			assertEquals(1,  accumulatedMetrics.eventsStreamed());
			assertEquals(1,  accumulatedMetrics.eventsHandled());
			assertEquals(refThree, accumulatedMetrics.lastEventReference());

			projectorMetrics = projector.runSingleBatch();
			assertEquals(2, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refFour, projectorMetrics.lastEventReference());

			accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(2, accumulatedMetrics.queriesDone());
			assertEquals(2,  accumulatedMetrics.eventsStreamed());
			assertEquals(2,  accumulatedMetrics.eventsHandled());
			assertEquals(refFour, accumulatedMetrics.lastEventReference());
		}

		@Test
		void testProjectorWithBookmarkOnFirstExecution( ) {
			TestProjection projection = new TestProjection();

			EventReference refTwo = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "two"))).findFirst().get().reference();
			EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).findFirst().get().reference();
			EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).findFirst().get().reference();

			es.placeBookmark("someReader", refFour, Tags.none());

			var projector = Projector.from(es).towards(projection).bookmarkProgress().withReader("someReader").readBeforeFirstExecution().done().inBatchesOf(1).build();

			es.placeBookmark("someReader", refTwo, Tags.none());

			ProjectorMetrics projectorMetrics = projector.runSingleBatch();
			assertEquals(1, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refThree, projectorMetrics.lastEventReference());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(1, accumulatedMetrics.queriesDone());
			assertEquals(1,  accumulatedMetrics.eventsStreamed());
			assertEquals(1,  accumulatedMetrics.eventsHandled());
			assertEquals(refThree, accumulatedMetrics.lastEventReference());

			es.placeBookmark("someReader", refTwo, Tags.none());

			projectorMetrics = projector.runSingleBatch();
			assertEquals(2, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refFour, projectorMetrics.lastEventReference());

			accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(2, accumulatedMetrics.queriesDone());
			assertEquals(2,  accumulatedMetrics.eventsStreamed());
			assertEquals(2,  accumulatedMetrics.eventsHandled());
			assertEquals(refFour, accumulatedMetrics.lastEventReference());
		}


		@Test
		void testProjectorWithBookmarkOnEachExecution( ) {
			TestProjection projection = new TestProjection();

			EventReference refTwo = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "two"))).findFirst().get().reference();
			EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).findFirst().get().reference();
			EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).findFirst().get().reference();

			es.placeBookmark("someReader", refFour, Tags.none());

			var projector = Projector.from(es).towards(projection).bookmarkProgress().withReader("someReader").readBeforeEachExecution().done().inBatchesOf(1).build();

			es.placeBookmark("someReader", refTwo, Tags.none());

			ProjectorMetrics projectorMetrics = projector.runSingleBatch();
			assertEquals(1, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refThree, projectorMetrics.lastEventReference());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(1, accumulatedMetrics.queriesDone());
			assertEquals(1,  accumulatedMetrics.eventsStreamed());
			assertEquals(1,  accumulatedMetrics.eventsHandled());
			assertEquals(refThree, accumulatedMetrics.lastEventReference());

			es.placeBookmark("someReader", refTwo, Tags.none());

			projectorMetrics = projector.runSingleBatch();
			assertEquals(2, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refThree, projectorMetrics.lastEventReference());

			accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(2, accumulatedMetrics.queriesDone());
			assertEquals(2,  accumulatedMetrics.eventsStreamed());
			assertEquals(2,  accumulatedMetrics.eventsHandled());
			assertEquals(refThree, accumulatedMetrics.lastEventReference());
		}

		@Test
		void testProjectorWithBookmarkManualTrigger ( ) {
			TestProjection projection = new TestProjection();

			EventReference refOne = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "one"))).findFirst().get().reference();
			EventReference refTwo = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "two"))).findFirst().get().reference();
			EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).findFirst().get().reference();
			EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).findFirst().get().reference();

			es.placeBookmark("someReader", refFour, Tags.none());

			var projector = Projector.from(es).towards(projection).bookmarkProgress().withReader("someReader").readOnManualTriggerOnly().done().inBatchesOf(1).build();

			es.placeBookmark("someReader", refTwo, Tags.none());

			ProjectorMetrics projectorMetrics = projector.runSingleBatch();
			assertEquals(1, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refOne, projectorMetrics.lastEventReference());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(1, accumulatedMetrics.queriesDone());
			assertEquals(1,  accumulatedMetrics.eventsStreamed());
			assertEquals(1,  accumulatedMetrics.eventsHandled());
			assertEquals(refOne, accumulatedMetrics.lastEventReference());

			projectorMetrics = projector.runSingleBatch();
			assertEquals(2, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refThree, projectorMetrics.lastEventReference());

			accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(2, accumulatedMetrics.queriesDone());
			assertEquals(2,  accumulatedMetrics.eventsStreamed());
			assertEquals(2,  accumulatedMetrics.eventsHandled());
			assertEquals(refThree, accumulatedMetrics.lastEventReference());

			es.placeBookmark("someReader", refTwo, Tags.none());

			projector.readBookmark();

			projectorMetrics = projector.runSingleBatch();
			assertEquals(3, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refThree, projectorMetrics.lastEventReference());

			accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(3, accumulatedMetrics.queriesDone());
			assertEquals(3,  accumulatedMetrics.eventsStreamed());
			assertEquals(3,  accumulatedMetrics.eventsHandled());
			assertEquals(refThree, accumulatedMetrics.lastEventReference());

			es.removeBookmark("someReader");

			projector.readBookmark();

			projectorMetrics = projector.runSingleBatch();
			assertEquals(4, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());
			assertEquals(refOne, projectorMetrics.lastEventReference());

			accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(4, accumulatedMetrics.queriesDone());
			assertEquals(4,  accumulatedMetrics.eventsStreamed());
			assertEquals(4,  accumulatedMetrics.eventsHandled());
			assertEquals(refOne, accumulatedMetrics.lastEventReference());
		}


		@Test
		void testProjectorWithStepOfOne ( ) {
			TestProjection projection = new TestProjection();

			var projector = Projector.from(es).towards(projection).inBatchesOf(1).build();

			ProjectorMetrics projectorMetrics = projector.run();
			assertEquals(4, projection.counter());
			assertEquals(5, projectorMetrics.queriesDone());
			assertEquals(4,  projectorMetrics.eventsStreamed());
			assertEquals(4,  projectorMetrics.eventsHandled());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(5, accumulatedMetrics.queriesDone());
			assertEquals(4,  accumulatedMetrics.eventsStreamed());
			assertEquals(4,  accumulatedMetrics.eventsHandled());

			BatchAwareTestProjection batchAwareProjection = new BatchAwareTestProjection();
			var batchAwareProjector = Projector.from(es).towards(batchAwareProjection).inBatchesOf(1).build();

			projectorMetrics = batchAwareProjector.run();
			assertEquals(4, batchAwareProjection.counter());
			assertEquals(4, batchAwareProjection.beforeTriggered());
			assertEquals(4, batchAwareProjection.afterTriggered());
			assertEquals(0, batchAwareProjection.cancelTriggered());
			assertEquals(5, projectorMetrics.queriesDone());
			assertEquals(4,  projectorMetrics.eventsStreamed());
			assertEquals(4,  projectorMetrics.eventsHandled());

			accumulatedMetrics = batchAwareProjector.accumulatedMetrics();
			assertEquals(5, accumulatedMetrics.queriesDone());
			assertEquals(4,  accumulatedMetrics.eventsStreamed());
			assertEquals(4,  accumulatedMetrics.eventsHandled());
		}

		@Test
		void testProjectorWithStepOfTwo ( ) {
			TestProjection projection = new TestProjection();

			var projector = Projector.from(es).towards(projection).inBatchesOf(2).build();

			ProjectorMetrics projectorMetrics = projector.run();
			assertEquals(4, projection.counter());
			assertEquals(3, projectorMetrics.queriesDone());
			assertEquals(4,  projectorMetrics.eventsStreamed());
			assertEquals(4,  projectorMetrics.eventsHandled());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(3, accumulatedMetrics.queriesDone());
			assertEquals(4,  accumulatedMetrics.eventsStreamed());
			assertEquals(4,  accumulatedMetrics.eventsHandled());

			BatchAwareTestProjection batchAwareProjection = new BatchAwareTestProjection();
			var batchAwareProjector = Projector.from(es).towards(batchAwareProjection).inBatchesOf(2).build();

			projectorMetrics = batchAwareProjector.run();
			assertEquals(4, batchAwareProjection.counter());
			assertEquals(2, batchAwareProjection.beforeTriggered());
			assertEquals(2, batchAwareProjection.afterTriggered());
			assertEquals(0, batchAwareProjection.cancelTriggered());
			assertEquals(3, projectorMetrics.queriesDone());
			assertEquals(4,  projectorMetrics.eventsStreamed());
			assertEquals(4,  projectorMetrics.eventsHandled());

			accumulatedMetrics = batchAwareProjector.accumulatedMetrics();
			assertEquals(3, accumulatedMetrics.queriesDone());
			assertEquals(4,  accumulatedMetrics.eventsStreamed());
			assertEquals(4,  accumulatedMetrics.eventsHandled());

		}

		@Test
		void testProjectorQueryUntilCertainEvent ( ) {
			TestProjection projection = new TestProjection();

			EventReference ref = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).findFirst().get().reference();

			var projector = Projector.from(es).towards(projection).build();

			ProjectorMetrics projectorMetrics = projector.runUntil(ref);
			assertEquals(3, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(3,  projectorMetrics.eventsStreamed());
			assertEquals(3,  projectorMetrics.eventsHandled());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(1, accumulatedMetrics.queriesDone());
			assertEquals(3,  accumulatedMetrics.eventsStreamed());
			assertEquals(3,  accumulatedMetrics.eventsHandled());
		}

		@Test
		void testProjectorMultipleRuns ( ) {
			TestProjection projection = new TestProjection();

			EventStreamId stream = EventStreamId.forContext("app").withPurpose("alternative");
			EventStream<MockDomainEvent> alternativeStream = eventStore.getEventStream(stream, MockDomainEvent.class);

			append(alternativeStream, new FirstDomainEvent("1"), Tags.of("nr", "one"));

			var projector = Projector.from(alternativeStream).towards(projection).build();

			ProjectorMetrics projectorMetrics = projector.run();
			assertEquals(1, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(1, accumulatedMetrics.queriesDone());
			assertEquals(1,  accumulatedMetrics.eventsStreamed());
			assertEquals(1,  accumulatedMetrics.eventsHandled());

			append(alternativeStream, new FirstDomainEvent("2"), Tags.of("nr", "two"));

			projectorMetrics = projector.run();
			assertEquals(2, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(1,  projectorMetrics.eventsStreamed());
			assertEquals(1,  projectorMetrics.eventsHandled());

			accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(2, accumulatedMetrics.queriesDone());
			assertEquals(2,  accumulatedMetrics.eventsStreamed());
			assertEquals(2,  accumulatedMetrics.eventsHandled());
		}

		@Test
		void testProjectorStartInStreamQueryUntilCertainEvent ( ) {
			TestProjection projection = new TestProjection();

			EventReference refAfter = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "one"))).findFirst().get().reference();
			EventReference refUntil = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).findFirst().get().reference();

			var projector = Projector.from(es).towards(projection).startingAfter(refAfter).build();

			ProjectorMetrics projectorMetrics = projector.runUntil(refUntil);
			assertEquals(2, projection.counter());
			assertEquals(1, projectorMetrics.queriesDone());
			assertEquals(2,  projectorMetrics.eventsStreamed());
			assertEquals(2,  projectorMetrics.eventsHandled());

			ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
			assertEquals(1, accumulatedMetrics.queriesDone());
			assertEquals(2,  accumulatedMetrics.eventsStreamed());
			assertEquals(2,  accumulatedMetrics.eventsHandled());
		}


		@Test
		void testProjectorWithInitQuery ( ) {
			EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery");
			EventStream<MockDomainEvent> initEs = eventStore.getEventStream(stream, MockDomainEvent.class);

			append(initEs, new FirstDomainEvent("10"), Tags.none());
			append(initEs, new FirstDomainEvent("5"), Tags.none());
			append(initEs, new ThirdDomainEvent("savepoint:15"), Tags.none());
			append(initEs, new FirstDomainEvent("3"), Tags.none());
			append(initEs, new FirstDomainEvent("7"), Tags.none());

			InitQueryProjection projection = new InitQueryProjection();
			var projector = Projector.from(initEs).towards(projection).build();

			ProjectorMetrics metrics = projector.run();

			assertEquals(3, projection.counter());
			assertEquals("savepoint:15", projection.lastSavepoint());
			assertEquals(2, metrics.queriesDone());
			assertEquals(3, metrics.eventsHandled());
		}

		@Test
		void testProjectorWithInitQueryNoSavepointExists ( ) {
			EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery-nosavepoint");
			EventStream<MockDomainEvent> initEs = eventStore.getEventStream(stream, MockDomainEvent.class);

			append(initEs, new FirstDomainEvent("10"), Tags.none());
			append(initEs, new FirstDomainEvent("5"), Tags.none());
			append(initEs, new FirstDomainEvent("3"), Tags.none());

			InitQueryProjection projection = new InitQueryProjection();
			var projector = Projector.from(initEs).towards(projection).build();

			ProjectorMetrics metrics = projector.run();

			assertEquals(3, projection.counter());
			assertNull(projection.lastSavepoint());
			assertEquals(2, metrics.queriesDone());
			assertEquals(3, metrics.eventsHandled());
		}

		@Test
		void testProjectorWithInitQueryAndBookmarkingIgnoresInitQuery ( ) {
			EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery-bookmark");
			EventStream<MockDomainEvent> initEs = eventStore.getEventStream(stream, MockDomainEvent.class);

			append(initEs, new FirstDomainEvent("10"), Tags.none());
			append(initEs, new FirstDomainEvent("5"), Tags.none());
			append(initEs, new ThirdDomainEvent("savepoint:15"), Tags.none());
			append(initEs, new FirstDomainEvent("3"), Tags.none());
			append(initEs, new FirstDomainEvent("7"), Tags.none());

			InitQueryProjection projection = new InitQueryProjection();
			var projector = Projector.from(initEs).towards(projection)
					.bookmarkProgress().withReader("initquery-test-reader").readBeforeEachExecution().done()
					.build();

			ProjectorMetrics metrics = projector.run();

			assertEquals(4, projection.counter());
			assertNull(projection.lastSavepoint());
			assertEquals(1, metrics.queriesDone());
			assertEquals(4, metrics.eventsHandled());
		}

		@Test
		void testProjectorWithInitQueryMultipleRuns ( ) {
			EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery-multirun");
			EventStream<MockDomainEvent> initEs = eventStore.getEventStream(stream, MockDomainEvent.class);

			append(initEs, new FirstDomainEvent("10"), Tags.none());
			append(initEs, new ThirdDomainEvent("savepoint:10"), Tags.none());
			append(initEs, new FirstDomainEvent("5"), Tags.none());

			InitQueryProjection projection = new InitQueryProjection();
			var projector = Projector.from(initEs).towards(projection).build();

			ProjectorMetrics metrics1 = projector.run();
			assertEquals(2, projection.counter());
			assertEquals("savepoint:10", projection.lastSavepoint());

			append(initEs, new FirstDomainEvent("3"), Tags.none());

			ProjectorMetrics metrics2 = projector.run();
			assertEquals(3, projection.counter());
			assertEquals(1, metrics2.eventsHandled());
		}

		@Test
		void testProjectorBackwardsWithLimitEnforcesTotalLimit ( ) {
			BackwardsLimitProjection projection = new BackwardsLimitProjection();
			var projector = Projector.from(es).towards(projection).build();

			ProjectorMetrics metrics = projector.run();
			assertEquals(1, projection.counter());
			assertEquals("4", projection.lastValue());
			assertEquals(1, metrics.eventsStreamed());
			assertEquals(1, metrics.eventsHandled());
		}

		@Test
		void testProjectorBackwardsWithLimitReturnsMostRecentEventReference ( ) {
			BackwardsLimitProjection projection = new BackwardsLimitProjection();
			var projector = Projector.from(es).towards(projection).build();

			EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).findFirst().get().reference();

			ProjectorMetrics metrics = projector.run();
			assertEquals(refFour, metrics.mostRecentEventReference());
			assertEquals(refFour, metrics.lastEventReference());
		}

		@Test
		void testProjectorBackwardsWithLimitGreaterThanOne ( ) {
			BackwardsLimit3Projection projection = new BackwardsLimit3Projection();
			var projector = Projector.from(es).towards(projection).build();

			EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).findFirst().get().reference();
			EventReference refSix = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "six"))).findFirst().get().reference();

			ProjectorMetrics metrics = projector.run();
			assertEquals(3, projection.counter());
			assertEquals(3, metrics.eventsStreamed());
			assertEquals(3, metrics.eventsHandled());
			assertEquals(refSix, metrics.mostRecentEventReference());
			assertEquals(refThree, metrics.lastEventReference());
		}

		@Test
		void testProjectorForwardMostRecentEqualsLast ( ) {
			TestProjection projection = new TestProjection();
			var projector = Projector.from(es).towards(projection).build();

			ProjectorMetrics metrics = projector.run();
			assertEquals(metrics.lastEventReference(), metrics.mostRecentEventReference());
		}

		private List<Event<MockDomainEvent>> append ( EventStream<MockDomainEvent> es, MockDomainEvent event, Tags tags ) {
			return es.append(AppendCriteria.none(), Collections.singletonList(Event.of(event, tags)));
		}

		class TestProjection implements ProjectionWithoutMetaData<MockDomainEvent> {

			private int counter;

			@Override
			public void when(MockDomainEvent event) {
				counter++;
			}

			@Override
			public EventQuery eventQuery() {
				return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, ThirdDomainEvent.class), Tags.none());
			}

			public int counter ( ) {
				return counter;
			}

		}

		class BatchAwareTestProjection implements BatchAwareProjection<MockDomainEvent> {

			private int counter;
			private int beforeTriggered;
			private int afterTriggered;
			private int cancelTriggered;

			@Override
			public void when(Event<MockDomainEvent> event) {
				counter++;
			}

			@Override
			public EventQuery eventQuery() {
				return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, ThirdDomainEvent.class), Tags.none());
			}

			@Override
			public void beforeBatch() {
				beforeTriggered++;
			}

			@Override
			public void afterBatch(Optional<EventReference> lastEventReference) {
				afterTriggered++;
			}

			@Override
			public void cancelBatch() {
				this.cancelTriggered++;
			}

			public int counter ( ) {
				return counter;
			}

			public int beforeTriggered ( ) {
				return beforeTriggered;
			}

			public int afterTriggered ( ) {
				return afterTriggered;
			}

			public int cancelTriggered ( ) {
				return cancelTriggered;
			}

		}

		class FailingBatchAwareTestProjection implements BatchAwareProjection<MockDomainEvent> {

			private int counter;
			private int beforeTriggered;
			private int afterTriggered;
			private int cancelTriggered;

			@Override
			public void when(Event<MockDomainEvent> event) {
				if ( event.data().equals(new FirstDomainEvent("4"))) {
					throw new RuntimeException("UNIT TEST FAKED PROBLEM WITH EVENT PROCESSING");
				}
				counter++;
			}

			@Override
			public EventQuery eventQuery() {
				return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, ThirdDomainEvent.class), Tags.none());
			}

			@Override
			public void beforeBatch() {
				beforeTriggered++;
			}

			@Override
			public void afterBatch(Optional<EventReference> lastEventReference) {
				afterTriggered++;
			}

			@Override
			public void cancelBatch() {
				this.cancelTriggered++;
			}

			public int counter ( ) {
				return counter;
			}

			public int beforeTriggered ( ) {
				return beforeTriggered;
			}

			public int afterTriggered ( ) {
				return afterTriggered;
			}

			public int cancelTriggered ( ) {
				return cancelTriggered;
			}

		}

		class BackwardsLimit3Projection implements ProjectionWithoutMetaData<MockDomainEvent> {

			private int counter;

			@Override
			public void when(MockDomainEvent event) {
				counter++;
			}

			@Override
			public EventQuery eventQuery() {
				return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, ThirdDomainEvent.class), Tags.none()).backwards().limit(3);
			}

			public int counter ( ) {
				return counter;
			}

		}

		class BackwardsLimitProjection implements ProjectionWithoutMetaData<MockDomainEvent> {

			private int counter;
			private String lastValue;

			@Override
			public void when(MockDomainEvent event) {
				counter++;
				if ( event instanceof FirstDomainEvent f ) {
					lastValue = f.value();
				}
			}

			@Override
			public EventQuery eventQuery() {
				return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none()).backwards().limit(1);
			}

			public int counter ( ) {
				return counter;
			}

			public String lastValue ( ) {
				return lastValue;
			}

		}

		class InitQueryProjection implements Projection<MockDomainEvent> {

			private int counter;
			private String lastSavepoint;

			@Override
			public EventQuery initQuery() {
				return EventQuery.forEvents(EventTypesFilter.of(ThirdDomainEvent.class), Tags.none()).backwards().limit(1);
			}

			@Override
			public EventQuery eventQuery() {
				return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
			}

			@Override
			public void when(Event<MockDomainEvent> event) {
				counter++;
				if ( event.data() instanceof ThirdDomainEvent t ) {
					lastSavepoint = t.value();
				}
			}

			public int counter ( ) {
				return counter;
			}

			public String lastSavepoint ( ) {
				return lastSavepoint;
			}

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
	class OnPostgres extends Tests {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(); PostgresContainer.cleanup(); }

		@Override
		EventStorage createEventStorage ( ) {
			return PostgresEventStorage.newBuilder()
					.name("unit-test")
					.dataSource(PostgresContainer.dataSource())
					.initializeDatabase()
					.build();
		}

		@Override
		void destroyEventStorage ( EventStorage storage ) {
			((PostgresEventStorageImpl)storage).stop();
			PostgresContainer.closeDataSource();
		}
	}

}
