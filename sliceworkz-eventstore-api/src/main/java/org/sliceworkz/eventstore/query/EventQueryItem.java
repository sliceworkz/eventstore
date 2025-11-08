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
package org.sliceworkz.eventstore.query;

import java.security.InvalidParameterException;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Part of an {@link EventQuery} that represents a single matching rule.
 * @param eventTypes - Events match if they are ANY of the listed types
 * @param tags - Events match if they container ALL of the listed tags. 
 */
public record EventQueryItem ( EventTypesFilter eventTypes, Tags tags ) {
	
	public EventQueryItem ( EventTypesFilter eventTypes, Tags tags ) {
		if ( eventTypes == null ) {
			throw new InvalidParameterException("eventTypes is required on query (can be 'any')");
		}
		if ( tags == null ) {
			throw new InvalidParameterException("tags is required on query (can by 'any')");
		}
		this.eventTypes = eventTypes;
		this.tags = tags;
	}

	public boolean matches ( Event<?> event ) {
		return eventTypes.matches(EventType.of(event.data())) && event.tags().containsAll(tags);
	}

	public boolean matches ( EventType eventType, Tags eventTags ) {
		return eventTypes.matches(eventType) && eventTags.containsAll(tags);
	}

}
