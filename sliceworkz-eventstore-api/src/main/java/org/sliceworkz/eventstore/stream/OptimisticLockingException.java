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

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Throw when the {@link AppendCriteria} are violated, and newer relevant facts are found than the ones the decision was based upon.
 */
public class OptimisticLockingException extends RuntimeException {
	
	private final EventQuery query;
    private final Optional<EventReference> expectedLastEventReference;

    public OptimisticLockingException(EventQuery query, Optional<EventReference> expectedLastEventReference ) {
        super(
        	expectedLastEventReference.isPresent()?
        		String.format(
		            "Optimistic locking failed. Expected last Event with EventReference %s/%d, was not last anymore for EventQuery: %s",
		            expectedLastEventReference != null ? expectedLastEventReference.get().id().value() : -1,
		            expectedLastEventReference != null ? expectedLastEventReference.get().position() : -1,
		            query):
		        		String.format(
		    		            "Optimistic locking failed. Empty EventStream expected and Events found for EventQuery: %s",
		    		            query)
        );
        this.query = query;
        this.expectedLastEventReference = expectedLastEventReference;
    }

	public EventQuery getQuery() {
		return query;
	}

	public Optional<EventReference> getExpectedLastEventReference() {
		return expectedLastEventReference;
	}

}
