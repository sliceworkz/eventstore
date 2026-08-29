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
package org.sliceworkz.eventstore.benchmark.env;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything about the machine and the server that a benchmark number depends on but does not
 * mention.
 *
 * <p>This exists so that comparing two runs can <em>fail</em> rather than mislead. A throughput
 * figure is meaningless without the {@code shared_buffers} it was measured with, and the difference
 * between two figures measured on different hardware is not a regression -- but nothing about the
 * numbers themselves says so. The comparator refuses to diff two runs whose reports differ, which
 * only works if the report captures the settings that actually move results.
 *
 * <p>The PostgreSQL settings collected here are the ones that decide event-store behaviour
 * specifically: buffer and cache sizing (whether an index fits in memory at 10 million events), WAL
 * sizing and {@code synchronous_commit} (which dominate append throughput), {@code random_page_cost}
 * (which decides whether the planner picks the GIN index or a sequential scan), and
 * {@code max_connections} against the pool size.
 */
public record EnvironmentReport (
		Map<String, String> jvm,
		Map<String, String> host,
		Map<String, String> postgres ) {

	private static final Logger LOGGER = LoggerFactory.getLogger(EnvironmentReport.class);

	/**
	 * Settings worth recording. Not an exhaustive dump of {@code pg_settings} -- a few hundred rows
	 * that mostly never change would bury the handful that decide the answer, and would make every
	 * two reports differ for reasons nobody cares about.
	 */
	private static final List<String> POSTGRES_SETTINGS = List.of(
			"server_version",
			"shared_buffers",
			"effective_cache_size",
			"work_mem",
			"maintenance_work_mem",
			"max_wal_size",
			"min_wal_size",
			"checkpoint_completion_target",
			"synchronous_commit",
			"fsync",
			"full_page_writes",
			"wal_compression",
			"random_page_cost",
			"seq_page_cost",
			"effective_io_concurrency",
			"max_connections",
			"max_worker_processes",
			"max_parallel_workers",
			"max_parallel_workers_per_gather",
			"autovacuum",
			"autovacuum_vacuum_scale_factor",
			"autovacuum_analyze_scale_factor",
			"jit",
			"track_io_timing",
			"lc_messages");

	/** Captures the environment a target is running in. */
	public static EnvironmentReport capture ( BenchmarkTarget target ) {
		Map<String, String> postgres = target.dataSource()
				.map(EnvironmentReport::readPostgresSettings)
				.orElseGet(Map::of);
		return new EnvironmentReport(readJvm(), readHost(), postgres);
	}

	private static Map<String, String> readJvm ( ) {
		Map<String, String> jvm = new LinkedHashMap<>();
		jvm.put("java.version", System.getProperty("java.version"));
		jvm.put("java.vm.name", System.getProperty("java.vm.name"));
		jvm.put("java.vm.version", System.getProperty("java.vm.version"));
		jvm.put("java.vendor", System.getProperty("java.vendor"));
		jvm.put("max.heap.bytes", String.valueOf(Runtime.getRuntime().maxMemory()));
		return jvm;
	}

	private static Map<String, String> readHost ( ) {
		Map<String, String> host = new LinkedHashMap<>();
		host.put("os.name", System.getProperty("os.name"));
		host.put("os.version", System.getProperty("os.version"));
		host.put("os.arch", System.getProperty("os.arch"));
		host.put("available.processors", String.valueOf(Runtime.getRuntime().availableProcessors()));
		host.putAll(readLinuxCpuAndMemory());
		return host;
	}

	/**
	 * CPU model and total RAM off {@code /proc}, which is where they are on the machines this suite is
	 * meant to run on. Absent elsewhere rather than guessed: a wrong CPU model in a report is worse
	 * than a missing one, because the comparator would treat two different machines as one.
	 */
	private static Map<String, String> readLinuxCpuAndMemory ( ) {
		Map<String, String> details = new LinkedHashMap<>();
		try {
			java.nio.file.Path cpuinfo = java.nio.file.Path.of("/proc/cpuinfo");
			if ( java.nio.file.Files.isReadable(cpuinfo) ) {
				java.nio.file.Files.readAllLines(cpuinfo).stream()
						.filter(line -> line.startsWith("model name"))
						.findFirst()
						.ifPresent(line -> details.put("cpu.model", line.substring(line.indexOf(':') + 1).strip()));
			}
			java.nio.file.Path meminfo = java.nio.file.Path.of("/proc/meminfo");
			if ( java.nio.file.Files.isReadable(meminfo) ) {
				java.nio.file.Files.readAllLines(meminfo).stream()
						.filter(line -> line.startsWith("MemTotal"))
						.findFirst()
						.ifPresent(line -> details.put("memory.total", line.substring(line.indexOf(':') + 1).strip()));
			}
		} catch ( Exception e ) {
			LOGGER.debug("could not read CPU or memory details from /proc", e);
		}
		return details;
	}

	private static Map<String, String> readPostgresSettings ( DataSource dataSource ) {
		Map<String, String> settings = new LinkedHashMap<>();
		String sql = "SELECT name, setting, unit FROM pg_settings WHERE name = ANY (?)";

		try ( Connection connection = dataSource.getConnection() ) {
			try ( var statement = connection.prepareStatement(sql) ) {
				statement.setArray(1, connection.createArrayOf("text", POSTGRES_SETTINGS.toArray()));
				try ( ResultSet rows = statement.executeQuery() ) {
					while ( rows.next() ) {
						String unit = rows.getString("unit");
						String value = rows.getString("setting") + ( unit == null ? "" : unit );
						settings.put(rows.getString("name"), value);
					}
				}
			}
			settings.put("version", scalar(connection, "SELECT version()"));
			settings.put("current_database", scalar(connection, "SELECT current_database()"));
		} catch ( Exception e ) {
			// a benchmark that cannot describe its environment is still a benchmark; it just cannot be
			// compared against a baseline, and the comparator says so rather than this failing the run
			LOGGER.warn("could not read PostgreSQL settings; this run will not be comparable against a baseline", e);
		}
		return settings;
	}

	private static String scalar ( Connection connection, String sql ) throws java.sql.SQLException {
		try ( Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(sql) ) {
			return rows.next() ? rows.getString(1) : null;
		}
	}

	/**
	 * Whether two runs were measured in environments alike enough to be compared. Deliberately strict:
	 * a false "no regression" is far more expensive than a refusal to answer.
	 */
	public boolean comparableTo ( EnvironmentReport other ) {
		return other != null
				&& jvm.equals(other.jvm)
				&& host.equals(other.host)
				&& postgres.equals(other.postgres);
	}

	/** The keys on which this differs from another report, for explaining a refusal to compare. */
	public List<String> differencesFrom ( EnvironmentReport other ) {
		List<String> differences = new java.util.ArrayList<>();
		collectDifferences("jvm", jvm, other.jvm, differences);
		collectDifferences("host", host, other.host, differences);
		collectDifferences("postgres", postgres, other.postgres, differences);
		return differences;
	}

	private static void collectDifferences ( String group, Map<String, String> mine, Map<String, String> theirs,
			List<String> into ) {
		java.util.Set<String> keys = new java.util.TreeSet<>(mine.keySet());
		keys.addAll(theirs.keySet());
		for ( String key : keys ) {
			String a = mine.get(key);
			String b = theirs.get(key);
			if ( a == null ? b != null : !a.equals(b) ) {
				into.add("%s.%s: %s vs %s".formatted(group, key, a, b));
			}
		}
	}
}
