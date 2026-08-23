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
package org.sliceworkz.eventstore.benchmark.corpus;

import java.util.List;
import java.util.Map;

/**
 * The handful of concrete values a workload has to know about a corpus in order to query it
 * meaningfully -- each recorded with the number of events it actually matches.
 *
 * <p>Without this a read benchmark has to invent something to look for, and the two ways of doing
 * that are both wrong. Picking a value at random gives a different selectivity on every run, so the
 * numbers move for reasons that have nothing to do with the code. Picking a fixed literal risks
 * matching nothing at all -- and a query that matches nothing is <em>fast</em>, so it reports a
 * flattering number rather than an error. Publishing the counts alongside the values lets a workload
 * assert the corpus is what it claims before it measures anything.
 *
 * @param hotEntity the entity with the most events, at the head of the Zipf distribution -- the
 *        contended DCB boundary, and the realistic worst case for "read this entity's history"
 * @param coldEntity an entity out in the tail, with a handful of events; the uncontended boundary
 * @param needleTagValue a marker tag matching a deliberately tiny number of events, so an index scan
 *        is the only sensible plan
 * @param swatheTagValue a marker tag matching roughly one percent of the store, which is where the
 *        planner starts preferring a sequential scan and where one number for "a tag query" would be
 *        a lie
 * @param matchCounts how many events each of the above actually matches, keyed by a label
 * @param midCursorPosition the {@code event_position} at roughly the halfway point, for cursor-walk
 *        workloads that must start somewhere reproducible rather than at the beginning
 * @param knownEventId an event id known to exist, for the by-id workload
 * @param streamPurposes the distinct purposes that exist under a {@code PER_ENTITY} design, empty
 *        under {@code TAGGED}
 * @param meanPayloadBytes the measured mean serialized payload size of a sales event. Measured rather
 *        than assumed, because a payload profile's name is an adjective and this is the number: the
 *        first FAT corpus came out at 656 bytes because large orders were only a quarter of the mix
 */
public record CorpusFacts (
		String hotEntity,
		String coldEntity,
		String needleTagValue,
		String swatheTagValue,
		Map<String, Long> matchCounts,
		Long midCursorPosition,
		String knownEventId,
		List<String> streamPurposes,
		Double meanPayloadBytes ) {

	/** Labels used as keys in {@link #matchCounts()}. */
	public static final String COUNT_TOTAL = "total";
	public static final String COUNT_HOT_ENTITY = "hotEntity";
	public static final String COUNT_COLD_ENTITY = "coldEntity";
	public static final String COUNT_NEEDLE = "needle";
	public static final String COUNT_SWATHE = "swathe";

	/** How many events the needle tag is aimed at. Small enough that only an index scan makes sense. */
	public static final int NEEDLE_TARGET_MATCHES = 10;

	/** What fraction of the store the swathe tag is aimed at. */
	public static final double SWATHE_TARGET_FRACTION = 0.01d;

	public CorpusFacts {
		matchCounts = matchCounts == null ? Map.of() : Map.copyOf(matchCounts);
		streamPurposes = streamPurposes == null ? List.of() : List.copyOf(streamPurposes);
	}

	/** The recorded match count for a label, or zero if it was never recorded. */
	public long count ( String label ) {
		return matchCounts.getOrDefault(label, 0L);
	}

	/**
	 * Checks that this corpus is usable for measurement, throwing rather than letting a workload
	 * quietly report the speed of a query that finds nothing.
	 *
	 * <p>The check is on the recorded counts rather than on the store, so it is free and can be run
	 * before every trial. It catches the failure that matters -- a generator change, or a spec whose
	 * entity count and volume interact badly, leaving a marker tag on no events at all.
	 */
	public void requireUsable ( ) {
		requirePositive(COUNT_TOTAL);
		requirePositive(COUNT_HOT_ENTITY);
		requirePositive(COUNT_COLD_ENTITY);
		requirePositive(COUNT_NEEDLE);
		requirePositive(COUNT_SWATHE);

		if ( count(COUNT_HOT_ENTITY) <= count(COUNT_COLD_ENTITY) ) {
			// the whole point of having both is that they differ; if they do not, the entity
			// distribution is flat and every "hot boundary" measurement is measuring a cold one
			throw new IllegalStateException(
					"corpus is degenerate: the hot entity has %d events and the cold one %d, so there is no skew to measure"
							.formatted(count(COUNT_HOT_ENTITY), count(COUNT_COLD_ENTITY)));
		}
		if ( knownEventId == null ) {
			throw new IllegalStateException("corpus records no known event id, so the by-id workload cannot run");
		}
	}

	private void requirePositive ( String label ) {
		if ( count(label) <= 0 ) {
			throw new IllegalStateException(
					"corpus is degenerate: '%s' matches no events, so any workload using it would measure an empty result"
							.formatted(label));
		}
	}
}
