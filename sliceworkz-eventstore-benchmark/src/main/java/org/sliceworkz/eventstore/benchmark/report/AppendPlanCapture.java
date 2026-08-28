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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>Each workload is explained twice.</b> PostgreSQL holds two plans for a re-used prepared
 * statement and the report needs to know which one the throughput was measured on, so the capture pins
 * each in turn -- see {@link AutoExplain.PlanCacheMode}. The generic plan is always reported; the
 * custom one only when it is a different plan rather than the same one with different numbers.
 *
 * <p><b>The capture appends, and puts the events back.</b> A conditional append cannot be explained
 * without running it, so a handful of events per workload per round are written and then deleted by
 * position afterwards. A few dead tuples in a corpus that is restored or rebuilt before anything is
 * measured again is cheap; leaving the events would be worse than that, because the corpus manifest
 * verifies its event count and would rebuild the whole corpus on the next run.
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
	 * <p>This is no longer what decides <em>which</em> plan is captured -- {@link AutoExplain.PlanCacheMode}
	 * does that, after counting executions turned out to be off by one and produced a report whose
	 * captured plans contradicted the measurements. What the warm-up is still for is everything else the
	 * first execution of a statement pays and a benchmark in its millionth does not: the statement
	 * becoming server-prepared, the pages it touches reaching shared buffers, the pool settling on one
	 * connection.
	 */
	private static final int WARMUP_INVOCATIONS = 8;

	/**
	 * Appended to the shape of the plan the measured run is running on.
	 *
	 * <p>Both suffixes name the collision mode the capture was addressed under, and everything from the
	 * opening bracket is what {@code MarkdownRenderer.measured} already cuts off before matching a plan
	 * to its row, so this costs the shape nothing it is parsed for. It is here because a report can be
	 * re-rendered by a later version of the renderer than the one that measured it: a plan captured
	 * before the capture honoured the profile's mode carries no marker, and one that does carries the
	 * mode it really used, so neither can be read as the other.
	 */
	private static final String GENERIC_SUFFIX = " (collision=%s, generic plan)";

	/** Appended to a custom plan, which is only reported when it differs from the generic one. */
	private static final String CUSTOM_SUFFIX = " (collision=%s, custom plan, first executions only)";

	private AppendPlanCapture ( ) { }

	/**
	 * Captures a plan per conditional-append workload, or an empty list where that is not possible.
	 *
	 * @param target a store opened <em>after</em> {@link AutoExplain#enable}, so its pooled connections
	 *        carry the setting
	 * @param image the container image tag whose log carries the plans, or null for a server whose log
	 *        this process cannot read -- in which case nothing is captured
	 * @param collision the profile's collision mode, so the captured statement is addressed the way the
	 *        measured ones were. This used to be hardwired to {@link Collision#SPREAD}, which made a
	 *        contention profile's captured plan describe a statement it never issued: the three
	 *        write-contention profiles came back with byte-identical plans -- same parameters, same
	 *        cursor, same cost -- while their measured throughputs differed fourfold
	 */
	public static List<QueryPlans.Plan> capture ( BenchmarkTarget target, String image, String prefix,
			CorpusSpec spec, CorpusFacts facts, List<Workload> workloads, String targetLabel,
			Collision collision ) {
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
			// One thread, and the profile's own collision mode: at one writer that reproduces where the
			// appends are addressed -- which stream, which boundary -- without reproducing the contention
			// between writers, which no single-threaded capture could show and which auto_explain would
			// not attribute anyway. An OptimisticLockingException under ONE_BOUNDARY is expected and still
			// leaves a plan in the log, so a losing append is explained like a winning one.
			WorkloadContext context = new WorkloadContext(target, spec, facts, collision, 0, 1, spec.seed());
			Map<String, String> generic = captureAll(conditional, context, image, prefix, dataSource,
					AutoExplain.PlanCacheMode.GENERIC);
			Map<String, String> custom = captureAll(conditional, context, image, prefix, dataSource,
					AutoExplain.PlanCacheMode.CUSTOM);

			for ( Workload workload : conditional ) {
				String steadyState = generic.get(workload.name());
				if ( steadyState == null ) {
					continue;
				}
				String shape = QueryPlans.CAPTURED_SHAPE_PREFIX
						+ QueryPlans.shapeFor(workload.name(), targetLabel);
				plans.add(new QueryPlans.Plan(shape + GENERIC_SUFFIX.formatted(collision.label()), "", steadyState));

				String firstExecutions = custom.get(workload.name());
				if ( firstExecutions != null && !QueryPlans.sameShape(firstExecutions, steadyState) ) {
					plans.add(new QueryPlans.Plan(shape + CUSTOM_SUFFIX.formatted(collision.label()), "",
							firstExecutions));
				}
			}
		} catch ( RuntimeException e ) {
			LOGGER.warn("could not capture the store's own append plans", e);
		} finally {
			AutoExplain.resetPlanCacheMode(dataSource);
			removeCapturedEvents(dataSource, prefix, headBefore);
		}
		return plans;
	}

	/**
	 * Every workload's plan under one plan-cache mode, keyed by workload name.
	 *
	 * <p>The mode is set on the database and the pool's connections are retired, so the round runs on
	 * connections that carry it. A mode that cannot be set means the round is skipped rather than
	 * silently capturing whatever the server felt like planning -- the case this whole class exists to
	 * stop being reported as fact.
	 */
	private static Map<String, String> captureAll ( List<Workload> workloads, WorkloadContext context,
			String image, String prefix, DataSource dataSource, AutoExplain.PlanCacheMode mode ) {
		if ( !AutoExplain.planCacheMode(dataSource, mode) ) {
			return Map.of();
		}
		Map<String, String> plans = new LinkedHashMap<>();
		for ( Workload workload : workloads ) {
			captureOne(workload, context, image, prefix)
					.ifPresent(explain -> plans.put(workload.name(), explain));
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

	private static Optional<String> captureOne ( Workload workload, WorkloadContext context,
			String image, String prefix ) {
		// Warm before marking the log, so what is captured is a statement the server has seen before and
		// whose pages are in shared buffers, as they are throughout a measured trial.
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
				return plan;
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
