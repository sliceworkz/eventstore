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
import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventstore.benchmark.load.LoadResult;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A whole run: what was measured, and everything needed to know what the measurement means.
 *
 * <p>Written as one JSON file so a run is a single artifact that can be committed, diffed and quoted.
 * The alternative -- JMH's output in one place, the environment in another, the corpus in a third --
 * is how the figures in this project's own documentation came to exist without anything that could
 * reproduce them.
 *
 * @param manifest what the numbers are about
 * @param benchmarks the operation-level results, flattened from JMH
 * @param load the sustained-load results, if the profile had a load section
 * @param plans representative query plans, where the target was SQL-backed
 */
public record RunReport (
		RunManifest manifest,
		List<BenchmarkRow> benchmarks,
		List<LoadResult> load,
		List<QueryPlans.Plan> plans ) {

	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(SerializationFeature.INDENT_OUTPUT)
			// a report read back by a later version should not fail over a field that version added
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	/** The file name a report is written under, inside a run's directory. */
	public static final String FILE_NAME = "report.json";

	public RunReport {
		benchmarks = benchmarks == null ? List.of() : List.copyOf(benchmarks);
		load = load == null ? List.of() : List.copyOf(load);
		plans = plans == null ? List.of() : List.copyOf(plans);
	}

	public void writeTo ( Path directory ) {
		try {
			Files.createDirectories(directory);
			Files.writeString(directory.resolve(FILE_NAME), JSON.writeValueAsString(this));
			Files.writeString(directory.resolve("report.md"), new MarkdownRenderer(this).render());
		} catch ( IOException e ) {
			throw new UncheckedIOException("could not write the run report to " + directory, e);
		}
	}

	/** Reads a report, from either its directory or the JSON file itself. */
	public static RunReport read ( Path path ) {
		Path file = Files.isDirectory(path) ? path.resolve(FILE_NAME) : path;
		if ( !Files.isReadable(file) ) {
			throw new IllegalArgumentException("no readable run report at " + file);
		}
		try {
			return JSON.readValue(Files.readString(file), RunReport.class);
		} catch ( IOException e ) {
			throw new UncheckedIOException("could not read the run report at " + file, e);
		}
	}

	/** A row by its key, for lining two runs up against each other. */
	public Optional<BenchmarkRow> row ( String key ) {
		return benchmarks.stream().filter(row -> row.key().equals(key)).findFirst();
	}

	/** Whether every load scenario passed its correctness checks. */
	public boolean isSound ( ) {
		return load.stream().allMatch(LoadResult::isSound);
	}
}
