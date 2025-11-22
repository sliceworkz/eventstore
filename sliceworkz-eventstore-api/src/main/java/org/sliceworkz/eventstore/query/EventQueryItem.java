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
 *
 * <p>An EventQueryItem defines a single criterion within an {@link EventQuery}.
 * When multiple EventQueryItems are present in a query, they represent an OR condition.
 * Within a single EventQueryItem, the event type filter and tags represent an AND condition.
 *
 * <p><strong>Matching Logic:</strong>
 * <ul>
 *   <li>An event matches if its type is ANY of the types specified in the {@link EventTypesFilter}</li>
 *   <li>AND the event contains ALL of the tags specified in the tags collection</li>
 * </ul>
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Match CustomerRegistered OR CustomerUpdated events with "region=EU" tag
 * EventQueryItem item1 = new EventQueryItem(
 *     EventTypesFilter.of(CustomerRegistered.class, CustomerUpdated.class),
 *     Tags.of("region", "EU")
 * );
 *
 * // Match any event type with "customer=123" tag
 * EventQueryItem item2 = new EventQueryItem(
 *     EventTypesFilter.any(),
 *     Tags.of("customer", "123")
 * );
 *
 * // Match OrderPlaced events with multiple tags
 * EventQueryItem item3 = new EventQueryItem(
 *     EventTypesFilter.of(OrderPlaced.class),
 *     Tags.of("customer", "123", "status", "pending")
 * );
 * }</pre>
 *
 * @param eventTypes the filter specifying which event types to match (events match if they are ANY of the listed types)
 * @param tags the tags that events must contain (events match if they contain ALL of the listed tags)
 *
 * @see EventQuery
 * @see EventTypesFilter
 * @see Tags
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

	/**
	 * Tests whether the given event matches this query item.
	 * The event matches if its type matches the event types filter AND it contains all required tags.
	 *
	 * @param event the event to test
	 * @return true if the event matches this query item, false otherwise
	 */
	public boolean matches ( Event<?> event ) {
		return eventTypes.matches(EventType.of(event.data())) && event.tags().containsAll(tags);
	}

	/**
	 * Tests whether an event with the given type and tags matches this query item.
	 * The event matches if its type matches the event types filter AND the tags contain all required tags.
	 *
	 * @param eventType the type of the event
	 * @param eventTags the tags of the event
	 * @return true if the event matches this query item, false otherwise
	 */
	public boolean matches ( EventType eventType, Tags eventTags ) {
		return eventTypes.matches(eventType) && eventTags.containsAll(tags);
	}

}
