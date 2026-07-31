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
package org.sliceworkz.eventstore.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;

class OptimizingApendListenerDecoratorTest {

	/**
	 * Enough pairs to catch a lost notification; the window is narrow, so the count is what makes this
	 * a net rather than a formality. The defect it guards against showed up about once in 20.000 pairs.
	 */
	private static final int RACING_PAIRS = 40_000;

	private static EventReference reference ( long position ) {
		return EventReference.of(EventId.of(UUID.randomUUID().toString()), position, 1);
	}

	/**
	 * Two notifications arriving at once must never lose the later one.
	 * <p>
	 * This is what a storage does when one append lands several events: the backend notifies per event,
	 * and the notifications are handed to the listener on separate threads. If the later one is
	 * dropped, the listener is left believing the stream ends at the earlier event, and anything
	 * waiting for it — a projection, a test — waits forever. The failure is invisible from the
	 * appending side, which succeeded.
	 */
	@Test
	void testLaterOfTwoSimultaneousNotificationsIsNeverLost ( ) throws InterruptedException {
		int lost = 0;
		try ( ExecutorService notifiers = Executors.newVirtualThreadPerTaskExecutor() ) {
			for ( int pair = 0; pair < RACING_PAIRS; pair++ ) {
				AtomicReference<EventReference> seenByDelegate = new AtomicReference<>();
				OptimizingApendListenerDecorator decorator = new OptimizingApendListenerDecorator(
						reference -> {
							seenByDelegate.set(reference);
							return reference;
						});

				EventReference earlier = reference(1);
				EventReference later = reference(2);

				CountDownLatch bothReady = new CountDownLatch(1);
				CountDownLatch bothDone = new CountDownLatch(2);
				for ( EventReference notification : new EventReference[] { earlier, later } ) {
					notifiers.execute(( ) -> {
						try {
							bothReady.await();
							decorator.eventsAppended(notification);
						} catch ( InterruptedException e ) {
							Thread.currentThread().interrupt();
						} finally {
							bothDone.countDown();
						}
					});
				}
				bothReady.countDown();
				bothDone.await();

				if ( !later.equals(seenByDelegate.get()) ) {
					lost++;
				}
			}
		}

		assertEquals(0, lost,
			"the later of two simultaneous notifications was dropped %d times in %d pairs".formatted(lost, RACING_PAIRS));
	}

	/**
	 * The point of the decorator: a listener that has already seen a reference is not told again.
	 */
	@Test
	void testNotificationAlreadySeenByTheListenerIsSkipped ( ) {
		AtomicInteger deliveries = new AtomicInteger();
		EventStreamEventuallyConsistentAppendListener counting = reference -> {
			deliveries.incrementAndGet();
			return reference;
		};
		OptimizingApendListenerDecorator decorator = new OptimizingApendListenerDecorator(counting);

		EventReference reference = reference(1);
		decorator.eventsAppended(reference);
		decorator.eventsAppended(reference);
		decorator.eventsAppended(reference(1));

		assertEquals(1, deliveries.get(), "a reference the listener already processed must not be delivered again");
	}

	/**
	 * A listener occupied with one notification does not hold up the threads delivering the next: they
	 * register what they have and leave, and the in-progress delivery picks the newest target up.
	 */
	@Test
	void testSlowListenerDoesNotBlockTheNotifyingThread ( ) throws Exception {
		CountDownLatch listenerEntered = new CountDownLatch(1);
		CountDownLatch releaseListener = new CountDownLatch(1);
		AtomicReference<EventReference> seenByDelegate = new AtomicReference<>();

		OptimizingApendListenerDecorator decorator = new OptimizingApendListenerDecorator(reference -> {
			listenerEntered.countDown();
			try {
				releaseListener.await(5, TimeUnit.SECONDS);
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
			seenByDelegate.set(reference);
			return reference;
		});

		Thread first = new Thread(( ) -> decorator.eventsAppended(reference(1)));
		first.start();
		listenerEntered.await();

		// the listener is still inside the first notification; this one must not block behind it
		long start = System.nanoTime();
		decorator.eventsAppended(reference(2));
		long tookMs = (System.nanoTime() - start) / 1_000_000;

		releaseListener.countDown();
		first.join();

		assertEquals(true, tookMs < 1_000, "notifying took %dms, so it waited for the listener".formatted(tookMs));
	}

}
