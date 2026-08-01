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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;

/**
 * The subscription lifetime contract: what a backend must do with a listener between
 * {@link EventStorage#subscribe} and {@link EventStorage#unsubscribe}.
 * <p>
 * The scenario worth the whole class is {@link #aSubscriptionOutlivesTheCallerDroppingTheStream()}.
 * Both in-tree backends used to keep listeners as {@code WeakReference}s, so a stream nobody kept a
 * variable for was collected and its subscription went quiet — no exception, no log, and timing that
 * depended on when a collection happened to run. That is the one failure mode a test suite cannot
 * stumble over by accident: on a small heap a test JVM may never collect at all, and the suite passes
 * while live updates are broken in production.
 * <p>
 * The rest pin down the other side of holding listeners strongly: since nothing will ever release
 * them on the caller's behalf, {@code close()} has to, exactly and repeatably.
 */
public class EventStreamSubscriptionLifecycleTest extends AbstractEventStoreTest {

	private EventStream<MockDomainEvent> stream ( ) {
		return eventStore().getEventStream(EventStreamId.forContext("subscriptions"), MockDomainEvent.class);
	}

	private void append ( EventStream<MockDomainEvent> stream, String payload ) {
		stream.append(AppendCriteria.none(), Collections.singletonList(Event.of(new FirstDomainEvent(payload), Tags.none())));
	}

	private EventStreamEventuallyConsistentAppendListener counting ( AtomicInteger counter ) {
		return reference -> {
			counter.incrementAndGet();
			return reference;
		};
	}

	/**
	 * Subscribes through a stream and then lets every reference to it die with this frame, so that the
	 * caller keeps nothing but the counter its listener increments.
	 */
	private void subscribeThroughAStreamWeThenForget ( AtomicInteger notifications ) {
		stream().subscribe(counting(notifications));
	}

	/**
	 * Runs collections until a known-unreachable object has been collected, then asserts it was — so a
	 * scenario relying on collection either really got one or fails, rather than passing vacuously on a
	 * JVM that ignored the request.
	 */
	private void provokeGarbageCollection ( ) {
		WeakReference<Object> canary = new WeakReference<>(new Object());
		for ( int attempt = 0; attempt < 10 && canary.get() != null; attempt++ ) {
			System.gc();
		}
		assertNull(canary.get(),
			"this JVM did not collect an unreachable object on request, so this scenario cannot prove anything");
	}

	@ForEachBackend
	void aSubscriptionOutlivesTheCallerDroppingTheStream ( ) {
		AtomicInteger notifications = new AtomicInteger();
		subscribeThroughAStreamWeThenForget(notifications);

		// nothing in this test can reach the subscribing stream any more. Were it held weakly, this is
		// where it would be collected and its listener would fall silent
		provokeGarbageCollection();

		append(stream(), "appended after the subscribing stream went out of scope");

		waitBecauseOfEventualConsistency(( ) -> notifications.get() >= 1);
	}

	@ForEachBackend
	void closingAStreamEndsItsSubscription ( ) {
		AtomicInteger closedStreamNotifications = new AtomicInteger();
		EventStream<MockDomainEvent> toBeClosed = stream();
		toBeClosed.subscribe(counting(closedStreamNotifications));

		AtomicInteger witnessNotifications = new AtomicInteger();
		EventStream<MockDomainEvent> witness = stream();
		witness.subscribe(counting(witnessNotifications));

		toBeClosed.close();

		append(witness, "appended after one subscription was closed");

		// the witness is what makes the silence meaningful: it proves the notification round trip ran to
		// completion, so the closed stream was passed over rather than merely slower than the assertion
		waitBecauseOfEventualConsistency(( ) -> witnessNotifications.get() >= 1);
		assertEquals(0, closedStreamNotifications.get(),
			"a closed stream must receive no further notifications");
	}

	/**
	 * Subscribes a stream, closes it, and hands back only a weak reference — so that after this frame
	 * returns, the storage's own registration is the only thing that could still be holding it.
	 */
	private WeakReference<EventStream<MockDomainEvent>> subscribeCloseAndKeepOnlyAWeakReference ( AtomicInteger notifications ) {
		EventStream<MockDomainEvent> stream = stream();
		stream.subscribe(counting(notifications));
		stream.close();
		return new WeakReference<>(stream);
	}

	@ForEachBackend
	void closingAStreamLetsTheStorageReleaseIt ( ) {
		WeakReference<EventStream<MockDomainEvent>> closed = subscribeCloseAndKeepOnlyAWeakReference(new AtomicInteger());

		provokeGarbageCollection();

		// this is the scenario that catches a backend which accepted the subscription but never
		// implemented unsubscribe: its listeners are silently immortal, and since they are held
		// strongly that is an outright leak of every stream and every store behind them. No count of
		// delivered notifications reveals it -- a closed stream has already discarded its listeners, so
		// it stays quiet either way -- but reachability does
		assertNull(closed.get(),
			"a closed stream must be released by the storage; this backend is still holding it, so it most likely does not implement EventStorage.unsubscribe");
	}

	@ForEachBackend
	void closingAStreamDiscardsItsListenersRatherThanParkingThem ( ) {
		EventStream<MockDomainEvent> stream = stream();
		AtomicInteger fromBeforeClose = new AtomicInteger();
		stream.subscribe(counting(fromBeforeClose));

		stream.close();

		AtomicInteger fromAfterClose = new AtomicInteger();
		stream.subscribe(counting(fromAfterClose));
		append(stream, "appended after re-subscribing");

		waitBecauseOfEventualConsistency(( ) -> fromAfterClose.get() >= 1);
		assertEquals(0, fromBeforeClose.get(),
			"a listener dropped by close() must not be revived by a later subscription on the same stream");
	}

	@ForEachBackend
	void closingAStreamIsIdempotentAndLeavesItUsable ( ) {
		EventStream<MockDomainEvent> neverSubscribed = stream();

		// a stream nobody subscribed to holds no registration, so closing it -- twice -- has nothing to
		// release and must still be silent about it
		neverSubscribed.close();
		neverSubscribed.close();

		append(neverSubscribed, "appended through a closed stream");
		assertEquals(1, neverSubscribed.query(EventQuery.matchAll()).count(),
			"closing a stream ends its subscriptions; it must not disable the handle");
	}

	@ForEachBackend
	void aStreamClosesItsSubscriptionAtTheEndOfATryWithResources ( ) {
		AtomicInteger notifications = new AtomicInteger();
		AtomicInteger witnessNotifications = new AtomicInteger();

		EventStream<MockDomainEvent> witness = stream();
		witness.subscribe(counting(witnessNotifications));

		try ( EventStream<MockDomainEvent> scoped = stream() ) {
			scoped.subscribe(counting(notifications));
			append(scoped, "appended inside the block");
			waitBecauseOfEventualConsistency(( ) -> notifications.get() >= 1);
		}

		int afterTheBlock = notifications.get();
		append(witness, "appended after the block");

		waitBecauseOfEventualConsistency(( ) -> witnessNotifications.get() >= 2);
		assertEquals(afterTheBlock, notifications.get(),
			"a stream closed by try-with-resources must stop receiving notifications");
	}

}
