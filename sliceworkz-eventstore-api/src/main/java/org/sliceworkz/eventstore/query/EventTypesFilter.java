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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.sliceworkz.eventstore.events.EventType;

public record EventTypesFilter ( Set<EventType> eventTypes ) {

	public boolean matches ( EventType eventType ) {
		// if we don't specify specific types, we accept all
		return eventTypes.isEmpty() || eventTypes.contains(eventType);
	}
	
	public static final EventTypesFilter any ( ) {
		return of(new Class[] {});
	}
	
	public static final EventTypesFilter of ( Class<?>... eventClasses ) {
		return of(Arrays.asList(eventClasses));
	}
	
	public static final EventTypesFilter of ( List<Class<?>> eventClasses ) {
		return new EventTypesFilter(eventClasses.stream().map(EventType::of).collect(Collectors.<EventType>toSet()));
	}

	public static final EventTypesFilter of ( Set<EventType> eventTypes ) {
		return new EventTypesFilter(eventTypes);
	}
	
}
