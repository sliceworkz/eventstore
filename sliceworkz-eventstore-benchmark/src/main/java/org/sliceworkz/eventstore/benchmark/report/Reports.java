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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.config.BenchmarkProfile;

/**
 * Where reports live, and the rules for moving one into the committed set.
 *
 * <p>Two directories with different standards. Scratch runs land in {@code target/} and are
 * gitignored -- most runs are experiments and committing them would bury the ones that matter.
 * A curated run is <b>published</b> into {@code results/&lt;version&gt;/&lt;profile&gt;/}, which is in
 * the repository, so its numbers are reviewable in a pull request and quotable from the docs.
 *
 * <p>Publishing is a deliberate step with conditions attached, because the figures in this project's
 * documentation went stale precisely by being quoted from runs nobody could reproduce. A run measured
 * against a container running stock defaults, or one whose store drifted materially, is fine to look
 * at and not fine to publish.
 */
public final class Reports {

	private static final Logger LOGGER = LoggerFactory.getLogger(Reports.class);

	/** Where uncommitted runs go. */
	public static final Path SCRATCH_ROOT = Path.of("target", "benchmark");

	/** Where curated runs go, inside the module. */
	public static final Path PUBLISHED_ROOT = Path.of("results");

	private Reports ( ) { }

	/** The scratch directory for a profile's run. */
	public static Path scratchDirectoryFor ( BenchmarkProfile profile ) {
		return SCRATCH_ROOT.resolve(profile.name());
	}

	/** Where a run would be published to. */
	public static Path publishedDirectoryFor ( RunManifest manifest ) {
		return PUBLISHED_ROOT.resolve(manifest.suiteVersion()).resolve(manifest.profileName());
	}

	/**
	 * Copies a run into the committed set.
	 *
	 * @param force publish despite the conditions being unmet, for a run somebody has decided is worth
	 *        keeping anyway. The reasons are recorded in the report either way, so a caveated baseline
	 *        stays caveated rather than becoming an unqualified number.
	 * @return where it was published
	 */
	public static Path publish ( Path runDirectory, boolean force ) {
		RunReport report = RunReport.read(runDirectory);
		List<String> reasons = report.reasonsNotPublishable();

		if ( !reasons.isEmpty() && !force ) {
			throw new IllegalStateException(
					"this run is not suitable as a published baseline:\n%s\nPass --force to publish it anyway; the reasons stay recorded in the report."
							.formatted(reasons.stream().map(r -> "  - " + r).reduce(( a, b ) -> a + "\n" + b).orElse("")));
		}
		if ( !report.isSound() ) {
			// never forceable: a run that failed a correctness check describes work that did not happen,
			// and publishing it would put a wrong number somewhere people quote from
			throw new IllegalStateException(
					"this run failed a correctness check, so its numbers describe work that did not happen. "
							+ "That is not publishable under any flag -- fix the cause and re-run.");
		}

		Path destination = publishedDirectoryFor(report.manifest());
		try {
			Files.createDirectories(destination);
			try ( var entries = Files.list(runDirectory) ) {
				for ( Path source : entries.filter(Files::isRegularFile).toList() ) {
					Files.copy(source, destination.resolve(source.getFileName()),
							StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch ( IOException e ) {
			throw new UncheckedIOException("could not publish the run to " + destination, e);
		}

		LOGGER.info("published {} to {}", report.manifest().profileName(), destination);
		return destination;
	}

	/**
	 * The most recently published baseline for a profile, searching newest version first.
	 *
	 * <p>Versions are compared as strings, which orders them correctly for the {@code major.minor.patch}
	 * scheme this project uses and would not for one with unpadded numbers past nine. Worth revisiting
	 * if that ever changes; wrong ordering here would silently compare against an older baseline than
	 * intended.
	 */
	public static Optional<Path> latestBaselineFor ( String profileName ) {
		if ( !Files.isDirectory(PUBLISHED_ROOT) ) {
			return Optional.empty();
		}
		try ( var versions = Files.list(PUBLISHED_ROOT) ) {
			return versions
					.filter(Files::isDirectory)
					.sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
					.map(version -> version.resolve(profileName))
					.filter(directory -> Files.isReadable(directory.resolve(RunReport.FILE_NAME)))
					.findFirst();
		} catch ( IOException e ) {
			throw new UncheckedIOException("could not search for a baseline of " + profileName, e);
		}
	}
}
