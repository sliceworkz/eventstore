/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.AbstractEventStoreTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
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

public class ProjectorTest extends AbstractEventStoreTest {

	private EventStream<MockDomainEvent> es; 
	
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");
		es = eventStore().getEventStream(stream, MockDomainEvent.class);
		
		append(es, new FirstDomainEvent("1"), Tags.of("nr", "one"));
		append(es, new SecondDomainEvent("2"), Tags.of("nr", "two"));
		append(es, new ThirdDomainEvent("3"), Tags.of("nr", "three"));
		append(es, new FirstDomainEvent("4"), Tags.of("nr", "four"));
		append(es, new SecondDomainEvent("5"), Tags.of("nr", "five"));
		append(es, new ThirdDomainEvent("6"), Tags.of("nr", "six"));
	}

	@Test
	void testProjector ( ) {
		TestProjection projection = new TestProjection();	
		
		var projector = Projector.from(es).towards(projection).build();

		ProjectorMetrics projectorMetrics = projector.run();
		assertEquals(4, projection.counter()); // SecondDomainEvent type is left out by the query
		assertEquals(1, projectorMetrics.queriesDone());
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
			ProjectorMetrics projectorMetrics = batchAwareProjector.run();
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
			ProjectorMetrics projectorMetrics = batchAwareProjector.run();
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
			ProjectorMetrics projectorMetrics = batchAwareProjector.run();
		});
		assertEquals("UNIT TEST FAKED PROBLEM WITH EVENT PROCESSING", e.getCause().getMessage());
		assertEquals(4, e.getEventReference().position());
		
		assertEquals(2, batchAwareProjection.counter()); // SecondDomainEvent type is left out by the query, so 2 processed, third failed
		assertEquals(2, batchAwareProjection.beforeTriggered()); // single batch
		assertEquals(1, batchAwareProjection.afterTriggered());  // one failed, doesn't call after then
		assertEquals(1, batchAwareProjection.cancelTriggered()); // should be called because of exception  
		
		ProjectorMetrics accumulatedMetrics = batchAwareProjector.accumulatedMetrics();
		assertEquals(2, accumulatedMetrics.queriesDone());
		assertEquals(3,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(2,  accumulatedMetrics.eventsHandled());
		assertEquals(3, accumulatedMetrics.lastEventReference().position());

		// new re-run to check whether we start over from last batch
		
		e = assertThrows (ProjectorException.class, ()->{
			ProjectorMetrics projectorMetrics = batchAwareProjector.run();
		});
		assertEquals("UNIT TEST FAKED PROBLEM WITH EVENT PROCESSING", e.getCause().getMessage());
		assertEquals(4, e.getEventReference().position());
		
		assertEquals(2, batchAwareProjection.counter()); // SecondDomainEvent type is left out by the query, so 2 processed
		assertEquals(3, batchAwareProjection.beforeTriggered()); // single batch
		assertEquals(1, batchAwareProjection.afterTriggered());  // second failed
		assertEquals(2, batchAwareProjection.cancelTriggered()); // should be called because of exception  
		
		accumulatedMetrics = batchAwareProjector.accumulatedMetrics();
		assertEquals(3, accumulatedMetrics.queriesDone());
		assertEquals(4,  accumulatedMetrics.eventsStreamed()); // last run: one skipped, one error
		assertEquals(2,  accumulatedMetrics.eventsHandled());  // no new handled successfully (one skipped, one error in last run)
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

		// set a bookmark for the Projector to find
		es.placeBookmark("someReader", refTwo, Tags.none()); 
		
		var projector = Projector.from(es).towards(projection).bookmarkProgress().withReader("someReader").readAtCreationOnly().done().inBatchesOf(1).build();
		
		// setting the bookmark again after construction shouldn't be picked up as our projector is configured to only read the bookmark at creation
		es.placeBookmark("someReader", refFour, Tags.none()); 


		// run a batch of size 1

		ProjectorMetrics projectorMetrics = projector.runSingleBatch();
		assertEquals(1, projection.counter()); 
		// according to the query and the start situation, event 3 should be read if the bookmark is properly read
		assertEquals(1, projectorMetrics.queriesDone()); 
		assertEquals(1,  projectorMetrics.eventsStreamed());
		assertEquals(1,  projectorMetrics.eventsHandled());
		assertEquals(refThree, projectorMetrics.lastEventReference());

		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone());
		assertEquals(1,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(1,  accumulatedMetrics.eventsHandled());
		assertEquals(refThree, accumulatedMetrics.lastEventReference());
		
		// run another batch of size 1
		
		projectorMetrics = projector.runSingleBatch();
		assertEquals(2, projection.counter()); 
		assertEquals(1, projectorMetrics.queriesDone()); 
		// according to the query and the start situation, event 4 should be read if the bookmark is properly read
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

		// set a bookmark for the Projector to find
		es.placeBookmark("someReader", refFour, Tags.none()); 
		
		var projector = Projector.from(es).towards(projection).bookmarkProgress().withReader("someReader").readBeforeFirstExecution().done().inBatchesOf(1).build();
		
		// setting the bookmark again after construction should be picked up as our projector is configured to only read the bookmark at first execution
		es.placeBookmark("someReader", refTwo, Tags.none()); 


		// run a batch of size 1

		ProjectorMetrics projectorMetrics = projector.runSingleBatch();
		assertEquals(1, projection.counter()); 
		// according to the query and the start situation, event 3 should be read if the bookmark is properly read
		assertEquals(1, projectorMetrics.queriesDone()); 
		assertEquals(1,  projectorMetrics.eventsStreamed());
		assertEquals(1,  projectorMetrics.eventsHandled());
		assertEquals(refThree, projectorMetrics.lastEventReference());

		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone());
		assertEquals(1,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(1,  accumulatedMetrics.eventsHandled());
		assertEquals(refThree, accumulatedMetrics.lastEventReference());

		// setting the bookmark again after first run shouldn't be picked up as our projector is configured to only read the bookmark at first execution
		es.placeBookmark("someReader", refTwo, Tags.none()); 

		// run another batch of size 1
		
		projectorMetrics = projector.runSingleBatch();
		assertEquals(2, projection.counter()); 
		assertEquals(1, projectorMetrics.queriesDone()); 
		// according to the query and the start situation, event 4 should be read if the bookmark is properly read
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

		// set a bookmark for the Projector to find
		es.placeBookmark("someReader", refFour, Tags.none()); 
		
		var projector = Projector.from(es).towards(projection).bookmarkProgress().withReader("someReader").readBeforeEachExecution().done().inBatchesOf(1).build();
		
		// setting the bookmark again after construction should be picked up as our projector is configured to read the bookmark before each execution
		es.placeBookmark("someReader", refTwo, Tags.none()); 


		// run a batch of size 1

		ProjectorMetrics projectorMetrics = projector.runSingleBatch();
		assertEquals(1, projection.counter()); 
		// according to the query and the start situation, event 3 should be read if the bookmark is properly read
		assertEquals(1, projectorMetrics.queriesDone()); 
		assertEquals(1,  projectorMetrics.eventsStreamed());
		assertEquals(1,  projectorMetrics.eventsHandled());
		assertEquals(refThree, projectorMetrics.lastEventReference());

		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone());
		assertEquals(1,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(1,  accumulatedMetrics.eventsHandled());
		assertEquals(refThree, accumulatedMetrics.lastEventReference());

		// setting the bookmark again after first run should picked up as our projector is configured to read the bookmark before each execution
		es.placeBookmark("someReader", refTwo, Tags.none()); 

		// run another batch of size 1
		
		projectorMetrics = projector.runSingleBatch();
		assertEquals(2, projection.counter()); 
		assertEquals(1, projectorMetrics.queriesDone()); 
		// according to the query and the start situation, event 4 should be read if the bookmark is properly read
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

		// set a bookmark for the Projector to find
		es.placeBookmark("someReader", refFour, Tags.none()); 
		
		var projector = Projector.from(es).towards(projection).bookmarkProgress().withReader("someReader").readOnManualTriggerOnly().done().inBatchesOf(1).build();
		
		// setting the bookmark again after construction should be picked up as our projector is configured to read the bookmark before each execution
		es.placeBookmark("someReader", refTwo, Tags.none()); 


		// run a batch of size 1

		ProjectorMetrics projectorMetrics = projector.runSingleBatch();
		assertEquals(1, projection.counter()); 
		// according to the query and the start situation, event 3 should be read if the bookmark is properly read
		assertEquals(1, projectorMetrics.queriesDone()); 
		assertEquals(1,  projectorMetrics.eventsStreamed());
		assertEquals(1,  projectorMetrics.eventsHandled());
		assertEquals(refOne, projectorMetrics.lastEventReference());

		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone());
		assertEquals(1,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(1,  accumulatedMetrics.eventsHandled());
		assertEquals(refOne, accumulatedMetrics.lastEventReference());

		// run another batch of size 1
		
		projectorMetrics = projector.runSingleBatch();
		assertEquals(2, projection.counter()); 
		assertEquals(1, projectorMetrics.queriesDone()); 
		// according to the query and the start situation, event 4 should be read if the bookmark is properly read
		assertEquals(1,  projectorMetrics.eventsStreamed());
		assertEquals(1,  projectorMetrics.eventsHandled());
		assertEquals(refThree, projectorMetrics.lastEventReference());

		accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(2, accumulatedMetrics.queriesDone());
		assertEquals(2,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(2,  accumulatedMetrics.eventsHandled());
		assertEquals(refThree, accumulatedMetrics.lastEventReference());

		// setting the bookmark again should picked up as our projector is configured to read the bookmark before each execution
		es.placeBookmark("someReader", refTwo, Tags.none());
		
		projector.readBookmark(); // now we should read event three again ...

		projectorMetrics = projector.runSingleBatch();
		assertEquals(3, projection.counter()); 
		// according to the query and the start situation, event 3 should be read if the bookmark is properly read
		assertEquals(1, projectorMetrics.queriesDone()); 
		assertEquals(1,  projectorMetrics.eventsStreamed());
		assertEquals(1,  projectorMetrics.eventsHandled());
		assertEquals(refThree, projectorMetrics.lastEventReference());

		accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(3, accumulatedMetrics.queriesDone());
		assertEquals(3,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(3,  accumulatedMetrics.eventsHandled());
		assertEquals(refThree, accumulatedMetrics.lastEventReference());

	
		// setting the bookmark back at start should picked up as our projector is configured to read the bookmark before each execution
		es.removeBookmark("someReader");
		
		projector.readBookmark(); // now we should read event three again ...

		projectorMetrics = projector.runSingleBatch();
		assertEquals(4, projection.counter()); 
		// according to the query and the start situation, event 3 should be read if the bookmark is properly read
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
		assertEquals(4, projection.counter()); // SecondDomainEvent type is left out by the query
		assertEquals(5, projectorMetrics.queriesDone()); // we now need a query for each one
		assertEquals(4,  projectorMetrics.eventsStreamed());
		assertEquals(4,  projectorMetrics.eventsHandled());

		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(5, accumulatedMetrics.queriesDone());
		assertEquals(4,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(4,  accumulatedMetrics.eventsHandled());

		BatchAwareTestProjection batchAwareProjection = new BatchAwareTestProjection();	
		var batchAwareProjector = Projector.from(es).towards(batchAwareProjection).inBatchesOf(1).build();

		projectorMetrics = batchAwareProjector.run();
		assertEquals(4, batchAwareProjection.counter()); // SecondDomainEvent type is left out by the query
		assertEquals(4, batchAwareProjection.beforeTriggered()); // no batch started when no events
		assertEquals(4, batchAwareProjection.afterTriggered());  // equal amount expected
		assertEquals(0, batchAwareProjection.cancelTriggered());  
		assertEquals(5, projectorMetrics.queriesDone()); // we now need a query for each one
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
		assertEquals(4, projection.counter()); // SecondDomainEvent type is left out by the query
		assertEquals(3, projectorMetrics.queriesDone()); // we now need 3 queries to find all 5. last round, we asked 2 and got 1, so done.
		assertEquals(4,  projectorMetrics.eventsStreamed());
		assertEquals(4,  projectorMetrics.eventsHandled());

		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(3, accumulatedMetrics.queriesDone());
		assertEquals(4,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(4,  accumulatedMetrics.eventsHandled());

		BatchAwareTestProjection batchAwareProjection = new BatchAwareTestProjection();	
		var batchAwareProjector = Projector.from(es).towards(batchAwareProjection).inBatchesOf(2).build();

		projectorMetrics = batchAwareProjector.run();
		assertEquals(4, batchAwareProjection.counter()); // SecondDomainEvent type is left out by the query
		assertEquals(2, batchAwareProjection.beforeTriggered()); // no batch started when no events
		assertEquals(2, batchAwareProjection.afterTriggered());  // equal amount expected
		assertEquals(0, batchAwareProjection.cancelTriggered());  
		assertEquals(3, projectorMetrics.queriesDone()); // we now need 3 queries to find all 5. last round, we asked 2 and got 1, so done.
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
		assertEquals(3, projection.counter()); // SecondDomainEvent type is left out by the query, until removes everything after four
		assertEquals(1, projectorMetrics.queriesDone());
		assertEquals(3,  projectorMetrics.eventsStreamed()); // since we pass in until, we don't stream the extra events
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
		EventStream<MockDomainEvent> alternativeStream = eventStore().getEventStream(stream, MockDomainEvent.class);
		
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
		assertEquals(2, projection.counter()); // second event now also processed by projection 
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
		assertEquals(2, projection.counter()); // SecondDomainEvent type is left out by the query, until removes everything after four
		assertEquals(1, projectorMetrics.queriesDone());
		assertEquals(2,  projectorMetrics.eventsStreamed()); // since we pass "until", we don't stream the extra events 
		assertEquals(2,  projectorMetrics.eventsHandled());
	
		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone());
		assertEquals(2,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(2,  accumulatedMetrics.eventsHandled());
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

	@Override
	public EventStorage createEventStorage() {
		return InMemoryEventStorage.newBuilder().build();
	}

	
}
