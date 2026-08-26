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

import org.sliceworkz.eventstore.benchmark.config.BenchmarkProfile;
import org.sliceworkz.eventstore.benchmark.config.Profiles;
import org.sliceworkz.eventstore.benchmark.env.TargetSpec;
import org.sliceworkz.eventstore.benchmark.workload.WorkloadContext.Collision;

/**
 * How a JMH fork learns what it is measuring.
 *
 * <p>A fork is a bare JVM started by JMH: it inherits nothing from the launcher but its command line.
 * Everything the benchmark needs -- which profile, which of its targets, how writers collide -- has to
 * arrive as a system property and be reconstructed on the other side. This class is both ends of that
 * channel, so the property names exist in exactly one place; a mismatch between what the launcher
 * sets and what the fork reads would leave the fork silently falling back to a default and measuring
 * something nobody asked for.
 */
public record BenchmarkConfig ( BenchmarkProfile profile, int targetIndex, Collision collision ) {

	public static final String PROFILE_PROPERTY = "benchmark.profile";
	public static final String TARGET_INDEX_PROPERTY = "benchmark.target.index";
	public static final String COLLISION_PROPERTY = "benchmark.collision";

	/**
	 * Where a fork records how far its store drifted, for the launcher to pick up.
	 *
	 * <p>Drift is measured inside a fork and reported by the launcher, and a fork is a separate process
	 * that hands back nothing but its JMH results file -- which has no room for this. So it goes through
	 * a file in the run's output directory. Before this existed the report simply recorded zero, which
	 * made the publish guard against a drifted store unable to fire at all.
	 */
	public static final String DRIFT_FILE_PROPERTY = "benchmark.drift.file";

	/**
	 * Reconstructs the configuration inside a fork.
	 *
	 * @throws IllegalStateException if the profile property is absent, which means the benchmark was
	 *         started directly rather than through the runner -- worth failing loudly, since the
	 *         alternative is measuring an arbitrary default and reporting it under the profile's name
	 */
	public static BenchmarkConfig fromSystemProperties ( ) {
		String profileName = System.getProperty(PROFILE_PROPERTY);
		if ( profileName == null || profileName.isBlank() ) {
			throw new IllegalStateException(
					"no %s system property: these benchmarks are meant to be started with 'benchmark jmh --profile=<name>', which supplies the corpus and target they measure against"
							.formatted(PROFILE_PROPERTY));
		}
		return new BenchmarkConfig(
				Profiles.resolve(profileName),
				Integer.getInteger(TARGET_INDEX_PROPERTY, 0),
				Collision.parse(System.getProperty(COLLISION_PROPERTY)));
	}

	/** The target this fork measures. */
	public TargetSpec target ( ) {
		if ( targetIndex < 0 || targetIndex >= profile.targets().size() ) {
			throw new IllegalStateException("target index %d is out of range for profile '%s', which has %d targets"
					.formatted(targetIndex, profile.name(), profile.targets().size()));
		}
		return profile.targets().get(targetIndex);
	}

	/** The JVM arguments a launcher must pass into a fork for it to reconstruct this. */
	public static String[] jvmArgsFor ( String profileName, int targetIndex, Collision collision,
			java.nio.file.Path driftFile ) {
		return new String[] {
				"-D%s=%s".formatted(PROFILE_PROPERTY, profileName),
				"-D%s=%d".formatted(TARGET_INDEX_PROPERTY, targetIndex),
				"-D%s=%s".formatted(COLLISION_PROPERTY, collision.name()),
				"-D%s=%s".formatted(DRIFT_FILE_PROPERTY, driftFile.toAbsolutePath())
		};
	}

	/**
	 * Records a trial's drift, appending one value per line.
	 *
	 * <p>Never throws. A drift figure is worth having and is not worth failing a completed trial over,
	 * and the reader treats an absent file as "not measured" rather than as zero.
	 */
	public static void recordDrift ( double drift, double iterationGrowth ) {
		String path = System.getProperty(DRIFT_FILE_PROPERTY);
		if ( path == null || path.isBlank() ) {
			return;
		}
		try {
			java.nio.file.Files.writeString(java.nio.file.Path.of(path),
					drift + " " + iterationGrowth + System.lineSeparator(),
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch ( java.io.IOException e ) {
			org.slf4j.LoggerFactory.getLogger(BenchmarkConfig.class)
					.warn("could not record this trial's drift to {}; the report will say it was not measured",
							path, e);
		}
	}

	/**
	 * The worst drift any trial of a run reported, or empty when none did.
	 *
	 * <p>The worst rather than the mean: the question the figure answers is whether any measurement in
	 * this run was taken against a store that had moved, and averaging that away is how a run with one
	 * badly drifted trial comes to look clean.
	 */
	public static java.util.OptionalDouble worstDriftIn ( java.nio.file.Path driftFile ) {
		return worstColumnIn(driftFile, 0);
	}

	/**
	 * The worst single-iteration growth any trial reported, or empty when none did.
	 *
	 * <p>Recorded alongside the drift and reported separately, because under a restore-per-iteration
	 * policy the drift is zero by construction and says nothing about how far the store moved inside an
	 * iteration -- see {@code CorpusRestore.worstIterationGrowth()}.
	 */
	public static java.util.OptionalDouble worstIterationGrowthIn ( java.nio.file.Path driftFile ) {
		return worstColumnIn(driftFile, 1);
	}

	private static java.util.OptionalDouble worstColumnIn ( java.nio.file.Path driftFile, int column ) {
		if ( !java.nio.file.Files.isReadable(driftFile) ) {
			return java.util.OptionalDouble.empty();
		}
		try {
			return java.nio.file.Files.readAllLines(driftFile).stream()
					.map(String::strip)
					.filter(line -> !line.isEmpty())
					.map(line -> line.split("\\s+"))
					// a line written before growth was recorded carries the drift only; it is not a zero
					.filter(fields -> fields.length > column)
					.mapToDouble(fields -> Double.parseDouble(fields[column]))
					.max();
		} catch ( java.io.IOException | NumberFormatException e ) {
			return java.util.OptionalDouble.empty();
		}
	}
}
