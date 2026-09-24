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
package org.sliceworkz.eventstore.testing.tck.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.junit.jupiter.api.BeforeEach;

public class ProjectorTest extends AbstractEventStoreTest {

	private EventStream<MockDomainEvent> es;

	@BeforeEach
	void seedStream ( ) {
		es = eventStore().getEventStream(EventStreamId.forContext("app").withPurpose("default"), MockDomainEvent.class);

		append(es, new FirstDomainEvent("1"), Tags.of("nr", "one"));
		append(es, new SecondDomainEvent("2"), Tags.of("nr", "two"));
		append(es, new ThirdDomainEvent("3"), Tags.of("nr", "three"));
		append(es, new FirstDomainEvent("4"), Tags.of("nr", "four"));
		append(es, new SecondDomainEvent("5"), Tags.of("nr", "five"));
		append(es, new ThirdDomainEvent("6"), Tags.of("nr", "six"));
	}

	@ForEachBackend
	void testProjector ( ) {
		TestProjection projection = new TestProjection();

		var projector = Projector.from(es).into(projection).build();

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
		var batchAwareProjector = Projector.from(es).into(batchAwareProjection).build();

		projectorMetrics = batchAwareProjector.run();
		assertEquals(4, batchAwareProjection.counter()); // SecondDomainEvent type is left out by the query
		assertEquals(1, batchAwareProjection.beforeTriggered()); // single batch
		assertEquals(1, batchAwareProjection.afterTriggered());  // equal amount expected
		assertEquals(0, batchAwareProjection.cancelTriggered());
	}

	/**
	 * A projector without a source or a projection cannot run, and is refused when it is built rather
	 * than left to fail from inside its first batch.
	 */
	@ForEachBackend
	void aProjectorWithoutASourceOrAProjectionIsRefusedWhenBuilt ( ) {
		TestProjection projection = new TestProjection();

		IllegalArgumentException noSource = assertThrows(IllegalArgumentException.class,
				() -> Projector.<MockDomainEvent>from(null));
		assertEquals("no event source: a projector reads from one, so from(...) needs it", noSource.getMessage());

		IllegalStateException noProjection = assertThrows(IllegalStateException.class,
				() -> Projector.from(es).build());
		assertEquals("no projection configured, call into(...) before build()", noProjection.getMessage());

		// a bookmark read setting says when to read a bookmark, so without a reader there is nothing it can mean
		IllegalStateException noBookmark = assertThrows(IllegalStateException.class,
				() -> Projector.from(es).into(projection).readBookmarkOnce().build());
		assertEquals("no bookmark to read: call bookmarkAs(...) before choosing when the bookmark is read", noBookmark.getMessage());
		assertThrows(IllegalStateException.class, () -> Projector.from(es).into(projection).readBookmarkOnRequest().build());

		assertThrows(IllegalArgumentException.class, () -> Projector.from(es).into(projection).inBatchesOf(0));
		assertThrows(IllegalArgumentException.class, () -> Projector.from(es).into(projection).inBatchesOf(-5));
	}

	/**
	 * The projection's query is read once per run, however many events and batches the run spans:
	 * storage is asked with it and every event is matched against it, and reading it per event would
	 * let the two disagree for a projection that computes its query.
	 */
	@ForEachBackend
	void theProjectionQueryIsReadOncePerRun ( ) {
		CountingQueryProjection projection = new CountingQueryProjection();
		Projector<MockDomainEvent> projector = Projector.from(es).into(projection).inBatchesOf(1).build();
		assertEquals(0, projection.eventQueryReads, "building a projector reads the initialisation query, not the event query");

		ProjectorMetrics metrics = projector.run();

		assertEquals(projection.eventsSeen, metrics.eventsHandled());
		assertTrue(metrics.eventsHandled() > 1, "the run must span several batches for the count to mean anything");
		assertEquals(1, projection.eventQueryReads, "one read for the run, whatever its batch count");

		projector.run();
		assertEquals(2, projection.eventQueryReads, "and one more for the next run");
	}

	@ForEachBackend
	void testFailingProjector ( ) {
		FailingBatchAwareTestProjection batchAwareProjection = new FailingBatchAwareTestProjection();
		var batchAwareProjector = Projector.from(es).into(batchAwareProjection).build();

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

	@ForEachBackend
	void testFailingProjectorInBatchesOf2 ( ) {
		FailingBatchAwareTestProjection batchAwareProjection = new FailingBatchAwareTestProjection();
		var batchAwareProjector = Projector.from(es).into(batchAwareProjection).inBatchesOf(2).build();

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

	@ForEachBackend
	void testProjectorWithBookmarkingWithoutReaderName ( ) {
		TestProjection projection = new TestProjection();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, ()->Projector.from(es).into(projection).bookmarkAs(null));
		assertEquals("bookmarking requires a reader name", e.getMessage());
		assertThrows(IllegalArgumentException.class, ()->Projector.from(es).into(projection).bookmarkAs("  "));
		assertThrows(IllegalArgumentException.class, ()->Projector.from(es).into(projection).bookmarkAs("someReader", null));
	}

	/** A projector named for its observations is named something: a null or blank name is refused. */
	@ForEachBackend
	void testProjectorWithABlankNameIsRefused ( ) {
		TestProjection projection = new TestProjection();

		assertThrows(IllegalArgumentException.class, ()->Projector.from(es).into(projection).named(null));
		assertThrows(IllegalArgumentException.class, ()->Projector.from(es).into(projection).named("  "));
	}

	/**
	 * The tags given with the reader name are stored on the bookmark the projector places, and take no
	 * part in reading it back.
	 */
	@ForEachBackend
	void testProjectorStoresTheBookmarkTagsItWasGiven ( ) {
		TestProjection projection = new TestProjection();
		Tags tags = Tags.of("tenant", "acme");

		Projector.from(es).into(projection).bookmarkAs("taggedReader", tags).inBatchesOf(1).build().runSingleBatch();

		Bookmark bookmark = es.getBookmarks().stream().filter(b -> b.reader().equals("taggedReader")).findFirst().orElseThrow();
		assertEquals(tags, bookmark.tags());
		assertEquals(bookmark.reference(), es.getBookmark("taggedReader").orElseThrow());
	}

	@ForEachBackend
	void testProjectorWithBookmarkOnFirstExecution( ) {
		TestProjection projection = new TestProjection();

		EventReference refTwo = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "two"))).stream().findFirst().get().reference();
		EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).stream().findFirst().get().reference();
		EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).stream().findFirst().get().reference();

		es.placeBookmark("someReader", refFour, Tags.none());

		var projector = Projector.from(es).into(projection).bookmarkAs("someReader").readBookmarkOnce().inBatchesOf(1).build();

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

	@ForEachBackend
	void testProjectorWithBookmarkOnEachExecution( ) {
		TestProjection projection = new TestProjection();

		EventReference refTwo = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "two"))).stream().findFirst().get().reference();
		EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).stream().findFirst().get().reference();
		EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).stream().findFirst().get().reference();

		es.placeBookmark("someReader", refFour, Tags.none());

		var projector = Projector.from(es).into(projection).bookmarkAs("someReader").inBatchesOf(1).build();

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

	/**
	 * A projector configured with nothing but a reader name reads its bookmark before every execution.
	 * That default is what makes bookmarking resume a projection after a restart: a second projector
	 * built the same way picks up where the first one left off, and a bookmark moved elsewhere between
	 * two runs is followed rather than overrun.
	 */
	@ForEachBackend
	void testProjectorReadsTheBookmarkBeforeEachExecutionByDefault ( ) {
		EventReference refOne = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "one"))).stream().findFirst().get().reference();
		EventReference refTwo = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "two"))).stream().findFirst().get().reference();
		EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).stream().findFirst().get().reference();
		EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).stream().findFirst().get().reference();

		// the "first process": no mode chosen, runs one batch and bookmarks it
		TestProjection first = new TestProjection();
		var firstProjector = Projector.from(es).into(first).bookmarkAs("someReader").inBatchesOf(1).build();

		ProjectorMetrics projectorMetrics = firstProjector.runSingleBatch();
		assertEquals(1, first.counter());
		assertEquals(refOne, projectorMetrics.lastEventReference());
		assertEquals(refOne, es.getBookmark("someReader").orElse(null));

		// the "restarted process": a projector built with only the reader name resumes at the bookmark,
		// rather than replaying from the start as one that never reads its bookmark would
		TestProjection second = new TestProjection();
		var secondProjector = Projector.from(es).into(second).bookmarkAs("someReader").inBatchesOf(1).build();

		projectorMetrics = secondProjector.runSingleBatch();
		assertEquals(1, second.counter());
		assertEquals(refThree, projectorMetrics.lastEventReference()); // the next matching event after the bookmark, not the first in the stream
		assertEquals(refThree, es.getBookmark("someReader").orElse(null));

		// a bookmark rewound between two executions of the same projector is followed, where a projector
		// that kept its own cursor would have gone on to the event tagged "four"
		es.placeBookmark("someReader", refTwo, Tags.none());

		projectorMetrics = secondProjector.runSingleBatch();
		assertEquals(2, second.counter());
		assertEquals(refThree, projectorMetrics.lastEventReference());
		assertNotEquals(refFour, projectorMetrics.lastEventReference());
	}

	@ForEachBackend
	void testProjectorWithBookmarkManualTrigger ( ) {
		TestProjection projection = new TestProjection();

		EventReference refOne = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "one"))).stream().findFirst().get().reference();
		EventReference refTwo = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "two"))).stream().findFirst().get().reference();
		EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).stream().findFirst().get().reference();
		EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).stream().findFirst().get().reference();

		es.placeBookmark("someReader", refFour, Tags.none());

		var projector = Projector.from(es).into(projection).bookmarkAs("someReader").readBookmarkOnRequest().inBatchesOf(1).build();

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

	@ForEachBackend
	void testProjectorWithStepOfOne ( ) {
		TestProjection projection = new TestProjection();

		var projector = Projector.from(es).into(projection).inBatchesOf(1).build();

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
		var batchAwareProjector = Projector.from(es).into(batchAwareProjection).inBatchesOf(1).build();

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

	@ForEachBackend
	void testProjectorWithStepOfTwo ( ) {
		TestProjection projection = new TestProjection();

		var projector = Projector.from(es).into(projection).inBatchesOf(2).build();

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
		var batchAwareProjector = Projector.from(es).into(batchAwareProjection).inBatchesOf(2).build();

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

	@ForEachBackend
	void testProjectorQueryUntilCertainEvent ( ) {
		TestProjection projection = new TestProjection();

		EventReference ref = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).stream().findFirst().get().reference();

		var projector = Projector.from(es).into(projection).build();

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

	@ForEachBackend
	void testProjectorMultipleRuns ( ) {
		TestProjection projection = new TestProjection();

		EventStreamId stream = EventStreamId.forContext("app").withPurpose("alternative");
		EventStream<MockDomainEvent> alternativeStream = eventStore().getEventStream(stream, MockDomainEvent.class);

		append(alternativeStream, new FirstDomainEvent("1"), Tags.of("nr", "one"));

		var projector = Projector.from(alternativeStream).into(projection).build();

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

	@ForEachBackend
	void testProjectorStartInStreamQueryUntilCertainEvent ( ) {
		TestProjection projection = new TestProjection();

		EventReference refAfter = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "one"))).stream().findFirst().get().reference();
		EventReference refUntil = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).stream().findFirst().get().reference();

		var projector = Projector.from(es).into(projection).startingAfter(refAfter).build();

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

	@ForEachBackend
	void testProjectorWithInitQuery ( ) {
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery");
		EventStream<MockDomainEvent> initEs = eventStore().getEventStream(stream, MockDomainEvent.class);

		append(initEs, new FirstDomainEvent("10"), Tags.none());
		append(initEs, new FirstDomainEvent("5"), Tags.none());
		append(initEs, new ThirdDomainEvent("savepoint:15"), Tags.none());
		append(initEs, new FirstDomainEvent("3"), Tags.none());
		append(initEs, new FirstDomainEvent("7"), Tags.none());

		InitQueryProjection projection = new InitQueryProjection();
		var projector = Projector.from(initEs).into(projection).build();

		ProjectorMetrics metrics = projector.run();

		assertEquals(3, projection.counter());
		assertEquals("savepoint:15", projection.lastSavepoint());
		assertEquals(2, metrics.queriesDone());
		assertEquals(3, metrics.eventsHandled());
	}

	@ForEachBackend
	void testProjectorWithInitQueryNoSavepointExists ( ) {
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery-nosavepoint");
		EventStream<MockDomainEvent> initEs = eventStore().getEventStream(stream, MockDomainEvent.class);

		append(initEs, new FirstDomainEvent("10"), Tags.none());
		append(initEs, new FirstDomainEvent("5"), Tags.none());
		append(initEs, new FirstDomainEvent("3"), Tags.none());

		InitQueryProjection projection = new InitQueryProjection();
		var projector = Projector.from(initEs).into(projection).build();

		ProjectorMetrics metrics = projector.run();

		assertEquals(3, projection.counter());
		assertNull(projection.lastSavepoint());
		assertEquals(2, metrics.queriesDone());
		assertEquals(3, metrics.eventsHandled());
	}

	@ForEachBackend
	void testProjectorWithInitQueryAndBookmarkingIgnoresInitQuery ( ) {
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery-bookmark");
		EventStream<MockDomainEvent> initEs = eventStore().getEventStream(stream, MockDomainEvent.class);

		append(initEs, new FirstDomainEvent("10"), Tags.none());
		append(initEs, new FirstDomainEvent("5"), Tags.none());
		append(initEs, new ThirdDomainEvent("savepoint:15"), Tags.none());
		append(initEs, new FirstDomainEvent("3"), Tags.none());
		append(initEs, new FirstDomainEvent("7"), Tags.none());

		InitQueryProjection projection = new InitQueryProjection();
		var projector = Projector.from(initEs).into(projection)
				.bookmarkAs("initquery-test-reader")
				.build();

		ProjectorMetrics metrics = projector.run();

		assertEquals(4, projection.counter());
		assertNull(projection.lastSavepoint());
		assertEquals(1, metrics.queriesDone());
		assertEquals(4, metrics.eventsHandled());
	}

	@ForEachBackend
	void testProjectorWithInitQueryMultipleRuns ( ) {
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery-multirun");
		EventStream<MockDomainEvent> initEs = eventStore().getEventStream(stream, MockDomainEvent.class);

		append(initEs, new FirstDomainEvent("10"), Tags.none());
		append(initEs, new ThirdDomainEvent("savepoint:10"), Tags.none());
		append(initEs, new FirstDomainEvent("5"), Tags.none());

		InitQueryProjection projection = new InitQueryProjection();
		var projector = Projector.from(initEs).into(projection).build();

		ProjectorMetrics metrics1 = projector.run();
		assertEquals(2, projection.counter());
		assertEquals("savepoint:10", projection.lastSavepoint());

		append(initEs, new FirstDomainEvent("3"), Tags.none());

		ProjectorMetrics metrics2 = projector.run();
		assertEquals(3, projection.counter());
		assertEquals(1, metrics2.eventsHandled());
	}

	/**
	 * A bounded run bounds the savepoint too. Left unbounded, the init query returns the newest savepoint
	 * in the store -- here one written past the requested point in time -- and the main query then starts
	 * beyond the boundary, so a point-in-time projection silently reports present-day state.
	 */
	@ForEachBackend
	void testProjectorWithInitQueryRunUntilIgnoresLaterSavepoints ( ) {
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery-rununtil");
		EventStream<MockDomainEvent> initEs = eventStore().getEventStream(stream, MockDomainEvent.class);

		append(initEs, new FirstDomainEvent("10"), Tags.none());
		append(initEs, new ThirdDomainEvent("savepoint:10"), Tags.none());
		append(initEs, new FirstDomainEvent("5"), Tags.none());
		append(initEs, new ThirdDomainEvent("savepoint:15"), Tags.none());
		append(initEs, new FirstDomainEvent("3"), Tags.none());

		EventReference until = initEs.query(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())).get(1).reference(); // the "5", written before the second savepoint

		InitQueryProjection projection = new InitQueryProjection();
		var projector = Projector.from(initEs).into(projection).build();

		ProjectorMetrics metrics = projector.runUntil(until);

		assertEquals("savepoint:10", projection.lastSavepoint());
		assertEquals(2, projection.counter()); // the savepoint, then the "5"
		assertEquals(2, metrics.eventsHandled());
	}

	/**
	 * A savepoint handler that throws fails the run the way a failing batch does: as a
	 * {@link ProjectorException} naming the savepoint event, with the cursor back where the run started.
	 * The next run then runs the init query again. The savepoint that fails here is the second one the
	 * init query hands over, so the cursor had already moved to the first: left there, the next run would
	 * skip the init query and start the main query from a read model that was never initialised.
	 */
	@ForEachBackend
	void testProjectorWrapsAFailingSavepointHandlerInAProjectorException ( ) {
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("initquery-failing");
		EventStream<MockDomainEvent> initEs = eventStore().getEventStream(stream, MockDomainEvent.class);

		append(initEs, new FirstDomainEvent("10"), Tags.none());
		EventReference olderSavepoint = append(initEs, new ThirdDomainEvent("savepoint:10"), Tags.none()).get(0).reference();
		append(initEs, new FirstDomainEvent("5"), Tags.none());
		append(initEs, new ThirdDomainEvent("savepoint:15"), Tags.none());
		append(initEs, new FirstDomainEvent("3"), Tags.none());

		FailingSavepointProjection projection = new FailingSavepointProjection();
		var projector = Projector.from(initEs).into(projection).build();

		ProjectorException e = assertThrows(ProjectorException.class, projector::run);
		assertEquals("UNIT TEST FAKED PROBLEM WITH SAVEPOINT", e.getCause().getMessage());
		assertEquals(olderSavepoint, e.getEventReference());
		assertEquals(1, projection.counter()); // the newer savepoint landed, the older one threw

		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone()); // the init query only
		assertEquals(2, accumulatedMetrics.eventsHandled()); // handled, not landed -- as for a failing batch
		assertNull(accumulatedMetrics.lastEventReference()); // back where the run started

		projection.stopFailing();
		ProjectorMetrics metrics = projector.run();

		assertEquals("savepoint:10", projection.lastSavepoint()); // the init query ran again, both savepoints handled
		assertEquals(5, projection.counter()); // the newer savepoint a second time, the older one, then the "5" and the "3"
		assertEquals(2, metrics.queriesDone());
		assertEquals(4, metrics.eventsHandled());
	}

	/**
	 * A manual bookmark read while a run is in progress waits for the run to finish, and then resets the
	 * position. A subscribed projector runs on the storage's notification thread, so a
	 * {@code readBookmark()} from application code is exactly this race; a cursor moved mid-run is
	 * overwritten by the run's next batch, so the read either has no effect or lands between two
	 * batches of one run.
	 */
	@ForEachBackend
	void testReadBookmarkWaitsForARunInProgress ( ) throws InterruptedException {
		BlockingProjection projection = new BlockingProjection();
		var projector = Projector.from(es).into(projection)
				.bookmarkAs("blocking-reader").readBookmarkOnRequest()
				.inBatchesOf(1)
				.build();

		AtomicReference<Throwable> runFailure = new AtomicReference<>();
		Thread run = new Thread(() -> {
			try {
				projector.run();
			} catch ( Throwable t ) {
				runFailure.set(t);
			}
		}, "projector-run");
		run.start();
		assertTrue(projection.entered().await(5, TimeUnit.SECONDS), "the run did not reach the projection");

		Thread read = new Thread(projector::readBookmark, "projector-readBookmark");
		read.start();
		read.join(300);
		assertTrue(read.isAlive(), "readBookmark returned while a run was in progress");

		projection.proceed().countDown();
		run.join(5000);
		read.join(5000);
		assertNull(runFailure.get());
		assertEquals(false, run.isAlive());
		assertEquals(false, read.isAlive());

		assertEquals(4, projection.counter()); // the run finished undisturbed: SecondDomainEvent is left out by the query
		EventReference last = projector.accumulatedMetrics().lastEventReference();
		assertNotNull(last);
		assertEquals(Optional.of(last), es.getBookmark("blocking-reader")); // placed by the run's last batch

		// the read, applied after the run, put the position at that bookmark: nothing is left to project
		ProjectorMetrics next = projector.runSingleBatch();
		assertEquals(0, next.eventsHandled());
		assertEquals(4, projection.counter());
	}

	@ForEachBackend
	void testProjectorBackwardsWithLimitEnforcesTotalLimit ( ) {
		BackwardsLimitProjection projection = new BackwardsLimitProjection();
		var projector = Projector.from(es).into(projection).build();

		ProjectorMetrics metrics = projector.run();
		assertEquals(1, projection.counter());
		assertEquals("4", projection.lastValue());
		assertEquals(1, metrics.eventsStreamed());
		assertEquals(1, metrics.eventsHandled());
	}

	@ForEachBackend
	void testProjectorBackwardsWithLimitReturnsMostRecentEventReference ( ) {
		BackwardsLimitProjection projection = new BackwardsLimitProjection();
		var projector = Projector.from(es).into(projection).build();

		EventReference refFour = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).stream().findFirst().get().reference();

		ProjectorMetrics metrics = projector.run();
		assertEquals(refFour, metrics.mostRecentEventReference());
		assertEquals(refFour, metrics.lastEventReference());
	}

	@ForEachBackend
	void testProjectorBackwardsWithLimitGreaterThanOne ( ) {
		BackwardsLimit3Projection projection = new BackwardsLimit3Projection();
		var projector = Projector.from(es).into(projection).build();

		EventReference refThree = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "three"))).stream().findFirst().get().reference();
		EventReference refSix = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "six"))).stream().findFirst().get().reference();

		ProjectorMetrics metrics = projector.run();
		assertEquals(3, projection.counter());
		assertEquals(3, metrics.eventsStreamed());
		assertEquals(3, metrics.eventsHandled());
		assertEquals(refSix, metrics.mostRecentEventReference());
		assertEquals(refThree, metrics.lastEventReference());
	}

	@ForEachBackend
	void testProjectorForwardMostRecentEqualsLast ( ) {
		TestProjection projection = new TestProjection();
		var projector = Projector.from(es).into(projection).build();

		ProjectorMetrics metrics = projector.run();
		assertEquals(metrics.lastEventReference(), metrics.mostRecentEventReference());
	}

	private List<Event<MockDomainEvent>> append ( EventStream<MockDomainEvent> es, MockDomainEvent event, Tags tags ) {
		return es.append(AppendCriteria.none(), Collections.singletonList(Event.of(event, tags)));
	}

	class TestProjection implements Projection<MockDomainEvent> {

		private int counter;

		@Override
		public void when(Event<MockDomainEvent> event) {
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

	class BackwardsLimit3Projection implements Projection<MockDomainEvent> {

		private int counter;

		@Override
		public void when(Event<MockDomainEvent> event) {
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

	class BackwardsLimitProjection implements Projection<MockDomainEvent> {

		private int counter;
		private String lastValue;

		@Override
		public void when(Event<MockDomainEvent> event) {
			counter++;
			if ( event.data() instanceof FirstDomainEvent f ) {
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

	/**
	 * Counts how often the projector asks for its query.
	 */
	private static final class CountingQueryProjection implements Projection<MockDomainEvent> {

		private int eventQueryReads = 0;
		private int eventsSeen = 0;

		@Override
		public EventQuery eventQuery ( ) {
			eventQueryReads++;
			return EventQuery.matchAll();
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			eventsSeen++;
		}

	}

	/**
	 * The savepoint pattern with two savepoints in the init query, whose handler throws on the second
	 * savepoint it is handed until told to stop.
	 */
	class FailingSavepointProjection implements Projection<MockDomainEvent> {

		private int counter;
		private String lastSavepoint;
		private boolean failing = true;

		@Override
		public EventQuery initQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(ThirdDomainEvent.class), Tags.none()).backwards().limit(2);
		}

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
			if ( event.data() instanceof ThirdDomainEvent t ) {
				if ( failing && lastSavepoint != null ) {
					throw new RuntimeException("UNIT TEST FAKED PROBLEM WITH SAVEPOINT");
				}
				lastSavepoint = t.value();
			}
			counter++;
		}

		public void stopFailing ( ) {
			failing = false;
		}

		public int counter ( ) {
			return counter;
		}

		public String lastSavepoint ( ) {
			return lastSavepoint;
		}

	}

	/**
	 * Blocks inside the handler of the first event until released, so a test can act while a run is in
	 * progress.
	 */
	class BlockingProjection implements Projection<MockDomainEvent> {

		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch proceed = new CountDownLatch(1);
		private volatile int counter;

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, ThirdDomainEvent.class), Tags.none());
		}

		@Override
		public void when(Event<MockDomainEvent> event) {
			counter++;
			if ( counter == 1 ) {
				entered.countDown();
				try {
					if ( !proceed.await(10, TimeUnit.SECONDS) ) {
						throw new IllegalStateException("the test never released the projection");
					}
				} catch ( InterruptedException e ) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
			}
		}

		public CountDownLatch entered ( ) {
			return entered;
		}

		public CountDownLatch proceed ( ) {
			return proceed;
		}

		public int counter ( ) {
			return counter;
		}

	}

}
