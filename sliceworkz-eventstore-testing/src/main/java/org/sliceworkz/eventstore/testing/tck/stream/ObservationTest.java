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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.observability.EventStoreObserver;
import org.sliceworkz.eventstore.observability.Observation;
import org.sliceworkz.eventstore.observability.Outcome;
import org.sliceworkz.eventstore.observability.StreamInfo;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventPage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.stream.Subscription;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.RecordingObserver;
import org.sliceworkz.eventstore.testing.RecordingObserver.Recording;
import org.sliceworkz.eventstore.testing.RecordingObserver.Signal;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.SecondDomainEvent;

/**
 * What a store reports to its {@link EventStoreObserver}: every operation as a scope that is started,
 * completed or failed exactly once, and closed, on the caller's thread — with what it was asked and
 * what it answered.
 * <p>
 * The observations are a contract of the store, not of a backend, but they carry what the backend
 * answered — the stored events a read returned, whether an append was swallowed as a retry or refused at
 * its boundary — so they are pinned per backend. Every scenario also asserts that the
 * {@link RecordingObserver} found no breach of the scope contract along the way.
 */
public class ObservationTest extends AbstractEventStoreTest {

	private static final EventStreamId STREAM = EventStreamId.forContext("observed").withPurpose("one");

	private RecordingObserver observer;
	private EventStore observedStore;

	private EventStream<MockDomainEvent> stream ( ) {
		if ( observer == null ) {
			observer = new RecordingObserver();
			observedStore = EventStore.on(eventStorage()).observer(observer).build();
		}
		return observedStore.getEventStream(STREAM, MockDomainEvent.class);
	}

	private void assertNoViolations ( ) {
		assertEquals(List.of(), observer.violations(), "the store breached the scope contract");
		assertTrue(observer.recordings().stream().allMatch(Recording::closed), "every scope is closed");
		observedStore.close();
	}

	// --- appends --------------------------------------------------------------------------------------

	@ForEachBackend
	void anAppendIsReportedWithWhatItCarriedAndWhatItStored ( ) {
		EventStream<MockDomainEvent> stream = stream();

		List<Event<MockDomainEvent>> appended = stream.append(AppendCriteria.none(), List.of(
				Event.of(new FirstDomainEvent("a"), Tags.none()),
				Event.of(new FirstDomainEvent("b"), Tags.none()),
				Event.of(new SecondDomainEvent("c"), Tags.none())));

		Recording recording = observer.last(Observation.Append.class);
		Observation.Append append = recording.observation(Observation.Append.class);
		assertEquals(new StreamInfo(eventStorage().name(), STREAM, true), append.stream());
		assertEquals(Map.of(EventType.of(FirstDomainEvent.class), 2, EventType.of(SecondDomainEvent.class), 1), append.submittedPerType());
		assertFalse(append.conditional(), "an append without criteria is not conditional");
		assertEquals(0, append.idempotencyKeys());

		Outcome.Appended outcome = recording.outcome(Outcome.Appended.class);
		assertEquals(3, outcome.stored());
		assertEquals(Map.of(EventType.of(FirstDomainEvent.class), 2, EventType.of(SecondDomainEvent.class), 1), outcome.storedPerType());
		assertEquals(appended.getLast().reference(), outcome.last(), "the last stored event is the last one handed back");
		assertFalse(outcome.storageTime().isNegative());
		assertTrue(recording.parent().isEmpty(), "an append made by the caller nests under nothing");
		assertEquals(Thread.currentThread().getName(), recording.thread(), "observed on the caller's thread");
		assertNoViolations();
	}

	/**
	 * A DCB conflict is the store answering a stale decision, and is reported as that answer —
	 * {@link Outcome.Conflicted} — and never as a failure, while the caller still receives the exception.
	 */
	@ForEachBackend
	void aConflictAtTheBoundaryCompletesAsConflictedAndIsNotAFailure ( ) {
		EventStream<MockDomainEvent> stream = stream();
		EventQuery boundary = EventQuery.forTypes(FirstDomainEvent.class);
		Optional<org.sliceworkz.eventstore.events.EventReference> head = stream.head();
		stream.append(Event.of(new FirstDomainEvent("meanwhile"), Tags.none()));

		assertThrows(OptimisticLockingException.class, () -> stream.append(
				AppendCriteria.of(boundary, head.orElse(null)), Event.of(new FirstDomainEvent("stale"), Tags.none())));

		Recording recording = observer.last(Observation.Append.class);
		assertTrue(recording.observation(Observation.Append.class).conditional());
		Outcome.Conflicted conflicted = recording.outcome(Outcome.Conflicted.class);
		assertEquals(boundary.filter(), conflicted.boundary(), "the boundary the caller decided on");
		assertEquals(head, conflicted.expected());
		assertTrue(recording.failure().isEmpty(), "a conflict is an answer, not a failure");
		assertNoViolations();
	}

	// --- reads ----------------------------------------------------------------------------------------

	@ForEachBackend
	void aQueryAndAPageAreReportedAsReadsWithWhatTheyRead ( ) {
		EventStream<MockDomainEvent> stream = stream();
		List<Event<MockDomainEvent>> appended = stream.append(List.of(
				Event.of(new FirstDomainEvent("a"), Tags.none()),
				Event.of(new SecondDomainEvent("b"), Tags.none()),
				Event.of(new FirstDomainEvent("c"), Tags.none())));
		observer.clear();

		EventQuery firsts = EventQuery.forTypes(FirstDomainEvent.class);
		assertEquals(2, stream.query(firsts).size());
		Recording query = observer.last(Observation.Query.class);
		assertEquals(firsts.filter(), query.observation(Observation.Query.class).filter(), "the caller's own filter");
		assertTrue(query.observation(Observation.Query.class).after().isEmpty());
		Outcome.Read read = query.outcome(Outcome.Read.class);
		assertEquals(2, read.storedEventsRead());
		assertEquals(Map.of(EventType.of(FirstDomainEvent.class), 2), read.readPerStoredType());
		assertEquals(2, read.eventsReturned());

		EventPage<MockDomainEvent> page = stream.page(EventQuery.matchAll().limit(2), appended.getFirst().reference());
		assertEquals(2, page.events().size());
		Recording paged = observer.last(Observation.Query.class);
		assertEquals(Optional.of(appended.getFirst().reference()), paged.observation(Observation.Query.class).after());
		assertEquals(2, paged.outcome(Outcome.Read.class).storedEventsRead());
		assertEquals(2, observer.recordings(Observation.Query.class).size(), "a page is a read like a query");
		assertNoViolations();
	}

	@ForEachBackend
	void aLookupByIdReportsWhetherItFoundTheEvent ( ) {
		EventStream<MockDomainEvent> stream = stream();
		Event<MockDomainEvent> appended = stream.append(Event.of(new FirstDomainEvent("a"), Tags.none())).getFirst();

		stream.getEventById(appended.reference().id());
		assertTrue(observer.last(Observation.GetEvent.class).outcome(Outcome.Found.class).found());

		EventId unknown = EventId.create();
		stream.getEventById(unknown);
		assertEquals(unknown, observer.last(Observation.GetEvent.class).observation(Observation.GetEvent.class).id());
		assertFalse(observer.last(Observation.GetEvent.class).outcome(Outcome.Found.class).found());
		assertNoViolations();
	}

	@ForEachBackend
	void bookmarksAreReportedWhenPlacedReadAndListed ( ) {
		EventStream<MockDomainEvent> stream = stream();
		Event<MockDomainEvent> appended = stream.append(Event.of(new FirstDomainEvent("a"), Tags.none())).getFirst();

		stream.placeBookmark("reader", appended.reference(), Tags.none());
		Recording placed = observer.last(Observation.PlaceBookmark.class);
		assertEquals("reader", placed.observation(Observation.PlaceBookmark.class).reader());
		placed.outcome(Outcome.Done.class);

		stream.getBookmark("reader");
		assertTrue(observer.last(Observation.GetBookmark.class).outcome(Outcome.Found.class).found());
		stream.getBookmark("nobody");
		assertFalse(observer.last(Observation.GetBookmark.class).outcome(Outcome.Found.class).found());

		stream.getBookmarks();
		assertEquals(1, observer.last(Observation.ListBookmarks.class).outcome(Outcome.Counted.class).count());
		assertNoViolations();
	}

	/**
	 * A read that fails is reported as the failure the caller receives: here a stored event the stream's
	 * mappings cannot read.
	 */
	@ForEachBackend
	void aReadThatCannotBeAnsweredIsReportedAsFailed ( ) {
		stream().append(Event.of(new FirstDomainEvent("a"), Tags.none()));
		EventStream<OtherEvent> other = observedStore.getEventStream(STREAM, OtherEvent.class);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> other.query(EventQuery.matchAll()));
		Recording query = observer.last(Observation.Query.class);
		assertSame(thrown, query.failure().orElseThrow());
		assertTrue(query.outcome().isEmpty());
		assertNoViolations();
	}

	// --- projectors -----------------------------------------------------------------------------------

	/**
	 * A projector reports each batch as an observation of its own, with the batch's page query and its
	 * bookmark placement nested inside it — which is what makes a batch the parent span of its reads.
	 */
	@ForEachBackend
	void aProjectorBatchWrapsItsPageQueryAndItsBookmark ( ) {
		EventStream<MockDomainEvent> stream = stream();
		stream.append(List.of(
				Event.of(new FirstDomainEvent("a"), Tags.none()),
				Event.of(new SecondDomainEvent("b"), Tags.none()),
				Event.of(new FirstDomainEvent("c"), Tags.none())));
		observer.clear();

		CountingProjection projection = new CountingProjection(EventQuery.forTypes(FirstDomainEvent.class));
		Projector.from(stream).into(projection).bookmarkAs("counter").inBatchesOf(2).build().run();
		assertEquals(2, projection.handled.get());

		List<Recording> batches = observer.recordings(Observation.ProjectorBatch.class);
		assertFalse(batches.isEmpty());
		Recording first = batches.getFirst();
		Observation.ProjectorBatch batch = first.observation(Observation.ProjectorBatch.class);
		assertEquals(Observation.ProjectorBatch.Phase.BATCH, batch.phase());
		assertEquals("CountingProjection", batch.projection());
		assertEquals(Optional.of("counter"), batch.reader());
		assertEquals(STREAM, batch.stream().stream());

		int handled = batches.stream().mapToInt(r -> r.outcome(Outcome.Projected.class).eventsHandled()).sum();
		assertEquals(2, handled, "every event handed to the projection is reported by the batch that handed it");
		assertTrue(batches.stream().anyMatch(r -> r.outcome(Outcome.Projected.class).bookmarked()));

		for ( Recording query : observer.recordings(Observation.Query.class) ) {
			assertTrue(query.parent().isPresent() && query.parent().get().observation() instanceof Observation.ProjectorBatch,
					"a projector's page query nests under its batch: " + query);
		}
		for ( Recording bookmark : observer.recordings(Observation.PlaceBookmark.class) ) {
			assertTrue(bookmark.parent().isPresent() && bookmark.parent().get().observation() instanceof Observation.ProjectorBatch,
					"a projector's bookmark nests under its batch: " + bookmark);
		}
		assertNoViolations();
	}

	/**
	 * A projector named on its builder reports its batches under that name — what a framework wrapping its
	 * own components in one adapter class needs, or every one of them is reported under the adapter's name.
	 * Unnamed, an anonymous projection falls back to its full class name, having no simple one.
	 */
	@ForEachBackend
	void aNamedProjectorReportsItsBatchesUnderItsName ( ) {
		EventStream<MockDomainEvent> stream = stream();
		stream.append(Event.of(new FirstDomainEvent("a"), Tags.none()));
		observer.clear();

		Projector.from(stream).into(new CountingProjection(EventQuery.forTypes(FirstDomainEvent.class))).named("account-balances").build().run();
		assertEquals("account-balances", observer.last(Observation.ProjectorBatch.class).observation(Observation.ProjectorBatch.class).projection());

		CountingProjection anonymous = new CountingProjection(EventQuery.forTypes(FirstDomainEvent.class)) { };
		Projector.from(stream).into(anonymous).build().run();
		assertEquals(anonymous.getClass().getName(), observer.last(Observation.ProjectorBatch.class).observation(Observation.ProjectorBatch.class).projection());
		assertNoViolations();
	}

	@ForEachBackend
	void aSavepointReadIsReportedAsTheInitPhase ( ) {
		EventStream<MockDomainEvent> stream = stream();
		stream.append(List.of(
				Event.of(new SecondDomainEvent("savepoint"), Tags.none()),
				Event.of(new FirstDomainEvent("after"), Tags.none())));
		observer.clear();

		CountingProjection projection = new CountingProjection(EventQuery.forTypes(FirstDomainEvent.class)) {
			@Override
			public EventQuery initQuery ( ) {
				return EventQuery.forTypes(SecondDomainEvent.class).backwards().limit(1);
			}
		};
		Projector.from(stream).into(projection).build().run();

		Recording init = observer.recordings(Observation.ProjectorBatch.class).getFirst();
		assertEquals(Observation.ProjectorBatch.Phase.INIT, init.observation(Observation.ProjectorBatch.class).phase());
		assertEquals(1, init.outcome(Outcome.Projected.class).eventsHandled());
		assertFalse(init.outcome(Outcome.Projected.class).bookmarked());
		assertNoViolations();
	}

	@ForEachBackend
	void aFailingProjectionFailsItsBatch ( ) {
		EventStream<MockDomainEvent> stream = stream();
		stream.append(Event.of(new FirstDomainEvent("a"), Tags.none()));
		observer.clear();

		Projection<MockDomainEvent> failing = new CountingProjection(EventQuery.matchAll()) {
			@Override
			public void when ( Event<MockDomainEvent> event ) {
				throw new IllegalStateException("projection bug");
			}
		};
		RuntimeException thrown = assertThrows(RuntimeException.class, () -> Projector.from(stream).into(failing).build().run());

		Recording batch = observer.last(Observation.ProjectorBatch.class);
		assertSame(thrown, batch.failure().orElseThrow(), "the batch fails with what the caller receives");
		assertNoViolations();
	}

	// --- lifecycle ------------------------------------------------------------------------------------

	@ForEachBackend
	void subscriptionsAreReportedOpenedAndClosed ( ) {
		EventStream<MockDomainEvent> stream = stream();
		assertEquals(1, observer.signals(Signal.StreamOpened.class).size(), "the handle was reported opened");

		Subscription first = stream.subscribe(atLeastUntil -> atLeastUntil);
		stream.subscribe(atLeastUntil -> atLeastUntil);
		assertEquals(2, observer.liveSubscriptions());

		first.close();
		first.close();
		assertEquals(1, observer.liveSubscriptions(), "closing a handle ends that subscription, once");

		observedStore.close();
		assertEquals(0, observer.liveSubscriptions(), "closing the store ends the rest");
		assertEquals(List.of(), observer.violations());
	}

	// --- erasure --------------------------------------------------------------------------------------

	@ForEachBackend
	void anErasureIsReportedWithTheCategoriesItErased ( ) {
		observer = new RecordingObserver();
		observedStore = EventStore.on(eventStorage()).observer(observer)
				.shredding(AesGcmShreddingCodec.over(backend().shreddingKeyStore(eventStorage()))).build();
		DataSubject alice = DataSubject.of("customer", "alice-42");
		observedStore.getEventStream(STREAM, ProfileEvent.class)
				.append(Event.of(new ProfileEvent.NameRecorded(Shreddable.of("Alice", alice)), Tags.none()));

		observedStore.erase("customer", "alice-42", ErasureReason.of("art.17"));
		Recording erase = observer.last(Observation.Erase.class);
		Observation.Erase asked = erase.observation(Observation.Erase.class);
		assertEquals("alice-42", asked.subjectId());
		assertTrue(asked.category().isEmpty(), "a whole-person erasure names no category");
		Outcome.Erased erased = erase.outcome(Outcome.Erased.class);
		assertEquals(1, erased.keysShredded());
		assertEquals(List.of(alice.category()), erased.categories());

		observedStore.eraseCategory(alice, ErasureReason.of("again"));
		Outcome.Erased again = observer.last(Observation.Erase.class).outcome(Outcome.Erased.class);
		assertEquals(0, again.keysShredded(), "nothing left to erase");
		assertEquals(List.of(), again.categories());
		assertNoViolations();
	}

	// --- containment ----------------------------------------------------------------------------------

	/**
	 * An observer that throws costs its own recording and nothing else: the operation completes as if
	 * nothing observed it.
	 */
	@ForEachBackend
	void anObserverThatThrowsDoesNotFailTheOperation ( ) {
		EventStoreObserver throwing = new EventStoreObserver() {
			@Override
			public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
				return new Observation.Scope<>() {
					@Override public void completed ( O outcome ) { throw new IllegalStateException("broken observer"); }
					@Override public void failed ( Throwable failure ) { throw new IllegalStateException("broken observer"); }
					@Override public void close ( ) { throw new IllegalStateException("broken observer"); }
				};
			}
			@Override
			public void streamOpened ( StreamInfo stream ) {
				throw new IllegalStateException("broken observer");
			}
		};
		try ( EventStore store = EventStore.on(eventStorage()).observer(throwing).build() ) {
			EventStream<MockDomainEvent> stream = store.getEventStream(STREAM, MockDomainEvent.class);
			stream.append(Event.of(new FirstDomainEvent("a"), Tags.none()));
			assertEquals(1, stream.query(EventQuery.matchAll()).size());
		}
	}

	// --- fixtures -------------------------------------------------------------------------------------

	/** A hierarchy the stream's events are not stored under, so reading them through it fails. */
	public sealed interface OtherEvent {
		/** @param value anything */
		record Unrelated ( String value ) implements OtherEvent { }
	}

	/** An event carrying personal data, so a subject has a key to erase. */
	public sealed interface ProfileEvent {
		/** @param name the subject's name */
		record NameRecorded ( Shreddable<String> name ) implements ProfileEvent { }
	}

	private static class CountingProjection implements Projection<MockDomainEvent> {

		private final EventQuery query;
		private final AtomicInteger handled = new AtomicInteger();

		CountingProjection ( EventQuery query ) {
			this.query = query;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return query;
		}

		@Override
		public void when ( Event<MockDomainEvent> event ) {
			handled.incrementAndGet();
		}

	}

}
