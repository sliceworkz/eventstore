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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;

/**
 * A storage notifies its listeners holding none of its own locks, so a listener that waits for another
 * thread to use the storage is not waiting on itself.
 * <p>
 * A listener runs code the storage does not control, and what it waits for is its own business: a
 * logger's appender lock, an executor's queue, a thread it hands the notification to. Notified with the
 * storage locked, a listener waiting for a thread that needs the storage never gets an answer. On Java 21
 * the same shape is a deadlock of the whole process without any listener waiting on the storage itself:
 * a virtual thread that waits while holding a monitor, or waits to enter one, pins its carrier thread, so
 * a listener waiting for a lock under the storage's monitor, with the other carriers pinned by threads
 * waiting to enter it, leaves that lock's next owner no carrier to run on. Nothing is thrown or logged;
 * everything that touches the storage simply stops.
 * <p>
 * Each scenario has the listener hand a read of the storage to another thread and wait for it, bounded,
 * so a storage that notifies under its lock fails the scenario instead of hanging the build.
 */
public class ListenerMayUseTheStorageTest extends AbstractEventStoreTest {

	private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

	private final ExecutorService otherThread = Executors.newSingleThreadExecutor();
	private final List<String> appendProbes = new CopyOnWriteArrayList<>();
	private final List<String> bookmarkProbes = new CopyOnWriteArrayList<>();

	@AfterEach
	void stopTheOtherThread ( ) {
		otherThread.shutdownNow();
	}

	/** Reads the storage on another thread and waits for it, recording whether the read came back. */
	private String readOnAnotherThread ( ) {
		Future<?> read = otherThread.submit(( ) -> eventStorage().head(EventStreamId.anyContext()));
		try {
			read.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			return "read";
		} catch ( TimeoutException e ) {
			read.cancel(true);
			return "timed out: the storage was still locked while it notified";
		} catch ( Exception e ) {
			return "failed: " + e;
		}
	}

	private void probeOnEveryNotification ( ) {
		eventStorage().subscribe(new EventStorage.EventStoreListener() {
			@Override
			public void notify ( EventStorage.AppendsToEventStoreNotification newEventsInStore ) {
				appendProbes.add(readOnAnotherThread());
			}

			@Override
			public void notify ( EventStorage.BookmarkPlacedNotification bookmarkPlaced ) {
				bookmarkProbes.add(readOnAnotherThread());
			}
		});
	}

	private EventReference appendOne ( ) {
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(
				EventStreamId.forContext("listener").withPurpose("probe"), MockDomainEvent.class);
		return stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("probe"), Tags.none())).getLast().reference();
	}

	@ForEachBackend
	void anAppendListenerMayWaitForAnotherThreadReadingTheStorage ( ) {
		probeOnEveryNotification();

		appendOne();

		waitBecauseOfEventualConsistency(( ) -> !appendProbes.isEmpty(), PROBE_TIMEOUT.multipliedBy(2));
		assertEquals(List.of("read"), appendProbes);
	}

	@ForEachBackend
	void aBookmarkListenerMayWaitForAnotherThreadReadingTheStorage ( ) {
		EventReference event = appendOne();
		probeOnEveryNotification();

		eventStorage().bookmark("listener-probe", event, Tags.none());
		waitBecauseOfEventualConsistency(( ) -> bookmarkProbes.size() == 1, PROBE_TIMEOUT.multipliedBy(2));

		eventStorage().bookmarkReadPosition("listener-probe-read-position", event, Tags.none());
		waitBecauseOfEventualConsistency(( ) -> bookmarkProbes.size() == 2, PROBE_TIMEOUT.multipliedBy(2));

		assertEquals(List.of("read", "read"), bookmarkProbes);
	}

}
