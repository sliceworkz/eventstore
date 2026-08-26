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
package org.sliceworkz.eventstore.benchmark.jmh;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusProvisioner;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.benchmark.workload.Workload;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext.Collision;
import org.sliceworkz.eventstore.benchmark.workload.Workloads;

/**
 * The store a trial measures against, and everything about it.
 *
 * <p>Held at {@link Scope#Benchmark} because it is expensive and shared: one open store, one corpus,
 * one set of facts, for however many threads the trial runs. The per-thread state that sits on top of
 * it is {@link ThreadContext}.
 *
 * <p>The workload is a {@code @Param}, which is what keeps the benchmark matrix from exploding into a
 * class per operation. Everything else -- corpus, target, thread count, iteration counts -- comes from
 * the profile via {@link BenchmarkConfig}, so a run is described by a file rather than by twenty-seven
 * annotations.
 */
@State(Scope.Benchmark)
public class CorpusState {

	private static final Logger LOGGER = LoggerFactory.getLogger(CorpusState.class);

	/**
	 * Which operation this trial measures.
	 *
	 * <p>The default is a single harmless read so that running a benchmark class directly from an IDE
	 * does something rather than failing on an empty parameter; a real run always overrides it from the
	 * profile.
	 */
	@Param({ "query-stream-page" })
	public String workload;

	private BenchmarkConfig config;
	private CorpusProvisioner.Prepared prepared;
	private Workload resolved;
	private CorpusRestore restore;

	@Setup(Level.Trial)
	public void setUpTrial ( ) {
		config = BenchmarkConfig.fromSystemProperties();
		resolved = Workloads.byName(workload);

		resolved.requirement().rejectionFor(config.profile().corpus()).ifPresent(rejection -> {
			throw new IllegalStateException("workload '%s' cannot run here: %s".formatted(workload, rejection));
		});

		// Provisioning returns the target open.  For a SQL corpus this is a lookup; for an in-memory one
		// it regenerates, because a fork is a fresh JVM and nothing survives from the launcher.
		prepared = new CorpusProvisioner(config.profile().corpus()).open(config.target(), false, null);

		// Cheap, and it is the difference between a benchmark and a benchmark-shaped illusion: a query
		// matching nothing is fast, so a corpus that does not hold what this workload looks for would
		// report an excellent number rather than an error.
		prepared.outcome().facts().requireUsable();

		restore = new CorpusRestore(prepared.target(), config.profile().corpus(),
				new CorpusProvisioner(config.profile().corpus()).prefix(),
				resolved.requirement().mutatesStore());
		restore.beginTrial();

		LOGGER.info("trial: workload={} target={} corpus={} events, restore={}",
				workload, config.target().describe(),
				prepared.outcome().eventCount(), restore.describe());
	}

	/**
	 * Puts the corpus back before each iteration, where the policy calls for it.
	 *
	 * <p>For an in-memory store there is no template to copy from, so "restore" means regenerating --
	 * which is why that path rebuilds the whole store rather than issuing SQL.
	 */
	@Setup(Level.Iteration)
	public void restoreBeforeIteration ( ) {
		if ( restore.policy() != CorpusRestore.Policy.PER_ITERATION ) {
			return;
		}
		if ( prepared.target().isSqlBacked() ) {
			restore.restore();
		} else {
			regenerateInMemory();
		}
	}

	private void regenerateInMemory ( ) {
		prepared.close();
		prepared = new CorpusProvisioner(config.profile().corpus()).open(config.target(), false, null);
	}

	@TearDown(Level.Trial)
	public void tearDownTrial ( ) {
		try {
			restore.endTrial();
		} finally {
			// Read off the restore rather than from endTrial's return, because a trial that drifted past
			// the threshold throws -- and that is exactly the trial whose figure the report needs.
			BenchmarkConfig.recordDrift(restore.lastMeasuredDrift(), restore.worstIterationGrowth());
			try {
				// Hand the store back as the corpus its manifest describes, so the next fork reuses it
				// instead of correctly refusing to and rebuilding a hundred thousand events.
				restore.restoreToBaseline();
			} finally {
				restore.cleanUp();
				prepared.close();
			}
		}
	}

	public Workload workload ( ) {
		return resolved;
	}

	public BenchmarkTarget target ( ) {
		return prepared.target();
	}

	public CorpusSpec spec ( ) {
		return config.profile().corpus();
	}

	public CorpusFacts facts ( ) {
		return prepared.outcome().facts();
	}

	public Collision collision ( ) {
		return config.collision();
	}
}
