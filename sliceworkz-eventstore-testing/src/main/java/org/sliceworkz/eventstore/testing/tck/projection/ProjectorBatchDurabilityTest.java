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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.FirstDomainEvent;

/**
 * What a batch boundary is worth to a projection that writes somewhere durable.
 *
 * <p>A {@link BatchAwareProjection} commits its own work in {@code afterBatch}, and the bookmark
 * recording how far it has come lives in the event store — two stores with no transaction between
 * them. Everything here is about the seam that leaves:
 *
 * <ul>
 *   <li>the bookmark is written <b>per batch</b>, so a crash costs one batch and not the whole
 *       catch-up run that had already committed twenty of them;</li>
 *   <li>a batch whose commit <b>fails</b> takes the projector's cursor back with it, so its events
 *       are offered again rather than skipped — the ordering is at-least-once, never at-most-once;</li>
 *   <li>a failure is reported as a {@link ProjectorException} whatever part of the batch produced it,
 *       so a caller that distinguishes projection failures from anything else still can;</li>
 *   <li>a {@code cancelBatch} that throws never replaces the failure that caused it.</li>
 * </ul>
 */
public class ProjectorBatchDurabilityTest extends AbstractEventStoreTest {

	private static final String READER = "batch-durability";

	private EventStream<MockDomainEvent> es;

	@BeforeEach
	void seedStream ( ) {
		es = eventStore().getEventStream(EventStreamId.forContext("app").withPurpose("batchdurability"), MockDomainEvent.class);
		for ( int i = 1; i <= 6; i++ ) {
			es.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent(String.valueOf(i)), Tags.none())));
		}
	}

	/**
	 * The bookmark moves while the run is still going. Read from inside the second batch it already
	 * names the end of the first — where a bookmark placed once per run would still be absent, and a
	 * crash anywhere in a long catch-up would replay every batch it had committed.
	 */
	@ForEachBackend
	void aBookmarkIsPlacedAfterEveryBatchRatherThanOncePerRun ( ) {
		RecordingProjection projection = new RecordingProjection();
		projection.readBookmarkOnEachAfterBatch(es);

		Projector<MockDomainEvent> projector = Projector.from(es).towards(projection)
				.inBatchesOf(2)
				.bookmarkProgress().withReader(READER).done()
				.build();

		projector.run();

		assertEquals(3, projection.batchEnds().size(), "6 events in batches of 2 is three batches");

		// what the bookmark held when each batch was about to commit
		List<Optional<EventReference>> seen = projection.bookmarksSeen();
		assertTrue(seen.get(0).isEmpty(), "nothing is bookmarked before the first batch commits");
		assertEquals(projection.batchEnds().get(0), seen.get(1).orElse(null),
				"the second batch sees the first one bookmarked -- the bookmark does not wait for the run to end");
		assertEquals(projection.batchEnds().get(1), seen.get(2).orElse(null),
				"and the third sees the second");

		assertEquals(projection.batchEnds().get(2), es.getBookmark(READER).orElse(null),
				"the run ends with the last batch bookmarked");
	}

	/**
	 * A commit that fails means the batch did not land, and the events in it have to come round
	 * again. Advancing the cursor past a rolled-back batch loses those events for good, with the
	 * bookmark eventually written past the hole and nothing raised anywhere.
	 */
	@ForEachBackend
	void aBatchWhoseCommitFailsIsProjectedAgainRatherThanSkipped ( ) {
		RecordingProjection projection = new RecordingProjection();
		projection.failCommitOfBatch(1);

		Projector<MockDomainEvent> projector = Projector.from(es).towards(projection)
				.inBatchesOf(2)
				.bookmarkProgress().withReader(READER).readOnManualTriggerOnly().done()
				.build();

		ProjectorException failure = assertThrows(ProjectorException.class, projector::run,
				"a failed commit is a projection failure, not a bare RuntimeException escaping the projector");
		assertEquals("UNIT TEST FAKED COMMIT FAILURE", failure.getCause().getMessage());

		assertTrue(es.getBookmark(READER).isEmpty(),
				"nothing committed, so nothing is bookmarked");
		assertEquals(2, projection.handled().size(), "the first batch was offered");

		// the same events must be offered again -- the projector may not have moved past them
		projection.handled().clear();
		projector.run();

		assertEquals(6, projection.handled().size(),
				"every event is offered again, including the two whose batch failed to commit");
	}

	/** And the rest of the run is abandoned rather than committed on top of a batch that is not there. */
	@ForEachBackend
	void aBatchWhoseCommitFailsStopsTheRun ( ) {
		RecordingProjection projection = new RecordingProjection();
		projection.failCommitOfBatch(1);

		Projector<MockDomainEvent> projector = Projector.from(es).towards(projection)
				.inBatchesOf(2)
				.bookmarkProgress().withReader(READER).readOnManualTriggerOnly().done()
				.build();

		assertThrows(ProjectorException.class, projector::run);

		assertEquals(1, projection.batchesStarted(), "the batches behind a failed commit are not attempted");
		assertEquals(0, projection.batchesCancelled(),
				"and the batch is not cancelled on top of the commit that already ended it");
	}

	/**
	 * The rollback is a consequence of the failure, so it never becomes the failure. Reporting a
	 * rollback problem in place of the poison event that caused it sends whoever reads the log
	 * looking in the wrong store.
	 */
	@ForEachBackend
	void aRollbackThatFailsDoesNotReplaceTheFailureThatCausedIt ( ) {
		RecordingProjection projection = new RecordingProjection();
		projection.failOnEvent("2");
		projection.failRollback();

		Projector<MockDomainEvent> projector = Projector.from(es).towards(projection)
				.inBatchesOf(2)
				.bookmarkProgress().withReader(READER).readOnManualTriggerOnly().done()
				.build();

		ProjectorException failure = assertThrows(ProjectorException.class, projector::run);

		assertEquals("UNIT TEST FAKED PROBLEM WITH EVENT PROCESSING", failure.getCause().getMessage(),
				"the cause reported is the one that broke the batch");
		assertEquals(1, failure.getCause().getSuppressed().length,
				"the rollback failure is kept, attached to the cause rather than replacing it");
		assertEquals("UNIT TEST FAKED ROLLBACK FAILURE", failure.getCause().getSuppressed()[0].getMessage());

		assertTrue(es.getBookmark(READER).isEmpty(), "a batch that failed is not bookmarked");
	}

	/** A batch that never reached its first matching event has nothing to roll back either. */
	@ForEachBackend
	void aProjectionFailureRollsTheBatchBackAndIsReportedWithItsCause ( ) {
		RecordingProjection projection = new RecordingProjection();
		projection.failOnEvent("4");

		Projector<MockDomainEvent> projector = Projector.from(es).towards(projection)
				.inBatchesOf(2)
				.bookmarkProgress().withReader(READER).readOnManualTriggerOnly().done()
				.build();

		ProjectorException failure = assertThrows(ProjectorException.class, projector::run);
		assertSame(RuntimeException.class, failure.getCause().getClass());

		assertEquals(1, projection.batchesCancelled(), "the failing batch is rolled back");
		assertEquals(projection.batchEnds().get(0), es.getBookmark(READER).orElse(null),
				"the batch that did commit stays bookmarked -- its work is durable and must not be repeated");
		assertFalse(projection.batchEnds().isEmpty());
	}

	/**
	 * A projection recording everything the projector did to it, and able to fail at each of the
	 * three points a real one can: handling an event, committing, and rolling back.
	 */
	private static class RecordingProjection implements BatchAwareProjection<MockDomainEvent> {

		private final List<EventReference> handled = new ArrayList<>();
		private final List<EventReference> batchEnds = new ArrayList<>();
		private final List<Optional<EventReference>> bookmarksSeen = new ArrayList<>();

		private int batchesStarted;
		private int batchesCancelled;

		private EventStream<MockDomainEvent> bookmarkSource;
		private int commitFailsOnBatch = -1;
		private String failOnEvent;
		private boolean rollbackFails;

		private EventReference lastInBatch;

		void readBookmarkOnEachAfterBatch ( EventStream<MockDomainEvent> source ) {
			this.bookmarkSource = source;
		}

		void failCommitOfBatch ( int batchNumber ) {
			this.commitFailsOnBatch = batchNumber;
		}

		void failOnEvent ( String id ) {
			this.failOnEvent = id;
		}

		void failRollback ( ) {
			this.rollbackFails = true;
		}

		List<EventReference> handled ( ) {
			return handled;
		}

		List<EventReference> batchEnds ( ) {
			return batchEnds;
		}

		List<Optional<EventReference>> bookmarksSeen ( ) {
			return bookmarksSeen;
		}

		int batchesStarted ( ) {
			return batchesStarted;
		}

		int batchesCancelled ( ) {
			return batchesCancelled;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			if ( failOnEvent != null && event.data() instanceof FirstDomainEvent first && failOnEvent.equals(first.value()) ) {
				throw new RuntimeException("UNIT TEST FAKED PROBLEM WITH EVENT PROCESSING");
			}
			handled.add(event.reference());
			lastInBatch = event.reference();
		}

		@Override
		public void beforeBatch ( ) {
			batchesStarted++;
			lastInBatch = null;
		}

		@Override
		public void afterBatch ( Optional<EventReference> lastEventReference ) {
			if ( bookmarkSource != null ) {
				bookmarksSeen.add(bookmarkSource.getBookmark(READER));
			}
			if ( batchesStarted == commitFailsOnBatch ) {
				throw new RuntimeException("UNIT TEST FAKED COMMIT FAILURE");
			}
			if ( lastInBatch != null ) {
				batchEnds.add(lastInBatch);
			}
		}

		@Override
		public void cancelBatch ( ) {
			batchesCancelled++;
			if ( rollbackFails ) {
				throw new RuntimeException("UNIT TEST FAKED ROLLBACK FAILURE");
			}
		}

	}

}
