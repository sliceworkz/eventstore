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
			writeMarkdownTo(directory);
		} catch ( IOException e ) {
			throw new UncheckedIOException("could not write the run report to " + directory, e);
		}
	}

	/**
	 * Renders {@code report.md} again from what this report already holds, leaving the JSON alone.
	 *
	 * <p>The JSON is the record and the Markdown is a view of it, so a change to how a run is
	 * <em>presented</em> -- a table that was listing the wrong steps, a paragraph that has since been
	 * shown to be wrong -- should cost a second rather than another measured run. Everything the
	 * renderer reads is in the JSON, captured query plans included.
	 */
	public void writeMarkdownTo ( Path directory ) {
		try {
			Files.createDirectories(directory);
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

	/**
	 * A measurement whose confidence interval is wider than this fraction of its own score is not a
	 * measurement. The report has always said so in the sentence under its results table; this is the
	 * same number, applied.
	 */
	private static final double MAX_RELATIVE_ERROR = 0.10d;

	/**
	 * Why this run is not fit to be committed as a baseline, or empty.
	 *
	 * <p>The manifest answers everything decidable from the circumstances -- the server, the drift, the
	 * suite version -- and this adds the one condition only the rows can answer: whether the numbers
	 * are precise enough to mean anything. {@code large-tier-writes} published with
	 * {@code append-type-and-tag} at <b>120.6% relative error</b>, a figure whose error bar is wider
	 * than the figure, two lines above the report's own sentence saying that above about ten percent a
	 * measurement is too noisy to compare against anything. Nothing checked it, because the gate lived
	 * on the manifest and the manifest has never seen a row.
	 *
	 * <p>Noisy rows are listed rather than counted, because <em>which</em> workload is unusable decides
	 * whether the run is worth keeping: a baseline whose control is solid and whose one contended
	 * workload is noisy may well be worth {@code --force}, and one whose headline number is the noisy
	 * one is not.
	 */
	public List<String> reasonsNotPublishable ( ) {
		List<String> reasons = new java.util.ArrayList<>(manifest.reasonsNotPublishable());
		List<String> noisy = benchmarks.stream()
				.filter(row -> !Double.isNaN(row.relativeError()) && row.relativeError() > MAX_RELATIVE_ERROR)
				.map(row -> "%s (%s, %d thread%s) at %.0f%%".formatted(row.workload(), row.target(),
						row.threads(), row.threads() == 1 ? "" : "s", row.relativeError() * 100))
				.toList();
		if ( !noisy.isEmpty() ) {
			reasons.add("%d measurement%s too noisy to compare against anything, past the %.0f%% this report"
					.formatted(noisy.size(), noisy.size() == 1 ? " is" : "s are", MAX_RELATIVE_ERROR * 100)
					+ " calls uncomparable: " + String.join(", ", noisy));
		}
		return reasons;
	}
}
