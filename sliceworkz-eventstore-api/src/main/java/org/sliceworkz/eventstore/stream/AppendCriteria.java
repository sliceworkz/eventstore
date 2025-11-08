/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Criteria set to determine whether {@link Event}s can be added to the event store.
 * Crucial to the Dynamic Context Boundary (DCB) event store mechanism.
 * 
 * When business decisions are made, historical {@link Event}s are queried with an {@link EventQuery}, and the decision is based on all relevant facts.
 * The resulting domain {@link Event}s are only appended to the event store if by the time they are stored, no new relevant facts are found in the store.
 * In practice, this allows a dynamic optimistic locking concept, effectively checking whether no new relevant facts are known before the storage of the {@link Event}s. 
 * This is checked by including the original {@link EventQuery} which selects all historical {@link Event}s (facts), 
 * as well as an {@link EventReference} to the last relevant {@link Event} in the store on which the decision is based.
 * If any event deemed relevant by the {@link EventQuery} is found after the reference {@link Event}, no append is done.
 * Simple appends without locking (without relevant facts to consider) are possible with EventQuery.none() 
 * 
 * @param eventQuery the query to run when appending the Events
 * @param expectedLastEventReference the last known Event matching the query that our decision was based upon, or Optional.empty() for none assumed (empty EventStream)
 */
public record AppendCriteria ( EventQuery eventQuery, Optional<EventReference> expectedLastEventReference ) {

	/**
	 * Specifies that no AppedCriteria should be applied, so the append will be carried out regardless of the history.
	 * @return AppendCriteria without conditional appends
	 */
	public static final AppendCriteria none ( ) {
		return new AppendCriteria(EventQuery.matchNone(), null);
	}
	
	/**
	 * Convenience method to create an AppendQuery object. 
	 * @param eventQuery the query to run when appending the Events
	 * @param reference the last known Event matching the query that our decision was based upon, or Optional.empty() for none assumed (empty EventStream)
	 * @return AppendCriteria without conditional appends
	 */
	public static final AppendCriteria of ( EventQuery eventQuery, Optional<EventReference> reference ) {
		return new AppendCriteria(eventQuery, reference);
	}

	/**
	 * Returns whether this EventCriteria object represents 'no criteria'.
	 * @return boolean specifying whether this represents 'no criteria'
	 */
	public boolean isNone ( ) {
		return eventQuery.isMatchNone ( );
	}
	
}
