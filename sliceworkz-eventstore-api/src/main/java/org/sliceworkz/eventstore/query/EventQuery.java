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
 * Multiple {@link EventQueryItem}s can be included in the query.  If one match is found, the Event matches the overall EventQuery.
 * When no {@link EventQueryItem}s are listed (empty list), no {@link Event} will match the query (match none)
 * When no list of {@link EventQueryItem}s is passed (value null), any {@link Event} will match the query (match all)
 * An "until" EventReference allows to query up until a certain point in the Event history
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

	public boolean matches ( Event<?> event ) {
		return matches(event.type(), event.tags(), event.reference());
	}
	
	public boolean matches ( StoredEvent event ) {
		return matches(event.type(), event.tags(), event.reference());
	}

	public boolean matches ( EventType eventType, Tags tags, EventReference reference ) {
		boolean match = true;
		if ( until == null || until.position() >= reference.position() ) {
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

	@JsonIgnore
	public boolean isMatchNone ( ) {
		return items != null && items.isEmpty();
	}
	
	@JsonIgnore
	public boolean isMatchAll ( ) {
		return items == null;
	}

	/**
	 * Creates an EventQuery that combines the criteria of both (UNION)
	 * With regards to the "until" reference, both need to have the same value (or both need to be unset).
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

	public static final EventQuery matchNone (  ) {
		return new EventQuery();
	}

	public static final EventQuery matchAll (  ) {
		return new EventQuery(null, null);
	}
	
	public static final EventQuery forEvents ( EventTypesFilter eventTypes, Tags tags ) {
		return forEvents(new EventQueryItem(eventTypes, tags));
	}
	
	public static final EventQuery forEvents ( EventQueryItem queryItem ) {
		return new EventQuery(Collections.singletonList(queryItem), null);
	}

	public EventQuery until ( EventReference until ) {
		return new EventQuery(items, until);
	}
	
	public EventQuery untilIfEarlier ( EventReference newUntil ) {
		if ( newUntil != null  ) {
			if (this.until == null ) { // we don't have an until value, so we just take on the new one
				return new EventQuery(items, newUntil);
			} else if ( this.until.position() > newUntil.position() ) { // newUntil asks to stop earlier in the stream
				return new EventQuery(items, newUntil);
			} else { // our until value is earlier in the stream than the new one, not expanding our query
				return this;
			}
		} else {
			return this;
		}
	}

}
