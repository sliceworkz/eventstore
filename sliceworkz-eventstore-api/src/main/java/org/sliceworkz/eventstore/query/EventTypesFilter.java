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

/**
 * Filter for selecting events based on their type.
 *
 * <p>EventTypesFilter allows you to specify which event types should match a query.
 * An empty set of event types means "match any type" (wildcard filter).
 * A non-empty set means "match if the event type is one of the specified types" (OR condition).
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Match any event type (wildcard)
 * EventTypesFilter anyType = EventTypesFilter.any();
 *
 * // Match a single event type
 * EventTypesFilter singleType = EventTypesFilter.of(CustomerRegistered.class);
 *
 * // Match multiple event types (OR condition)
 * EventTypesFilter multipleTypes = EventTypesFilter.of(
 *     CustomerRegistered.class,
 *     CustomerUpdated.class,
 *     CustomerDeleted.class
 * );
 *
 * // Match from a list of classes
 * List<Class<?>> eventClasses = List.of(OrderPlaced.class, OrderShipped.class);
 * EventTypesFilter fromList = EventTypesFilter.of(eventClasses);
 *
 * // Match from a set of EventTypes
 * Set<EventType> eventTypeSet = Set.of(
 *     EventType.of(PaymentReceived.class),
 *     EventType.of(PaymentRefunded.class)
 * );
 * EventTypesFilter fromSet = EventTypesFilter.of(eventTypeSet);
 * }</pre>
 *
 * @param eventTypes the set of event types to match (empty set means match any type)
 *
 * @see EventQuery
 * @see EventQueryItem
 * @see EventType
 */
public record EventTypesFilter ( Set<EventType> eventTypes ) {

	/**
	 * Tests whether the given event type matches this filter.
	 * An event type matches if the filter is empty (wildcard) or if the event type is in the set of allowed types.
	 *
	 * @param eventType the event type to test
	 * @return true if the event type matches this filter, false otherwise
	 */
	public boolean matches ( EventType eventType ) {
		// if we don't specify specific types, we accept all
		return eventTypes.isEmpty() || eventTypes.contains(eventType);
	}

	/**
	 * Creates a wildcard filter that matches any event type.
	 * This is equivalent to an empty set of event types.
	 *
	 * @return an EventTypesFilter that matches any event type
	 */
	public static final EventTypesFilter any ( ) {
		return of(new Class[] {});
	}

	/**
	 * Creates a filter that matches events of the specified types.
	 * Multiple types represent an OR condition: events match if they are ANY of the specified types.
	 *
	 * @param eventClasses the event classes to match
	 * @return an EventTypesFilter that matches the specified event types
	 */
	public static final EventTypesFilter of ( Class<?>... eventClasses ) {
		return of(Arrays.asList(eventClasses));
	}

	/**
	 * Creates a filter that matches events of the specified types from a list.
	 * Multiple types represent an OR condition: events match if they are ANY of the specified types.
	 *
	 * @param eventClasses the list of event classes to match
	 * @return an EventTypesFilter that matches the specified event types
	 */
	public static final EventTypesFilter of ( List<Class<?>> eventClasses ) {
		return new EventTypesFilter(eventClasses.stream().map(EventType::of).collect(Collectors.<EventType>toSet()));
	}

	/**
	 * Creates a filter from a set of EventType objects.
	 * Multiple types represent an OR condition: events match if they are ANY of the specified types.
	 *
	 * @param eventTypes the set of event types to match
	 * @return an EventTypesFilter that matches the specified event types
	 */
	public static final EventTypesFilter of ( Set<EventType> eventTypes ) {
		return new EventTypesFilter(eventTypes);
	}
	
}
