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
package org.sliceworkz.eventstore.stream;

import java.util.Optional;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Criteria set to determine whether {@link Event}s can be added to the event store.
 * Crucial to the Dynamic Context Boundary (DCB) event store mechanism.
 *
 * When business decisions are made, historical {@link Event}s are queried with an {@link EventQuery}, and the decision is based on all relevant facts.
 * The resulting domain {@link Event}s are only appended to the event store if by the time they are stored, no new relevant facts are found in the store.
 * In practice, this allows a dynamic optimistic locking concept, effectively checking whether no new relevant facts are known before the storage of the {@link Event}s.
 * This is checked by including the {@link EventFilter} (the pure matching criteria from the original query, without direction or limit),
 * as well as an {@link EventReference} to the last relevant {@link Event} in the store on which the decision is based.
 * If any event deemed relevant by the {@link EventFilter} is found after the reference {@link Event}, no append is done.
 * Simple appends without locking (without relevant facts to consider) are possible with AppendCriteria.none()
 *
 * @param eventFilter the filter defining which events are relevant for the consistency check
 * @param expectedLastEventReference the last known Event matching the filter that our decision was based upon, or Optional.empty() for none assumed (empty EventStream)
 */
public record AppendCriteria ( EventFilter eventFilter, Optional<EventReference> expectedLastEventReference ) {

	/**
	 * Specifies that no AppendCriteria should be applied, so the append will be carried out regardless of the history.
	 * @return AppendCriteria without conditional appends
	 */
	public static final AppendCriteria none ( ) {
		return new AppendCriteria(EventFilter.matchNone(), null);
	}

	/**
	 * Creates an AppendCriteria from an {@link EventFilter} and a reference to the last known matching event.
	 * This is the primary factory method — use the filter extracted from your query via {@link EventQuery#filter()}.
	 *
	 * @param eventFilter the filter defining relevant events for the consistency check
	 * @param reference the last known Event matching the filter that our decision was based upon (null if none)
	 * @return AppendCriteria for conditional appends
	 */
	public static final AppendCriteria of ( EventFilter eventFilter, EventReference reference ) {
		return new AppendCriteria(eventFilter, Optional.ofNullable(reference));
	}

	/**
	 * Convenience method that creates an AppendCriteria from an {@link EventQuery}, extracting its filter.
	 * Direction and limit from the query are discarded — only the pure matching criteria matter for optimistic locking.
	 *
	 * @param eventQuery the query whose filter will be used for the consistency check
	 * @param reference the last known Event matching the query that our decision was based upon (null if none)
	 * @return AppendCriteria for conditional appends
	 */
	public static final AppendCriteria of ( EventQuery eventQuery, EventReference reference ) {
		return new AppendCriteria(eventQuery.filter(), Optional.ofNullable(reference));
	}

	/**
	 * Returns whether this EventCriteria object represents 'no criteria'.
	 * @return boolean specifying whether this represents 'no criteria'
	 */
	public boolean isNone ( ) {
		return eventFilter.isMatchNone ( );
	}

}
