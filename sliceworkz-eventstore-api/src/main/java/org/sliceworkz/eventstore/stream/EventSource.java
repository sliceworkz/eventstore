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
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;

public interface EventSource<DOMAIN_EVENT_TYPE> {
	
	// EventReference until is part of the (functional) EventQuery, after is not.  
	// EventReference 'after' supports technical optimizations; it makes no from functional point of view for a EventQuery to start in the middle of a Stream.
	Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query, EventReference after, Limit limit ); 

	default Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query, EventReference after ) {
		return query(query, after, Limit.none());
	}
	
	default Stream<Event<DOMAIN_EVENT_TYPE>> query ( EventQuery query ) { 
		// from the start of the stream, without limit
		return query(query, null, Limit.none());
	}

	default Stream<Event<DOMAIN_EVENT_TYPE>> queryBackwards ( EventQuery query, Limit limit ) {
		return queryBackwards(query, null, limit);
	}

	default Stream<Event<DOMAIN_EVENT_TYPE>> queryBackwards ( EventQuery query, EventReference before ) {
		return queryBackwards(query, before, Limit.none());
	}

	Stream<Event<DOMAIN_EVENT_TYPE>> queryBackwards ( EventQuery query, EventReference before, Limit limit );
	
	Optional<EventReference> queryReference ( EventId id );
	
	Optional<Event<DOMAIN_EVENT_TYPE>> getEventById ( EventId eventId );

	
	void subscribe ( EventStreamEventuallyConsistentAppendListener listener );
	
	void subscribe ( EventStreamConsistentAppendListener<DOMAIN_EVENT_TYPE> listener );
	
	void subscribe ( EventStreamEventuallyConsistentBookmarkListener listener );

	
	void placeBookmark ( String reader, EventReference reference, Tags tags );
	
	Optional<EventReference> getBookmark ( String reader );

}
