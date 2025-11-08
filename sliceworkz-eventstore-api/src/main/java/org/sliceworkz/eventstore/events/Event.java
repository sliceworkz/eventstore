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
package org.sliceworkz.eventstore.events;

import java.time.LocalDateTime;

import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Event
 */
public record Event<DOMAIN_EVENT_TYPE> ( EventStreamId stream, EventType type, EventType storedType, EventReference reference, DOMAIN_EVENT_TYPE data, Tags tags, LocalDateTime timestamp ) {

	public Event ( EventStreamId stream, EventType type, EventType storedType, EventReference reference, DOMAIN_EVENT_TYPE data, Tags tags, LocalDateTime timestamp ) {
		if ( stream == null ) {
			throw new IllegalArgumentException("stream is required on event");
		}
		if ( type == null ) {
			throw new IllegalArgumentException("type is required on event");
		}
		if ( storedType == null ) {
			throw new IllegalArgumentException("storedType is required on event");
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
		this.storedType = storedType;
		this.reference = reference;
		this.data = data;
		this.tags = tags;
		this.timestamp = timestamp;
	}

	public Event<DOMAIN_EVENT_TYPE> withTags ( Tags tags ) {
		return new Event<>(stream, type, storedType, reference, data, tags, timestamp);
	}
	
	public static final <DOMAIN_EVENT_TYPE> Event<DOMAIN_EVENT_TYPE> of ( EventStreamId stream, EventReference reference, EventType type, EventType storedType, DOMAIN_EVENT_TYPE data, Tags tags, LocalDateTime timestamp ) {
		return new Event<DOMAIN_EVENT_TYPE>(stream, type, storedType, reference, data, tags, timestamp);
	}
	
	public static final <DOMAIN_EVENT_TYPE> EphemeralEvent<DOMAIN_EVENT_TYPE> of ( DOMAIN_EVENT_TYPE data, Tags tags) {
		return EphemeralEvent.of(data, tags);
	}

	
}
