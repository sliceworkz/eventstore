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
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusFacts;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusGenerator;
import org.sliceworkz.eventstore.benchmark.domain.TagKeys;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;

/**
 * Captures {@code EXPLAIN (ANALYZE, BUFFERS)} for the read shapes the suite measures, so a surprising
 * number can be attributed to a plan instead of guessed at.
 *
 * <p>Nearly every puzzling read result comes down to one question -- did the planner use the index or
 * scan the table -- and that question is unanswerable from a duration alone. Two runs differing
 * threefold with the same code and the same corpus is usually one of them having crossed the
 * selectivity threshold where a sequential scan wins.
 *
 * <p><b>These are representative statements, not the ones the store issued.</b> The store builds its
 * SQL internally and does not expose it, so these are written here to match the documented shape:
 * the {@code pg_snapshot_xmin} barrier, the stream scoping, the {@code event_tags @> ARRAY[...]}
 * containment and the {@code (event_tx, event_position)} ordering. That is enough to answer the
 * index-or-scan question and to show the row counts and buffer reads, and it is <em>not</em> a
 * substitute for the real statement: if the backend's query builder changes, these will silently go
 * on describing the old shape. Every captured plan is labelled accordingly in the report.
 */
public final class QueryPlans {

	private static final Logger LOGGER = LoggerFactory.getLogger(QueryPlans.class);

	/** One captured plan. */
	public record Plan ( String shape, String sql, String explain ) { }

	private QueryPlans ( ) { }

	/**
	 * Captures a plan for each read shape, or an empty list where there is no database.
	 *
	 * <p>Runs after measurement, never during: {@code EXPLAIN ANALYZE} executes the query, and doing
	 * that alongside a benchmark would be measuring the benchmark's observer.
	 */
	public static List<Plan> capture ( BenchmarkTarget target, String prefix, CorpusSpec spec, CorpusFacts facts ) {
		if ( target.dataSource().isEmpty() ) {
			return List.of();
		}
		DataSource dataSource = target.dataSource().get();
		List<Plan> plans = new ArrayList<>();

		// Which purpose these statements scope to is a property of the corpus, not a constant. A
		// PER_ENTITY corpus puts the entity id in the purpose, so a hard-coded 'default' matched nothing
		// -- and an EXPLAIN over an empty result is worse than no plan at all: it reports a sub-millisecond
		// index scan and looks like an answer.
		boolean perEntity = spec.streamDesign() == CorpusSpec.StreamDesign.PER_ENTITY;
		String purposeClause = perEntity ? "" : " AND stream_purpose = ?";

		plans.add(capture(dataSource, "stream page (unfiltered, limit 500)",
				"""
				SELECT event_position, event_tx::text, event_id, event_type, event_data, event_tags
				FROM %sevents
				WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
				  AND stream_context = ?%s
				ORDER BY event_tx::xid8, event_position
				LIMIT 500""".formatted(prefix, purposeClause),
				scoped(perEntity, "inventory", null)));

		plans.add(capture(dataSource, "tag needle (~10 matches)",
				"""
				SELECT event_position, event_tx::text, event_id, event_type, event_data, event_tags
				FROM %sevents
				WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
				  AND stream_context = ?%s
				  AND ((event_tags @> ARRAY[?]::text[]))
				ORDER BY event_tx::xid8, event_position""".formatted(prefix, purposeClause),
				scoped(perEntity, "inventory", null,
						CorpusGenerator.MARKER_TAG_KEY + ":" + facts.needleTagValue())));

		plans.add(capture(dataSource, "tag swathe (~1% of the store)",
				"""
				SELECT event_position, event_tx::text, event_id, event_type, event_data, event_tags
				FROM %sevents
				WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
				  AND stream_context = ?%s
				  AND ((event_tags @> ARRAY[?]::text[]))
				ORDER BY event_tx::xid8, event_position
				LIMIT 500""".formatted(prefix, purposeClause),
				scoped(perEntity, "inventory", null,
						CorpusGenerator.MARKER_TAG_KEY + ":" + facts.swatheTagValue())));

		// Entity-scoped: under PER_ENTITY the entity IS the purpose, which is the whole point of that
		// design -- so this statement is a different shape on the two corpora, and that difference is
		// exactly what the stream-design comparison is about.
		plans.add(capture(dataSource, "one entity's whole history (hot)",
				"""
				SELECT event_position, event_tx::text, event_id, event_type, event_data, event_tags
				FROM %sevents
				WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
				  AND stream_context = ? AND stream_purpose = ?
				  AND ((event_tags @> ARRAY[?]::text[]))
				ORDER BY event_tx::xid8, event_position""".formatted(prefix),
				"inventory", perEntity ? facts.hotEntity() : "default",
				TagKeys.SKU + ":" + facts.hotEntity()));

		plans.add(capture(dataSource, "most recent event, backwards limit 1",
				"""
				SELECT event_position, event_tx::text, event_id, event_type, event_data, event_tags
				FROM %sevents
				WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
				  AND stream_context = ? AND stream_purpose = ?
				  AND ((event_tags @> ARRAY[?]::text[]))
				ORDER BY event_tx::xid8 DESC, event_position DESC
				LIMIT 1""".formatted(prefix),
				"inventory", perEntity ? facts.hotEntity() : "default",
				TagKeys.SKU + ":" + facts.hotEntity()));

		return plans.stream().filter(java.util.Objects::nonNull).toList();
	}

/**
	 * Binds the context, the purpose where the design has a fixed one, then the rest.
	 *
	 * <p>A PER_ENTITY corpus has no single purpose to scope a store-wide read to, so the predicate is
	 * absent from the SQL and its parameter must be absent too.
	 */
	private static String[] scoped ( boolean perEntity, String context, String purpose, String... rest ) {
		List<String> parameters = new ArrayList<>();
		parameters.add(context);
		if ( !perEntity ) {
			parameters.add(purpose == null ? "default" : purpose);
		}
		parameters.addAll(List.of(rest));
		return parameters.toArray(new String[0]);
	}

	private static Plan capture ( DataSource dataSource, String shape, String sql, String... parameters ) {
		try ( Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"EXPLAIN (ANALYZE, BUFFERS, VERBOSE false, FORMAT TEXT) " + sql) ) {
			for ( int i = 0; i < parameters.length; i++ ) {
				statement.setString(i + 1, parameters[i]);
			}
			StringBuilder explain = new StringBuilder();
			try ( ResultSet rows = statement.executeQuery() ) {
				while ( rows.next() ) {
					explain.append(rows.getString(1)).append('\n');
				}
			}
			return new Plan(shape, sql, explain.toString().stripTrailing());
		} catch ( SQLException e ) {
			// a plan that cannot be captured is a missing section of the report, not a failed run
			LOGGER.warn("could not capture a plan for '{}'", shape, e);
			return null;
		}
	}

	/** Whether a captured plan chose a sequential scan, which is the thing worth noticing at a glance. */
	public static boolean isSequentialScan ( Plan plan ) {
		return plan.explain().contains("Seq Scan");
	}
}
