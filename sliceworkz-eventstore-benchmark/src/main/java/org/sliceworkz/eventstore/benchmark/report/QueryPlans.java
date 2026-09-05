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
	 * Marks a plan {@link ReadPlanCapture} read back from the server for one of the store's own reads.
	 *
	 * <p>Separate from {@link #CAPTURED_SHAPE_PREFIX} because the two carry different qualifications --
	 * an append plan names the collision mode it was addressed under, a read has no such thing -- and
	 * because a report holding only one of them should introduce only that one. {@link #isCaptured}
	 * is what the renderer asks where the distinction does not matter.
	 */
	public static final String CAPTURED_READ_SHAPE_PREFIX = "read as issued: ";

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

	/** Whether this plan came back from the server for a statement the store itself issued. */
	public static boolean isCaptured ( Plan plan ) {
		return plan.shape().startsWith(CAPTURED_SHAPE_PREFIX)
				|| plan.shape().startsWith(CAPTURED_READ_SHAPE_PREFIX);
	}

	/** A captured plan's shape with whichever prefix it carries removed, for matching it to its row. */
	public static String capturedSubject ( Plan plan ) {
		String shape = plan.shape();
		if ( shape.startsWith(CAPTURED_SHAPE_PREFIX) ) {
			return shape.substring(CAPTURED_SHAPE_PREFIX.length());
		}
		if ( shape.startsWith(CAPTURED_READ_SHAPE_PREFIX) ) {
			return shape.substring(CAPTURED_READ_SHAPE_PREFIX.length());
		}
		return shape;
	}

	/**
	 * Whether two plans differ only in their numbers.
	 *
	 * <p>Reduces a plan to the nodes it is made of -- their kind and what they read -- and compares
	 * that, so a custom plan is reported beside the generic one when it uses a different index or scans
	 * where the other seeks, and suppressed when it is the same plan with different row counts. That
	 * distinction is the entire reason for capturing twice; two identical plans in the report would only
	 * make the section longer.
	 */
	public static boolean sameShape ( String one, String other ) {
		return nodesOf(one).equals(nodesOf(other));
	}

	private static List<String> nodesOf ( String explain ) {
		List<String> nodes = new ArrayList<>();
		for ( String line : explain.split("\n") ) {
			int cost = line.indexOf("  (cost=");
			if ( cost < 0 ) {
				continue;
			}
			String node = line.substring(0, cost).strip();
			nodes.add(node.startsWith("->") ? node.substring(2).strip() : node);
		}
		return nodes;
	}

	private QueryPlans ( ) { }

	/**
	 * Captures a plan for each read shape, or an empty list where there is no database.
	 *
	 * <p>Runs after measurement, never during: {@code EXPLAIN ANALYZE} executes the query, and doing
	 * that alongside a benchmark would be measuring the benchmark's observer.
	 *
	 * @param includeAppendPredicates whether to also reconstruct the DCB check's predicates, which is
	 *        worth doing only for a profile that appends
	 */
	public static List<Plan> capture ( BenchmarkTarget target, String prefix, CorpusSpec spec,
			CorpusFacts facts, boolean includeAppendPredicates ) {
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

		// The one read shape that carries a boundary; everything above reads from the head of a stream,
		// where there is no cursor to compare against.
		facts.midCursor().ifPresent(cursor -> plans.add(capture(dataSource,
				"cursor page from the midpoint (limit 500)",
				"""
				SELECT event_position, event_tx::text, event_id, event_type, event_data, event_tags
				FROM %sevents
				WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
				  AND %s
				  AND stream_context = ?%s
				ORDER BY event_tx::xid8, event_position
				LIMIT 500""".formatted(prefix, tupleBoundary(">"), purposeClause),
				withScope(perEntity, tupleParameters(cursor.tx(), cursor.position())))));

		// Only where the profile appends. A read-only profile used to carry six DCB-check plans for
		// statements it never issued, under a caveat saying they might not describe what ran -- which
		// was true and beside the point, since nothing in the run ran anything like them.
		if ( includeAppendPredicates ) {
			plans.addAll(appendPredicates(dataSource, prefix, spec, facts, perEntity));
		}

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
	 * <p>The reconstructed predicates are scoped to the hot entity, and the walk draws entities with
	 * the corpus's own skew -- so under {@code TAGGED}, where one stream holds every entity, the hot
	 * entity's steady-state boundary is its expected re-draw gap old: one draw in every
	 * {@code 1/share} lands on it, and every draw in between appends one event to the shared stream.
	 * Under {@code PER_ENTITY} the predicate is scoped to that entity's own stream, which receives
	 * only its own appends, so the distance is one whatever the walk does.
	 */
	private static int boundaryDistance ( CorpusSpec spec, boolean perEntity ) {
		if ( perEntity ) {
			return 1;
		}
		double hotShare = new org.sliceworkz.eventstore.benchmark.corpus.EntityDistribution(spec.entityCount()).shareOf(0);
		return Math.max(1, (int) Math.round(1.0d / hotShare));
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
		List<String> parameters = new ArrayList<>(List.of("inventory", purpose));
		parameters.addAll(List.of(tupleParameters(head.tx(), head.position())));
		return capture(dataSource, shape,
				"""
				SELECT 1
				FROM %sevents
				WHERE stream_context = ? AND stream_purpose = ?
				  AND ( %s )
				  AND %s
				LIMIT 1""".formatted(prefix, filter, tupleBoundary(">")),
				parameters.toArray(new String[0]));
	}

	/**
	 * The {@code (event_tx, event_position)} boundary, as a row constructor comparison -- the spelling
	 * the store uses, and the one a btree can turn into a start condition. See
	 * {@code PostgresEventStorageImpl.appendTupleBoundary}.
	 *
	 * <p>A copy of the store's predicate rather than the store's predicate itself, exactly as every
	 * statement in this class is a copy of a read path rather than the read path. What that costs is
	 * that the two can drift; what it buys is a plan for a statement {@code EXPLAIN ANALYZE} may
	 * execute without appending anything.
	 */
	private static String tupleBoundary ( String comparison ) {
		return "(event_tx, event_position) %s (?::xid8, ?::bigint)".formatted(comparison);
	}

	private static String[] tupleParameters ( String tx, long position ) {
		return new String[] { tx, String.valueOf(position) };
	}

	private static String[] tupleParameters ( long tx, long position ) {
		return tupleParameters(Long.toUnsignedString(tx), position);
	}

	/** Boundary parameters first, then the stream scope, matching the order the statement binds them. */
	private static String[] withScope ( boolean perEntity, String[] boundary ) {
		List<String> parameters = new ArrayList<>(List.of(boundary));
		parameters.add("inventory");
		if ( !perEntity ) {
			parameters.add("default");
		}
		return parameters.toArray(new String[0]);
	}

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

	/**
	 * One thing worth saying about a plan: a short badge for the heading and a sentence under it.
	 *
	 * @param badge what to put beside the shape, or null for a finding that only deserves the sentence
	 * @param note what it means and, where there is one, what to do about it
	 */
	public record Verdict ( String badge, String note ) { }

	/**
	 * What is worth saying about a plan without reading it line by line.
	 *
	 * <p><b>Why the report should judge rather than only display.</b> A plan is forty lines of a
	 * notation most readers skim, and the things that matter in it are recognisable by pattern: a
	 * sequential scan where an index was available, a bitmap that went lossy because {@code work_mem}
	 * was too small, a sort that spilled to disk, JIT compilation charged to a query that did not need
	 * it. All four are in this suite's own published plans and all four went unremarked until somebody
	 * read them by hand. Naming them costs nothing and is what turns the section from evidence into an
	 * answer.
	 *
	 * <p>These are observations about the plan in front of them, never inferences about why the planner
	 * chose it -- that reasoning belongs in prose a person writes, and has already been wrong once here.
	 */
	public static List<Verdict> verdictsOn ( Plan plan ) {
		String explain = plan.explain();
		List<Verdict> verdicts = new java.util.ArrayList<>();

		if ( explain.contains("Seq Scan") ) {
			long discarded = sumOf(explain, "Rows Removed by Filter: ");
			verdicts.add(new Verdict("sequential scan",
					discarded > 0
							? ("no index served this, so it read the table from the beginning and discarded "
									+ "%,d rows on the way. A predicate the index can start from -- the cursor "
									+ "boundary alone does this -- turns the same question into a seek.")
									.formatted(discarded)
							: "no index served this, so it read the table from the beginning."));
		}
		if ( explain.contains("lossy=") ) {
			verdicts.add(new Verdict("lossy bitmap",
					"the bitmap outgrew work_mem, so whole pages were marked instead of rows and every "
							+ "row on them had to be re-checked. Raising work_mem for this statement removes "
							+ "the recheck entirely."));
		}
		if ( explain.contains("external merge") || explain.contains("external sort") ) {
			verdicts.add(new Verdict("sorts on disk",
					"the sort did not fit in work_mem and spilled to disk. Either the read returns more "
							+ "rows than it needs -- a limit or a savepoint -- or work_mem is too small for "
							+ "the size of result this query is meant to produce."));
		}
		jitCost(explain).ifPresent(millis -> verdicts.add(new Verdict("JIT " + millis + "ms",
				"PostgreSQL compiled this query before running it, which it does when the estimated cost "
						+ "is high. On a query that turns out to be short the compilation is most of the "
						+ "wait, and jit_above_cost is the knob.")));
		// Named because it is the counter-example the section needs: the same DCB check without a tag
		// gets exactly this, and the contrast is the finding rather than either plan alone.
		if ( explain.contains("Index Cond") && explain.contains("ROW(event_tx") ) {
			verdicts.add(new Verdict(null,
					"the cursor boundary is an Index Cond here, so the scan starts at the boundary rather "
							+ "than filtering its way to it."));
		}
		return verdicts;
	}

	/** Adds up every occurrence of a counter the plan may report once per node. */
	private static long sumOf ( String explain, String label ) {
		long total = 0;
		int at = explain.indexOf(label);
		while ( at >= 0 ) {
			int from = at + label.length();
			int to = from;
			while ( to < explain.length() && Character.isDigit(explain.charAt(to)) ) {
				to++;
			}
			if ( to > from ) {
				total += Long.parseLong(explain.substring(from, to));
			}
			at = explain.indexOf(label, to);
		}
		return total;
	}

	/** What JIT compilation cost this query, in whole milliseconds, when it happened at all. */
	private static java.util.Optional<String> jitCost ( String explain ) {
		int jit = explain.indexOf("JIT:");
		if ( jit < 0 ) {
			return java.util.Optional.empty();
		}
		java.util.regex.Matcher matcher = // the Timing line writes "Total 204.927 ms", with no colon, unlike every other label in it
		java.util.regex.Pattern.compile("Total:? ([0-9.]+) ms")
				.matcher(explain.substring(jit));
		return matcher.find()
				? java.util.Optional.of("%.0f".formatted(Double.parseDouble(matcher.group(1))))
				: java.util.Optional.empty();
	}
}
