/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
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

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Dynamic Consistency Boundary (DCB) style query that allows to dynamically select the {@link org.sliceworkz.eventstore.events.Event}s that are of interest.
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
 * <p>The query builds its filter and reads nothing off it. {@link #forTypes(Class...)},
 * {@link #forTags(Tags)}, {@link #forEvents(EventTypesFilter, Tags)}, {@link #tagged(Tags)},
 * {@link #or(EventQuery)}, {@link #until(EventReference)} and {@link #untilIfEarlier(EventReference)}
 * build the same filter their {@link EventFilter} namesakes build, carrying the query's direction and
 * limit along; whether an event matches, whether the filter matches everything or nothing, its items
 * and its boundary are questions for the filter, asked through {@link #filter()}:
 * {@code query.filter().matches(event)}, {@code query.filter().isMatchNone()}. The alternative -- the
 * query re-exposing each of those readers as a method of its own -- loses because it hands every
 * caller two spellings of one question and makes the filter's readers a surface to keep in step here;
 * the readers of a query's own are the two it adds, {@link #isBackwards()} and {@link #limit()}.
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Query all events
 * EventQuery allEvents = EventQuery.matchAll();
 *
 * // Query every event of one hierarchy about one customer: the types first, then the tags
 * // every matching event must carry. A sealed root stands for every event type under it
 * EventQuery customer = EventQuery.forTypes(CustomerEvent.class).tagged("customer", "123");
 *
 * // The same query built from its two halves at once
 * EventQuery customerEvents = EventQuery.forEvents(
 *     EventTypesFilter.of(CustomerRegistered.class, CustomerUpdated.class),
 *     Tags.of("region", "EU")
 * );
 *
 * // Query every event about one customer, whatever its type
 * EventQuery anyAboutCustomer = EventQuery.forTags(Tags.of("customer", "123"));
 *
 * // A decision spanning two boundaries: the union of both
 * EventQuery either = EventQuery.forTypes(StudentSubscribed.class).tagged("student", studentId)
 *                               .or(EventQuery.forTypes(CourseEvent.class).tagged("course", courseId));
 *
 * // Query backwards (newest first) with limit
 * EventQuery mostRecent = EventQuery.forTypes(CustomerRegistered.class).tagged("customer", "123")
 *                                   .backwards().limit(1);
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
 * @see EventTypesFilter
 * @see org.sliceworkz.eventstore.stream.AppendCriteria
 * @see Tags
 */
public record EventQuery ( EventFilter filter, Direction direction, Limit limit ) {

	/**
	 * Defines the traversal direction for event queries.
	 * <p>
	 * The direction affects the order events come back in, never which events match. It is also the
	 * direction the storage SPI is asked with —
	 * {@link org.sliceworkz.eventstore.spi.EventStorage#query(EventFilter, org.sliceworkz.eventstore.stream.EventStreamId, org.sliceworkz.eventstore.events.EventReference, Limit, Direction)}
	 * takes it as it takes the query's {@link Limit} — so a query's direction reaches a backend as the
	 * value it holds, with no second type to translate into.
	 */
	public enum Direction {
		/** Events are returned in chronological order (oldest to newest). */
		FORWARD,
		/** Events are returned in reverse chronological order (newest to oldest). */
		BACKWARD
	}

	/**
	 * Creates a query over the given filter, read in the given direction and bounded by the given limit.
	 * Each defaults where absent: a {@code null} filter matches nothing, a {@code null} direction is
	 * {@link Direction#FORWARD} and a {@code null} limit is {@link Limit#none()}.
	 *
	 * @param filter the matching criteria, or {@code null} for {@link EventFilter#matchNone()}
	 * @param direction the traversal direction, or {@code null} for {@link Direction#FORWARD}
	 * @param limit how many stored events to read, or {@code null} for {@link Limit#none()}
	 */
	public EventQuery ( EventFilter filter, Direction direction, Limit limit ) {
		this.filter = filter != null ? filter : EventFilter.matchNone();
		this.direction = direction != null ? direction : Direction.FORWARD;
		this.limit = limit != null ? limit : Limit.none();
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
	 * <p>
	 * The limit is how many <em>stored</em> events to read; see {@link Limit} for what that means when
	 * upcasting is in play.
	 *
	 * @param limit how many stored events to read
	 * @return a new EventQuery with the specified limit
	 */
	public EventQuery limit ( Limit limit ) {
		return new EventQuery(filter, direction, limit);
	}

	/**
	 * Returns a new EventQuery with the specified limit.
	 * <p>
	 * The limit is how many <em>stored</em> events to read; see {@link Limit} for what that means when
	 * upcasting is in play.
	 *
	 * @param n how many stored events to read (must be positive)
	 * @return a new EventQuery with the specified limit
	 */
	public EventQuery limit ( long n ) {
		return new EventQuery(filter, direction, Limit.to(n));
	}

	/**
	 * Narrows this query to events carrying the given tags, keeping its direction and limit; the
	 * filter is narrowed as {@link EventFilter#tagged(Tags)} narrows it, so the tags apply to every
	 * item and chained calls accumulate.
	 *
	 * @param tags the tags every matching event must carry, on top of the tags its item already requires
	 * @return a new EventQuery narrowed to events carrying the tags
	 * @throws IllegalArgumentException if {@code tags} is {@code null}
	 */
	public EventQuery tagged ( Tags tags ) {
		return new EventQuery(filter.tagged(tags), direction, limit);
	}

	/**
	 * Narrows this query to events carrying the given tag; {@link #tagged(Tags)} with
	 * {@link Tags#of(String, String)}.
	 * <pre>{@code
	 * EventQuery customer = EventQuery.forTypes(CustomerEvent.class).tagged("customer", "123");
	 * }</pre>
	 *
	 * @param key the tag's key
	 * @param value the tag's value
	 * @return a new EventQuery narrowed to events carrying the tag
	 * @throws IllegalArgumentException for a tag that cannot be constructed, see {@link org.sliceworkz.eventstore.events.Tag#of(String, String)}
	 */
	public EventQuery tagged ( String key, String value ) {
		return tagged(Tags.of(key, value));
	}

	/**
	 * Creates a new EventQuery that is the union of this query and another: the result matches
	 * every event that matches either.
	 *
	 * <p>The underlying filters are united via {@link EventFilter#or(EventFilter)}.
	 * Both queries must share the same direction, and neither query may have a limit set.
	 *
	 * <p>Limited queries cannot be united because a shared limit over the union does not
	 * preserve per-query semantics: e.g. two {@code backwards().limit(1)} savepoint queries
	 * united into {@code (A OR B) limit 1} would return the single most-recent event of
	 * <em>either</em> type, not the last of A <em>and</em> the last of B. Limited queries must
	 * therefore be executed separately.
	 *
	 * @param other the other query to unite with this one
	 * @return a new EventQuery representing the union of both queries
	 * @throws IllegalArgumentException if the directions differ, the "until" references are
	 *         incompatible, or either query has a limit set
	 */
	public EventQuery or ( EventQuery other ) {
		if ( this.direction != other.direction ) {
			throw new IllegalArgumentException("can't combine two EventQuery with different directions");
		}

		if ( this.limit.isSet() || other.limit.isSet() ) {
			throw new IllegalArgumentException("can't combine an EventQuery that has a limit set");
		}

		EventFilter combinedFilter = this.filter.or(other.filter);
		return new EventQuery(combinedFilter, this.direction, Limit.none());
	}

	/**
	 * The union of this query and another.
	 *
	 * @param other the other query to unite with this one
	 * @return a new EventQuery representing the union of both queries
	 * @throws IllegalArgumentException if the directions differ, the "until" references are
	 *         incompatible, or either query has a limit set
	 * @deprecated a union is an <em>or</em>, and the method is called that: use {@link #or(EventQuery)}
	 */
	@Deprecated(since = "0.11.0", forRemoval = true)
	public EventQuery combineWith ( EventQuery other ) {
		return or(other);
	}

	/**
	 * Creates a match-none query that will match no events.
	 * Useful when no criteria should be applied (e.g., unconditional appends with {@link org.sliceworkz.eventstore.stream.AppendCriteria}).
	 *
	 * @return an EventQuery that matches no events
	 */
	public static final EventQuery matchNone (  ) {
		return new EventQuery(EventFilter.matchNone(), Direction.FORWARD, Limit.none());
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
	 * Creates a query for events matching the specified event types and tags: the general form, built
	 * from both halves at once. {@link #forTypes(Class...)} followed by {@link #tagged(String, String)}
	 * builds the same query as a chain.
	 *
	 * @param eventTypes the filter specifying which event types to match
	 * @param tags the tags that events must contain (all tags must be present)
	 * @return an EventQuery matching the specified criteria
	 */
	public static final EventQuery forEvents ( EventTypesFilter eventTypes, Tags tags ) {
		return new EventQuery(EventFilter.forEvents(eventTypes, tags), Direction.FORWARD, Limit.none());
	}

	/**
	 * Creates a query for events of the specified types, whatever tags they carry: the start of the
	 * fluent form, narrowed with {@link #tagged(String, String)} where a decision is about one entity.
	 * <pre>{@code
	 * EventQuery customer = EventQuery.forTypes(CustomerEvent.class).tagged("customer", "123");
	 * }</pre>
	 * The classes are resolved as {@link EventTypesFilter#of(Class...)} resolves them, so a sealed
	 * interface stands for every event type under it and the root of a hierarchy names all of it. It is
	 * {@link #forEvents(EventTypesFilter, Tags)} with {@link Tags#none()}, and equivalent to it in every
	 * respect.
	 *
	 * @param eventClasses the event classes to match; a sealed interface stands for every event type under it
	 * @return an EventQuery matching events of the types, whatever their tags
	 * @throws IllegalArgumentException for an interface that is not sealed
	 */
	public static final EventQuery forTypes ( Class<?>... eventClasses ) {
		return forEvents(EventTypesFilter.of(eventClasses), Tags.none());
	}

	/**
	 * Creates a query for events of any type carrying the specified tags.
	 * <p>
	 * This is the query a consistency boundary is usually spelled with: every fact about one
	 * entity, whatever its type. It is {@link #forEvents(EventTypesFilter, Tags)} with
	 * {@link EventTypesFilter#any()}, and equivalent to it in every respect.
	 * <pre>{@code
	 * EventQuery customer = EventQuery.forTags(Tags.of("customer", "123"));
	 * }</pre>
	 *
	 * @param tags the tags that events must contain (all tags must be present)
	 * @return an EventQuery matching events of any type carrying the tags
	 */
	public static final EventQuery forTags ( Tags tags ) {
		return forEvents(EventTypesFilter.any(), tags);
	}

	/**
	 * Creates a new EventQuery with the specified "until" reference.
	 * The resulting query will only match events up to and including the specified reference.
	 *
	 * @param until the reference to query up to (events after this reference will not match), or null for no boundary
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
