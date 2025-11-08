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
