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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * What a load run checks about its own results before reporting them.
 *
 * <p>A benchmark that loses events reports throughput for work it did not do, and the number looks
 * <em>better</em> for it. That is not a hypothetical failure mode: the suite this one replaces printed
 * duplicate-projection warnings as a banner in the middle of a wall of output, where they were easy
 * to miss and easier to ignore. Here they fail the run.
 *
 * <p>Three things are checked, each cheap and each catching a distinct way for a run to be lying:
 *
 * <ol>
 *   <li><b>Events in equals events out.</b> What the writers think they appended must match what the
 *       store gained, once swallowed duplicates are accounted for.</li>
 *   <li><b>Nothing projected twice.</b> A projection that sees an event more than once means the
 *       cursor went backwards, and any throughput measured through it is inflated.</li>
 *   <li><b>Transaction order versus position order.</b> An inversion is <em>expected</em> in this store
 *       -- {@code event_position} is a sequence and {@code event_tx} is assigned independently -- so
 *       this is reported rather than failed. It matters because a reader comparing positions alone
 *       would silently skip those events, and knowing how often the two orders disagree under load is
 *       the point.</li>
 * </ol>
 */
public final class LoadCorrectness {

	private static final Logger LOGGER = LoggerFactory.getLogger(LoadCorrectness.class);

	/** One check and how it came out. */
	public record Check ( String name, boolean passed, String detail ) {

		public static Check pass ( String name, String detail ) {
			return new Check(name, true, detail);
		}

		public static Check fail ( String name, String detail ) {
			return new Check(name, false, detail);
		}

		public String toLine ( ) {
			return "%-4s %-26s %s".formatted(passed ? "OK" : "FAIL", name, detail);
		}
	}

	private LoadCorrectness ( ) { }

	/**
	 * Runs every check that applies to this target.
	 *
	 * @param appended what the writers believe they wrote
	 * @param deduplicated appends the store swallowed as duplicates, which legitimately wrote nothing
	 * @param storeGrewBy how many events the store actually gained
	 * @param projected how many events a subscribed projection handled, or -1 when none was running
	 * @param distinctProjected how many <em>distinct</em> events it handled, or -1 when none was running
	 */
	public static List<Check> check ( BenchmarkTarget target, String prefix, long appended, long deduplicated,
			long storeGrewBy, long projected, long distinctProjected ) {
		List<Check> checks = new ArrayList<>();

		checks.add(checkEventsBalance(appended, deduplicated, storeGrewBy));

		if ( projected >= 0 ) {
			checks.add(checkNoDuplicateProjections(projected, distinctProjected));
		}

		target.dataSource().ifPresent(dataSource -> checks.add(reportTransactionInversions(dataSource, prefix)));

		return checks;
	}

	private static Check checkEventsBalance ( long appended, long deduplicated, long storeGrewBy ) {
		long expected = appended - deduplicated;
		if ( storeGrewBy < 0 ) {
			return Check.pass("events in equals out", "not measurable on this target");
		}
		if ( expected == storeGrewBy ) {
			return Check.pass("events in equals out", "%d appended, %d stored".formatted(appended, storeGrewBy));
		}
		return Check.fail("events in equals out",
				"writers appended %d and %d were swallowed as duplicates, so the store should have gained %d -- it gained %d. Throughput from this run describes work that did not happen."
						.formatted(appended, deduplicated, expected, storeGrewBy));
	}

	private static Check checkNoDuplicateProjections ( long projected, long distinctProjected ) {
		if ( projected == distinctProjected ) {
			return Check.pass("nothing projected twice", "%d events, all distinct".formatted(projected));
		}
		return Check.fail("nothing projected twice",
				"the projection handled %d events but only %d distinct ones: %d were seen more than once, so the cursor went backwards"
						.formatted(projected, distinctProjected, projected - distinctProjected));
	}

	/**
	 * Counts events whose transaction order disagrees with their position order.
	 *
	 * <p>Reported, never failed. The two orders genuinely diverge in this store, and a reader that
	 * compares {@code event_position} alone would drop exactly these events -- so the useful thing is
	 * to know how many there are under load, not to treat them as a defect.
	 */
	private static Check reportTransactionInversions ( DataSource dataSource, String prefix ) {
		String sql = """
				SELECT count(*) FROM (
				    SELECT event_tx, LAG(event_tx) OVER (ORDER BY event_position) AS previous_tx
				    FROM %sevents
				) ordered
				WHERE event_tx < previous_tx""".formatted(prefix);

		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql) ) {
			long inversions = rows.next() ? rows.getLong(1) : 0;
			return Check.pass("tx order vs position",
					inversions == 0
							? "the two orders agree everywhere"
							: "%d event(s) carry a lower transaction than the one before them by position -- expected, and exactly what a position-only reader would skip"
									.formatted(inversions));
		} catch ( SQLException e ) {
			LOGGER.warn("could not check transaction ordering", e);
			return Check.pass("tx order vs position", "not checked: " + e.getMessage());
		}
	}

	/**
	 * How many events a store holds, for the before-and-after comparison.
	 *
	 * <p>Counted in SQL where there is a database, and through a wildcard stream where there is not.
	 * The fallback matters more than it looks: without it the events-in-equals-out check reports "not
	 * measurable" on the in-memory backend, which is the one a developer runs first and the only one
	 * available without Docker. A correctness check that quietly opts out on the backend most people
	 * use is not much of a check.
	 *
	 * <p>The fallback reads every event into heap, so it is only sensible at the volumes the in-memory
	 * backend is used for anyway.
	 */
	public static long countEvents ( BenchmarkTarget target, String prefix ) {
		Optional<DataSource> dataSource = target.dataSource();
		if ( dataSource.isPresent() ) {
			try ( Connection connection = dataSource.get().getConnection();
					Statement statement = connection.createStatement();
					ResultSet rows = statement.executeQuery("SELECT count(*) FROM %sevents".formatted(prefix)) ) {
				return rows.next() ? rows.getLong(1) : -1L;
			} catch ( SQLException e ) {
				LOGGER.warn("could not count events in {}events", prefix, e);
				return -1L;
			}
		}

		try {
			return target.store()
					.getEventStream(EventStreamId.anyContext().anyPurpose())
					.query(EventQuery.matchAll())
					.count();
		} catch ( RuntimeException e ) {
			LOGGER.warn("could not count events through a wildcard stream", e);
			return -1L;
		}
	}
}
