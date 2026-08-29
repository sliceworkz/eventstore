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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads JMH's own JSON output into {@link BenchmarkRow}s.
 *
 * <p>Parsed rather than re-derived, deliberately: JMH's file is the record of what was measured, and
 * a report that recomputed scores from raw samples would be a second implementation of statistics
 * that could disagree with the first. This only flattens.
 *
 * <p>Navigated as a tree rather than bound to records, because JMH's schema carries a great deal this
 * report has no use for -- per-fork, per-iteration raw values, percentile tables, JVM arguments -- and
 * a strict binding would break every time JMH added a field.
 */
public final class JmhResults {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private JmhResults ( ) { }

	/**
	 * One JMH result file and the target it measured.
	 *
	 * <p>The pairing exists because JMH's output cannot carry it. A target is a property of the
	 * launcher's loop, not of a benchmark, so the file is the only place the association survives -- and
	 * losing it makes two targets' rows indistinguishable, which is worse than not measuring the second.
	 */
	public record ResultFile ( Path path, String target ) { }

	/** Reads every result file of a run, in the order given. */
	public static List<BenchmarkRow> readAll ( List<ResultFile> files ) {
		List<BenchmarkRow> rows = new ArrayList<>();
		for ( ResultFile file : files ) {
			rows.addAll(read(file.path(), file.target()));
		}
		return rows;
	}

	/** Reads one JMH result file, attributing every row in it to the given target. */
	public static List<BenchmarkRow> read ( Path file, String target ) {
		if ( !Files.isReadable(file) ) {
			throw new IllegalArgumentException("no readable JMH result file at " + file);
		}
		try {
			JsonNode root = JSON.readTree(Files.readString(file));
			List<BenchmarkRow> rows = new ArrayList<>();
			for ( JsonNode benchmark : root ) {
				rows.add(toRow(benchmark, target));
			}
			return rows;
		} catch ( IOException e ) {
			throw new UncheckedIOException("could not read the JMH result file " + file, e);
		} catch ( RuntimeException e ) {
			throw new IllegalArgumentException(
					"%s is not a JMH result file, or is from an incompatible version: %s".formatted(file, e.getMessage()),
					e);
		}
	}

	private static BenchmarkRow toRow ( JsonNode benchmark, String target ) {
		JsonNode primary = benchmark.path("primaryMetric");

		Map<String, Double> secondary = new LinkedHashMap<>();
		JsonNode secondaryMetrics = benchmark.path("secondaryMetrics");
		for ( String name : secondaryMetrics.propertyNames() ) {
			secondary.put(name, secondaryMetrics.path(name).path("score").asDouble(0));
		}

		return new BenchmarkRow(
				target,
				benchmark.path("params").path("workload").asString("(unparameterised)"),
				benchmark.path("threads").asInt(1),
				benchmark.path("mode").asString("?"),
				primary.path("score").asDouble(Double.NaN),
				primary.path("scoreError").asDouble(Double.NaN),
				primary.path("scoreUnit").asString(""),
				secondary);
	}
}
