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
package org.sliceworkz.eventstore.benchmark.jmh;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;

/**
 * Puts a corpus back the way it was after a benchmark has appended to it.
 *
 * <p>An append benchmark grows the store, which breaks the premise JMH rests on: that every iteration
 * measures the same thing. How badly depends entirely on scale. A thousand-event corpus that gains
 * fifty thousand rows during a five-second iteration is no longer the corpus anyone asked about --
 * the second iteration measures a store fifty times the size of the first, and the reported figure is
 * an average over a moving target. At ten million the same fifty thousand rows are half a percent and
 * nobody would notice.
 *
 * <p>So the policy is per tier, and the drift is <b>reported rather than hidden</b>:
 *
 * <table border="1">
 *   <caption>restore policy</caption>
 *   <tr><th>tier</th><th>policy</th><th>cost</th></tr>
 *   <tr><td>10³</td><td>restore before every iteration</td><td>milliseconds</td></tr>
 *   <tr><td>10⁵</td><td>restore before every iteration</td><td>about a second</td></tr>
 *   <tr><td>10⁷</td><td>restore per trial; drift accepted, measured, and failed above a threshold</td>
 *       <td>minutes, paid once</td></tr>
 * </table>
 *
 * <p>Restore is a copy from an immutable template table taken at provisioning time. The obvious
 * alternative -- deleting the rows the benchmark added -- is cheaper and worse: it leaves dead tuples
 * that autovacuum reclaims <em>during the next measurement</em>, injecting noise exactly where it does
 * the most damage. A truncate-and-copy leaves a clean heap every time.
 *
 * <p><b>An in-memory corpus cannot be restored at all</b>, only regenerated, since there is no
 * template to copy from and no SQL to do it with. That is cheap at the small tier and is one more
 * reason the large tier belongs on PostgreSQL.
 */
public final class CorpusRestore {

	private static final Logger LOGGER = LoggerFactory.getLogger(CorpusRestore.class);

	/** Above this volume, restoring between iterations costs more than the drift it prevents. */
	private static final long PER_TRIAL_THRESHOLD = 1_000_000L;

	/** Growth beyond this fraction of the corpus makes a trial's numbers not about that corpus. */
	private static final double DEFAULT_MAX_DRIFT = 0.02d;

	/**
	 * Growth within a single iteration worth mentioning. Far looser than {@link #DEFAULT_MAX_DRIFT},
	 * because this one is a remark rather than a verdict -- see {@code warnIfIterationGrowthIsLarge}.
	 */
	private static final double ITERATION_GROWTH_WARN = 0.25d;

	/** When to put the corpus back. */
	public enum Policy {
		/** Before every iteration. Correct, and affordable below a million events. */
		PER_ITERATION,
		/** Once per trial, accepting measured drift within it. The only option at the large tier. */
		PER_TRIAL,
		/** Nothing to restore, because the workloads only read. */
		NONE
	}

	private final BenchmarkTarget target;
	private final CorpusSpec spec;
	private final String prefix;
	private final Policy policy;
	private final double maxDrift;

	private long baselineCount = -1;

	/** What {@link #endTrial()} last computed, kept so a trial that failed on it can still report it. */
	private double lastMeasuredDrift;

	public CorpusRestore ( BenchmarkTarget target, CorpusSpec spec, String prefix, boolean mutating ) {
		this.target = target;
		this.spec = spec;
		this.prefix = prefix;
		this.policy = policyFor(spec, mutating);
		this.maxDrift = DEFAULT_MAX_DRIFT;
	}

	private static Policy policyFor ( CorpusSpec spec, boolean mutating ) {
		if ( !mutating ) {
			return Policy.NONE;
		}
		return spec.volume() >= PER_TRIAL_THRESHOLD ? Policy.PER_TRIAL : Policy.PER_ITERATION;
	}

	public Policy policy ( ) {
		return policy;
	}

	/**
	 * Called once per trial: takes the template the restores copy from, and records the baseline the
	 * drift is measured against.
	 */
	public void beginTrial ( ) {
		if ( policy == Policy.NONE ) {
			return;
		}
		target.dataSource().ifPresent(this::createTemplate);
		baselineCount = currentCount().orElse(-1L);
	}

	/** Called before each iteration; a no-op unless the policy says otherwise. */
	public void beforeIteration ( ) {
		if ( policy != Policy.PER_ITERATION ) {
			return;
		}
		restore();
	}

	/**
	 * Called at the end of a trial. Returns how far the store drifted, as a fraction of the corpus.
	 *
	 * @throws IllegalStateException if the drift exceeded the threshold, because a number measured
	 *         against a store that grew by more than a few percent is not a number about that corpus
	 */
	public double endTrial ( ) {
		if ( policy == Policy.NONE || baselineCount <= 0 ) {
			return 0;
		}
		long now = currentCount().orElse(baselineCount);
		double drift = ( now - baselineCount ) / (double) baselineCount;

		if ( policy == Policy.PER_ITERATION ) {
			// The corpus is put back before every iteration, so the store each measurement describes is
			// the corpus and the run's drift is zero by construction. What this figure measures is one
			// iteration's worth of appends, which is a different thing and must not be compared against
			// the threshold: an append benchmark at the small tier routinely multiplies a thousand-event
			// store several times over inside a single iteration, and failing the trial for it would fail
			// every append benchmark this suite has.
			warnIfIterationGrowthIsLarge(drift, now);
			lastMeasuredDrift = 0;
			return 0;
		}

		lastMeasuredDrift = drift;
		if ( drift > maxDrift ) {
			throw new IllegalStateException(
					"the store grew by %.1f%% during this trial (%d events to %d), past the %.0f%% allowed: these numbers describe a store that changed while they were being taken"
							.formatted(drift * 100, baselineCount, now, maxDrift * 100));
		}
		if ( drift > 0 ) {
			LOGGER.info("store drifted {}% during the trial ({} to {} events)",
					"%.2f".formatted(drift * 100), baselineCount, now);
		}
		return drift;
	}

	/**
	 * Puts the corpus back.
	 *
	 * <p>For a SQL target this is a truncate and copy from the template, plus resetting the position
	 * sequence, clearing bookmarks, and re-analyzing -- without which the planner holds statistics for
	 * a table that no longer exists and the next iteration's plans are chosen on stale numbers.
	 */
	public void restore ( ) {
		Optional<DataSource> dataSource = target.dataSource();
		if ( dataSource.isEmpty() ) {
			// An in-memory corpus has no template and no SQL. Regenerating it here would mean rebuilding
			// the whole store between iterations, which at this tier is affordable but is the caller's
			// decision to make -- CorpusState does it, because it owns the store.
			return;
		}
		restoreSql(dataSource.get());
	}

	/**
	 * Says so when a single iteration moves the store materially.
	 *
	 * <p>Restoring between iterations fixes drift <em>across</em> them and can do nothing about growth
	 * <em>within</em> one. An append workload that adds a quarter of the corpus during a measured
	 * iteration is timing an ever-larger store, so its last operations are not measuring what its first
	 * ones did, and the reported average is over a moving target however clean each iteration started.
	 *
	 * <p>Not a failure. It is inherent to timing appends against a small corpus, it is exactly what the
	 * smoke tier does, and the remedies -- a shorter iteration or a larger corpus -- are the profile
	 * author's to choose. Worth one line in the log rather than a silently softer number.
	 */
	private void warnIfIterationGrowthIsLarge ( double growth, long now ) {
		if ( growth <= ITERATION_GROWTH_WARN ) {
			return;
		}
		LOGGER.warn("one iteration grew {}events by {}% ({} to {}): the corpus is restored between "
				+ "iterations, but within one the store is a moving target. Shorten the iteration or "
				+ "measure against a larger corpus if this number is meant to be quoted.",
				prefix, "%.0f".formatted(growth * 100), baselineCount, now);
	}

	private void createTemplate ( DataSource dataSource ) {
		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement() ) {
			// dropped and retaken per trial rather than kept: a template left behind by an earlier run
			// might have been taken from a corpus this run has since rebuilt
			statement.execute("DROP TABLE IF EXISTS %sevents_template".formatted(prefix));
			statement.execute("CREATE TABLE %sevents_template AS SELECT * FROM %sevents".formatted(prefix, prefix));
			LOGGER.info("took a restore template of {}events", prefix);
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not take a restore template of %sevents".formatted(prefix), e);
		}
	}

	private void restoreSql ( DataSource dataSource ) {
		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement() ) {
			statement.execute("TRUNCATE TABLE %sevents CASCADE".formatted(prefix));
			statement.execute("INSERT INTO %sevents SELECT * FROM %sevents_template".formatted(prefix, prefix));
			// the sequence keeps counting from where the benchmark left it, so without this the restored
			// rows and the next appends would collide on event_position
			statement.execute(
					"SELECT setval(pg_get_serial_sequence('%sevents', 'event_position'), COALESCE((SELECT max(event_position) FROM %sevents), 1))"
							.formatted(prefix, prefix));
			statement.execute("DELETE FROM %sbookmarks".formatted(prefix));
			statement.execute("ANALYZE %sevents".formatted(prefix));
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not restore %sevents from its template".formatted(prefix), e);
		}
	}

	private Optional<Long> currentCount ( ) {
		Optional<DataSource> dataSource = target.dataSource();
		if ( dataSource.isEmpty() ) {
			return Optional.empty();
		}
		try ( Connection connection = dataSource.get().getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT count(*) FROM %sevents".formatted(prefix)) ) {
			return rows.next() ? Optional.of(rows.getLong(1)) : Optional.empty();
		} catch ( SQLException e ) {
			LOGGER.warn("could not count {}events, so drift cannot be reported for this trial", prefix, e);
			return Optional.empty();
		}
	}

	/**
	 * Puts the corpus back one last time, at the end of a trial.
	 *
	 * <p>Not about this trial's numbers -- those are already taken. It is about the <em>next</em> run:
	 * a mutating trial leaves its appends in the store, so the store no longer matches the count its
	 * manifest records, and the reuse check correctly refuses to reuse it. The corpus is then rebuilt
	 * from scratch by the next fork, and the one after that, for as long as append workloads keep
	 * running -- three seconds a time at the medium tier, and minutes at the large one.
	 *
	 * <p>Restoring here is far cheaper than regenerating there, and it also removes a source of
	 * variance the numbers can do without: a rebuilt corpus is a different physical layout with a cold
	 * cache, which is part of why this profile's error bars are as wide as they are.
	 */
	public void restoreToBaseline ( ) {
		if ( policy == Policy.NONE ) {
			return;
		}
		restore();
	}

	/** Removes the template, so a corpus is not left with a stale copy of itself beside it. */
	public void cleanUp ( ) {
		target.dataSource().ifPresent(dataSource -> {
			try ( Connection connection = dataSource.getConnection();
					Statement statement = connection.createStatement() ) {
				statement.execute("DROP TABLE IF EXISTS %sevents_template".formatted(prefix));
			} catch ( SQLException e ) {
				LOGGER.warn("could not drop the restore template {}events_template", prefix, e);
			}
		});
	}

	/** The drift {@link #endTrial()} last computed, available even when it threw over it. */
	public double lastMeasuredDrift ( ) {
		return lastMeasuredDrift;
	}

	/** A sentence describing the policy, for the run's manifest. */
	public String describe ( ) {
		return switch ( policy ) {
			case NONE -> "no restore: every workload in this run is read-only";
			case PER_ITERATION -> "restored from a template before every iteration (%d events)".formatted(spec.volume());
			case PER_TRIAL -> "restored once per trial; intra-trial drift measured and capped at %.0f%%"
					.formatted(maxDrift * 100);
		};
	}
}
