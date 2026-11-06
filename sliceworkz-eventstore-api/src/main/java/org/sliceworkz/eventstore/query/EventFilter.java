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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Which events are relevant: the business half of a read or a consistency boundary.
 *
 * <p>An EventFilter is a union of {@link EventFilterItem}s, each naming event types, the tags an event
 * must carry, and optionally the part of the history it is about — events recorded
 * {@link #after(EventReference) after} one reference, {@link #until(EventReference) until} another.
 * It carries no traversal semantics: direction, limit and the technical read bound
 * ({@link EventQuery#notAfter(EventReference)}) belong to {@link EventQuery}, which wraps a filter for
 * reading. A filter is also what an {@link org.sliceworkz.eventstore.stream.AppendCriteria} checks, which
 * is why the read bound has no place here: a consistency boundary says which facts are relevant, never
 * up to where they were read.
 *
 * <p><strong>Building a filter.</strong> The common case is one type, or one sealed hierarchy, narrowed
 * to one entity, and it is spelled as a chain: the types first, then the tags every matching event must
 * carry, then its bounds, then a union where the decision spans several boundaries.
 * <pre>{@code
 * EventFilter customer  = EventFilter.forTypes(CustomerEvent.class).tagged("customer", id);
 * EventFilter movements = EventFilter.forTypes(StockAdded.class, StockPicked.class)
 *                                    .tagged("product", product)
 *                                    .after(lastCount);
 * EventFilter either    = movements.or(EventFilter.forTypes(PriceChanged.class).tagged("product", product));
 * }</pre>
 * Every operation that narrows — {@link #tagged(Tags)}, {@link #after(EventReference)},
 * {@link #until(EventReference)} and their {@code IfLater}/{@code IfEarlier} forms — applies to every
 * item of the filter it is called on, so a bound set before a union stays with the items it was set on.
 * {@link #of(EventTypesFilter, Tags)} is the same filter built from its two halves at once.
 *
 * <p><strong>Match Semantics:</strong>
 * <ul>
 *   <li><strong>Match All:</strong> When the items list is null, any event matches</li>
 *   <li><strong>Match None:</strong> When the items list is empty, no event matches</li>
 *   <li><strong>Match Specific:</strong> When the items list contains one or more {@link EventFilterItem}s,
 *       events matching any item will match (OR condition)</li>
 * </ul>
 *
 * @param items the list of items to match against (null for match-all, empty for match-none, populated for specific criteria)
 *
 * @see EventQuery
 * @see EventFilterItem
 * @see org.sliceworkz.eventstore.stream.AppendCriteria
 */
public record EventFilter ( List<EventFilterItem> items ) {

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
	 * Tests whether an event with the given attributes matches this filter: whether it matches at least
	 * one of its items, bounds included, or the filter matches everything.
	 * <p>
	 * An item's bounds are over <em>stored</em> events, compared through
	 * {@link EventReference#storedEventHappenedAfter(EventReference)}: every event a stored event upcasts
	 * into is on the same side of a bound, whatever its index.
	 *
	 * @param eventType the type of the event
	 * @param tags the tags of the event
	 * @param reference the reference of the event
	 * @return true if the event matches this filter, false otherwise
	 */
	public boolean matches ( EventType eventType, Tags tags, EventReference reference ) {
		if ( items == null ) {
			return true;
		}
		for ( EventFilterItem item : items ) {
			if ( item.matches(eventType, tags, reference) ) {
				return true;
			}
		}
		return false;
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
	 * Whether any item of this filter carries a bound.
	 *
	 * @return true if at least one item has an {@code after} or an {@code until}
	 */
	@JsonIgnore
	public boolean isBounded ( ) {
		return items != null && items.stream().anyMatch(EventFilterItem::isBounded);
	}

	/**
	 * Narrows this filter to events carrying the given tags, on top of whatever each of its items
	 * already requires: the result matches an event when this filter matches it <em>and</em> the event
	 * carries every one of the tags. Chained calls accumulate, so
	 * {@code forTypes(X.class).tagged("a", "1").tagged("b", "2")} requires both tags.
	 * <p>
	 * Narrowing distributes over a union: {@code a.or(b).tagged(t)} is {@code a.tagged(t).or(b.tagged(t))},
	 * which is what makes the operation well-defined on a filter of several items — every item gets
	 * the tags, and no item is singled out. A match-all filter narrowed this way is
	 * {@link #forTags(Tags)}; a match-none filter stays match-none, since there is nothing to narrow.
	 * Each item keeps its bounds.
	 *
	 * @param tags the tags every matching event must carry, on top of the tags its item already requires
	 * @return a new EventFilter narrowed to events carrying the tags
	 * @throws IllegalArgumentException if {@code tags} is {@code null}
	 */
	public EventFilter tagged ( Tags tags ) {
		if ( tags == null ) {
			throw new IllegalArgumentException("tags is required to narrow a filter (Tags.none() narrows nothing)");
		}
		return eachItem(item -> new EventFilterItem(item.eventTypes(), item.tags().merge(tags), item.after(), item.until()));
	}

	/**
	 * Narrows this filter to events carrying the given tag; {@link #tagged(Tags)} with
	 * {@link Tags#of(String, String)}.
	 *
	 * @param key the tag's key
	 * @param value the tag's value
	 * @return a new EventFilter narrowed to events carrying the tag
	 * @throws IllegalArgumentException for a tag that cannot be constructed, see {@link org.sliceworkz.eventstore.events.Tag#of(String, String)}
	 */
	public EventFilter tagged ( String key, String value ) {
		return tagged(Tags.of(key, value));
	}

	/**
	 * Sets the lower bound of every item: only events recorded after {@code after} match (exclusive).
	 * <p>
	 * It <em>replaces</em> whatever lower bound an item had, and {@code null} removes it;
	 * {@link #afterIfLater(EventReference)} narrows instead. A business bound, in recording order:
	 * "the movements after the last stock count". A match-all filter becomes one item of any type
	 * bounded this way; a match-none filter stays match-none.
	 *
	 * @param after the exclusive lower bound, or {@code null} for none
	 * @return a new EventFilter whose items are bounded after the reference
	 */
	public EventFilter after ( EventReference after ) {
		return eachItem(item -> item.withBounds(after, item.until()));
	}

	/**
	 * Sets the upper bound of every item: only events recorded at or before {@code until} match
	 * (inclusive).
	 * <p>
	 * It <em>replaces</em> whatever upper bound an item had, and {@code null} removes it;
	 * {@link #untilIfEarlier(EventReference)} narrows instead. A business bound, in recording order:
	 * "the invoices recorded up to the year close". It is not the technical bound a decider pins its
	 * reads at, which is {@link EventQuery#notAfter(EventReference)}: an item bound at or before the
	 * expected reference of an {@link org.sliceworkz.eventstore.stream.AppendCriteria} is left out of the
	 * check, because no event appended later can match it. A match-all filter becomes one item of any
	 * type bounded this way; a match-none filter stays match-none.
	 *
	 * @param until the inclusive upper bound, or {@code null} for none
	 * @return a new EventFilter whose items are bounded at the reference
	 */
	public EventFilter until ( EventReference until ) {
		return eachItem(item -> item.withBounds(item.after(), until));
	}

	/**
	 * Narrows the lower bound of every item to the later of its own and {@code after}: an item without a
	 * lower bound gets this one, an item bounded later keeps its own. {@code null} changes nothing.
	 *
	 * @param after the exclusive lower bound to narrow to, or {@code null}
	 * @return a new EventFilter whose items are bounded after the later of the two references
	 */
	public EventFilter afterIfLater ( EventReference after ) {
		if ( after == null ) {
			return this;
		}
		return eachItem(item -> item.withBounds(later(item.after(), after), item.until()));
	}

	/**
	 * Narrows the upper bound of every item to the earlier of its own and {@code until}: an item without
	 * an upper bound gets this one, an item bounded earlier keeps its own. {@code null} changes nothing.
	 *
	 * @param until the inclusive upper bound to narrow to, or {@code null}
	 * @return a new EventFilter whose items are bounded at the earlier of the two references
	 */
	public EventFilter untilIfEarlier ( EventReference until ) {
		if ( until == null ) {
			return this;
		}
		return eachItem(item -> item.withBounds(item.after(), earlier(item.until(), until)));
	}

	/**
	 * Creates a new EventFilter that is the union of this filter and another: the result matches every
	 * event that matches either. The items of both are kept side by side, each with its own types, tags
	 * and bounds, so the union of two filters narrowed to different entities, or bounded at different
	 * points of the history, is a filter over both. A match-all on either side makes the union match-all.
	 * Match-none on either side contributes nothing.
	 *
	 * @param other the other filter to unite with this one
	 * @return a new EventFilter representing the union of both filters
	 */
	public EventFilter or ( EventFilter other ) {
		if ( this.items == null || other.items == null ) {
			return matchAll(); // match-all on either side: the union is everything
		}
		return new EventFilter(Stream.concat(this.items.stream(), other.items.stream()).toList());
	}

	/**
	 * Creates a match-none filter that will match no events.
	 *
	 * @return an EventFilter that matches no events
	 */
	public static EventFilter matchNone ( ) {
		return new EventFilter(new ArrayList<>());
	}

	/**
	 * Creates a match-all filter that will match all events.
	 *
	 * @return an EventFilter that matches all events
	 */
	public static EventFilter matchAll ( ) {
		return new EventFilter(null);
	}

	/**
	 * Creates a filter of one item: events of the given types carrying the given tags. The two halves of
	 * what {@code forTypes(...).tagged(...)} builds as a chain, given at once; both spellings make equal
	 * filters.
	 *
	 * @param eventTypes the filter specifying which event types to match
	 * @param tags the tags that events must contain (all tags must be present)
	 * @return an EventFilter matching the specified criteria
	 */
	public static EventFilter of ( EventTypesFilter eventTypes, Tags tags ) {
		return of(new EventFilterItem(eventTypes, tags));
	}

	/**
	 * Creates a filter from its items: the union of them, each with its own types, tags and bounds. No
	 * items is a filter that matches nothing, as {@link #matchNone()}.
	 *
	 * @param items the items defining the match criteria
	 * @return an EventFilter of the items
	 * @throws IllegalArgumentException if {@code items} or one of them is {@code null}
	 */
	public static EventFilter of ( EventFilterItem... items ) {
		if ( items == null ) {
			throw new IllegalArgumentException("items is required (none for a filter that matches nothing)");
		}
		for ( EventFilterItem item : items ) {
			if ( item == null ) {
				throw new IllegalArgumentException("an item of a filter cannot be null");
			}
		}
		return new EventFilter(List.of(items));
	}

	/**
	 * Creates a filter for events of the specified types, whatever tags they carry: the start of the
	 * fluent form, to be narrowed with {@link #tagged(String, String)} where a decision is about one
	 * entity.
	 * <pre>{@code
	 * EventFilter customer = EventFilter.forTypes(CustomerEvent.class).tagged("customer", "123");
	 * }</pre>
	 * The classes are resolved as {@link EventTypesFilter#of(Class...)} resolves them, so a sealed
	 * interface stands for every event type under it and the root of a hierarchy names all of it. It is
	 * {@link #of(EventTypesFilter, Tags)} with {@link Tags#none()}, and equivalent to it in every
	 * respect.
	 *
	 * @param eventClasses the event classes to match; a sealed interface stands for every event type under it
	 * @return an EventFilter matching events of the types, whatever their tags
	 * @throws IllegalArgumentException for an interface that is not sealed
	 */
	public static EventFilter forTypes ( Class<?>... eventClasses ) {
		return of(EventTypesFilter.of(eventClasses), Tags.none());
	}

	/**
	 * Creates a filter for events of any type carrying the specified tags: every fact about one entity,
	 * which is what a consistency boundary is usually spelled with.
	 * <p>
	 * {@link #of(EventTypesFilter, Tags)} with {@link EventTypesFilter#any()}, and equivalent to it in
	 * every respect.
	 *
	 * @param tags the tags that events must contain (all tags must be present)
	 * @return an EventFilter matching events of any type carrying the tags
	 */
	public static EventFilter forTags ( Tags tags ) {
		return of(EventTypesFilter.any(), tags);
	}

	/**
	 * Creates a filter for events of any type carrying the given tag; {@link #forTags(Tags)} with
	 * {@link Tags#of(String, String)}.
	 * <pre>{@code
	 * EventFilter customer = EventFilter.forTags("customer", "123");
	 * }</pre>
	 *
	 * @param key the tag's key
	 * @param value the tag's value
	 * @return an EventFilter matching events of any type carrying the tag
	 * @throws IllegalArgumentException for a tag that cannot be constructed, see {@link org.sliceworkz.eventstore.events.Tag#of(String, String)}
	 */
	public static EventFilter forTags ( String key, String value ) {
		return forTags(Tags.of(key, value));
	}

	/**
	 * Applies an operation to every item: match-none stays match-none, and match-all is first made one
	 * item of any type without tags, which is the same set of events and has something to narrow.
	 */
	private EventFilter eachItem ( UnaryOperator<EventFilterItem> operation ) {
		if ( isMatchNone() ) {
			return this;
		}
		List<EventFilterItem> current = isMatchAll()
				? Collections.singletonList(new EventFilterItem(EventTypesFilter.any(), Tags.none()))
				: items;
		return new EventFilter(current.stream().map(operation).toList());
	}

	private static EventReference later ( EventReference current, EventReference candidate ) {
		return ( current == null || candidate.storedEventHappenedAfter(current) ) ? candidate : current;
	}

	private static EventReference earlier ( EventReference current, EventReference candidate ) {
		return ( current == null || current.storedEventHappenedAfter(candidate) ) ? candidate : current;
	}

}
