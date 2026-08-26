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
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext;

/**
 * Captures {@code EXPLAIN (ANALYZE, BUFFERS)} for the shapes the suite measures -- the read queries,
 * and the DCB consistency check the conditional appends run -- so a surprising number can be
 * attributed to a plan instead of guessed at.
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

	/**
	 * Marks the plans for the DCB consistency check, which the report qualifies rather than presents.
	 * A constant because the renderer matches on it -- a shape renamed in one place and not the other
	 * would silently drop the qualification and leave the plans reading as an answer.
	 */
	public static final String APPEND_SHAPE_PREFIX = "DCB check: ";

	/**
	 * Marks a plan {@link AppendPlanCapture} read back from the server for the store's own statement.
	 * Checked before {@link #APPEND_SHAPE_PREFIX}, and deliberately does not start with it: these carry
	 * none of that qualification, being the plan PostgreSQL actually chose rather than a reconstruction.
	 */
	public static final String CAPTURED_SHAPE_PREFIX = "DCB check as issued: ";

	/**
	 * Separates the workload from the target in a captured plan's shape.
	 *
	 * <p>A run measuring two PostgreSQL configurations explains both, so a plan has to say which store
	 * it came from -- and the renderer has to be able to get back to that target's rows, since the
	 * measured ms/op it prints beside a plan is only meaningful for the store the plan came from.
	 */
	public static final String SHAPE_TARGET_SEPARATOR = " @ ";

	/** A captured plan's shape, minus the prefix: {@code append-types @ postgres:18/metrics=off}. */
	public static String shapeFor ( String workload, String targetLabel ) {
		return targetLabel == null || targetLabel.isBlank()
				? workload
				: workload + SHAPE_TARGET_SEPARATOR + targetLabel;
	}

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

		plans.addAll(appendPredicates(dataSource, prefix, spec, facts, perEntity));

		return plans.stream().filter(java.util.Objects::nonNull).toList();
	}

	/**
	 * Plans for the DCB consistency check itself, which is where the append curve's surprises live.
	 *
	 * <p>The read shapes above answer nothing about {@code append-*}: a conditional append is an
	 * {@code INSERT ... WHERE NOT EXISTS (…)}, and it is that {@code NOT EXISTS} the curve is really
	 * measuring. Two results from the curve are unexplainable without these, and both are the kind of
	 * thing only a plan can settle:
	 *
	 * <ul>
	 * <li><b>A types-only check is several times slower than one that also carries a tag</b>, which
	 * inverts the intuition that a narrower filter costs more -- and inverts the in-memory backend,
	 * where the ordering is the intuitive one. The obvious candidate is that a filter with no tag has
	 * nothing to offer the {@code (stream_context, stream_purpose, event_tags)} GIN index.</li>
	 * <li><b>The OR-group curve has a cliff rather than a slope</b> -- flat from five items to ten, an
	 * order of magnitude below two. A cost that stops growing when the input keeps growing is a plan
	 * flip, not a per-item cost.</li>
	 * </ul>
	 *
	 * <p>Captured as {@code SELECT ... LIMIT 1} rather than as the INSERT, because {@code EXPLAIN
	 * ANALYZE} executes what it explains and a benchmark report has no business appending events. That
	 * makes these representative in the same way the read shapes are: the predicate is the store's,
	 * the statement around it is not. Deliberately carrying <em>no</em> {@code pg_snapshot_xmin}
	 * filter, because the append-side check does not have one either.
	 */
	private static List<Plan> appendPredicates ( DataSource dataSource, String prefix, CorpusSpec spec,
			CorpusFacts facts, boolean perEntity ) {
		List<Plan> plans = new ArrayList<>();

		String purpose = perEntity ? facts.hotEntity() : "default";

		// How far back the boundary sits is the whole measurement, and getting it wrong makes every
		// plan identical and trivial. The first version of this anchored at the head of the log,
		// reasoning that a steady-state append presents the reference its own previous append
		// returned. That is true per entity and false per log: under the SPREAD rotation the workload
		// visits every other entity before coming back, so the reference it presents is a full
		// rotation old and the check has to search everything appended since. Anchored at the head
		// the range is empty, the tag and type predicates are applied to zero rows, and all five
		// shapes came back at 12-37us in plans identical to each other -- three orders of magnitude
		// away from the 0.9-16ms the same shapes actually take, and looking for all the world like an
		// answer.
		int distance = boundaryDistance(spec, perEntity);
		Boundary boundary = readBoundary(dataSource, prefix, purpose, distance);
		if ( boundary == null ) {
			return plans;
		}
		String sku = TagKeys.SKU + ":" + facts.hotEntity();
		String anchor = " -- boundary %,d events back".formatted(distance);

		plans.add(appendPredicate(dataSource, prefix, purpose, boundary,
				APPEND_SHAPE_PREFIX + "event types only, no tag (append-types)" + anchor,
				"event_type IN ('StockReserved','StockPicked')"));

		plans.add(appendPredicate(dataSource, prefix, purpose, boundary,
				APPEND_SHAPE_PREFIX + "four types scoped to one SKU (append-type-and-tag)" + anchor,
				stockTypesWithTags("ARRAY['" + sku + "']")));

		plans.add(appendPredicate(dataSource, prefix, purpose, boundary,
				APPEND_SHAPE_PREFIX + "one item carrying three AND-ed tags (append-multi-tag)" + anchor,
				stockTypesWithTags("ARRAY['" + sku + "','" + TagKeys.CHANNEL + ":web','"
						+ TagKeys.WAREHOUSE + ":WH-1']")));

		// Ten as well as two and five, even though five and ten measured the same: a plan that is
		// identical at both ends of the flat stretch is what turns "it stopped growing" into "it flipped
		// once and then stopped caring", and a plan that differs there means something else is going on.
		for ( int groups : new int[] { 2, 5, 10 } ) {
			StringBuilder predicate = new StringBuilder();
			predicate.append(stockTypesWithTags("ARRAY['" + sku + "']"));
			for ( int i = 1; i < groups; i++ ) {
				predicate.append(" OR ").append(stockTypesWithTags(
						"ARRAY['" + TagKeys.SKU + ":"
								+ WorkloadContext.companionEntity(i, spec.entityCount()) + "']"));
			}
			plans.add(appendPredicate(dataSource, prefix, purpose, boundary,
					APPEND_SHAPE_PREFIX + "%d OR-ed filter items (append-or-groups-%d)%s".formatted(groups, groups, anchor),
					predicate.toString()));
		}

		return plans;
	}

	private static String stockTypesWithTags ( String tagArray ) {
		return "( event_type IN ('StockReceived','StockReserved','StockReleased','StockPicked')"
				+ " AND event_tags @> " + tagArray + "::text[] )";
	}

	/** An (event_tx, event_position) to present as the boundary a conditional append decided on. */
	private record Boundary ( String tx, long position ) { }

	/**
	 * How many events pass between one entity's append and its next, measured in the scope the
	 * predicate is confined to -- which is what the check has to search.
	 *
	 * <p>Under {@code TAGGED} one stream holds every entity, so a full rotation of the writable
	 * entities lands in it before the workload returns to any one of them. Under {@code PER_ENTITY}
	 * the predicate is scoped to that entity's own stream, which receives exactly one append per
	 * rotation, so the same reasoning gives one.
	 */
	private static int boundaryDistance ( CorpusSpec spec, boolean perEntity ) {
		return perEntity ? 1 : Math.max(1, spec.entityCount());
	}

	private static Boundary readBoundary ( DataSource dataSource, String prefix, String purpose, int distance ) {
		// One literal, deliberately: `.formatted` binds tighter than `+`, so with the statement split
		// across two concatenated strings only the second one was formatted and `%sevents` reached the
		// server verbatim -- a syntax error at position 44 that cost the whole append-predicate section.
		//
		// Scoped exactly as the predicate is, so "one rotation back" is counted over the same events
		// the check will have to search rather than over the whole table.
		String sql = """
				SELECT event_tx::text, event_position
				FROM %sevents
				WHERE stream_context = ? AND stream_purpose = ?
				ORDER BY event_tx %s, event_position %s
				OFFSET ? LIMIT 1""";
		try ( Connection connection = dataSource.getConnection() ) {
			Boundary boundary = readOne(connection, sql.formatted(prefix, "DESC", "DESC"), purpose, distance);
			// A stream holding fewer events than one rotation has no row that far back. Fall back to
			// its oldest -- the furthest back this corpus can express, so an overstated range rather
			// than the head's empty one, which is the failure this method exists to avoid.
			return boundary != null ? boundary
					: readOne(connection, sql.formatted(prefix, "ASC", "ASC"), purpose, 0);
		} catch ( SQLException e ) {
			LOGGER.warn("could not read a boundary reference; skipping the append-predicate plans", e);
			return null;
		}
	}

	private static Boundary readOne ( Connection connection, String sql, String purpose, int offset )
			throws SQLException {
		try ( PreparedStatement statement = connection.prepareStatement(sql) ) {
			statement.setString(1, "inventory");
			statement.setString(2, purpose);
			statement.setInt(3, offset);
			try ( ResultSet rows = statement.executeQuery() ) {
				return rows.next() ? new Boundary(rows.getString(1), rows.getLong(2)) : null;
			}
		}
	}

	private static Plan appendPredicate ( DataSource dataSource, String prefix, String purpose, Boundary head,
			String shape, String filter ) {
		return capture(dataSource, shape,
				"""
				SELECT 1
				FROM %sevents
				WHERE stream_context = ? AND stream_purpose = ?
				  AND ( %s )
				  AND ( event_tx > ?::xid8
				        OR ( event_tx = ?::xid8 AND event_position > ?::bigint ) )
				LIMIT 1""".formatted(prefix, filter),
				"inventory", purpose, head.tx(), head.tx(), String.valueOf(head.position()));
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
