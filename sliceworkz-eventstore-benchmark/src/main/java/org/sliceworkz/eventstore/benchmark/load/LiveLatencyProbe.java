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
package org.sliceworkz.eventstore.benchmark.load;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.domain.InventoryEvent;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Measures how long it takes for an appended event to become visible somewhere else.
 *
 * <p>Two things get timed, and having both is the point. {@code notify} is append-returns to
 * subscriber-callback: pure delivery, with no projection work in it at all. {@code end-to-end} is
 * append-returns to the read model having committed. Their difference is how much of the wait a user
 * feels belongs to the store's plumbing and how much to the projection -- and those get fixed in
 * completely different places, so a single end-to-end number leaves you guessing which.
 *
 * <p><b>Both are timed on one clock, in this JVM.</b> The suite this replaces joined the store's
 * {@code event_timestamp} against a read-model timestamp in SQL, which is only a latency when the
 * application and the database share a clock: PostgreSQL stamps {@code event_timestamp} from the
 * <em>server</em>, and there is no clock seam anywhere in the library to align them. Any skew went
 * straight into the reported figure, and against a remote database the number was partly a
 * measurement of NTP.
 */
final class LiveLatencyProbe implements AutoCloseable {

	private static final Logger LOGGER = LoggerFactory.getLogger(LiveLatencyProbe.class);

	private final LoadScenario scenario;
	private final PendingAppends pending = new PendingAppends();
	private final LatencyRecorder deliveryLatency;

	private EventStream<InventoryEvent> subscribedStream;
	private CountingProjection projection;

	private LiveLatencyProbe ( LoadScenario scenario ) {
		this.scenario = scenario;
		this.deliveryLatency = new LatencyRecorder(
				scenario == LoadScenario.END_TO_END_LATENCY ? "append to read model" : "append to notification");
	}

	/** A probe for the given scenario; an inert one for the scenarios that measure no delivery. */
	static LiveLatencyProbe forScenario ( LoadScenario scenario, BenchmarkTarget target ) {
		LiveLatencyProbe probe = new LiveLatencyProbe(scenario);
		if ( scenario.needsSubscription() ) {
			probe.subscribe(target);
		}
		return probe;
	}

	private void subscribe ( BenchmarkTarget target ) {
		subscribedStream = target.store().getEventStream(
				EventStreamId.forContext("inventory").anyPurpose(), InventoryEvent.class);

		if ( scenario == LoadScenario.NOTIFY_LATENCY ) {
			// The listener does nothing but stop the clock.  Anything else it did would be counted as
			// delivery, which is exactly the conflation this scenario exists to avoid.
			subscribedStream.subscribe(( EventStreamEventuallyConsistentAppendListener ) atLeastUntil -> {
				pending.drainUpTo(atLeastUntil.position(), System.nanoTime(), deliveryLatency);
				return atLeastUntil;
			});
			return;
		}

		projection = new CountingProjection(pending, deliveryLatency);
		Projector.<InventoryEvent>newBuilder()
				.from(subscribedStream)
				.towards(projection)
				.subscribe()
				.build();
	}

	/** Notes an append, if the workload's result carried the reference of one. */
	void appended ( Object result, long startedAtNanos ) {
		if ( !scenario.needsSubscription() ) {
			return;
		}
		lastReferenceOf(result).ifPresent(reference -> pending.appended(reference.position(), startedAtNanos));
	}

	private static Optional<EventReference> lastReferenceOf ( Object result ) {
		if ( result instanceof List<?> list && !list.isEmpty() && list.getLast() instanceof Event<?> event ) {
			return Optional.of(event.reference());
		}
		return Optional.empty();
	}

	/**
	 * Waits for notifications still in flight after the writers stopped.
	 *
	 * <p>Without this the last fraction of a second of appends would every one of them be counted as
	 * undelivered, which would look like a delivery fault and is only the run ending mid-flight.
	 */
	void awaitQuiet ( Duration timeout ) {
		if ( !scenario.needsSubscription() ) {
			return;
		}
		long deadline = System.nanoTime() + timeout.toNanos();
		int previous = -1;
		while ( System.nanoTime() < deadline ) {
			int outstanding = pending.outstanding();
			if ( outstanding == 0 ) {
				return;
			}
			if ( outstanding == previous ) {
				// nothing has arrived since the last look; give it a little longer, then give up
				sleepQuietly(200);
			} else {
				sleepQuietly(50);
			}
			previous = outstanding;
		}
		LOGGER.info("{} append(s) were never announced within {}", pending.outstanding(), timeout);
	}

	private static void sleepQuietly ( long millis ) {
		try {
			Thread.sleep(millis);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	/** The distributions this probe measured, empty for a scenario that measures no delivery. */
	List<LatencyRecorder.Summary> summaries ( ) {
		if ( !scenario.needsSubscription() || deliveryLatency.count() == 0 ) {
			return List.of();
		}
		return List.of(deliveryLatency.summarise());
	}

	/** How many events the projection handled, or -1 when none was running. */
	long projectedCount ( ) {
		return projection == null ? -1 : projection.handled.sum();
	}

	/** How many <em>distinct</em> events it handled, or -1 when none was running. */
	long distinctProjectedCount ( ) {
		return projection == null ? -1 : projection.seen.size();
	}

	/**
	 * Whether everything appended was eventually announced.
	 *
	 * <p>A handful outstanding is tolerated, and the tolerance is the writer count. The run stops by
	 * flipping a flag, so at that instant every writer may have an append that has returned but whose
	 * notification has not yet been delivered -- those are in flight, not lost. Failing on them would
	 * make every clean run report a delivery fault, which is the fastest way to teach someone to ignore
	 * a check.
	 *
	 * <p>More than that is worth failing on: for this store, notifications not arriving means
	 * subscribed projections silently stop advancing.
	 */
	Optional<LoadCorrectness.Check> deliveryCheck ( int writers ) {
		if ( !scenario.needsSubscription() ) {
			return Optional.empty();
		}
		int outstanding = pending.outstanding();
		long matched = pending.matched();
		int tolerated = Math.max(writers, 1);

		if ( outstanding == 0 ) {
			return Optional.of(LoadCorrectness.Check.pass("every append announced",
					"%d append(s) reached the subscriber".formatted(matched)));
		}
		if ( outstanding <= tolerated ) {
			return Optional.of(LoadCorrectness.Check.pass("every append announced",
					"%d reached the subscriber; %d still in flight when the run stopped, within the %d writer(s) that can each hold one"
							.formatted(matched, outstanding, tolerated)));
		}
		return Optional.of(LoadCorrectness.Check.fail("every append announced",
				"%d append(s) reached the subscriber but %d never did -- past the %d that could be in flight, so notifications are being lost"
						.formatted(matched, outstanding, tolerated)));
	}

	@Override
	public void close ( ) {
		if ( subscribedStream != null ) {
			// a subscribed stream is held by the storage until closed, deliberately, so that live updates
			// survive the caller dropping the variable -- which means nothing releases it on our behalf
			subscribedStream.close();
		}
	}

	/**
	 * Stops the clock when the read model has committed, not when the event was handed to the
	 * projection.
	 *
	 * <p>{@code afterBatch} rather than {@code when} because "committed" is what a user can see. Timing
	 * from {@code when} would leave out the batch's own commit, which is where a projection with a real
	 * store spends most of its time.
	 */
	private static final class CountingProjection implements BatchAwareProjection<InventoryEvent> {

		private final PendingAppends pending;
		private final LatencyRecorder recorder;
		private final LongAdder handled = new LongAdder();
		private final java.util.Set<EventId> seen = ConcurrentHashMap.newKeySet();
		private final List<EventReference> batch = new ArrayList<>();

		CountingProjection ( PendingAppends pending, LatencyRecorder recorder ) {
			this.pending = pending;
			this.recorder = recorder;
		}

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		public void when ( Event<InventoryEvent> event ) {
			handled.increment();
			// a Set of ids, so "the same event handled twice" is detectable rather than assumed away
			seen.add(event.reference().id());
			batch.add(event.reference());
		}

		@Override
		public void beforeBatch ( ) {
			batch.clear();
		}

		@Override
		public void afterBatch ( Optional<EventReference> lastEventReference ) {
			long now = System.nanoTime();
			lastEventReference.ifPresent(reference -> pending.drainUpTo(reference.position(), now, recorder));
			batch.clear();
		}

		@Override
		public void cancelBatch ( ) {
			batch.clear();
		}
	}
}
