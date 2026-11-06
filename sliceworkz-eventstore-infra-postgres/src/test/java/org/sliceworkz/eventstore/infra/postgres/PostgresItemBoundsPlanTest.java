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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Pins the statement a read gets when its filter's items carry different bounds: one ordered branch per
 * item, each starting at its own range, merged in order — never one walk over the widest range with
 * every item's range checked row by row.
 * <p>
 * The single-statement form puts every item's range inside its own OR group, and a range inside a
 * disjunction is no index start condition. PostgreSQL then walks the stream from the start of the widest
 * range and filters every row: on a million-event stream, an item bounded to the first 5% beside a broad
 * item bounded to the last 10% discarded 905.792 rows to return a page of 500. Correct, unlogged, and
 * linear in the stream, which is why the shape is pinned rather than left to be noticed.
 * <p>
 * <b>The corpus is the repro.</b> One stream, the narrow item selective by tag and bounded early, the
 * broad item matching a tenth of the stream and bounded late, so that a walk from the start would cross
 * almost all of it before the page fills. The assertions are about what the plan reads, not how long it
 * took: every branch starts at its own bound in an index, nothing scans the table, and the rows fetched
 * only to be discarded stay a small share of the stream.
 * <p>
 * The plans are taken as the store runs the statement: planned from its values on every execution,
 * since the store keeps it off the plan cache. A generic plan for this statement does not know how
 * narrow each branch is and collects the broad item's every row to sort them, which is why it is kept
 * off.
 */
public class PostgresItemBoundsPlanTest {

	private static final String CONTEXT = "inventory";

	private static final int EVENTS = 30000;

	/** Events per append, so the corpus spans transactions as a real one does. */
	private static final int BATCH = 100;

	/** A page as {@code Projector} reads one. */
	private static final int PAGE = 500;

	private static final int SKUS = 1000;

	private static final int WAREHOUSES = 10;

	private static final List<String> TYPES = List.of("StockAdded", "StockPicked", "StockCounted", "PriceChanged");

	private static final String PREFIX = "itembounds_";

	private static final Map<String, Fixture> FIXTURES = new ConcurrentHashMap<>();

	/** A seeded store, its pool, and the stream's events in order. */
	private record Fixture ( PostgresEventStorageImpl storage, DataSource dataSource, List<StoredEvent> events ) {

		EventReference at ( double share ) {
			return events.get((int) ( events.size() * share )).reference();
		}
	}

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		/**
		 * The shape that cost half a second in the single-statement form: a selective item bounded to the
		 * first 5%, a broad item bounded to the last 10%.
		 */
		@Test
		public void testDifferentlyBoundedItemsEachReadTheirOwnRange ( ) throws Exception {
			Fixture fixture = fixture(image);
			EventFilter filter = EventFilter.of(types("StockCounted"), Tags.of("sku", "SKU-42"))
					.until(fixture.at(0.05))
					.or(EventFilter.of(types("StockAdded", "StockPicked"), Tags.of("warehouse", "W-3"))
							.after(fixture.at(0.90)));

			String plan = explain(fixture, filter, null, Direction.FORWARD);

			assertTrue(plan.contains("Merge Append"), () -> "the branches are merged in order\n" + plan);
			assertFalse(plan.contains("Seq Scan"), () -> "no branch scans the table\n" + plan);
			assertTrue(rangeConditions(plan) >= 2, () -> "each bounded branch starts at its own bound in an index\n" + plan);
			assertTrue(rowsRemoved(plan) < EVENTS / 10, () -> "a branch fetched a large share of the stream only to discard it\n" + plan);
		}

		@Test
		public void testABackwardReadOfDifferentlyBoundedItemsReadsEachRangeFromItsEnd ( ) throws Exception {
			Fixture fixture = fixture(image);
			EventFilter filter = EventFilter.of(types("StockCounted"), Tags.none())
					.until(fixture.at(0.20))
					.or(EventFilter.of(types("PriceChanged"), Tags.none()).until(fixture.at(0.60)));

			String plan = explain(fixture, filter, fixture.at(0.95), Direction.BACKWARD);

			assertTrue(plan.contains("Merge Append"), () -> "the branches are merged in order\n" + plan);
			assertFalse(plan.contains("Seq Scan"), () -> "no branch scans the table\n" + plan);
			assertTrue(rowsRemoved(plan) < EVENTS / 10, () -> "a branch walked from the stream's end rather than its own\n" + plan);
		}

		/**
		 * A filter whose items share their bounds, which is every filter without any, keeps the one
		 * statement it always had: no union, the bounds conjoined at the top level.
		 */
		@Test
		public void testItemsSharingTheirBoundsKeepTheSingleStatement ( ) {
			Fixture fixture = fixture(image);
			EventFilter unbounded = EventFilter.of(types("StockAdded"), Tags.none()).or(EventFilter.forTags(Tags.of("sku", "SKU-1")));
			EventFilter shared = unbounded.after(fixture.at(0.10)).until(fixture.at(0.20));

			for ( EventFilter filter : List.of(unbounded, shared, EventFilter.matchAll(), EventFilter.forTags(Tags.of("sku", "SKU-1")).after(fixture.at(0.5))) ) {
				String sql = fixture.storage().querySql(filter, EventStreamId.forContext(CONTEXT), null, null, Limit.to(PAGE), Direction.FORWARD, new ArrayList<>());
				assertFalse(sql.contains("UNION"), () -> "one statement for " + filter + "\n" + sql);
				assertTrue(PostgresEventStorageImpl.sharesItemBounds(filter));
			}
		}

		@Test
		public void testABranchWhoseRangeIsEmptyIsLeftOutAndNothingLeftIsNoStatement ( ) {
			Fixture fixture = fixture(image);
			EventFilter live = EventFilter.forTags(Tags.of("sku", "SKU-1")).after(fixture.at(0.5));
			EventFilter empty = EventFilter.forTags(Tags.of("sku", "SKU-2")).after(fixture.at(0.4)).until(fixture.at(0.3));
			EventFilter behindTheCursor = EventFilter.forTags(Tags.of("sku", "SKU-3")).until(fixture.at(0.2));

			String sql = fixture.storage().querySql(live.or(empty).or(behindTheCursor), EventStreamId.forContext(CONTEXT),
					fixture.at(0.25), null, Limit.to(PAGE), Direction.FORWARD, new ArrayList<>());
			assertEquals(1, count(sql, "SELECT event_tx, event_position FROM"), () -> "one branch is left\n" + sql);

			assertNull(fixture.storage().querySql(empty.or(behindTheCursor), EventStreamId.forContext(CONTEXT),
					fixture.at(0.25), null, Limit.to(PAGE), Direction.FORWARD, new ArrayList<>()),
					"no statement when no branch can match");
		}

		// ---------------------------------------------------------------- explaining

		private String explain ( Fixture fixture, EventFilter filter, EventReference notAfter, Direction direction ) throws SQLException {
			List<Object> parameters = new ArrayList<>();
			String sql = fixture.storage().querySql(filter, EventStreamId.forContext(CONTEXT), null, notAfter, Limit.to(PAGE), direction, parameters);
			assertFalse(PostgresEventStorageImpl.sharesItemBounds(filter), "this scenario is about items whose bounds differ");
			try ( Connection connection = fixture.dataSource().getConnection() ) {
				connection.setAutoCommit(false);
				try ( Statement session = connection.createStatement() ) {
					session.execute("SET LOCAL plan_cache_mode = force_custom_plan");
				}
				try ( PreparedStatement explain = connection.prepareStatement("EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF) " + sql) ) {
					for ( int i = 0; i < parameters.size(); i++ ) {
						explain.setObject(i + 1, parameters.get(i));
					}
					StringBuilder plan = new StringBuilder();
					try ( ResultSet rows = explain.executeQuery() ) {
						while ( rows.next() ) {
							plan.append(rows.getString(1)).append('\n');
						}
					}
					connection.rollback();
					return plan.toString();
				}
			}
		}

		/** Index conditions carrying a range: a branch that starts at its own bound. */
		private long rangeConditions ( String plan ) {
			return plan.lines().map(String::strip)
					.filter(line -> line.startsWith("Index Cond:") && line.contains("ROW(event_tx, event_position)"))
					.count();
		}

		/** Rows fetched and discarded, summed over the whole plan. */
		private long rowsRemoved ( String plan ) {
			long removed = 0;
			Matcher matcher = Pattern.compile("Rows Removed by (?:Filter|Index Recheck): (\\d+)").matcher(plan);
			while ( matcher.find() ) {
				removed += Long.parseLong(matcher.group(1));
			}
			return removed;
		}

		private static int count ( String text, String part ) {
			int count = 0;
			for ( int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1) ) {
				count++;
			}
			return count;
		}
	}

	private static EventTypesFilter types ( String... names ) {
		return EventTypesFilter.of(java.util.Arrays.stream(names).map(EventType::new).collect(java.util.stream.Collectors.toSet()));
	}

	// ---------------------------------------------------------------- the corpus

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
			List<StoredEvent> events = storage.query(EventFilter.matchAll(), EventStreamId.forContext(CONTEXT),
					null, null, Limit.none(), Direction.FORWARD);
			return new Fixture(storage, dataSource, events);
		});
	}

	private static void release ( String image ) {
		Fixture fixture = FIXTURES.remove(image);
		if ( fixture != null ) {
			fixture.storage().close();
		}
	}

	private static void seed ( PostgresEventStorageImpl storage ) {
		EventStreamId stream = EventStreamId.forContext(CONTEXT);
		for ( int written = 0; written < EVENTS; written += BATCH ) {
			List<EventToStore> events = new ArrayList<>(BATCH);
			for ( int i = written; i < written + BATCH; i++ ) {
				events.add(new EventToStore(stream, new EventType(TYPES.get(i % TYPES.size())), "{}",
						Tags.of("sku", "SKU-" + ( i * 7919 % SKUS ), "warehouse", "W-" + ( i * 31 % WAREHOUSES )), null));
			}
			storage.append(AppendCriteria.none(), stream, events);
		}
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
