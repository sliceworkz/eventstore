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
 * Dynamic Consistency Boundary (DCB) style query that allows to dynamically select the {@link Event}s that are of interest.
 *
 * <p>EventQuery is the core mechanism for selecting relevant facts from the event store.
 * It consists of a list of {@link EventQueryItem}s and an optional "until" {@link EventReference} to query up to a specific point in history.
 * Multiple {@link EventQueryItem}s represent an OR condition: if any item matches, the Event matches the overall EventQuery.
 *
 * <p><strong>Match Semantics:</strong>
 * <ul>
 *   <li><strong>Match All:</strong> When the items list is null, any {@link Event} will match the query</li>
 *   <li><strong>Match None:</strong> When the items list is empty, no {@link Event} will match the query</li>
 *   <li><strong>Match Specific:</strong> When the items list contains one or more {@link EventQueryItem}s, events matching any item will match</li>
 * </ul>
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Query all events
 * EventQuery allEvents = EventQuery.matchAll();
 *
 * // Query no events
 * EventQuery noEvents = EventQuery.matchNone();
 *
 * // Query specific event types with tags
 * EventQuery customerEvents = EventQuery.forEvents(
 *     EventTypesFilter.of(CustomerRegistered.class, CustomerUpdated.class),
 *     Tags.of("region", "EU")
 * );
 *
 * // Query up to a specific point in history
 * EventQuery historicalQuery = EventQuery.forEvents(
 *     EventTypesFilter.any(),
 *     Tags.of("customer", "123")
 * ).until(lastKnownReference);
 *
 * // Combine multiple queries (UNION)
 * EventQuery combined = query1.combineWith(query2);
 * }</pre>
 *
 * <p><strong>DCB Significance:</strong>
 * In the Dynamic Consistency Boundary pattern, EventQuery defines which events are considered "relevant facts" for a business decision.
 * When used with {@link org.sliceworkz.eventstore.stream.AppendCriteria}, it enables optimistic locking by ensuring no new relevant facts
 * have emerged since the decision was made.
 *
 * @param items the list of query items to match against (null for match-all, empty for match-none, populated for specific criteria)
 * @param until the reference to query up to (null for no limit, or a specific reference to stop at that point in history)
 *
 * @see EventQueryItem
 * @see EventTypesFilter
 * @see org.sliceworkz.eventstore.stream.AppendCriteria
 * @see Tags
 */
public record EventQuery ( List<EventQueryItem> items, EventReference until ) {
	
	public EventQuery ( List<EventQueryItem> items, EventReference until ) {
		// can be null, this means no criteria at all (match-all)
		this.items = items;
		this.until = until;
	}
	
	public EventQuery (  ) {
		this(new ArrayList<>(), null);
	}

	/**
	 * Tests whether the given event matches this query.
	 *
	 * @param event the event to test
	 * @return true if the event matches this query, false otherwise
	 */
	public boolean matches ( Event<?> event ) {
		return matches(event.type(), event.tags(), event.reference());
	}

	/**
	 * Tests whether the given stored event matches this query.
	 *
	 * @param event the stored event to test
	 * @return true if the stored event matches this query, false otherwise
	 */
	public boolean matches ( StoredEvent event ) {
		return matches(event.type(), event.tags(), event.reference());
	}

	/**
	 * Tests whether an event with the given attributes matches this query.
	 * An event matches if:
	 * <ul>
	 *   <li>Its reference is before or at the "until" reference (if specified)</li>
	 *   <li>It matches at least one of the query items (or all items if match-all)</li>
	 * </ul>
	 *
	 * @param eventType the type of the event
	 * @param tags the tags of the event
	 * @param reference the reference of the event
	 * @return true if the event matches this query, false otherwise
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
	 * Checks if this query is a match-none query (will match no events).
	 *
	 * @return true if this query has an empty items list (match-none), false otherwise
	 */
	@JsonIgnore
	public boolean isMatchNone ( ) {
		return items != null && items.isEmpty();
	}

	/**
	 * Checks if this query is a match-all query (will match all events).
	 *
	 * @return true if this query has null items (match-all), false otherwise
	 */
	@JsonIgnore
	public boolean isMatchAll ( ) {
		return items == null;
	}

	/**
	 * Creates a new EventQuery that combines the criteria of this query with another (UNION operation).
	 * The resulting query will match events that match either this query or the other query.
	 *
	 * <p>With regards to the "until" reference, both queries must have the same value (or both must be unset).
	 * If the "until" references differ, an IllegalArgumentException is thrown.
	 *
	 * @param other the other query to combine with this one
	 * @return a new EventQuery representing the union of both queries
	 * @throws IllegalArgumentException if the "until" references are incompatible
	 */
	public EventQuery combineWith ( EventQuery other ) {
		List<EventQueryItem> combinedQueryItems = Stream.concat(this.items==null?Stream.empty():this.items.stream(), other.items==null?Stream.empty():other.items.stream()).toList();
		EventReference combinedUntil = null;
		if ( this.until == null && other.until == null ) {
			combinedUntil = null;
		} else if ( this.until == null || other.until == null) {
			throw new IllegalArgumentException("can't combine two EventQuery don't share the same until value (one was not set)");
		} else if ( this.until.equals(other.until)) {
			combinedUntil = this.until;
		} else {
			throw new IllegalArgumentException("can't combine two EventQuery that don't share the same until value (both different values)");
		}
		return new EventQuery(combinedQueryItems, combinedUntil);
	}

	/**
	 * Creates a match-none query that will match no events.
	 * Useful when no criteria should be applied (e.g., unconditional appends with {@link org.sliceworkz.eventstore.stream.AppendCriteria}).
	 *
	 * @return an EventQuery that matches no events
	 */
	public static final EventQuery matchNone (  ) {
		return new EventQuery();
	}

	/**
	 * Creates a match-all query that will match all events in the store.
	 * Useful for retrieving the complete event history.
	 *
	 * @return an EventQuery that matches all events
	 */
	public static final EventQuery matchAll (  ) {
		return new EventQuery(null, null);
	}

	/**
	 * Creates a query for events matching the specified event types and tags.
	 * This is the primary way to create a specific query.
	 *
	 * @param eventTypes the filter specifying which event types to match
	 * @param tags the tags that events must contain (all tags must be present)
	 * @return an EventQuery matching the specified criteria
	 */
	public static final EventQuery forEvents ( EventTypesFilter eventTypes, Tags tags ) {
		return forEvents(new EventQueryItem(eventTypes, tags));
	}

	/**
	 * Creates a query from a single query item.
	 *
	 * @param queryItem the query item defining the match criteria
	 * @return an EventQuery containing the single query item
	 */
	public static final EventQuery forEvents ( EventQueryItem queryItem ) {
		return new EventQuery(Collections.singletonList(queryItem), null);
	}

	/**
	 * Creates a new EventQuery with the specified "until" reference.
	 * The resulting query will only match events up to and including the specified reference.
	 *
	 * @param until the reference to query up to (events after this reference will not match)
	 * @return a new EventQuery with the "until" reference set
	 */
	public EventQuery until ( EventReference until ) {
		return new EventQuery(items, until);
	}

	/**
	 * Creates a new EventQuery with the "until" reference set to the earlier of the current "until" and the new reference.
	 * If the new reference is earlier than the current "until" (or if no "until" is set), the new reference is used.
	 * Otherwise, the current "until" reference is retained.
	 *
	 * @param newUntil the new reference to potentially use as the "until" boundary
	 * @return a new EventQuery with the "until" reference potentially updated
	 */
	public EventQuery untilIfEarlier ( EventReference newUntil ) {
		if ( newUntil != null  ) {
			if (this.until == null ) { // we don't have an until value, so we just take on the new one
				return new EventQuery(items, newUntil);
			} else if ( newUntil.happenedBefore(until) ) { // newUntil asks to stop earlier in the stream
				return new EventQuery(items, newUntil);
			} else { // our until value is earlier in the stream than the new one, not expanding our query
				return this;
			}
		} else {
			return this;
		}
	}

}
