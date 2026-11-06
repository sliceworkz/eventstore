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

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;

/**
 * Part of an {@link EventFilter} that represents a single matching rule.
 *
 * <p>An EventFilterItem defines a single criterion within an {@link EventFilter}.
 * When multiple EventFilterItems are present in a filter, they represent an OR condition.
 * Within a single EventFilterItem, the event types, the tags and the bounds represent an AND condition.
 *
 * <p><strong>Who builds one, and who reads one.</strong> Application code spells a filter from its
 * types, tags and bounds — {@link EventFilter#forTypes(Class...)}, {@link EventFilter#tagged(String, String)},
 * {@link EventFilter#after(EventReference)}, {@link EventFilter#until(EventReference)},
 * {@link EventFilter#or(EventFilter)} — and every spelling resolves to items, so the record is rarely
 * constructed by hand. It is public because it is what a storage reads off {@link EventFilter#items()}
 * to answer the filter: each item is one OR-branch of the query a backend builds, and the storage SPI
 * is a published contract.
 *
 * <p><strong>Matching Logic:</strong>
 * <ul>
 *   <li>An event matches if its type is ANY of the types specified in the {@link EventTypesFilter}</li>
 *   <li>AND the event contains ALL of the tags specified in the tags collection</li>
 *   <li>AND its stored event comes after {@code after}, when set (exclusive)</li>
 *   <li>AND its stored event comes at or before {@code until}, when set (inclusive)</li>
 * </ul>
 *
 * <p><strong>The bounds are business bounds, in recording order.</strong> They say which part of the
 * history is relevant to the decision this item stands for: the movements after the last stock count,
 * the invoices recorded up to the close of the year. They compare stored events in the
 * {@code (tx, position)} order the store reads in ({@link EventReference#storedEventHappenedAfter}),
 * so every event one stored event upcasts into is on the same side of a bound. Each item carries its
 * own, which is what lets one filter unite facts bounded at different points of the history. They are
 * not the technical bound a decider pins its reads at — that is {@link EventQuery#notAfter(EventReference)},
 * a property of the read, which never reaches a consistency boundary. An item whose bounds leave no
 * room ({@code after} at or past {@code until}) matches nothing.
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Match CustomerRegistered OR CustomerUpdated events with "region=EU" tag
 * EventFilterItem item1 = new EventFilterItem(
 *     EventTypesFilter.of(CustomerRegistered.class, CustomerUpdated.class),
 *     Tags.of("region", "EU")
 * );
 *
 * // Match the stock movements of one product recorded after its last count
 * EventFilterItem item2 = new EventFilterItem(
 *     EventTypesFilter.of(StockAdded.class, StockPicked.class),
 *     Tags.of("product", "WIDGET-42"),
 *     lastCount, null
 * );
 * }</pre>
 *
 * @param eventTypes the filter specifying which event types to match (events match if they are ANY of the listed types)
 * @param tags the tags that events must contain (events match if they contain ALL of the listed tags)
 * @param after the exclusive lower bound over stored events, or {@code null} for none
 * @param until the inclusive upper bound over stored events, or {@code null} for none
 *
 * @see EventFilter
 * @see EventTypesFilter
 * @see Tags
 */
public record EventFilterItem ( EventTypesFilter eventTypes, Tags tags, EventReference after, EventReference until ) {

	/**
	 * Constructs a filter item with validation. Neither the types nor the tags are optional: "no
	 * restriction" is spelled {@link EventTypesFilter#any()} and {@link Tags#none()}, never {@code null}.
	 * The bounds are optional, {@code null} being "unbounded".
	 *
	 * @param eventTypes the event types to match (required; {@link EventTypesFilter#any()} for all)
	 * @param tags the tags an event must carry (required; {@link Tags#none()} for none)
	 * @param after the exclusive lower bound, or {@code null}
	 * @param until the inclusive upper bound, or {@code null}
	 * @throws IllegalArgumentException if the types or the tags are {@code null}, like every other value type in this API
	 */
	public EventFilterItem {
		if ( eventTypes == null ) {
			throw new IllegalArgumentException("eventTypes is required on filter (can be 'any')");
		}
		if ( tags == null ) {
			throw new IllegalArgumentException("tags is required on filter (can be 'any')");
		}
	}

	/**
	 * Constructs an unbounded filter item: the given types and tags, over the whole history.
	 *
	 * @param eventTypes the event types to match (required; {@link EventTypesFilter#any()} for all)
	 * @param tags the tags an event must carry (required; {@link Tags#none()} for none)
	 * @throws IllegalArgumentException if either is {@code null}
	 */
	public EventFilterItem ( EventTypesFilter eventTypes, Tags tags ) {
		this(eventTypes, tags, null, null);
	}

	/**
	 * Tests whether the given event matches this filter item: its type, its tags and, where set, the bounds.
	 *
	 * @param event the event to test
	 * @return true if the event matches this filter item, false otherwise
	 */
	public boolean matches ( Event<?> event ) {
		return matches(EventType.of(event.data().getClass()), event.tags(), event.reference());
	}

	/**
	 * Tests whether an event with the given type, tags and reference matches this filter item.
	 *
	 * @param eventType the type of the event
	 * @param eventTags the tags of the event
	 * @param reference the reference of the event; only consulted where the item has a bound
	 * @return true if the event matches this filter item, false otherwise
	 */
	public boolean matches ( EventType eventType, Tags eventTags, EventReference reference ) {
		return eventTypes.matches(eventType) && eventTags.containsAll(tags) && withinBounds(reference);
	}

	/**
	 * Tests whether a reference lies within this item's bounds: after {@code after} and at or before
	 * {@code until}, comparing stored events. An item without bounds holds every reference.
	 *
	 * @param reference the reference to test
	 * @return true if the reference lies within the bounds
	 */
	public boolean withinBounds ( EventReference reference ) {
		if ( after != null && !reference.storedEventHappenedAfter(after) ) {
			return false;
		}
		return until == null || !reference.storedEventHappenedAfter(until);
	}

	/**
	 * Whether this item carries a bound.
	 *
	 * @return true if {@code after} or {@code until} is set
	 */
	@JsonIgnore
	public boolean isBounded ( ) {
		return after != null || until != null;
	}

	/**
	 * This item with its types and tags, and the given bounds.
	 *
	 * @param after the exclusive lower bound, or {@code null}
	 * @param until the inclusive upper bound, or {@code null}
	 * @return an item with the same types and tags and the given bounds
	 */
	public EventFilterItem withBounds ( EventReference after, EventReference until ) {
		return new EventFilterItem(eventTypes, tags, after, until);
	}

}
