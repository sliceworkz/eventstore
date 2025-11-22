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
package org.sliceworkz.eventstore.events;

/**
 * A unique reference to an event, combining its global ID and position within its stream.
 * <p>
 * EventReference serves dual purposes:
 * <ul>
 *   <li><strong>Identity:</strong> The {@link EventId} provides a globally unique identifier</li>
 *   <li><strong>Ordering:</strong> The position indicates where the event appears in its stream</li>
 * </ul>
 * <p>
 * Event references are crucial for implementing Dynamic Consistency Boundaries (DCB). When appending
 * events with optimistic locking via {@link org.sliceworkz.eventstore.stream.AppendCriteria}, the
 * reference to the last known relevant event is used to detect conflicts.
 * <p>
 * Positions start at 1 (not 0) and increment sequentially within a stream.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Query events and get the last reference
 * List<Event<CustomerEvent>> events = stream.query(
 *     EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123"))
 * ).toList();
 *
 * EventReference lastRef = events.getLast().reference();
 *
 * // Use the reference for optimistic locking
 * stream.append(
 *     AppendCriteria.of(someQuery, Optional.of(lastRef)),
 *     newEvent
 * );
 * }</pre>
 *
 * @param id the globally unique event identifier
 * @param position the sequential position of the event within its stream (starts at 1)
 * @see Event
 * @see EventId
 * @see org.sliceworkz.eventstore.stream.AppendCriteria
 */
public record EventReference ( EventId id, Long position ) {

	/**
	 * Constructs an EventReference with validation.
	 * <p>
	 * Both parameters are required and position must be greater than 0.
	 *
	 * @param id the event ID (required)
	 * @param position the position in the stream (required, must be &gt; 0)
	 * @throws IllegalArgumentException if id is null, position is null, or position is &le; 0
	 */
	public EventReference ( EventId id, Long position  ) {
		if ( id == null ) {
			throw new IllegalArgumentException("event id in reference cannot be null");
		}
		if ( position == null ) {
			throw new IllegalArgumentException("position in reference cannot be null");
		} else if ( position <= 0 ) {
			throw new IllegalArgumentException("position %d is invalid, should be larger than 0".formatted(position));
		}

		this.id = id;
		this.position = position;
	}

	/**
	 * Creates an EventReference from an existing event ID and position.
	 *
	 * @param id the event ID
	 * @param position the position in the stream (must be &gt; 0)
	 * @return a new EventReference
	 * @throws IllegalArgumentException if id is null, position is null, or position is &le; 0
	 */
	public static EventReference of ( EventId id, long position ) {
		return new EventReference(id, position);
	}

	/**
	 * Creates a new EventReference with a randomly generated ID.
	 * <p>
	 * This is typically used internally when appending events.
	 *
	 * @param position the position in the stream (must be &gt; 0)
	 * @return a new EventReference with a generated UUID-based ID
	 * @throws IllegalArgumentException if position is null or &le; 0
	 */
	public static EventReference create ( long position ) {
		return of ( EventId.create(), position );
	}

	/**
	 * Returns null to represent the absence of a reference.
	 * <p>
	 * This is used in {@link org.sliceworkz.eventstore.stream.AppendCriteria} when no last event reference
	 * is expected (e.g., when appending to an empty stream or when no optimistic locking is needed).
	 *
	 * @return null
	 */
	public static EventReference none ( ) {
		return null;
	}

}
