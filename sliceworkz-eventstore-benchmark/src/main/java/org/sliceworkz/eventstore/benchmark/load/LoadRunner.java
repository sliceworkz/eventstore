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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.config.BenchmarkProfile;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusProvisioner;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.benchmark.env.TargetSpec;
import org.sliceworkz.eventstore.benchmark.workload.Workload;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext.Collision;
import org.sliceworkz.eventstore.benchmark.workload.Workloads;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;

/**
 * Applies sustained load to a store and reports what happened.
 *
 * <p>This is the half of the suite JMH cannot host, and the reasons are structural rather than a
 * matter of taste:
 *
 * <ul>
 *   <li><b>The store grows.</b> JMH assumes every iteration measures the same thing, so the benchmark
 *       layer restores the corpus between iterations. That is correct there and hides the thing an
 *       ingest actually experiences -- an index that deepens, a table that outgrows the cache,
 *       autovacuum waking up mid-run.</li>
 *   <li><b>Conflicts are an outcome, not an error.</b> A run at one boundary is mostly failures by
 *       design, and their rate is the measurement.</li>
 *   <li><b>Latency needs a schedule.</b> A fixed offered rate makes coordinated omission possible to
 *       correct for; JMH's throughput mode has no notion of an operation being <em>due</em>.</li>
 *   <li><b>Some things take an unbounded time to arrive.</b> Append-to-projection latency is measured
 *       from a callback that fires whenever it fires, which does not fit inside an invocation.</li>
 * </ul>
 */
public final class LoadRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(LoadRunner.class);

	/** How long to wait after the load stops for notifications still in flight to land. */
	private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

	private LoadRunner ( ) { }

	/** Runs one of a profile's load scenarios against one target. */
	public static LoadResult run ( BenchmarkProfile profile, BenchmarkProfile.LoadSettings settings,
			TargetSpec targetSpec ) {
		LoadScenario scenario = LoadScenario.parse(settings.scenario());
		Collision collision = Collision.parse(settings.collision());
		WorkloadContext.collisionCaveat(collision, profile.corpus().streamDesign())
				.ifPresent(caveat -> LOGGER.warn("scenario '{}': {}", settings.scenario(), caveat));
		CorpusProvisioner provisioner = new CorpusProvisioner(profile.corpus());

		try ( CorpusProvisioner.Prepared prepared = provisioner.open(targetSpec, false, null) ) {
			BenchmarkTarget target = prepared.target();
			long eventsBefore = LoadCorrectness.countEvents(target, provisioner.prefix());

			try ( LiveLatencyProbe probe = LiveLatencyProbe.forScenario(scenario, target) ) {
				return drive(profile, settings, scenario, collision, prepared, provisioner.prefix(),
						eventsBefore, probe);
			}
		}
	}

	private static LoadResult drive ( BenchmarkProfile profile, BenchmarkProfile.LoadSettings settings,
			LoadScenario scenario, Collision collision, CorpusProvisioner.Prepared prepared, String prefix,
			long eventsBefore, LiveLatencyProbe probe ) {

		BenchmarkTarget target = prepared.target();
		Workload writeWorkload = Workloads.byName(writeWorkloadFor(scenario));
		Workload readWorkload = Workloads.byName("query-by-entity-hot");

		Counters counters = new Counters();
		List<LatencyRecorder> perThread = new ArrayList<>();
		List<Thread> threads = new ArrayList<>();

		CountDownLatch start = new CountDownLatch(1);
		AtomicLong recordingFrom = new AtomicLong(Long.MAX_VALUE);
		java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);

		int totalWriters = settings.writers();
		int totalReaders = scenario == LoadScenario.MIXED ? settings.readers() : 0;
		int totalThreads = totalWriters + totalReaders;

		for ( int i = 0; i < totalWriters; i++ ) {
			LatencyRecorder recorder = new LatencyRecorder("write");
			perThread.add(recorder);
			threads.add(worker(prepared, profile.corpus(), writeWorkload, collision, i, totalThreads, settings,
					counters, recorder, start, recordingFrom, running, probe, true));
		}
		for ( int i = 0; i < totalReaders; i++ ) {
			LatencyRecorder recorder = new LatencyRecorder("read");
			perThread.add(recorder);
			threads.add(worker(prepared, profile.corpus(), readWorkload, collision, totalWriters + i, totalThreads,
					settings, counters, recorder, start, recordingFrom, running, probe, false));
		}

		threads.forEach(Thread::start);

		long startedAt = System.nanoTime();
		start.countDown();

		// Ramp-up runs but is not recorded.  Its job is to get pools, caches and the JIT past their
		// cold state so the measured window is about the store rather than about starting up.
		sleep(Duration.ofSeconds(settings.rampUpSeconds()));
		long measureFrom = System.nanoTime();
		recordingFrom.set(measureFrom);
		probe.recordFrom(measureFrom);
		counters.resetForMeasurement();
		LOGGER.info("ramp-up done after {}s; measuring for {}s",
				settings.rampUpSeconds(), settings.durationSeconds());

		sleep(Duration.ofSeconds(settings.durationSeconds()));
		running.set(false);
		long measuredUntil = System.nanoTime();

		threads.forEach(thread -> {
			try {
				thread.join(TimeUnit.SECONDS.toMillis(30));
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			}
		});

		// Notifications in flight when the writers stopped still have somewhere to arrive; without this
		// wait the live-latency scenarios would report every one of them as undelivered.
		probe.awaitQuiet(DRAIN_TIMEOUT);

		long eventsAfter = LoadCorrectness.countEvents(target, prefix);
		long grewBy = eventsBefore < 0 || eventsAfter < 0 ? -1 : eventsAfter - eventsBefore;

		List<LatencyRecorder.Summary> latencies = new ArrayList<>();
		latencies.add(LatencyRecorder.merge("service time", perThread).summarise());
		probe.summaries().forEach(latencies::add);

		List<LoadCorrectness.Check> checks = new ArrayList<>(LoadCorrectness.check(target, prefix,
				counters.lifetimeWrites.sum(), counters.lifetimeDeduplicated.sum(), grewBy,
				probe.projectedCount(), probe.distinctProjectedCount()));
		probe.deliveryCheck(totalWriters).ifPresent(checks::add);

		return new LoadResult(scenario, Duration.ofNanos(measuredUntil - measureFrom),
				counters.operations.sum(), counters.successes.sum(), counters.conflicts.sum(),
				counters.deduplicated.sum(), counters.failures.sum(), latencies, checks, grewBy);
	}

	/**
	 * Which workload the writers run.
	 *
	 * <p>The live-latency scenarios use the unconditional append deliberately: they are measuring how
	 * long an event takes to reach a subscriber, and a conditional append that sometimes loses would
	 * put conflict handling into a number that is supposed to be about delivery.
	 */
	private static String writeWorkloadFor ( LoadScenario scenario ) {
		return switch ( scenario ) {
			case WRITE_SATURATION, MIXED -> "append-type-and-tag";
			case NOTIFY_LATENCY, END_TO_END_LATENCY -> "append-none";
		};
	}

	private static Thread worker ( CorpusProvisioner.Prepared prepared, CorpusSpec spec, Workload workload,
			Collision collision, int threadIndex, int threadCount, BenchmarkProfile.LoadSettings settings, Counters counters,
			LatencyRecorder recorder, CountDownLatch start, AtomicLong recordingFrom,
			java.util.concurrent.atomic.AtomicBoolean running, LiveLatencyProbe probe, boolean writer ) {

		return new Thread(() -> {
			WorkloadContext context = new WorkloadContext(prepared.target(), spec, prepared.outcome().facts(),
					collision, threadIndex, threadCount, spec.seed());
			awaitStart(start);
			runLoop(context, workload, settings, counters, recorder, recordingFrom, running, probe, writer,
					threadIndex, threadCount);
		}, "%s-%d".formatted(writer ? "writer" : "reader", threadIndex));
	}

	private static void awaitStart ( CountDownLatch start ) {
		try {
			start.await();
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	private static void runLoop ( WorkloadContext context, Workload workload,
			BenchmarkProfile.LoadSettings settings, Counters counters, LatencyRecorder recorder,
			AtomicLong recordingFrom, java.util.concurrent.atomic.AtomicBoolean running, LiveLatencyProbe probe,
			boolean writer, int threadIndex, int threadCount ) {

		workload.prepare(context);

		// Under a fixed rate each thread owns a slice of it, and every operation has a time it is *due*.
		// That schedule is what makes coordinated omission correctable: an operation that starts late
		// because the store was busy is recorded from when it should have started, not from when it did.
		boolean paced = settings.isFixedRate();
		long intervalNanos = paced
				? (long) ( 1_000_000_000.0d / Math.max(settings.targetRatePerSecond() / (double) threadCount, 1e-9) )
				: 0;
		long dueAt = System.nanoTime();

		while ( running.get() ) {
			if ( paced ) {
				parkUntil(dueAt);
			}
			long startedAt = paced ? dueAt : System.nanoTime();
			long invokedAt = System.nanoTime();

			try {
				Object result = workload.invoke(context);
				long finishedAt = System.nanoTime();

				boolean recording = finishedAt >= recordingFrom.get();
				if ( recording ) {
					recorder.record(finishedAt - startedAt);
				}
				counters.classify(result, writer, recording);
				if ( writer ) {
					probe.appended(result, invokedAt);
				}
			} catch ( RuntimeException e ) {
				if ( System.nanoTime() >= recordingFrom.get() ) {
					counters.failures.increment();
				}
				LOGGER.debug("load operation failed", e);
			}

			if ( paced ) {
				dueAt += intervalNanos;
			}
		}
	}

	/** Parks until a deadline, then spins the last stretch, which is what keeps the pacing honest. */
	private static void parkUntil ( long deadlineNanos ) {
		long remaining = deadlineNanos - System.nanoTime();
		if ( remaining <= 0 ) {
			// already behind schedule: do not sleep, the point is to catch up
			return;
		}
		if ( remaining > 1_000_000L ) {
			LockSupport.parkNanos(remaining - 500_000L);
		}
		while ( System.nanoTime() < deadlineNanos ) {
			Thread.onSpinWait();
		}
	}

	private static void sleep ( Duration duration ) {
		if ( duration.isZero() || duration.isNegative() ) {
			return;
		}
		try {
			Thread.sleep(duration.toMillis());
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Two sets of totals, because two different questions are being asked.
	 *
	 * <p>Throughput and latency are about the <em>measured window</em>, so those counters are reset when
	 * ramp-up ends -- warm-up work is not part of the answer. The events-in-equals-out check is about
	 * the <em>whole run</em>, because every append made, ramp-up included, had to land somewhere.
	 *
	 * <p>Conflating the two is not a hypothetical: the first version reset everything and compared the
	 * measured appends against a store that had also absorbed the ramp-up, so the check failed on every
	 * run with a discrepancy exactly one ramp-up long. The check was right and the runner was wrong.
	 */
	private static final class Counters {

		/* the measured window */
		final LongAdder operations = new LongAdder();
		final LongAdder successes = new LongAdder();
		final LongAdder conflicts = new LongAdder();
		final LongAdder deduplicated = new LongAdder();
		final LongAdder failures = new LongAdder();

		/* the whole run, never reset */
		final LongAdder lifetimeWrites = new LongAdder();
		final LongAdder lifetimeDeduplicated = new LongAdder();

		void classify ( Object result, boolean writer, boolean recording ) {
			boolean conflict = false;
			boolean duplicate = false;
			if ( result instanceof Number number ) {
				long value = number.longValue();
				conflict = value < 0;
				duplicate = value == 0 && writer;
			}

			if ( writer && !conflict ) {
				lifetimeWrites.increment();
				if ( duplicate ) {
					lifetimeDeduplicated.increment();
				}
			}
			if ( !recording ) {
				return;
			}

			operations.increment();
			if ( conflict ) {
				conflicts.increment();
			} else if ( duplicate ) {
				deduplicated.increment();
			} else {
				successes.increment();
			}
		}

		void resetForMeasurement ( ) {
			operations.reset();
			successes.reset();
			conflicts.reset();
			deduplicated.reset();
			failures.reset();
		}
	}

	/** The position of an event an append returned, if it wrote one. */
	static Optional<EventReference> lastReferenceOf ( Object result ) {
		if ( result instanceof List<?> list && !list.isEmpty()
				&& list.getLast() instanceof Event<?> event ) {
			return Optional.of(event.reference());
		}
		return Optional.empty();
	}
}
