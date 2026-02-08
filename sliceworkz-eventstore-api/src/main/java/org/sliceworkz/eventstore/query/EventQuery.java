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

import java.util.Collections;
import java.util.List;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Dynamic Consistency Boundary (DCB) style query that allows to dynamically select the {@link Event}s that are of interest.
 *
 * <p>EventQuery wraps an {@link EventFilter} (which contains the pure matching criteria: event types, tags,
 * and temporal boundary) together with traversal semantics (direction and limit) that control how results
 * are returned.
 *
 * <p>The separation between {@link EventFilter} and EventQuery is key:
 * <ul>
 *   <li>{@link EventFilter} defines <em>what</em> to match — event types, tags, and the "until" boundary</li>
 *   <li>EventQuery adds <em>how</em> to return results — direction (forward/backward) and limit</li>
 * </ul>
 *
 * <p>When used for optimistic locking via {@link org.sliceworkz.eventstore.stream.AppendCriteria},
 * only the {@link EventFilter} is needed (via {@link #filter()}), since direction and limit are
 * presentation concerns that must not affect conflict detection.
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Query all events
 * EventQuery allEvents = EventQuery.matchAll();
 *
 * // Query specific event types with tags
 * EventQuery customerEvents = EventQuery.forEvents(
 *     EventTypesFilter.of(CustomerRegistered.class, CustomerUpdated.class),
 *     Tags.of("region", "EU")
 * );
 *
 * // Query backwards (newest first) with limit
 * EventQuery mostRecent = EventQuery.forEvents(
 *     EventTypesFilter.of(CustomerRegistered.class),
 *     Tags.of("customer", "123")
 * ).backwards().limit(1);
 *
 * // Extract the filter for optimistic locking
 * EventFilter filter = mostRecent.filter();
 * AppendCriteria criteria = AppendCriteria.of(filter, lastRef);
 * }</pre>
 *
 * @param filter the event filter containing matching criteria (event types, tags, and temporal boundary)
 * @param direction the traversal direction (FORWARD or BACKWARD), defaults to FORWARD
 * @param limit the maximum number of events to return, defaults to no limit
 *
 * @see EventFilter
 * @see EventFilterItem
 * @see EventTypesFilter
 * @see org.sliceworkz.eventstore.stream.AppendCriteria
 * @see Tags
 */
public record EventQuery ( EventFilter filter, Direction direction, Limit limit ) {

	/**
	 * Defines the traversal direction for event queries.
	 */
	public enum Direction {
		/** Events are returned in chronological order (oldest to newest). */
		FORWARD,
		/** Events are returned in reverse chronological order (newest to oldest). */
		BACKWARD
	}

	public EventQuery ( EventFilter filter, Direction direction, Limit limit ) {
		this.filter = filter != null ? filter : EventFilter.matchNone();
		this.direction = direction != null ? direction : Direction.FORWARD;
		this.limit = limit != null ? limit : Limit.none();
	}

	/**
	 * Convenience constructor from raw filter components.
	 */
	public EventQuery ( List<EventFilterItem> items, EventReference until, Direction direction, Limit limit ) {
		this(new EventFilter(items, until), direction, limit);
	}

	/**
	 * Convenience constructor from raw filter components with default direction and limit.
	 */
	public EventQuery ( List<EventFilterItem> items, EventReference until ) {
		this(new EventFilter(items, until), Direction.FORWARD, Limit.none());
	}

	/**
	 * Creates a match-none EventQuery.
	 */
	public EventQuery ( ) {
		this(EventFilter.matchNone(), Direction.FORWARD, Limit.none());
	}

	/**
	 * Returns the list of query items from the underlying filter.
	 *
	 * @return the list of query items (null for match-all, empty for match-none)
	 */
	public List<EventFilterItem> items ( ) {
		return filter.items();
	}

	/**
	 * Returns the "until" reference from the underlying filter.
	 *
	 * @return the "until" reference, or null if no boundary is set
	 */
	public EventReference until ( ) {
		return filter.until();
	}

	/**
	 * Tests whether the given event matches this query.
	 *
	 * @param event the event to test
	 * @return true if the event matches this query, false otherwise
	 */
	public boolean matches ( Event<?> event ) {
		return filter.matches(event);
	}

	/**
	 * Tests whether the given stored event matches this query.
	 *
	 * @param event the stored event to test
	 * @return true if the stored event matches this query, false otherwise
	 */
	public boolean matches ( StoredEvent event ) {
		return filter.matches(event);
	}

	/**
	 * Tests whether an event with the given attributes matches this query.
	 *
	 * @param eventType the type of the event
	 * @param tags the tags of the event
	 * @param reference the reference of the event
	 * @return true if the event matches this query, false otherwise
	 */
	public boolean matches ( EventType eventType, Tags tags, EventReference reference ) {
		return filter.matches(eventType, tags, reference);
	}

	/**
	 * Checks if this query is a match-none query (will match no events).
	 *
	 * @return true if this query has an empty items list (match-none), false otherwise
	 */
	@JsonIgnore
	public boolean isMatchNone ( ) {
		return filter.isMatchNone();
	}

	/**
	 * Checks if this query is a match-all query (will match all events).
	 *
	 * @return true if this query has null items (match-all), false otherwise
	 */
	@JsonIgnore
	public boolean isMatchAll ( ) {
		return filter.isMatchAll();
	}

	/**
	 * Checks if this query has a backward direction.
	 *
	 * @return true if direction is BACKWARD, false otherwise
	 */
	@JsonIgnore
	public boolean isBackwards ( ) {
		return direction == Direction.BACKWARD;
	}

	/**
	 * Returns a new EventQuery with backward direction (newest first).
	 *
	 * @return a new EventQuery with direction set to BACKWARD
	 */
	public EventQuery backwards ( ) {
		return new EventQuery(filter, Direction.BACKWARD, limit);
	}

	/**
	 * Returns a new EventQuery with the specified limit.
	 *
	 * @param limit the maximum number of events to return
	 * @return a new EventQuery with the specified limit
	 */
	public EventQuery limit ( Limit limit ) {
		return new EventQuery(filter, direction, limit);
	}

	/**
	 * Returns a new EventQuery with the specified limit.
	 *
	 * @param n the maximum number of events to return (must be positive)
	 * @return a new EventQuery with the specified limit
	 */
	public EventQuery limit ( long n ) {
		return new EventQuery(filter, direction, Limit.to(n));
	}

	/**
	 * Creates a new EventQuery that combines the criteria of this query with another (UNION operation).
	 * The resulting query will match events that match either this query or the other query.
	 *
	 * <p>The underlying filters are combined via {@link EventFilter#combineWith(EventFilter)}.
	 * Direction and limit must be the same on both queries.
	 *
	 * @param other the other query to combine with this one
	 * @return a new EventQuery representing the union of both queries
	 * @throws IllegalArgumentException if the "until" references, directions, or limits are incompatible
	 */
	public EventQuery combineWith ( EventQuery other ) {
		if ( this.direction != other.direction ) {
			throw new IllegalArgumentException("can't combine two EventQuery with different directions");
		}

		if ( !this.limit.equals(other.limit) ) {
			throw new IllegalArgumentException("can't combine two EventQuery with different limits");
		}

		EventFilter combinedFilter = this.filter.combineWith(other.filter);
		return new EventQuery(combinedFilter, this.direction, this.limit);
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
		return new EventQuery(EventFilter.matchAll(), Direction.FORWARD, Limit.none());
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
		return forEvents(new EventFilterItem(eventTypes, tags));
	}

	/**
	 * Creates a query from a single query item.
	 *
	 * @param queryItem the query item defining the match criteria
	 * @return an EventQuery containing the single query item
	 */
	public static final EventQuery forEvents ( EventFilterItem queryItem ) {
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
		return new EventQuery(filter.until(until), direction, limit);
	}

	/**
	 * Creates a new EventQuery with the "until" reference set to the earlier of the current "until" and the new reference.
	 *
	 * @param newUntil the new reference to potentially use as the "until" boundary
	 * @return a new EventQuery with the "until" reference potentially updated
	 */
	public EventQuery untilIfEarlier ( EventReference newUntil ) {
		EventFilter updated = filter.untilIfEarlier(newUntil);
		if ( updated == filter ) {
			return this;
		}
		return new EventQuery(updated, direction, limit);
	}

}
