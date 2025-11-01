package org.sliceworkz.eventstore.spi;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Storage for EventStreams, allows adding to the Event log and Querying the Event Log.
 */
public interface EventStorage {
	
	String name ( );
	
	Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream, EventReference from, Limit limit, QueryDirection queryDirection ); 

	default Stream<StoredEvent> query ( EventQuery query, Optional<EventStreamId> stream, EventReference after, Limit limit ) {
		return query ( query, stream, after, limit, QueryDirection.FORWARD);
	}
	
	List<StoredEvent> append ( AppendCriteria appendCriteria, Optional<EventStreamId> stream, List<EventToStore> events );
	
	Optional<StoredEvent> getEventById ( EventId eventId );
	
	void subscribe ( EventStoreListener listener );

	enum QueryDirection {
		FORWARD,
		BACKWARD
	}

	Optional<EventReference> getBookmark ( String reader );
	
	void bookmark ( String reader, EventReference eventReference, Tags tags );
	
	
	/**
	 * notification that is telling EventStreams that new events are available, an consumers should come fetch and process them 
	 */
	record AppendsToEventStoreNotification ( EventStreamId stream, EventReference atLeastUntil ) { 
		
		public boolean isRelevantFor ( EventStreamId eventStreamCriteria ) {
			return eventStreamCriteria.canRead(stream);
		}
		
	}

	/**
	 * notification that is telling EventStreams that new events are available, an consumers should come fetch and process them 
	 */
	record BookmarkPlacedNotification ( String reader, EventReference bookmark ) { 
		
	}

	/**
	 * All listeners are notified synchronously, to allow any synchronous actions to be integrated.
	 * In typical usecase scenario's, all triggered actions should be made async as soon as possible.
	 */
	interface EventStoreListener {
		void notify ( AppendsToEventStoreNotification newEventsInStore );
		void notify ( BookmarkPlacedNotification bookmarkPlaced );
	}
	
	public record EventToStore ( EventStreamId stream, EventType type, String data, Tags tags ) {
		public StoredEvent positionAt ( EventReference reference, LocalDateTime timestamp) {
			return new StoredEvent(stream, type, reference, data, tags, timestamp);
		}
	}
	
	public record StoredEvent ( EventStreamId stream, EventType type, EventReference reference, String data, Tags tags, LocalDateTime timestamp ) {

	}

}
