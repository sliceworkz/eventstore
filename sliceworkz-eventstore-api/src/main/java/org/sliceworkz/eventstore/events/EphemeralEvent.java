package org.sliceworkz.eventstore.events;

import java.time.LocalDateTime;

import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * EphemeralEvent is an Event that has not been appended to an EventStream.  Hence is has no reference (no id, no position), and no timestamp) 
 */
public record EphemeralEvent<DOMAIN_EVENT_TYPE> ( EventType type, DOMAIN_EVENT_TYPE data, Tags tags ) {

	public EphemeralEvent ( EventType type, DOMAIN_EVENT_TYPE data, Tags tags ) {
		if ( type == null ) {
			throw new IllegalArgumentException("type is required on event");
		}
		if ( data == null ) {
			throw new IllegalArgumentException("data is required on event");
		}
		if ( tags == null ) {
			throw new IllegalArgumentException("tags is required on event");
		}
		this.type = type;
		this.data = data;
		this.tags = tags;
	}

	public EphemeralEvent<DOMAIN_EVENT_TYPE> withTags ( Tags tags ) {
		return new EphemeralEvent<>(type, data, tags);
	}
	
	public static final <DOMAIN_EVENT_TYPE> EphemeralEvent<DOMAIN_EVENT_TYPE> of ( DOMAIN_EVENT_TYPE data, Tags tags) {
		return new EphemeralEvent<DOMAIN_EVENT_TYPE>(EventType.of(data), data, tags);
	}
	
	public Event<DOMAIN_EVENT_TYPE> positionAt ( EventStreamId stream, EventReference reference, LocalDateTime timestamp ) {
		return Event.of(stream, reference, type, type, data, tags, timestamp);
	}
	
}
