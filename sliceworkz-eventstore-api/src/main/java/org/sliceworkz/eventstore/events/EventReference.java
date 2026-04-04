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
package org.sliceworkz.eventstore.events;

/**
 * A unique reference to an event, combining its global ID and position within its stream.
 * <p>
 * EventReference serves dual purposes:
 * <ul>
 *   <li><strong>Identity:</strong> The {@link EventId} provides a globally unique identifier</li>
 *   <li><strong>Ordering:</strong> The tx/position/index combination indicates where the event appears in its stream</li>
 * </ul>
 * <p>
 * Event references are crucial for implementing Dynamic Consistency Boundaries (DCB). When appending
 * events with optimistic locking via {@link org.sliceworkz.eventstore.stream.AppendCriteria}, the
 * reference to the last known relevant event is used to detect conflicts.
 * <p>
 * Transactions (tx) are an incrementing number without business meaning.
 * Positions start at 1 (not 0) and increment sequentially within a stream.
 * Within one transaction, multiple positions can exist if these events were added as a single unit of work.
 * <p>
 * The index field distinguishes multiple events that originate from the same stored event through
 * upcasting. When a legacy event is upcasted into multiple new events, each receives the same
 * id/position/tx but a different index (0, 1, 2, ...). For non-upcasted events, the index is always 0.
 * The index participates in ordering: events are compared by (tx, position, index).
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
 * @param tx the transaction during which this event was appended. Primary sort criterion before position
 * @param index the sub-event index within a single stored event (0 for non-upcasted events, 0..N for upcasted sub-events)
 * @see Event
 * @see EventId
 * @see org.sliceworkz.eventstore.stream.AppendCriteria
 */
public record EventReference ( EventId id, long position, long tx, int index ) {

	/**
	 * Constructs an EventReference with validation.
	 * <p>
	 * All parameters are required and position must be greater than 0.
	 *
	 * @param id the event ID (required)
	 * @param position the position in the stream (required, must be &gt; 0)
	 * @param tx the transaction number (required)
	 * @param index the sub-event index (must be &ge; 0)
	 * @throws IllegalArgumentException if id is null, position is &le; 0, or index is &lt; 0
	 */
	public EventReference ( EventId id, long position, long tx, int index ) {
		if ( id == null ) {
			throw new IllegalArgumentException("event id in reference cannot be null");
		}
		if ( position <= 0 ) {
			throw new IllegalArgumentException("position %d is invalid, should be larger than 0".formatted(position));
		}
		if ( index < 0 ) {
			throw new IllegalArgumentException("index %d is invalid, should be 0 or larger".formatted(index));
		}

		this.id = id;
		this.position = position;
		this.tx = tx;
		this.index = index;
	}

	/**
	 * Checks if this event happened before another event.
	 * <p>
	 * Events are ordered primarily by transaction (tx), secondarily by position within the transaction,
	 * and finally by index within the same stored event (for upcasted sub-events).
	 *
	 * @param other the event reference to compare against
	 * @return true if this event happened before the other event, false otherwise
	 */
	public boolean happenedBefore ( EventReference other ) {
		if ( this.tx != other.tx ) return this.tx < other.tx;
		if ( this.position != other.position ) return this.position < other.position;
		return this.index < other.index;
	}

	/**
	 * Checks if this event happened after another event.
	 * <p>
	 * Events are ordered primarily by transaction (tx), secondarily by position within the transaction,
	 * and finally by index within the same stored event (for upcasted sub-events).
	 *
	 * @param other the event reference to compare against
	 * @return true if this event happened after the other event, false otherwise
	 */
	public boolean happenedAfter ( EventReference other ) {
		if ( this.tx != other.tx ) return this.tx > other.tx;
		if ( this.position != other.position ) return this.position > other.position;
		return this.index > other.index;
	}

	/**
	 * Creates an EventReference from an existing event ID, position, transaction, and index.
	 *
	 * @param id the event ID
	 * @param position the position in the stream (must be &gt; 0)
	 * @param tx the transaction number
	 * @param index the sub-event index (0 for non-upcasted events)
	 * @return a new EventReference
	 * @throws IllegalArgumentException if id is null, position is &le; 0, or index is &lt; 0
	 */
	public static EventReference of ( EventId id, long position, long tx, int index ) {
		return new EventReference(id, position, tx, index);
	}

	/**
	 * Creates an EventReference from an existing event ID, position, and transaction with index 0.
	 *
	 * @param id the event ID
	 * @param position the position in the stream (must be &gt; 0)
	 * @param tx the transaction number
	 * @return a new EventReference with index 0
	 * @throws IllegalArgumentException if id is null, position is null, or position is &le; 0
	 */
	public static EventReference of ( EventId id, long position, long tx ) {
		return new EventReference(id, position, tx, 0);
	}

	/**
	 * Creates a new EventReference with a randomly generated ID and index 0.
	 * <p>
	 * This is typically used internally when appending events.
	 *
	 * @param position the position in the stream (must be &gt; 0)
	 * @param tx the transaction number
	 * @return a new EventReference with a generated UUID-based ID and index 0
	 * @throws IllegalArgumentException if position is null or &le; 0
	 */
	public static EventReference create ( long position, long tx ) {
		return of ( EventId.create(), position, tx, 0 );
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

	/**
	 * Creates a new EventReference with the same id, position, and tx but a different index.
	 *
	 * @param index the new sub-event index
	 * @return a new EventReference with the specified index
	 */
	public EventReference withIndex ( int index ) {
		return new EventReference(this.id, this.position, this.tx, index);
	}

	/**
	 * Returns a string representation of this EventReference in the format:
	 * {@code <id>:<tx>:<position>:<index>}
	 * <p>
	 * If the index is 0, the {@code :<index>} suffix is omitted.
	 *
	 * @return the string representation
	 */
	@Override
	public String toString ( ) {
		if ( index == 0 ) {
			return id.value() + ":" + tx + ":" + position;
		}
		return id.value() + ":" + tx + ":" + position + ":" + index;
	}

	/**
	 * Parses an EventReference from its string representation.
	 * <p>
	 * Expected format: {@code <id>:<tx>:<position>} or {@code <id>:<tx>:<position>:<index>}
	 * <p>
	 * When the index part is omitted, it defaults to 0.
	 *
	 * @param value the string to parse
	 * @return the parsed EventReference
	 * @throws IllegalArgumentException if the value is null, blank, or cannot be parsed
	 */
	public static EventReference fromString ( String value ) {
		if ( value == null || value.isBlank() ) {
			throw new IllegalArgumentException("EventReference string cannot be null or blank");
		}
		String[] parts = value.split(":");
		if ( parts.length < 3 || parts.length > 4 ) {
			throw new IllegalArgumentException("Invalid EventReference format: '%s'. Expected <id>:<tx>:<position> or <id>:<tx>:<position>:<index>".formatted(value));
		}
		try {
			EventId id = new EventId(parts[0]);
			long tx = Long.parseLong(parts[1]);
			long position = Long.parseLong(parts[2]);
			int index = parts.length == 4 ? Integer.parseInt(parts[3]) : 0;
			return new EventReference(id, position, tx, index);
		} catch ( IllegalArgumentException e ) {
			if ( e instanceof NumberFormatException ) {
				throw new IllegalArgumentException("Invalid EventReference format: '%s'. Cannot parse numeric components".formatted(value), e);
			}
			throw e;
		}
	}

}
