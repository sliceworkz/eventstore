package org.sliceworkz.eventstore.events;

import java.time.LocalDateTime;

import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Event
 */
public record Event<DOMAIN_EVENT_TYPE> ( EventStreamId stream, EventType type, EventReference reference, DOMAIN_EVENT_TYPE data, Tags tags, LocalDateTime timestamp ) {

	public Event ( EventStreamId stream, EventType type, EventReference reference, DOMAIN_EVENT_TYPE data, Tags tags, LocalDateTime timestamp ) {
		if ( stream == null ) {
			throw new IllegalArgumentException("stream is required on event");
		}
		if ( type == null ) {
			throw new IllegalArgumentException("type is required on event");
		}
		if ( reference == null ) {
			throw new IllegalArgumentException("reference is required on event");
		}
		if ( data == null ) {
			throw new IllegalArgumentException("data is required on event");
		}
		if ( tags == null ) {
			throw new IllegalArgumentException("tags is required on event");
		}
		this.stream = stream;
		this.type = type;
		this.reference = reference;
		this.data = data;
		this.tags = tags;
		this.timestamp = timestamp;
	}

	public Event<DOMAIN_EVENT_TYPE> withTags ( Tags tags ) {
		return new Event<>(stream, type, reference, data, tags, timestamp);
	}
	
	public static final <DOMAIN_EVENT_TYPE> Event<DOMAIN_EVENT_TYPE> of ( EventStreamId stream, EventReference reference, EventType type, DOMAIN_EVENT_TYPE data, Tags tags, LocalDateTime timestamp ) {
		return new Event<DOMAIN_EVENT_TYPE>(stream, EventType.of(data), reference, data, tags, timestamp);
	}
	
	public static final <DOMAIN_EVENT_TYPE> EphemeralEvent<DOMAIN_EVENT_TYPE> of ( DOMAIN_EVENT_TYPE data, Tags tags) {
		return EphemeralEvent.of(data, tags);
	}

	
}
