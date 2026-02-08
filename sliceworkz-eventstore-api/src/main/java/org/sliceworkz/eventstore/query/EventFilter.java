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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Pure matching criteria for selecting events from the event store.
 *
 * <p>EventFilter encapsulates the predicate logic for event matching: which event types and tags
 * to match, and an optional temporal boundary ({@code until}) that limits how far into the event
 * history to look. It does <em>not</em> carry traversal semantics such as direction or limit —
 * those belong to {@link EventQuery}, which wraps an EventFilter.
 *
 * <p>EventFilter is the correct type to use wherever only matching semantics are needed,
 * most notably in {@link org.sliceworkz.eventstore.stream.AppendCriteria} for optimistic locking,
 * where direction and limit are irrelevant.
 *
 * <p><strong>Match Semantics:</strong>
 * <ul>
 *   <li><strong>Match All:</strong> When the items list is null, any event matches</li>
 *   <li><strong>Match None:</strong> When the items list is empty, no event matches</li>
 *   <li><strong>Match Specific:</strong> When the items list contains one or more {@link EventQueryItem}s,
 *       events matching any item will match (OR condition)</li>
 * </ul>
 *
 * @param items the list of query items to match against (null for match-all, empty for match-none, populated for specific criteria)
 * @param until the reference to match up to (null for no boundary, or a specific reference to stop at that point in history)
 *
 * @see EventQuery
 * @see EventQueryItem
 * @see org.sliceworkz.eventstore.stream.AppendCriteria
 */
public record EventFilter ( List<EventQueryItem> items, EventReference until ) {

	/**
	 * Tests whether the given event matches this filter.
	 *
	 * @param event the event to test
	 * @return true if the event matches this filter, false otherwise
	 */
	public boolean matches ( Event<?> event ) {
		return matches(event.type(), event.tags(), event.reference());
	}

	/**
	 * Tests whether the given stored event matches this filter.
	 *
	 * @param event the stored event to test
	 * @return true if the stored event matches this filter, false otherwise
	 */
	public boolean matches ( StoredEvent event ) {
		return matches(event.type(), event.tags(), event.reference());
	}

	/**
	 * Tests whether an event with the given attributes matches this filter.
	 * An event matches if:
	 * <ul>
	 *   <li>Its reference is before or at the "until" reference (if specified)</li>
	 *   <li>It matches at least one of the query items (or all items if match-all)</li>
	 * </ul>
	 *
	 * @param eventType the type of the event
	 * @param tags the tags of the event
	 * @param reference the reference of the event
	 * @return true if the event matches this filter, false otherwise
	 */
	public boolean matches ( EventType eventType, Tags tags, EventReference reference ) {
		boolean match = true;
		if ( until == null || !reference.happenedAfter(until) ) {
			if ( items != null ) {
				if ( !items.isEmpty() ) { // null items = all match, empty items is none match
					// if any query item matches the event, we keep it
					match = items.stream().filter(i->i.matches(eventType, tags)).findAny().isPresent();
				} else {
					match = false; // no match since we have items (empty collection) but no criteria to adhere to
				}
			}
		} else {
			match = false;
		}
		return match;
	}

	/**
	 * Checks if this filter is a match-none filter (will match no events).
	 *
	 * @return true if this filter has an empty items list (match-none), false otherwise
	 */
	@JsonIgnore
	public boolean isMatchNone ( ) {
		return items != null && items.isEmpty();
	}

	/**
	 * Checks if this filter is a match-all filter (will match all events).
	 *
	 * @return true if this filter has null items (match-all), false otherwise
	 */
	@JsonIgnore
	public boolean isMatchAll ( ) {
		return items == null;
	}

	/**
	 * Creates a new EventFilter with the specified "until" reference.
	 * The resulting filter will only match events up to and including the specified reference.
	 *
	 * @param until the reference to match up to (events after this reference will not match)
	 * @return a new EventFilter with the "until" reference set
	 */
	public EventFilter until ( EventReference until ) {
		return new EventFilter(items, until);
	}

	/**
	 * Creates a new EventFilter with the "until" reference set to the earlier of the current "until" and the new reference.
	 * If the new reference is earlier than the current "until" (or if no "until" is set), the new reference is used.
	 * Otherwise, the current "until" reference is retained.
	 *
	 * @param newUntil the new reference to potentially use as the "until" boundary
	 * @return a new EventFilter with the "until" reference potentially updated
	 */
	public EventFilter untilIfEarlier ( EventReference newUntil ) {
		if ( newUntil != null ) {
			if ( this.until == null ) {
				return new EventFilter(items, newUntil);
			} else if ( newUntil.happenedBefore(until) ) {
				return new EventFilter(items, newUntil);
			} else {
				return this;
			}
		} else {
			return this;
		}
	}

	/**
	 * Creates a new EventFilter that combines the criteria of this filter with another (UNION operation).
	 * The resulting filter will match events that match either this filter or the other filter.
	 *
	 * <p>Both filters must have the same "until" reference (or both must be unset).
	 *
	 * @param other the other filter to combine with this one
	 * @return a new EventFilter representing the union of both filters
	 * @throws IllegalArgumentException if the "until" references are incompatible
	 */
	public EventFilter combineWith ( EventFilter other ) {
		List<EventQueryItem> combinedQueryItems = Stream.concat(this.items==null?Stream.empty():this.items.stream(), other.items==null?Stream.empty():other.items.stream()).toList();
		EventReference combinedUntil = null;
		if ( this.until == null && other.until == null ) {
			combinedUntil = null;
		} else if ( this.until == null || other.until == null) {
			throw new IllegalArgumentException("can't combine two EventFilter that don't share the same until value (one was not set)");
		} else if ( this.until.equals(other.until)) {
			combinedUntil = this.until;
		} else {
			throw new IllegalArgumentException("can't combine two EventFilter that don't share the same until value (both different values)");
		}

		return new EventFilter(combinedQueryItems, combinedUntil);
	}

	/**
	 * Creates a match-none filter that will match no events.
	 *
	 * @return an EventFilter that matches no events
	 */
	public static EventFilter matchNone ( ) {
		return new EventFilter(new ArrayList<>(), null);
	}

	/**
	 * Creates a match-all filter that will match all events.
	 *
	 * @return an EventFilter that matches all events
	 */
	public static EventFilter matchAll ( ) {
		return new EventFilter(null, null);
	}

	/**
	 * Creates a filter for events matching the specified event types and tags.
	 *
	 * @param eventTypes the filter specifying which event types to match
	 * @param tags the tags that events must contain (all tags must be present)
	 * @return an EventFilter matching the specified criteria
	 */
	public static EventFilter forEvents ( EventTypesFilter eventTypes, Tags tags ) {
		return forEvents(new EventQueryItem(eventTypes, tags));
	}

	/**
	 * Creates a filter from a single query item.
	 *
	 * @param queryItem the query item defining the match criteria
	 * @return an EventFilter containing the single query item
	 */
	public static EventFilter forEvents ( EventQueryItem queryItem ) {
		return new EventFilter(Collections.singletonList(queryItem), null);
	}

}
