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

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * How a filter is read: an {@link EventFilter} plus a direction, a limit and a read bound.
 *
 * <p>The two halves are kept apart on purpose:
 * <ul>
 *   <li>{@link EventFilter} says <em>which</em> events are relevant — types, tags, and the part of the
 *       history each item is about. It is what a consistency boundary checks.</li>
 *   <li>EventQuery says <em>how</em> they are read — {@link #backwards()}, {@link #limit(long)}, and
 *       {@link #notAfter(EventReference)}, the technical bound a read is pinned at. None of these is a
 *       property of what is relevant, so none of them reaches an
 *       {@link org.sliceworkz.eventstore.stream.AppendCriteria}.</li>
 * </ul>
 * A query is therefore always built from a filter, with nothing to shorten that:
 * <pre>{@code
 * EventFilter relevant = EventFilter.forTypes(CustomerEvent.class).tagged("customer", id);
 *
 * EventReference head = stream.head().orElse(null);
 * List<Event<CustomerEvent>> facts = stream.query(EventQuery.of(relevant).notAfter(head));
 * stream.append(AppendCriteria.of(relevant, head), Event.of(new CustomerNameChanged(id, "Jane"), Tags.of("customer", id)));
 *
 * // the newest savepoint: newest first, one stored event
 * EventQuery lastCount = EventQuery.of(EventFilter.forTypes(StockCounted.class).tagged("product", product))
 *                                  .backwards().limit(1);
 * }</pre>
 *
 * <p>There is no union of queries: unite the filters with {@link EventFilter#or(EventFilter)} and
 * read the union once. A direction, a limit or a read bound cannot be united meaningfully — two
 * {@code backwards().limit(1)} savepoint queries united would return the newest event of either,
 * not the newest of each.
 *
 * @param filter the event filter defining which events are relevant
 * @param direction the traversal direction (forward or backward), defaults to forward
 * @param limit the maximum number of stored events to read, defaults to no limit
 * @param notAfter the inclusive read bound over stored events, or {@code null} for none
 *
 * @see EventFilter
 * @see EventFilterItem
 * @see Direction
 */
public record EventQuery ( EventFilter filter, Direction direction, Limit limit, EventReference notAfter ) {

	/**
	 * Defines the traversal direction for event queries.
	 * <p>
	 * The direction affects the order events come back in, never which events match. It is also the
	 * direction the storage SPI is asked with —
	 * {@link org.sliceworkz.eventstore.spi.EventStorage#query(EventFilter, org.sliceworkz.eventstore.stream.EventStreamId, EventReference, EventReference, Limit, Direction)}
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
	 * Creates a query over the given filter, read in the given direction, bounded by the given limit and
	 * read bound. Each defaults where absent: a {@code null} filter matches nothing, a {@code null}
	 * direction is {@link Direction#FORWARD}, a {@code null} limit is {@link Limit#none()} and a
	 * {@code null} read bound is none.
	 *
	 * @param filter the matching criteria, or {@code null} for {@link EventFilter#matchNone()}
	 * @param direction the traversal direction, or {@code null} for {@link Direction#FORWARD}
	 * @param limit how many stored events to read, or {@code null} for {@link Limit#none()}
	 * @param notAfter the inclusive read bound, or {@code null} for none
	 */
	public EventQuery {
		filter = filter != null ? filter : EventFilter.matchNone();
		direction = direction != null ? direction : Direction.FORWARD;
		limit = limit != null ? limit : Limit.none();
	}

	/**
	 * Creates a query reading the given filter forward, with no limit and no read bound.
	 *
	 * @param filter which events are relevant
	 * @return a query over the filter
	 * @throws IllegalArgumentException if {@code filter} is {@code null}
	 */
	public static EventQuery of ( EventFilter filter ) {
		if ( filter == null ) {
			throw new IllegalArgumentException("filter is required (EventFilter.matchAll() or EventFilter.matchNone() to say so)");
		}
		return new EventQuery(filter, Direction.FORWARD, Limit.none(), null);
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
		return new EventQuery(filter, Direction.BACKWARD, limit, notAfter);
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
		return new EventQuery(filter, direction, limit, notAfter);
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
		return new EventQuery(filter, direction, Limit.to(n), notAfter);
	}

	/**
	 * Returns a new EventQuery that reads nothing recorded after {@code notAfter}: the read bound,
	 * inclusive, over stored events. It replaces any read bound already set, and {@code null} removes it.
	 * <p>
	 * This is the technical bound a decider pins its reads at — take the stream's
	 * {@link org.sliceworkz.eventstore.stream.EventSource#head() head} first, read every part of the
	 * decision {@code notAfter(head)}, and present the head as the expected reference of the append, so
	 * that several reads see one moment. It selects the same events whatever the direction: a backwards
	 * read starts at the bound and walks into history, it never reads what was recorded after it. It is a
	 * property of the read and not of what is relevant, which is why
	 * {@link org.sliceworkz.eventstore.stream.AppendCriteria#of(EventQuery, EventReference)} takes the
	 * filter only: a check bounded at the reference it compares against would find nothing new, ever.
	 * <p>
	 * A bound that belongs to the decision itself — "the invoices recorded up to the year close" — is a
	 * business bound and lives on the filter's items: {@link EventFilter#until(EventReference)}.
	 *
	 * @param notAfter the inclusive read bound, or {@code null} for none
	 * @return a new EventQuery with the read bound set
	 */
	public EventQuery notAfter ( EventReference notAfter ) {
		return new EventQuery(filter, direction, limit, notAfter);
	}

}
