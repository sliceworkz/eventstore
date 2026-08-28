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
import org.sliceworkz.eventstore.benchmark.workload.Workload;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext.Collision;
import org.sliceworkz.eventstore.testing.backend.PostgresContainer;

/**
 * Runs each read workload once with {@code auto_explain} on, and keeps the plan the server logged for
 * the statement the store actually issued.
 *
 * <p>The read counterpart to {@link AppendPlanCapture}, and it exists because the reconstructions in
 * {@link QueryPlans} turned out to be no more trustworthy for reads than they were for the append --
 * only quieter about it. The first {@code read-shapes} run made that concrete: the reconstruction for
 * the needle tag query reported <b>0.267ms of execution time alone</b>, while the whole measured
 * operation -- statement, round trip, and deserialising the events it returned -- took <b>0.205ms</b>.
 * A plan cannot explain an operation it is slower than. The reconstruction inlines its tag array as a
 * literal and is planned from real column statistics; the store binds it as a JDBC parameter and
 * re-uses the statement. That is the same gap the append had, and the report had no way to notice it
 * because there was nothing captured to check the reconstruction against.
 *
 * <p><b>Nothing is written, so nothing is undone.</b> Unlike the append capture this leaves the corpus
 * exactly as it found it -- which is also why it can run against the profile's own store without the
 * event-count bookkeeping that one needs.
 *
 * <p><b>Each workload is explained twice</b>, generic and custom, for the reason spelled out on
 * {@link AutoExplain.PlanCacheMode}: PostgreSQL holds two plans for a re-used prepared statement and a
 * report that shows one of them without saying which has not answered the question. The custom plan is
 * only reported where it is a different plan rather than the same one with different numbers.
 *
 * <p><b>Never while measuring.</b> Explaining every statement costs the server real work, so this runs
 * after the last measurement, from the launcher.
 */
public final class ReadPlanCapture {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReadPlanCapture.class);

	/** As {@link AppendPlanCapture}: bounds the wait for the container runtime to relay the log. */
	private static final long LOG_SETTLE_MILLIS = 250;

	private static final int LOG_ATTEMPTS = 8;

	/**
	 * How many times to run a workload before the one whose plan is kept.
	 *
	 * <p>Not what decides <em>which</em> of the two plans is captured -- {@link AutoExplain.PlanCacheMode}
	 * does that. This is for everything else the first execution pays and the millionth does not: the
	 * statement becoming server-prepared, its pages reaching shared buffers, the pool settling.
	 */
	private static final int WARMUP_INVOCATIONS = 8;

	private static final String GENERIC_SUFFIX = " (generic plan)";

	private static final String CUSTOM_SUFFIX = " (custom plan, first executions only)";

	/**
	 * The select list every read the backend issues begins with, paired with the events table below so
	 * a plan is matched on the statement's shape rather than on a table name that also appears inside
	 * an append's {@code NOT EXISTS}.
	 */
	private static final String SELECT_LIST = "SELECT event_position, event_tx::text";

	private ReadPlanCapture ( ) { }

	/**
	 * Captures a plan per read workload, or an empty list where that is not possible.
	 *
	 * @param target a store opened <em>after</em> {@link AutoExplain#enable}, so its pooled connections
	 *        carry the setting
	 * @param image the container image tag whose log carries the plans, or null for a server whose log
	 *        this process cannot read -- in which case nothing is captured
	 */
	public static List<QueryPlans.Plan> capture ( BenchmarkTarget target, String image, String prefix,
			CorpusSpec spec, CorpusFacts facts, List<Workload> workloads, String targetLabel ) {
		if ( image == null || target.dataSource().isEmpty() ) {
			return List.of();
		}
		DataSource dataSource = target.dataSource().get();
		List<Workload> reads = workloads.stream().filter(ReadPlanCapture::isRead).toList();
		if ( reads.isEmpty() ) {
			return List.of();
		}

		List<QueryPlans.Plan> plans = new ArrayList<>();
		try {
			// Collision does not reach a read -- every read workload addresses what the corpus facts name,
			// not what a writer was told to collide with -- so SPREAD here is the absence of a choice
			// rather than one, and unlike the append capture there is nothing for it to get wrong.
			WorkloadContext context = new WorkloadContext(target, spec, facts, Collision.SPREAD, 0, 1,
					spec.seed());
			Map<String, String> generic = captureAll(reads, context, image, prefix, dataSource,
					AutoExplain.PlanCacheMode.GENERIC);
			Map<String, String> custom = captureAll(reads, context, image, prefix, dataSource,
					AutoExplain.PlanCacheMode.CUSTOM);

			for ( Workload workload : reads ) {
				String steadyState = generic.get(workload.name());
				if ( steadyState == null ) {
					continue;
				}
				String shape = QueryPlans.CAPTURED_READ_SHAPE_PREFIX
						+ QueryPlans.shapeFor(workload.name(), targetLabel);
				plans.add(new QueryPlans.Plan(shape + GENERIC_SUFFIX, "", steadyState));

				String firstExecutions = custom.get(workload.name());
				if ( firstExecutions != null && !QueryPlans.sameShape(firstExecutions, steadyState) ) {
					plans.add(new QueryPlans.Plan(shape + CUSTOM_SUFFIX, "", firstExecutions));
				}
			}
		} catch ( RuntimeException e ) {
			LOGGER.warn("could not capture the store's own read plans", e);
		} finally {
			AutoExplain.resetPlanCacheMode(dataSource);
		}
		return plans;
	}

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
	 * Whether a workload reads rather than writes.
	 *
	 * <p>Every read workload is named {@code query-*}; {@code replay-batches} is the exception, and it
	 * belongs here because a projector run is the paging read repeated. Its plan is the <em>last</em>
	 * of its ten pages, which is the deep-cursor one and the only one of the ten worth explaining --
	 * the shallow pages are the same statement with a nearer boundary.
	 */
	private static boolean isRead ( Workload workload ) {
		return workload.name().startsWith("query-") || workload.name().equals("replay-batches");
	}

	private static Optional<String> captureOne ( Workload workload, WorkloadContext context,
			String image, String prefix ) {
		for ( int i = 0; i < WARMUP_INVOCATIONS; i++ ) {
			invokeQuietly(workload, context);
		}
		int mark = PostgresContainer.logs(image).length();
		invokeQuietly(workload, context);

		for ( int attempt = 0; attempt < LOG_ATTEMPTS; attempt++ ) {
			Optional<String> plan = AutoExplain.matching(
					AutoExplain.plansIn(PostgresContainer.logs(image), mark),
					SELECT_LIST, "FROM %sevents".formatted(prefix));
			if ( plan.isPresent() ) {
				return plan;
			}
			sleep();
		}
		LOGGER.info("no plan was logged for {}; auto_explain may not be loaded on the store's connections",
				workload.name());
		return Optional.empty();
	}

	/**
	 * A read that finds nothing, or a workload whose corpus cannot satisfy it, still leaves a plan --
	 * and a workload that throws is not worth failing the whole capture over.
	 */
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
}
