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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.sliceworkz.eventstore.events.EventType;

/**
 * Filter for selecting events based on their type.
 *
 * <p>EventTypesFilter allows you to specify which event types should match a query.
 * An empty set of event types means "match any type" (wildcard filter).
 * A non-empty set means "match if the event type is one of the specified types" (OR condition).
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Match any event type (wildcard)
 * EventTypesFilter anyType = EventTypesFilter.any();
 *
 * // Match a single event type
 * EventTypesFilter singleType = EventTypesFilter.of(CustomerRegistered.class);
 *
 * // Match multiple event types (OR condition)
 * EventTypesFilter multipleTypes = EventTypesFilter.of(
 *     CustomerRegistered.class,
 *     CustomerUpdated.class,
 *     CustomerDeleted.class
 * );
 *
 * // Match from a list of classes
 * List<Class<?>> eventClasses = List.of(OrderPlaced.class, OrderShipped.class);
 * EventTypesFilter fromList = EventTypesFilter.of(eventClasses);
 *
 * // Match every event type of a sealed hierarchy, or of one branch of it
 * EventTypesFilter wholeHierarchy = EventTypesFilter.of(CustomerEvent.class);
 *
 * // Match from a set of EventTypes
 * Set<EventType> eventTypeSet = Set.of(
 *     EventType.of(PaymentReceived.class),
 *     EventType.of(PaymentRefunded.class)
 * );
 * EventTypesFilter fromSet = EventTypesFilter.of(eventTypeSet);
 * }</pre>
 *
 * <p><strong>A sealed interface stands for every event type under it.</strong> An event is stored under
 * the stored name of its record (its simple name, or its {@link org.sliceworkz.eventstore.events.EventName}),
 * never under the name of an interface it implements, so
 * {@link #of(List)} resolves a sealed interface into the event types it permits, recursively: the root
 * of a hierarchy names all of it, a nested interface names its own branch. The filter then holds those
 * names only. A filter built from {@link EventType}s is literal, since a name says nothing about a
 * hierarchy.
 *
 * @param eventTypes the set of event types to match (empty set means match any type)
 *
 * @see EventQuery
 * @see EventFilterItem
 * @see EventType
 */
public record EventTypesFilter ( Set<EventType> eventTypes ) {

	/**
	 * Tests whether the given event type matches this filter.
	 * An event type matches if the filter is empty (wildcard) or if the event type is in the set of allowed types.
	 *
	 * @param eventType the event type to test
	 * @return true if the event type matches this filter, false otherwise
	 */
	public boolean matches ( EventType eventType ) {
		// if we don't specify specific types, we accept all
		return eventTypes.isEmpty() || eventTypes.contains(eventType);
	}

	/**
	 * Creates a wildcard filter that matches any event type.
	 * This is equivalent to an empty set of event types.
	 *
	 * @return an EventTypesFilter that matches any event type
	 */
	public static final EventTypesFilter any ( ) {
		return of(new Class[] {});
	}

	/**
	 * Creates a filter that matches events of the specified types.
	 * Multiple types represent an OR condition: events match if they are ANY of the specified types.
	 *
	 * @param eventClasses the event classes to match
	 * @return an EventTypesFilter that matches the specified event types
	 */
	public static final EventTypesFilter of ( Class<?>... eventClasses ) {
		return of(Arrays.asList(eventClasses));
	}

	/**
	 * Creates a filter that matches events of the specified types from a list.
	 * Multiple types represent an OR condition: events match if they are ANY of the specified types.
	 * <p>
	 * A sealed interface among the classes is resolved into the event types under it, recursively, so
	 * that the root of a hierarchy names every event type of it and a nested interface names its own
	 * branch; a class is taken by its stored name, exactly as {@link EventType#of(Class)} resolves it. The
	 * resolution happens here, at construction, rather than wherever the filter is matched, because a
	 * filter is matched in several places — the storage query, the store's re-check of the events it
	 * upcasts, a {@code Projector}'s check of the events it is handed, the optimistic-locking check of an
	 * append — and only some of them have the stream's type registrations at hand. Resolving once keeps
	 * them in agreement. The alternative — resolving an interface by name inside the typed serde —
	 * loses because a filter would then hold a name no stored event carries, matched correctly by
	 * whichever path happens to consult the serde and by none of the others.
	 * <p>
	 * A non-sealed interface cannot be resolved, since its implementations cannot be enumerated, and is
	 * refused with an {@link IllegalArgumentException} — the same refusal {@code getEventStream} gives it
	 * as an event root. A filter naming it literally would match nothing, silently.
	 *
	 * @param eventClasses the list of event classes to match; a sealed interface stands for every event
	 *        type under it
	 * @return an EventTypesFilter that matches the specified event types
	 * @throws IllegalArgumentException for an interface that is not sealed
	 */
	public static final EventTypesFilter of ( List<Class<?>> eventClasses ) {
		Set<EventType> eventTypes = new HashSet<>();
		eventClasses.forEach(eventClass -> collectEventTypes(eventClass, eventTypes));
		return new EventTypesFilter(eventTypes);
	}

	private static void collectEventTypes ( Class<?> eventClass, Set<EventType> into ) {
		if ( eventClass.isInterface() ) {
			if ( !eventClass.isSealed() ) {
				throw new IllegalArgumentException("interface %s should be sealed to allow Event Type determination".formatted(eventClass.getName()));
			}
			for ( Class<?> permitted : eventClass.getPermittedSubclasses() ) {
				collectEventTypes(permitted, into);
			}
		} else {
			into.add(EventType.of(eventClass));
		}
	}

	/**
	 * Creates a filter from a set of EventType objects.
	 * Multiple types represent an OR condition: events match if they are ANY of the specified types.
	 *
	 * @param eventTypes the set of event types to match
	 * @return an EventTypesFilter that matches the specified event types
	 */
	public static final EventTypesFilter of ( Set<EventType> eventTypes ) {
		return new EventTypesFilter(eventTypes);
	}
	
}
