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
package org.sliceworkz.eventstore.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of {@link EventQuery#merge(java.util.Collection)}: the minimal set of merged queries
 * to execute, together with the mapping that records which merged query absorbed each original.
 *
 * <p>This is an in-memory routing helper, not a persisted value — it carries no serialization
 * support. The typical usage is to run each {@link #mergedQueries() merged query} once, then for
 * each original query re-filter the merged result set using the original's own
 * {@link EventQuery#filter() filter}:
 *
 * <pre>{@code
 * MergedEventQueries merged = EventQuery.merge(List.of(q1, q2, q3));
 * Map<EventQuery, List<Event<?>>> resultsByMerged = new HashMap<>();
 * for (EventQuery mq : merged.mergedQueries()) {
 *     resultsByMerged.put(mq, stream.query(mq).toList());
 * }
 * // interpret q1's results:
 * List<Event<?>> forQ1 = resultsByMerged.get(merged.mergedFor(q1)).stream()
 *     .filter(q1::matches)
 *     .toList();
 * }</pre>
 *
 * <p><strong>Value semantics:</strong> {@link EventQuery} is a value type, so original queries
 * that are {@code equals} are indistinguishable. {@link #mergedFor(EventQuery)} returns the same
 * merged query for all of them, and they each contribute an entry to the list returned by
 * {@link #originalsFor(EventQuery)} (which therefore preserves duplicate counts).
 *
 * @see EventQuery#merge(java.util.Collection)
 */
public final class MergedEventQueries {

	private final List<EventQuery> merged;
	private final Map<EventQuery, EventQuery> originalToMerged;
	private final Map<EventQuery, List<EventQuery>> mergedToOriginals;

	/**
	 * Package-private constructor — instances are created only by {@link EventQuery#merge(java.util.Collection)}.
	 * Defensive, unmodifiable copies are taken of all arguments.
	 */
	MergedEventQueries ( List<EventQuery> merged, Map<EventQuery, EventQuery> originalToMerged,
			Map<EventQuery, List<EventQuery>> mergedToOriginals ) {
		this.merged = Collections.unmodifiableList(new ArrayList<>(merged));
		this.originalToMerged = Collections.unmodifiableMap(new LinkedHashMap<>(originalToMerged));
		Map<EventQuery, List<EventQuery>> reverse = new LinkedHashMap<>();
		mergedToOriginals.forEach((k, v) -> reverse.put(k, Collections.unmodifiableList(new ArrayList<>(v))));
		this.mergedToOriginals = Collections.unmodifiableMap(reverse);
	}

	/**
	 * The merged (output) queries to actually run, in stable order. The size is {@code y <= x}
	 * where {@code x} is the number of original queries passed to {@code merge}.
	 *
	 * @return the unmodifiable list of merged queries
	 */
	public List<EventQuery> mergedQueries ( ) {
		return merged;
	}

	/**
	 * Returns the merged query that absorbed the given original query.
	 *
	 * @param original an original query that was part of the merge input
	 * @return the merged query the original routed into
	 * @throws IllegalArgumentException if the query was not part of the merge input
	 */
	public EventQuery mergedFor ( EventQuery original ) {
		EventQuery m = originalToMerged.get(original);
		if ( m == null ) {
			throw new IllegalArgumentException("query was not part of the merge input");
		}
		return m;
	}

	/**
	 * Returns all original queries that routed into the given merged query.
	 *
	 * @param merged one of the {@link #mergedQueries() merged queries}
	 * @return the unmodifiable list of originals that routed into it
	 * @throws IllegalArgumentException if the query is not one of the merged queries
	 */
	public List<EventQuery> originalsFor ( EventQuery merged ) {
		List<EventQuery> originals = mergedToOriginals.get(merged);
		if ( originals == null ) {
			throw new IllegalArgumentException("query is not one of the merged queries");
		}
		return originals;
	}

	/**
	 * The number of merged queries ({@code y}).
	 *
	 * @return the merged query count
	 */
	public int mergedCount ( ) {
		return merged.size();
	}

}
