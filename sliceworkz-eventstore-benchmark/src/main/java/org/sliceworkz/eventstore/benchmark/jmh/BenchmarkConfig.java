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
	public static String[] jvmArgsFor ( String profileName, int targetIndex, Collision collision ) {
		return new String[] {
				"-D%s=%s".formatted(PROFILE_PROPERTY, profileName),
				"-D%s=%d".formatted(TARGET_INDEX_PROPERTY, targetIndex),
				"-D%s=%s".formatted(COLLISION_PROPERTY, collision.name())
		};
	}
}
