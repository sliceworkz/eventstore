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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.SecondDomainEvent;

/**
 * A consistency boundary must admit exactly one of several simultaneous appends.
 * <p>
 * Every other scenario in {@link OptimisticLockingTest} drives the boundary one thread at a time,
 * which proves the check <em>reads</em> correctly but says nothing about whether it is atomic. It is
 * the atomicity that the DCB guarantee rests on: if a storage evaluates "has anything matching my
 * filter arrived since my reference?" against a snapshot that another appender's uncommitted write
 * is not in yet, both appends see an empty boundary and both are admitted. Nothing fails, nothing is
 * logged, and the invariant the caller expressed is simply gone.
 * <p>
 * A backend that serializes its whole append path (the in-memory one synchronizes on the store)
 * satisfies this by construction. A SQL backend does not get it for free: it has to make the check
 * and the insert one indivisible step, because the conflicting row is a <em>phantom</em> at the
 * moment of the check and no row lock can be taken on a row that does not exist yet.
 * <p>
 * The scenario therefore fires several threads at one boundary from a common start signal and
 * asserts that exactly one wins and every other loser gets an {@link OptimisticLockingException}.
 * It repeats over independent boundaries because losing this race is probabilistic — a single round
 * can come out right by luck on a storage that has no atomicity at all.
 */
public class ConcurrentOptimisticLockingTest extends AbstractEventStoreTest {

	private static final String UNITTEST_BOUNDEDCONTEXT = "unittest";

	/**
	 * Contenders per boundary. Kept modest on purpose: the backends under test are configured with
	 * small connection pools, so beyond a handful the extra threads queue for a connection rather
	 * than race, and only lengthen the run.
	 */
	private static final int CONTENDERS = 8;

	/** Independent boundaries contended in turn — see the class comment on why one is not enough. */
	private static final int ROUNDS = 20;

	@ForEachBackend
	void exactlyOneOfManySimultaneousAppendsToTheSameBoundaryIsAdmitted ( ) throws Exception {

		EventStream<MockDomainEvent> eventStream = createEventStream();
		ExecutorService contenders = Executors.newFixedThreadPool(CONTENDERS);

		try {
			for ( int round = 0; round < ROUNDS; round++ ) {

				// each round gets its own tag, so the rounds are independent boundaries and a round's
				// outcome cannot be explained by what an earlier round left behind
				Tags boundaryTags = Tags.of("boundary", "round-%d".formatted(round));
				EventQuery boundaryQuery = EventQuery.forEvents(EventTypesFilter.any(), boundaryTags);

				// the fact everyone is about to decide on: one event, which everyone reads as the head
				// of the boundary before appending against it
				eventStream.append(AppendCriteria.none(),
						List.of(Event.of(new FirstDomainEvent("anchor-%d".formatted(round)), boundaryTags)));

				EventReference anchor = eventStream.query(boundaryQuery).toList().getLast().reference();
				assertNotNull(anchor);

				// every contender appends against the same reference, exactly as N replicas of one
				// decider would after all reading the same history
				AppendCriteria criteria = AppendCriteria.of(boundaryQuery, anchor);

				CountDownLatch startTogether = new CountDownLatch(1);
				CountDownLatch allDone = new CountDownLatch(CONTENDERS);
				AtomicInteger admitted = new AtomicInteger();
				AtomicInteger rejected = new AtomicInteger();
				AtomicReference<Throwable> unexpected = new AtomicReference<>();

				for ( int contender = 0; contender < CONTENDERS; contender++ ) {
					int id = contender;
					contenders.submit(() -> {
						try {
							startTogether.await();
							eventStream.append(criteria,
									List.of(Event.of(new SecondDomainEvent("contender-%d".formatted(id)), boundaryTags)));
							admitted.incrementAndGet();
						} catch (OptimisticLockingException e) {
							// the expected outcome for everyone who lost the race
							rejected.incrementAndGet();
						} catch (Throwable t) {
							unexpected.compareAndSet(null, t);
						} finally {
							allDone.countDown();
						}
					});
				}

				startTogether.countDown();
				assertEquals(true, allDone.await(60, TimeUnit.SECONDS),
						"round %d: not every contender finished within 60s".formatted(round));

				if ( unexpected.get() != null ) {
					throw new AssertionError("round %d: a contender failed with something other than an optimistic lock"
							.formatted(round), unexpected.get());
				}

				assertEquals(1, admitted.get(),
						"round %d: %d of %d simultaneous appends were admitted to the same consistency boundary — the boundary only permits one"
							.formatted(round, admitted.get(), CONTENDERS));
				assertEquals(CONTENDERS - 1, rejected.get(),
						"round %d: every append but the winner should have been rejected with an OptimisticLockingException".formatted(round));

				// and the store must agree with what the callers were told: the anchor plus one winner
				assertEquals(2, eventStream.query(boundaryQuery).count(),
						"round %d: the boundary should hold the anchor and exactly one appended event".formatted(round));
			}
		} finally {
			contenders.shutdownNow();
			contenders.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	private EventStream<MockDomainEvent> createEventStream ( ) {
		return EventStoreFactory.get().eventStore(eventStorage()).getEventStream(EventStreamId.forContext(UNITTEST_BOUNDEDCONTEXT), MockDomainEvent.class);
	}

}
