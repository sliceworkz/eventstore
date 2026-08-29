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

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Matches an append up with the moment its effect became visible somewhere else, so a live-latency
 * scenario can time the gap.
 *
 * <p>The awkward part is that notifications are <b>coalesced</b>. A subscriber is not told "event
 * 41 arrived"; it is told "you are behind at least as far as reference X", and one such notification
 * may cover a hundred appends -- deliberately, since the optimizing decorator exists to collapse
 * exactly that. So a naive one-append-one-callback pairing would either miss most events or record
 * the wrong pairs.
 *
 * <p>Instead every append parks its start time under its position, and a notification for position X
 * drains everything at or below X, recording each one's delay. Every appended event therefore gets a
 * latency, whether it was announced individually or as part of a batch of a hundred.
 *
 * <p><b>Ordering is by position alone here, and that is a deliberate simplification.</b> The store's
 * real order is the {@code (tx, position)} tuple and the two can disagree, so an event may be drained
 * by a notification that did not strictly cover it. For a latency histogram the error is bounded by
 * how far apart two concurrently-committing appends land, which is microseconds -- far below anything
 * this measures. It would be wrong to use this for a cursor; it is fine for a stopwatch.
 *
 * <p><b>Every append is tracked, only the measured ones are timed.</b> The two are deliberately
 * separate: the delivery check has to account for the whole run, ramp-up included, because every
 * append made had to be announced somewhere -- while the histogram must not carry the ramp-up, whose
 * job is to be slow. Recording both from the same pool is how the delivery latencies came to be
 * summarised over 26.001 samples where service time had 24.001, the difference being exactly one
 * ramp-up: the cold first notifications and the projector's first batch over a full corpus sat in
 * the p99.9, which is precisely the percentile the scenario exists to report.
 */
final class PendingAppends {

	private final ConcurrentSkipListMap<Long, Long> startedAtByPosition = new ConcurrentSkipListMap<>();
	private final LongAdder recorded = new LongAdder();
	private final AtomicLong recordingFrom = new AtomicLong(Long.MAX_VALUE);

	/** Notes that an event at this position was appended now. */
	void appended ( long position, long startedAtNanos ) {
		startedAtByPosition.put(position, startedAtNanos);
	}

	/** Opens the measured window: appends made from this instant on are timed, earlier ones are not. */
	void recordFrom ( long nanos ) {
		recordingFrom.set(nanos);
	}

	/**
	 * Times every append at or below {@code upToPosition} that was made inside the measured window,
	 * and forgets them all -- including the ramp-up's, which are drained and counted but not timed.
	 *
	 * @return how many were drained
	 */
	int drainUpTo ( long upToPosition, long nowNanos, LatencyRecorder into ) {
		int drained = 0;
		long from = recordingFrom.get();
		Map.Entry<Long, Long> entry;
		while ( ( entry = startedAtByPosition.pollFirstEntry() ) != null ) {
			if ( entry.getKey() > upToPosition ) {
				// past the notification's reach: put it back and stop, since the map is sorted
				startedAtByPosition.put(entry.getKey(), entry.getValue());
				break;
			}
			if ( entry.getValue() >= from ) {
				into.record(nowNanos - entry.getValue());
			}
			drained++;
		}
		recorded.add(drained);
		return drained;
	}

	/** How many appends were matched to a notification. */
	long matched ( ) {
		return recorded.sum();
	}

	/**
	 * How many appends were never announced.
	 *
	 * <p>Not necessarily a fault: the run stops while the last notifications are still in flight, so a
	 * handful outstanding at the end is normal. A large number means notifications are not arriving,
	 * which for this store would mean projections silently stop advancing -- worth seeing.
	 */
	int outstanding ( ) {
		return startedAtByPosition.size();
	}
}
