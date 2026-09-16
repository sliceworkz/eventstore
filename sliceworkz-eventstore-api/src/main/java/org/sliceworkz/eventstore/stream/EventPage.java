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
package org.sliceworkz.eventstore.stream;

import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * One page of a paged read: the events it holds, and what it took from storage to produce them.
 * <p>
 * A page is what {@link EventSource#page(EventQuery, EventReference)} answers. The events are what a
 * {@link EventSource#query(EventQuery, EventReference) query} would return for the same arguments,
 * read whole; the other two components describe the <em>stored</em> events that were read to produce
 * them, which the events alone cannot: an {@link org.sliceworkz.eventstore.events.Upcast @Upcast}
 * may turn a stored event into several or into none, so a page holding no events is not necessarily
 * an exhausted stream, and the reference to continue from is not necessarily on any event returned.
 * <ul>
 *   <li>{@link #events()} — the events read, upcast and filtered, in the query's direction.</li>
 *   <li>{@link #storedEventCount()} — how many stored events were read to produce them, which is
 *       what the query's {@link EventQuery#limit(long) limit} counts. A page shorter than the limit
 *       it was read with is the last one.</li>
 *   <li>{@link #lastStoredEventReference()} — the reference of the last stored event read, whole
 *       ({@code index} 0), which is the cursor of the next page, or empty when nothing was read. It
 *       is present for a page whose stored events all upcast into nothing, which is what lets a
 *       reader move past such events instead of re-reading them forever.</li>
 * </ul>
 * <p>
 * Paging to the end of a stream by hand:
 * <pre>{@code
 * EventQuery q = EventQuery.matchAll().limit(500);
 * EventReference cursor = null;
 * EventPage<CustomerEvent> page;
 * do {
 *     page = stream.page(q, cursor);
 *     page.events().forEach(this::handle);
 *     cursor = page.lastStoredEventReference().orElse(null);
 * } while ( page.storedEventCount() == 500 );
 * }</pre>
 *
 * @param <DOMAIN_EVENT_TYPE> the type of domain events in the stream the page was read from
 * @param events the events read, upcast and filtered, in the query's direction; never null, and
 *        held as an unmodifiable list
 * @param storedEventCount how many stored events were read to produce {@code events}; never negative
 * @param lastStoredEventReference the reference of the last stored event read, at {@code index} 0,
 *        or empty when {@code storedEventCount} is 0; never null
 * @see EventSource#page(EventQuery, EventReference)
 */
public record EventPage<DOMAIN_EVENT_TYPE> ( List<Event<DOMAIN_EVENT_TYPE>> events, long storedEventCount, Optional<EventReference> lastStoredEventReference ) {

	/**
	 * Validates the page and takes an unmodifiable copy of the events.
	 *
	 * @param events the events read
	 * @param storedEventCount how many stored events were read
	 * @param lastStoredEventReference the reference of the last stored event read, or empty
	 * @throws IllegalArgumentException if {@code events} or {@code lastStoredEventReference} is null,
	 *         if {@code storedEventCount} is negative, or if the reference is present for a page that
	 *         read nothing or absent for a page that read something
	 */
	public EventPage {
		if ( events == null ) {
			throw new IllegalArgumentException("events must not be null");
		}
		if ( lastStoredEventReference == null ) {
			throw new IllegalArgumentException("lastStoredEventReference must not be null; use Optional.empty() for a page that read nothing");
		}
		if ( storedEventCount < 0 ) {
			throw new IllegalArgumentException("storedEventCount must not be negative: " + storedEventCount);
		}
		if ( lastStoredEventReference.isPresent() != ( storedEventCount > 0 ) ) {
			throw new IllegalArgumentException("a page has a last stored event reference exactly when it read a stored event: count " + storedEventCount + ", reference " + lastStoredEventReference);
		}
		events = List.copyOf(events);
	}

	/**
	 * Whether this page read no stored event at all — the stream is exhausted past the cursor it was
	 * read from.
	 * <p>
	 * Not the same as {@link #events()} being empty: a page whose stored events all upcast into
	 * nothing holds no events and is not exhausted.
	 *
	 * @return true if no stored event was read
	 */
	public boolean isExhausted ( ) {
		return storedEventCount == 0;
	}

}
