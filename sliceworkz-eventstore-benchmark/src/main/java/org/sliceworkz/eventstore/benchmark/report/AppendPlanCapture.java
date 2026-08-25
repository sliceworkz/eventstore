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
package org.sliceworkz.eventstore.benchmark.report;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext.Collision;
import org.sliceworkz.eventstore.benchmark.workload.Workload;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext;
import org.sliceworkz.eventstore.testing.backend.PostgresContainer;

/**
 * Runs each conditional-append workload once with {@code auto_explain} on, and keeps the plan the
 * server logged for the statement the store actually issued.
 *
 * <p>This replaces guessing. The hand-written statements in {@link QueryPlans} reconstruct the
 * predicate faithfully and the parameterisation not at all, and for the append that difference decides
 * the plan -- see {@link AutoExplain} for how the two ended up inverted against each other. Here the
 * workload is the one that was measured, the statement is the one the backend built, and the plan is
 * the one PostgreSQL chose, so there is nothing left to be faithful about.
 *
 * <p><b>The capture appends, and puts the events back.</b> A conditional append cannot be explained
 * without running it, so one event per workload is written and then deleted by position afterwards. A
 * handful of dead tuples in a corpus that is restored or rebuilt before anything is measured again is
 * cheap; leaving the events would be worse than that, because the corpus manifest verifies its event
 * count and would rebuild the whole corpus on the next run.
 *
 * <p><b>Never while measuring.</b> Explaining every statement costs the server real work, so this runs
 * after the last measurement, from the launcher, against a store opened for the purpose.
 */
public final class AppendPlanCapture {

	private static final Logger LOGGER = LoggerFactory.getLogger(AppendPlanCapture.class);

	/**
	 * Long enough for the server to have flushed what it wrote to stderr and for the container runtime
	 * to have relayed it. The read is retried around this, so it bounds the wait rather than fixing it.
	 */
	private static final long LOG_SETTLE_MILLIS = 250;

	private static final int LOG_ATTEMPTS = 8;

	/**
	 * How many times to run a workload before the one whose plan is kept.
	 *
	 * <p><b>Why a warm-up is needed for a plan.</b> pgjdbc sends the first {@code prepareThreshold}
	 * executions of a statement one at a time with the parameter values attached, and PostgreSQL plans
	 * each from the real values; from the sixth it switches to a server-side prepared statement, and
	 * PostgreSQL may settle on a <em>generic</em> plan built against default selectivity instead. The
	 * two can differ, and this suite has watched them differ in the direction nobody expects: capturing
	 * a single execution of {@code append-type-and-tag} produced a 19ms sequential scan for an operation
	 * that measures 0.9ms, because the measured run had long since moved to a generic plan using the tag
	 * index while the one-shot custom plan had not. Six is pgjdbc's default threshold, and going a
	 * couple past it costs three more appends and removes the whole question.
	 *
	 * <p>The consequence for reading the report: these are steady-state plans, which is what the
	 * throughput numbers were measured on. An application issuing a given consistency boundary only a
	 * handful of times gets the custom plan instead, and may get a different one.
	 */
	private static final int WARMUP_INVOCATIONS = 8;

	private AppendPlanCapture ( ) { }

	/**
	 * Captures a plan per conditional-append workload, or an empty list where that is not possible.
	 *
	 * @param target a store opened <em>after</em> {@link AutoExplain#enable}, so its pooled connections
	 *        carry the setting
	 * @param image the container image tag whose log carries the plans, or null for a server whose log
	 *        this process cannot read -- in which case nothing is captured
	 */
	public static List<QueryPlans.Plan> capture ( BenchmarkTarget target, String image, String prefix,
			CorpusSpec spec, CorpusFacts facts, List<Workload> workloads ) {
		if ( image == null || target.dataSource().isEmpty() ) {
			return List.of();
		}
		DataSource dataSource = target.dataSource().get();
		List<Workload> conditional = workloads.stream().filter(AppendPlanCapture::isConditionalAppend).toList();
		if ( conditional.isEmpty() ) {
			return List.of();
		}

		long headBefore = headPosition(dataSource, prefix);
		List<QueryPlans.Plan> plans = new ArrayList<>();
		try {
			WorkloadContext context = new WorkloadContext(target, spec, facts, Collision.SPREAD, 0, 1, spec.seed());
			for ( Workload workload : conditional ) {
				captureOne(workload, context, image, prefix).ifPresent(plans::add);
			}
		} catch ( RuntimeException e ) {
			LOGGER.warn("could not capture the store's own append plans", e);
		} finally {
			removeCapturedEvents(dataSource, prefix, headBefore);
		}
		return plans;
	}

	/**
	 * Whether a workload's append carries a consistency boundary, which is the whole subject here.
	 *
	 * <p>{@code append-none} is excluded because it has no {@code NOT EXISTS} to explain, and the read
	 * workloads because {@link QueryPlans} covers those with statements whose parameterisation it does
	 * reproduce.
	 */
	private static boolean isConditionalAppend ( Workload workload ) {
		String name = workload.name();
		return ( name.startsWith("append-") || name.equals("decide-then-append") )
				&& !name.equals("append-none")
				&& !name.startsWith("append-batch")
				&& !name.startsWith("append-idempotent");
	}

	private static Optional<QueryPlans.Plan> captureOne ( Workload workload, WorkloadContext context,
			String image, String prefix ) {
		// Warm past the driver's threshold before marking the log, so what is captured is the plan the
		// measured run had rather than the one a first execution gets.
		for ( int i = 0; i < WARMUP_INVOCATIONS; i++ ) {
			invokeQuietly(workload, context);
		}
		int mark = PostgresContainer.logs(image).length();
		invokeQuietly(workload, context);

		String wanted = "INSERT INTO %sevents".formatted(prefix);
		for ( int attempt = 0; attempt < LOG_ATTEMPTS; attempt++ ) {
			Optional<String> plan = AutoExplain.matching(
					AutoExplain.plansIn(PostgresContainer.logs(image), mark), wanted);
			if ( plan.isPresent() ) {
				return plan.map(explain -> new QueryPlans.Plan(
						QueryPlans.CAPTURED_SHAPE_PREFIX + workload.name(), "", explain));
			}
			sleep();
		}
		LOGGER.info("no plan was logged for {}; auto_explain may not be loaded on the store's connections",
				workload.name());
		return Optional.empty();
	}

	/** An optimistic-locking failure is a legitimate outcome here, and still produced a plan. */
	private static void invokeQuietly ( Workload workload, WorkloadContext context ) {
		try {
			workload.invoke(context);
		} catch ( RuntimeException e ) {
			LOGGER.debug("workload {} did not complete during plan capture: {}", workload.name(), e.toString());
		}
	}

	private static void sleep ( ) {
		try {
			Thread.sleep(LOG_SETTLE_MILLIS);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for the server log", e);
		}
	}

	private static long headPosition ( DataSource dataSource, String prefix ) {
		String sql = "SELECT coalesce(max(event_position), 0) FROM %sevents".formatted(prefix);
		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql) ) {
			return rows.next() ? rows.getLong(1) : 0;
		} catch ( SQLException e ) {
			LOGGER.warn("could not read the head of the log before capturing plans", e);
			return Long.MAX_VALUE;   // deletes nothing afterwards rather than deleting the corpus
		}
	}

	/**
	 * Removes what the capture appended, leaving the corpus at exactly the size the manifest recorded.
	 *
	 * <p>Bounded by the position the log had before the capture, so it can only ever remove events this
	 * method's own caller wrote -- and reads {@code Long.MAX_VALUE} as "the mark could not be read", at
	 * which point deleting nothing is the only safe answer.
	 */
	private static void removeCapturedEvents ( DataSource dataSource, String prefix, long headBefore ) {
		if ( headBefore == Long.MAX_VALUE ) {
			return;
		}
		String sql = "DELETE FROM %sevents WHERE event_position > ?".formatted(prefix);
		try ( Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql) ) {
			statement.setLong(1, headBefore);
			int removed = statement.executeUpdate();
			if ( removed > 0 ) {
				LOGGER.info("removed the {} event(s) the plan capture appended", removed);
			}
		} catch ( SQLException e ) {
			LOGGER.warn("could not remove the events the plan capture appended; the corpus will be"
					+ " rebuilt on the next run because its event count no longer matches", e);
		}
	}
}
