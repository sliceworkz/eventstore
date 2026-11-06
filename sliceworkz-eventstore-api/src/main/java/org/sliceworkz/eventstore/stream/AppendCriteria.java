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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventFilterItem;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Criteria set to determine whether {@link Event}s can be added to the event store.
 * Crucial to the Dynamic Context Boundary (DCB) event store mechanism.
 *
 * When business decisions are made, historical {@link Event}s are read with an {@link EventQuery}, and the decision is based on all relevant facts.
 * The resulting domain {@link Event}s are only appended to the event store if by the time they are stored, no new relevant facts are found in the store.
 * In practice, this allows a dynamic optimistic locking concept, effectively checking whether no new relevant facts are known before the storage of the {@link Event}s.
 * This is checked by including the {@link EventFilter} (which events are relevant), as well as an {@link EventReference} to the
 * point in the store the decision is based on. If any event deemed relevant by the {@link EventFilter} is found after the
 * reference, no append is done. Simple appends without locking (without relevant facts to consider) are possible with
 * {@link #none()}.
 *
 * <h2>Built from the query the decision read with</h2>
 * The recommended way to pin a boundary is {@link EventSource#head()} taken before the read: every read of
 * the decision is bounded {@link EventQuery#notAfter(EventReference) notAfter} the head, and the head is the
 * expected reference of the append. The criteria can be built from that very query:
 * <pre>{@code
 * EventFilter relevant = EventFilter.forTags("customer", "123");
 * EventReference head = stream.head().orElse(null);
 *
 * EventQuery read = EventQuery.of(relevant).notAfter(head);
 * stream.query(read);                                   // the READ is bounded at the head
 * stream.append(AppendCriteria.of(read, head), ...);    // the CHECK takes the filter, never the read bound
 * }</pre>
 * Only the query's {@link EventQuery#filter() filter} is taken. Its direction, limit and read bound say how
 * the facts were read, not which facts are relevant, and a check bounded at the very reference it compares
 * against would find nothing new, ever.
 *
 * <h2>Items whose bounds the reference has passed are left out</h2>
 * The check asks for relevant events <em>after</em> the expected reference, so the business bounds of an
 * {@link EventFilterItem} are judged against it when the criteria is made, comparing stored events:
 * <ul>
 *   <li>an item {@link EventFilter#until(EventReference) until} a reference at or before the expected one
 *       can match no event appended later, and is removed;</li>
 *   <li>an item {@link EventFilter#after(EventReference) after} a reference at or before the expected one
 *       loses that bound, since every event after the expected reference is after it too;</li>
 *   <li>every other bound is kept, and checked.</li>
 * </ul>
 * Nothing is pruned without an expected reference: a decision on an empty boundary counts every matching
 * event. When every item is removed the filter matches nothing, so the append is unconditional — which is
 * correct, since the decision depends only on history no later append can change.
 *
 * @param eventFilter the filter defining which events are relevant for the consistency check, pruned against the reference
 * @param expectedLastEventReference the point in the store our decision was based upon, or Optional.empty() for none assumed (empty EventStream).
 *                                   Never null — a null handed to the canonical constructor is normalised to Optional.empty().
 */
public record AppendCriteria ( EventFilter eventFilter, Optional<EventReference> expectedLastEventReference ) {

	/**
	 * Normalises a null expectedLastEventReference into {@link Optional#empty()}, so this record can never hold
	 * a null in that component regardless of how it was built, and prunes the filter's items against the
	 * reference as the class javadoc describes. Callers — including third-party {@code EventStorage}
	 * implementations — can therefore call {@link #expectedLastEventReference()} and use the Optional directly.
	 *
	 * @param eventFilter the filter defining which events are relevant for the consistency check
	 * @param expectedLastEventReference the last known matching Event, or Optional.empty() (or null, normalised here) for none
	 */
	public AppendCriteria {
		expectedLastEventReference = expectedLastEventReference == null ? Optional.empty() : expectedLastEventReference;
		if ( eventFilter == null ) {
			throw new IllegalArgumentException("eventFilter is required (EventFilter.matchNone() for no criteria)");
		}
		eventFilter = prune(eventFilter, expectedLastEventReference.orElse(null));
	}

	/**
	 * Specifies that no AppendCriteria should be applied, so the append will be carried out regardless of the history.
	 * @return AppendCriteria without conditional appends
	 */
	public static final AppendCriteria none ( ) {
		return new AppendCriteria(EventFilter.matchNone(), Optional.empty());
	}

	/**
	 * Creates an AppendCriteria from an {@link EventFilter} and the point in the store the decision is based on.
	 *
	 * <p>
	 * A {@code null} reference is a boundary too: "I decided on an empty boundary", and any matching
	 * event in the stream is then a new relevant fact. It is the value {@code stream.head().orElse(null)}
	 * hands over for an empty stream, which is why an absent reference is spelled {@code null} on the
	 * input side and {@link java.util.Optional#empty()} on the answering side (see
	 * {@link EventReference}).
	 *
	 * @param eventFilter the filter defining relevant events for the consistency check
	 * @param reference the point our decision was based upon (the head the reads were pinned at), or null for an empty boundary
	 * @return AppendCriteria for conditional appends
	 */
	public static final AppendCriteria of ( EventFilter eventFilter, EventReference reference ) {
		return new AppendCriteria(eventFilter, Optional.ofNullable(reference));
	}

	/**
	 * Creates an AppendCriteria from the {@link EventQuery} the decision read with, taking its filter only.
	 * Its direction, limit and {@link EventQuery#notAfter(EventReference) read bound} play no part — they say
	 * how the facts were read, not which are relevant — so the query a decider read with is the one to pass.
	 *
	 * @param eventQuery the query whose filter will be used for the consistency check
	 * @param reference the point our decision was based upon (the head the reads were pinned at), or null for an empty boundary
	 * @return AppendCriteria for conditional appends
	 */
	public static final AppendCriteria of ( EventQuery eventQuery, EventReference reference ) {
		return new AppendCriteria(eventQuery.filter(), Optional.ofNullable(reference));
	}

	/**
	 * Returns whether this EventCriteria object represents 'no criteria'.
	 * @return boolean specifying whether this represents 'no criteria'
	 */
	public boolean isNone ( ) {
		return eventFilter.isMatchNone ( );
	}

	/**
	 * Leaves out what no event after the reference can match: an item bounded until the reference or
	 * earlier, and a lower bound at the reference or earlier. Without a reference, or without bounded
	 * items, the filter is returned as it is.
	 */
	private static EventFilter prune ( EventFilter filter, EventReference reference ) {
		if ( reference == null || !filter.isBounded() ) {
			return filter;
		}
		List<EventFilterItem> kept = new ArrayList<>(filter.items().size());
		for ( EventFilterItem item : filter.items() ) {
			if ( item.until() != null && !item.until().storedEventHappenedAfter(reference) ) {
				continue; // nothing after the reference is at or before this item's until
			}
			EventReference after = ( item.after() != null && !item.after().storedEventHappenedAfter(reference) ) ? null : item.after();
			kept.add(item.withBounds(after, item.until()));
		}
		return new EventFilter(kept);
	}

}
