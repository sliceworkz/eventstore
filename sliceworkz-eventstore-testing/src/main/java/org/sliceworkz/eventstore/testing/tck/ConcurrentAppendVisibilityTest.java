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
package org.sliceworkz.eventstore.testing.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;

/**
 * A reader tailing the stream must never skip an event, however the appends were interleaved.
 * <p>
 * This is the guarantee that costs the most to get right and is the easiest to lose. Positions are
 * handed out when an append starts but only become visible when it commits, so a reader that has
 * advanced its cursor past position N can be overtaken by a slower transaction that reserved N-1
 * earlier and commits later — and that event is then never seen again. The PostgreSQL
 * implementation avoids it by reading only below {@code pg_snapshot_xmin(pg_current_snapshot())};
 * any other storage has to solve the same problem some other way.
 * <p>
 * The scenario appends concurrently from several threads while tailing with a cursor, exactly as a
 * projector does, and asserts that every committed event is eventually observed exactly once. A
 * storage that reads uncommitted-adjacent positions fails here; one that returns events out of
 * order but never drops them passes, which is the actual contract.
 */
public class ConcurrentAppendVisibilityTest extends AbstractEventStoreTest {

	private static final int WRITERS = 4;
	private static final int EVENTS_PER_WRITER = 25;
	private static final int TOTAL = WRITERS * EVENTS_PER_WRITER;

	@ForEachBackend
	void tailingReaderSeesEveryConcurrentlyAppendedEvent ( ) throws Exception {

		EventStreamId streamId = EventStreamId.forContext("concurrency").withPurpose("visibility");
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		ExecutorService writers = Executors.newFixedThreadPool(WRITERS);
		CountDownLatch startTogether = new CountDownLatch(1);
		CountDownLatch allWritten = new CountDownLatch(WRITERS);
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();

		try {
			for ( int writer = 0; writer < WRITERS; writer++ ) {
				int id = writer;
				writers.submit(() -> {
					try {
						startTogether.await();
						for ( int i = 0; i < EVENTS_PER_WRITER; i++ ) {
							stream.append(AppendCriteria.none(),
									Event.of(new FirstDomainEvent("w%d-%d".formatted(id, i)), Tags.of("writer", String.valueOf(id))));
						}
					} catch (Throwable t) {
						writerFailure.compareAndSet(null, t);
					} finally {
						allWritten.countDown();
					}
				});
			}

			// tail from the start, advancing the cursor exactly as a projector would
			Set<String> seen = new HashSet<>();
			List<String> inObservedOrder = new ArrayList<>();
			EventReference cursor = null;

			startTogether.countDown();

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			boolean writersDone = false;
			while ( seen.size() < TOTAL && System.nanoTime() < deadline ) {
				List<Event<MockDomainEvent>> batch = stream.query(EventQuery.matchAll(), cursor).toList();
				for ( Event<MockDomainEvent> event : batch ) {
					String id = event.reference().id().value();
					assertTrue(seen.add(id), "the same event was observed twice while tailing: " + event.data());
					inObservedOrder.add(id);
				}
				if ( !batch.isEmpty() ) {
					cursor = batch.getLast().reference();
				} else if ( writersDone ) {
					// nothing new and nobody is writing any more: one more pass would find nothing either
					break;
				}
				writersDone = allWritten.await(batch.isEmpty() ? 50 : 0, TimeUnit.MILLISECONDS);
			}

			if ( writerFailure.get() != null ) {
				throw new AssertionError("an appending thread failed", writerFailure.get());
			}

			assertEquals(TOTAL, seen.size(),
					"a tailing reader missed %d of %d concurrently appended events".formatted(TOTAL - seen.size(), TOTAL));
			assertEquals(TOTAL, inObservedOrder.size(), "an event was observed more than once");
			assertEquals(TOTAL, stream.query(EventQuery.matchAll()).count(), "not every append reached the store");
		} finally {
			writers.shutdownNow();
			writers.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

}
