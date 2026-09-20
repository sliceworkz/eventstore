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
package org.sliceworkz.eventstore.infra.postgres;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins that a read of one stream, or of one context, is never served by a <em>wider</em> order index.
 * <p>
 * The three order indexes all carry the {@code (event_tx, event_position)} order behind different
 * leading columns, so a wider one can serve a narrower read: walk it in order and filter on the stream
 * columns it does not lead with. The planner takes that whenever the stream is a large share of the
 * table — the wider index is smaller, and it prices the filter as though the stream's events were
 * spread evenly through the order. They are not: a stream that has been quiet while other streams wrote
 * sits behind everything written since, so the walk covers all of it one row at a time, and grows with
 * everything those streams write. Every answer stays correct and nothing is logged, which is why this
 * is pinned rather than left to be noticed.
 * <p>
 * The mechanism is the admission predicate each wider order index is partial on
 * ({@code PostgresEventStorageImpl.GLOBAL_ORDER_ADMISSION} and its context counterpart): a tautology
 * the planner cannot prove, spelled out by exactly the statements whose scope binds no more than that
 * index leads with. A statement that does not carry it is not admitted to the index at all, whatever
 * the estimate — which is the property a cost-based fix cannot have, and is why the assertions here are
 * about which index a plan names rather than about how long anything took.
 * <p>
 * {@code PostgresGlobalOrderIndexTest} is the other half: that a wildcard or whole-context read still
 * <em>reaches</em> the order index it is for. Both halves are needed — a predicate nothing carries
 * leaves those reads scanning and sorting, and a predicate everything carries changes nothing.
 * <p>
 * <b>The corpus is the repro, and every part of it is load-bearing.</b> The contexts hold one stream
 * each, so the stream under test is a large share of the table; it is written first and then falls
 * quiet while the others write, so its newest event sits far back in the global order; and the others
 * interleave with each other rather than being written one context at a time, so {@code stream_context}
 * does not correlate with the heap and the planner does not price the stream's own index as the cheap
 * one anyway. Change any of the three and the scenarios pass whether or not the mechanism is there.
 * <p>
 * Each plan is taken twice, under a custom plan and under a forced generic one. The generic plan is the
 * one that matters most: the consistency check of a conditional append is server-prepared, so from the
 * sixth append onwards that is the plan the store actually runs.
 */
public class PostgresOrderIndexAdmissionTest {

	/** The context that is written first and then falls quiet. One stream, the default purpose. */
	private static final String QUIET_CONTEXT = "ledger";

	/** The contexts that keep writing afterwards, interleaved with each other. One stream each. */
	private static final List<String> BUSY_CONTEXTS = List.of("accounts", "sales");

	/** Events in the quiet stream. */
	private static final int QUIET_EVENTS = 2000;

	/** Events written after it falls quiet, split over the busy contexts. */
	private static final int BUSY_EVENTS = 6000;

	/** Events per append, so the corpus spans transactions as a real one does. */
	private static final int BATCH = 100;

	/** A page as {@code Projector} reads one. */
	private static final int PAGE = 500;

	/** One tag value per this many events, so a tag query is selective. */
	private static final int ENTITIES = 50;

	private static final String PREFIX = "orderadmission_";

	/** One seeded store per image: every scenario here only reads, so they can share it. */
	private static final Map<String, Fixture> FIXTURES = new ConcurrentHashMap<>();

	/** A seeded store, its pool, and the references the scenarios need from it. */
	private record Fixture ( PostgresEventStorageImpl storage, DataSource dataSource,
			EventReference quietHead, EventReference quietMidpoint ) { }

	/** A plan, and the plan-cache mode it was taken under. */
	private record Plan ( String mode, String text ) { }

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		/**
		 * The head of the quiet stream. Off the global order index this is a backward walk over
		 * everything the other streams have written since, discarding every row — 24ms against 0.08ms at
		 * 500.000 events, and growing.
		 */
		@Test
		public void testTheHeadOfAQuietStreamIsNotServedByAWiderOrderIndex ( ) throws Exception {
			Fixture fixture = fixture(image);
			EventStreamId quiet = EventStreamId.forContext(QUIET_CONTEXT);

			List<Plan> plans = explain(fixture, PostgresEventStorageImpl.headSql(PREFIX, quiet),
					List.of(quiet.context(), quiet.purpose()));

			assertServedBy(plans, "idx_events_stream_position");
		}

		/**
		 * A page of the quiet stream, carried by a cursor. The cursor has to stay an index start
		 * condition, which is what makes a page cost the page; a wider index demotes it to a filter over
		 * everything the other streams wrote.
		 */
		@Test
		public void testAPageOfAQuietStreamIsNotServedByAWiderOrderIndex ( ) throws Exception {
			Fixture fixture = fixture(image);
			List<Object> parameters = new ArrayList<>();
			String sql = fixture.storage().querySql(EventFilter.matchAll(), EventStreamId.forContext(QUIET_CONTEXT),
					fixture.quietMidpoint(), Limit.to(PAGE), Direction.FORWARD, parameters);

			List<Plan> plans = explain(fixture, sql, parameters);

			assertServedBy(plans, "idx_events_stream_position");
			for ( Plan plan : plans ) {
				assertTrue(indexCondition(plan.text()).contains("event_position"), () ->
						"the cursor has to be a start condition on the index, not a filter over rows already"
								+ " fetched from the head\n" + plan.text());
			}
		}

		/**
		 * The newest event of the quiet stream carrying a tag — the "last relevant event" shape a decider
		 * reads its boundary with. Answered from the stream's own indexes it is a probe; off the global
		 * order index it is a backward walk discarding everything the busy streams wrote, which is where
		 * the 113ms captured in the {@code crowded-store} benchmark came from.
		 */
		@Test
		public void testTheNewestTaggedEventOfAQuietStreamIsNotFoundByWalkingAWiderOrderIndex ( ) throws Exception {
			Fixture fixture = fixture(image);
			List<Object> parameters = new ArrayList<>();
			String sql = fixture.storage().querySql(EventFilter.forTags(Tags.of("entity", "7")),
					EventStreamId.forContext(QUIET_CONTEXT), null, Limit.to(1), Direction.BACKWARD, parameters);

			List<Plan> plans = explain(fixture, sql, parameters);

			assertNotServedBy(plans, "idx_events_global_order");
			assertNotServedBy(plans, "idx_events_context_order");
		}

		/**
		 * The consistency check of a conditional append, in the shape the store gives it when the criteria
		 * carries an expected reference: the ordered probe, walked forward from that reference. This is
		 * the worst of the four, because the statement is server-prepared and so runs from a cached
		 * generic plan — 56ms per append against 0.02ms, measured on a store whose checked stream sits
		 * 300.000 events behind its neighbours, with the boundary pinned at the stream's own head.
		 */
		@Test
		public void testTheConsistencyCheckOfAConditionalAppendIsNotServedByAWiderOrderIndex ( ) throws Exception {
			Fixture fixture = fixture(image);
			EventStreamId quiet = EventStreamId.forContext(QUIET_CONTEXT);
			AppendCriteria criteria = AppendCriteria.of(EventFilter.forTags(Tags.of("entity", "7")), fixture.quietHead());

			StringBuilder sql = new StringBuilder("SELECT 1 ");
			List<Object> parameters = new ArrayList<>();
			fixture.storage().addConsistencyCheck(sql, parameters, criteria, quiet);

			List<Plan> plans = explain(fixture, sql.toString(), parameters);

			assertServedBy(plans, "idx_events_stream_position");
		}

		/**
		 * The head of the quiet context, which binds the context and leaves the purpose open. Its own
		 * order index is the context one; the global one would walk every other context's events.
		 */
		@Test
		public void testTheHeadOfAQuietContextIsNotServedByTheGlobalOrderIndex ( ) throws Exception {
			Fixture fixture = fixture(image);
			EventStreamId context = EventStreamId.forContext(QUIET_CONTEXT).anyPurpose();

			List<Plan> plans = explain(fixture, PostgresEventStorageImpl.headSql(PREFIX, context),
					List.of(context.context()));

			assertServedBy(plans, "idx_events_context_order");
		}

		/**
		 * The other direction, on this corpus: the reads the wider indexes exist for do carry the
		 * admission predicate and are served by them. Without this, a predicate nobody carries would pass
		 * every scenario above while leaving a wildcard read scanning and sorting.
		 */
		@Test
		public void testAWildcardReadIsStillServedByTheGlobalOrderIndex ( ) throws Exception {
			Fixture fixture = fixture(image);
			List<Plan> plans = explain(fixture,
					PostgresEventStorageImpl.headSql(PREFIX, EventStreamId.anyContext()), List.of());

			assertServedBy(plans, "idx_events_global_order");
		}

		// ---------------------------------------------------------------- assertions

		/**
		 * The plan is an index scan on the named index, it names no other order index, and it discarded
		 * nothing — a row removed by a filter here is a foreign stream's event that was fetched to find
		 * that out, which is the cost this whole mechanism exists to avoid.
		 */
		private void assertServedBy ( List<Plan> plans, String index ) {
			for ( Plan plan : plans ) {
				assertTrue(plan.text().contains("using " + PREFIX + index), () ->
						"under a " + plan.mode() + " plan this read has to be served by " + index + "\n" + plan.text());
			}
			for ( String other : List.of("idx_events_stream_position", "idx_events_context_order", "idx_events_global_order") ) {
				if ( !other.equals(index) ) {
					assertNotServedBy(plans, other);
				}
			}
			for ( Plan plan : plans ) {
				assertFalse(plan.text().contains("Rows Removed by Filter"), () ->
						"under a " + plan.mode() + " plan this read fetched rows only to discard them\n" + plan.text());
			}
		}

		private void assertNotServedBy ( List<Plan> plans, String index ) {
			for ( Plan plan : plans ) {
				assertFalse(plan.text().contains(PREFIX + index), () ->
						"under a " + plan.mode() + " plan this read walks " + index + ", which does not lead with the"
								+ " stream columns it is scoped by, so it filters out every event of every other"
								+ " stream written since\n" + plan.text());
			}
		}

		/** Everything on the plan's {@code Index Cond} lines, or the empty string if it has none. */
		private String indexCondition ( String plan ) {
			return plan.lines()
					.map(String::strip)
					.filter(line -> line.startsWith("Index Cond:"))
					.reduce("", String::concat);
		}

		// ---------------------------------------------------------------- explaining

		/**
		 * The plan of one statement under both plan-cache modes.
		 * <p>
		 * {@code force_generic_plan} is the one that matters: a statement the store repeats on a
		 * connection becomes server-prepared, and from then on PostgreSQL may answer it from a plan built
		 * without any of the values bound to it. {@code ANALYZE} so that the plan carries what it
		 * actually discarded, and the statement is rolled back, which for these read-only statements
		 * costs nothing and keeps the shared corpus untouched.
		 */
		private List<Plan> explain ( Fixture fixture, String sql, List<Object> parameters ) throws SQLException {
			Map<String, String> plans = new LinkedHashMap<>();
			for ( String mode : List.of("force_custom_plan", "force_generic_plan") ) {
				try ( Connection connection = fixture.dataSource().getConnection() ) {
					connection.setAutoCommit(false);
					try ( Statement session = connection.createStatement() ) {
						session.execute("SET LOCAL plan_cache_mode = " + mode);
					}
					try ( PreparedStatement explain = connection.prepareStatement(
							"EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF) " + sql) ) {
						for ( int i = 0; i < parameters.size(); i++ ) {
							explain.setObject(i + 1, parameters.get(i));
						}
						StringBuilder plan = new StringBuilder();
						try ( ResultSet rows = explain.executeQuery() ) {
							while ( rows.next() ) {
								plan.append(rows.getString(1)).append('\n');
							}
						}
						plans.put(mode, plan.toString());
					}
					connection.rollback();
				}
			}
			return plans.entrySet().stream().map(entry -> new Plan(entry.getKey(), entry.getValue())).toList();
		}
	}

	// ---------------------------------------------------------------- the corpus

	/**
	 * The seeded store for an image, built on first use and shared by every scenario for it.
	 * <p>
	 * Seeding is the expensive part and every scenario reads the same corpus, so it is done once. The
	 * store is opened with {@code recreateDatabase()}, which is what makes a second run of the suite
	 * against a reused container start from an empty table.
	 */
	private static synchronized Fixture fixture ( String image ) {
		return FIXTURES.computeIfAbsent(image, key -> {
			DataSource dataSource = PostgresContainer.dataSource(key);
			PostgresEventStorageImpl storage = (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
					.name("unit-test")
					.prefix(PREFIX)
					.dataSource(dataSource)
					.recreateDatabase()
					.build();
			seed(storage);
			analyze(dataSource);
			List<StoredEvent> quiet = storage.query(EventFilter.matchAll(), EventStreamId.forContext(QUIET_CONTEXT),
					null, Limit.none(), Direction.FORWARD);
			return new Fixture(storage, dataSource,
					quiet.get(quiet.size() - 1).reference(), quiet.get(quiet.size() / 2).reference());
		});
	}

	/** Closes the store seeded for an image, if any; called before the container is stopped. */
	private static void release ( String image ) {
		Fixture fixture = FIXTURES.remove(image);
		if ( fixture != null ) {
			fixture.storage().close();
		}
	}

	/**
	 * The quiet stream first, in full, and then the busy contexts alternating batch by batch. The order
	 * is the point: see the class comment for what each part of it is doing.
	 */
	private static void seed ( PostgresEventStorageImpl storage ) {
		EventStreamId quiet = EventStreamId.forContext(QUIET_CONTEXT);
		for ( int written = 0; written < QUIET_EVENTS; written += BATCH ) {
			storage.append(AppendCriteria.none(), quiet, batch(quiet, written));
		}
		int busyBatches = BUSY_EVENTS / BATCH;
		for ( int batch = 0; batch < busyBatches; batch++ ) {
			EventStreamId busy = EventStreamId.forContext(BUSY_CONTEXTS.get(batch % BUSY_CONTEXTS.size()));
			storage.append(AppendCriteria.none(), busy, batch(busy, batch * BATCH));
		}
	}

	private static List<EventToStore> batch ( EventStreamId stream, int from ) {
		List<EventToStore> events = new ArrayList<>();
		for ( int i = 0; i < BATCH; i++ ) {
			events.add(new EventToStore(stream, new EventType("SomethingHappened"), "{}",
					Tags.of("entity", String.valueOf((from + i) % ENTITIES)), null));
		}
		return events;
	}

	private static void analyze ( DataSource dataSource ) {
		try ( Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement() ) {
			statement.execute("VACUUM ANALYZE " + PREFIX + "events");
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not analyze the seeded corpus", e);
		}
	}

	@Nested
	class OnPostgres16 extends Tests {

		OnPostgres16 ( ) { super(PostgresContainer.IMAGE_PG16); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG16);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			release(PostgresContainer.IMAGE_PG16);
			PostgresContainer.stop(PostgresContainer.IMAGE_PG16);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG16);
		}
	}

	@Nested
	class OnPostgres17 extends Tests {

		OnPostgres17 ( ) { super(PostgresContainer.IMAGE_PG17); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG17);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			release(PostgresContainer.IMAGE_PG17);
			PostgresContainer.stop(PostgresContainer.IMAGE_PG17);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17);
		}
	}

	@Nested
	class OnPostgres18 extends Tests {

		OnPostgres18 ( ) { super(PostgresContainer.IMAGE_PG18); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG18);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			release(PostgresContainer.IMAGE_PG18);
			PostgresContainer.stop(PostgresContainer.IMAGE_PG18);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18);
		}
	}
}
