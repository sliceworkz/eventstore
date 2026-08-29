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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns on PostgreSQL's {@code auto_explain} and reads back the plans it logs, so the report can show
 * the plan the store's own statement got rather than one reconstructed from the outside.
 *
 * <p><b>Why this exists.</b> {@link QueryPlans} writes statements by hand to match the shapes the
 * backend issues, and for the read queries that is close enough. For the conditional append it is not,
 * and the way it failed is worth keeping in mind for anything similar: the hand-written plans came out
 * <em>inverted</em> against the measurements -- a shape planning as a sub-millisecond index-only scan
 * measured slowest, and one planning as an eight-millisecond sequential scan measured nearly fastest.
 * The reconstruction was faithful in its predicate and wrong in its parameterisation. The store binds
 * its tag arrays and cursor as JDBC parameters and re-uses the prepared statement, so PostgreSQL plans
 * it generically against default selectivity; inlining the same values as literals gets a custom plan
 * from real column statistics, and that alone flips index-versus-scan. A reconstruction cannot be
 * trusted on the one question the plans exist to answer, so the server is asked instead.
 *
 * <p><b>How it is turned on.</b> {@code ALTER DATABASE ... SET session_preload_libraries} rather than a
 * session-level {@code LOAD}: the statements to be explained run on the store's own pooled connections,
 * which this class never touches. A database-level setting applies to every connection opened
 * afterwards, so a store built after {@link #enable} gets it and one built before does not. The
 * settings are placeholders until the module loads, which PostgreSQL accepts for any qualified name,
 * so they can all be set in one go.
 *
 * <p><b>Reading the plan back is only half of it.</b> Which plan the server has for a statement depends
 * on how many times it has been executed, so the capture also pins that -- see {@link PlanCacheMode}.
 *
 * <p><b>Everything here is best effort.</b> A managed PostgreSQL that does not ship the module, or a
 * role without the rights to alter the database, means no plans -- which is a missing section of the
 * report, not a failed run. Nothing in the measurement path depends on it, and it is deliberately not
 * enabled while anything is being measured: {@code log_min_duration = 0} makes the server format and
 * write a full plan for every statement, which is exactly the kind of observer that would change what
 * it observes.
 */
public final class AutoExplain {

	private static final Logger LOGGER = LoggerFactory.getLogger(AutoExplain.class);

	/**
	 * What the server writes before each plan it logs. Everything indented under it belongs to that
	 * plan, which is what makes the blocks separable without parsing the plan itself.
	 */
	private static final String PLAN_MARKER = "plan:";

	private AutoExplain ( ) { }

	/**
	 * Asks the database to explain every statement on connections opened from now on.
	 *
	 * @return whether it worked; false means the module or the privilege is absent and there will be
	 *         no plans, which callers report rather than fail on
	 */
	public static boolean enable ( DataSource dataSource ) {
		return configure(dataSource, "auto_explain", List.of(
				"SET session_preload_libraries = 'auto_explain'",
				// every statement, since the capture runs a handful deliberately rather than sampling
				"SET auto_explain.log_min_duration = 0",
				"SET auto_explain.log_analyze = on",
				"SET auto_explain.log_buffers = on",
				"SET auto_explain.log_timing = on",
				"SET auto_explain.log_format = 'text'",
				// the append's NOT EXISTS is a subplan of the INSERT, and the INSERT is what is issued
				"SET auto_explain.log_nested_statements = on"));
	}

	/** Puts the database back as it was; safe to call whether or not {@link #enable} succeeded. */
	public static void disable ( DataSource dataSource ) {
		configure(dataSource, "auto_explain", List.of(
				"RESET session_preload_libraries",
				"RESET auto_explain.log_min_duration",
				"RESET auto_explain.log_analyze",
				"RESET auto_explain.log_buffers",
				"RESET auto_explain.log_timing",
				"RESET auto_explain.log_format",
				"RESET auto_explain.log_nested_statements",
				"RESET plan_cache_mode"));
	}

	/**
	 * Which of PostgreSQL's two plans for a prepared statement to capture.
	 *
	 * <p><b>This is the whole difficulty of explaining the store's append, and it took three runs to
	 * see.</b> The backend binds its cursor and tag arrays as JDBC parameters and re-uses the statement,
	 * so PostgreSQL holds two plans for it: a <em>custom</em> one re-planned from the actual values on
	 * every execution, and a <em>generic</em> one planned once against default selectivity. It starts on
	 * custom, and from the sixth execution of the server-prepared statement it may switch to generic for
	 * good. pgjdbc adds a threshold of its own -- the statement only becomes server-prepared on the fifth
	 * execution -- so the switch is at the tenth execution overall, and a capture that warmed eight times
	 * and explained the ninth was measuring the last custom plan while the benchmark, millions of
	 * executions in, had long since settled on the generic one. That is exactly how far apart they can
	 * be: {@code append-or-groups-3} captured a 1.0ms bitmap plan for an operation measuring 15.9ms.
	 *
	 * <p>Counting executions to land on the far side of both thresholds would work and would be
	 * fragile -- both numbers are somebody else's default. Asking for the plan by name does not.
	 */
	public enum PlanCacheMode {

		/** Planned once against default selectivity: what a statement executed in a loop settles on. */
		GENERIC("force_generic_plan"),

		/** Re-planned from the actual parameter values: what the first few executions get. */
		CUSTOM("force_custom_plan");

		private final String setting;

		PlanCacheMode ( String setting ) {
			this.setting = setting;
		}
	}

	/**
	 * Pins which plan the server will use for statements on connections opened from now on.
	 *
	 * @return whether it worked; false leaves PostgreSQL choosing for itself, which is a plan that may
	 *         or may not be the one the measured run had
	 */
	public static boolean planCacheMode ( DataSource dataSource, PlanCacheMode mode ) {
		return configure(dataSource, "plan_cache_mode",
				List.of("SET plan_cache_mode = '%s'".formatted(mode.setting)));
	}

	/** Hands plan choice back to the server. */
	public static void resetPlanCacheMode ( DataSource dataSource ) {
		configure(dataSource, "plan_cache_mode", List.of("RESET plan_cache_mode"));
	}

	private static boolean configure ( DataSource dataSource, String what, List<String> settings ) {
		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement() ) {
			String database = currentDatabase(connection);
			for ( String setting : settings ) {
				statement.execute("ALTER DATABASE %s %s".formatted(quoteIdentifier(database), setting));
			}
			retireIdleConnections(dataSource);
			return true;
		} catch ( SQLException e ) {
			LOGGER.info("{} could not be configured here, so the report will carry no plans"
					+ " captured from the store's own statements: {}", what, e.getMessage());
			return false;
		}
	}

	/**
	 * Retires the pool's idle connections so the next one opened picks the new setting up.
	 *
	 * <p>Without this the whole thing is a no-op that looks like it worked. A database-level setting
	 * applies at connection time, and the pool the launcher holds is the one the container harness
	 * shares across targets -- already connected, and handing those same connections back. Opening
	 * another store does not help, because it is given the same pool.
	 *
	 * <p>Soft eviction rather than a close: the connections in use go back to the pool and are retired
	 * as they are returned, so nothing in flight is broken. A pool that is not Hikari's is left alone,
	 * and the capture then finds no plans and says so.
	 */
	private static void retireIdleConnections ( DataSource dataSource ) {
		if ( dataSource instanceof com.zaxxer.hikari.HikariDataSource pool && pool.isRunning() ) {
			pool.getHikariPoolMXBean().softEvictConnections();
		}
	}

	private static String currentDatabase ( Connection connection ) throws SQLException {
		try ( Statement statement = connection.createStatement();
				var rows = statement.executeQuery("SELECT current_database()") ) {
			rows.next();
			return rows.getString(1);
		}
	}

	/**
	 * Quotes a database name for use where a parameter cannot go.
	 *
	 * <p>The name comes from the server rather than from a caller, so this is about correctness for
	 * names needing quoting rather than about injection; doubling any embedded quote covers both.
	 */
	private static String quoteIdentifier ( String name ) {
		return '"' + name.replace("\"", "\"\"") + '"';
	}

	/**
	 * The plans the server logged in the tail of its log, newest last.
	 *
	 * <p>A block runs from the {@code plan:} marker to the first line that is not indented under it,
	 * which is how the server delimits them and needs no understanding of the plan's own grammar.
	 *
	 * @param log the whole server log
	 * @param from the length the log had before the statements of interest ran
	 */
	public static List<String> plansIn ( String log, int from ) {
		List<String> plans = new ArrayList<>();
		String tail = from >= log.length() ? "" : log.substring(from);
		String[] lines = tail.split("\n", -1);

		for ( int i = 0; i < lines.length; i++ ) {
			if ( !lines[i].stripTrailing().endsWith(PLAN_MARKER) ) {
				continue;
			}
			StringBuilder block = new StringBuilder();
			for ( int j = i + 1; j < lines.length; j++ ) {
				String line = lines[j];
				if ( line.isEmpty() || !Character.isWhitespace(line.charAt(0)) ) {
					break;
				}
				block.append(line.stripTrailing()).append('\n');
				i = j;
			}
			if ( !block.isEmpty() ) {
				plans.add(block.toString().stripTrailing());
			}
		}
		return plans;
	}

	/**
	 * The last logged plan whose query text contains every one of the given fragments, or empty.
	 *
	 * <p>The last rather than the first: a workload invoked once still produces the statements around
	 * it -- the boundary read a decider makes, the storage's own bookkeeping -- and the one being
	 * looked for is the one it ended on.
	 */
	public static java.util.Optional<String> matching ( List<String> plans, String... fragments ) {
		for ( int i = plans.size() - 1; i >= 0; i-- ) {
			String plan = plans.get(i);
			boolean all = true;
			for ( String fragment : fragments ) {
				if ( !plan.contains(fragment) ) {
					all = false;
					break;
				}
			}
			if ( all ) {
				return java.util.Optional.of(plan);
			}
		}
		return java.util.Optional.empty();
	}
}
