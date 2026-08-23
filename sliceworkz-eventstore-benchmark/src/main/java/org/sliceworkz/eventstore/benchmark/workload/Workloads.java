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
package org.sliceworkz.eventstore.benchmark.workload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sliceworkz.eventstore.benchmark.corpus.CorpusSpec;

/**
 * The catalogue: every workload the suite can run, by name.
 *
 * <p>Names are what a profile refers to, so they are part of the suite's configuration surface --
 * renaming one invalidates every profile and every committed baseline that mentions it. Treat them
 * the way the library treats event type names.
 */
public final class Workloads {

	private static final Map<String, Workload> BY_NAME = index();

	private Workloads ( ) { }

	private static Map<String, Workload> index ( ) {
		Map<String, Workload> byName = new LinkedHashMap<>();
		for ( Workload workload : concat(AppendWorkloads.all(), ReadWorkloads.all()) ) {
			Workload clash = byName.put(workload.name(), workload);
			if ( clash != null ) {
				throw new IllegalStateException("two workloads are both named '" + workload.name() + "'");
			}
		}
		// unmodifiableMap over the LinkedHashMap, not Map.copyOf: the latter makes no ordering promise,
		// so the catalogue listing came out shuffled and every report would order its rows differently
		return java.util.Collections.unmodifiableMap(byName);
	}

	private static List<Workload> concat ( List<Workload> first, List<Workload> second ) {
		List<Workload> all = new ArrayList<>(first.size() + second.size());
		all.addAll(first);
		all.addAll(second);
		return all;
	}

	/** Every workload, appends first then reads, in a stable order. */
	public static List<Workload> all ( ) {
		return List.copyOf(BY_NAME.values());
	}

	/** Every workload's name, for a profile author and for the {@code workloads} listing. */
	public static List<String> names ( ) {
		return List.copyOf(BY_NAME.keySet());
	}

	/**
	 * Looks a workload up.
	 *
	 * @throws IllegalArgumentException naming the available workloads, since a profile referring to a
	 *         workload that does not exist is the likeliest way to misconfigure a run
	 */
	public static Workload byName ( String name ) {
		Workload workload = BY_NAME.get(name);
		if ( workload == null ) {
			throw new IllegalArgumentException("no workload named '%s'; available workloads are %s"
					.formatted(name, String.join(", ", names())));
		}
		return workload;
	}

	/**
	 * The workloads a profile asked for, or every workload the corpus supports when it named none.
	 *
	 * <p>A workload a profile names explicitly but the corpus cannot support is an <b>error</b>: the
	 * person asked for it, and silently dropping it would produce a report missing a row nobody
	 * notices. One picked up by the "all" default is merely skipped, since nobody asked for it
	 * specifically.
	 */
	public static List<Workload> resolve ( List<String> requested, CorpusSpec spec ) {
		if ( requested == null || requested.isEmpty() ) {
			return all().stream()
					.filter(workload -> workload.requirement().rejectionFor(spec).isEmpty())
					.toList();
		}

		List<Workload> resolved = new ArrayList<>(requested.size());
		for ( String name : requested ) {
			Workload workload = byName(name);
			Optional<String> rejection = workload.requirement().rejectionFor(spec);
			if ( rejection.isPresent() ) {
				throw new IllegalArgumentException(
						"workload '%s' cannot run against this corpus: %s".formatted(name, rejection.get()));
			}
			resolved.add(workload);
		}
		return resolved;
	}

	/** Whether any of these workloads writes, and so whether the corpus needs restoring. */
	public static boolean anyMutates ( List<Workload> workloads ) {
		return workloads.stream().anyMatch(workload -> workload.requirement().mutatesStore());
	}
}
