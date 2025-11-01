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
