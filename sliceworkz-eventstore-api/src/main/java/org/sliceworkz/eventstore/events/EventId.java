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

import java.util.UUID;

/**
 * Unique identifier for an event.
 * <p>
 * Each event in the store has a globally unique ID, generated as a UUIDv7. The EventId is part of
 * the {@link EventReference} which combines the ID with the event's position in its stream.
 * <p>
 * Event IDs are immutable and are automatically generated when events are appended to a stream.
 * Since version 0.8.0, IDs are generated using UUIDv7 (time-ordered) instead of random UUID v4,
 * which improves B-tree index performance for append-only workloads.
 * <p>
 * The default {@link #create()} method generates a random UUIDv4, suitable for in-memory
 * and testing use. Storage implementations may generate their own EventIds using more robust
 * generators (e.g., UUIDv7 with monotonic counters, database-native UUIDv7).
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Typically generated automatically when appending
 * EventId id = EventId.create();  // Generates a new UUIDv7
 *
 * // Or create from existing ID string
 * EventId id = EventId.of("550e8400-e29b-41d4-a716-446655440000");
 * }</pre>
 *
 * @param value the unique identifier string (typically a UUID)
 * @see EventReference
 * @see Event
 */
public record EventId ( String value ) {

	/**
	 * Constructs an EventId with validation.
	 * <p>
	 * The value must not be null or empty/blank.
	 *
	 * @param value the unique identifier string (required, non-blank)
	 * @throws IllegalArgumentException if value is null or blank
	 */
	public EventId ( String value ) {

		if ( value == null || "".equals(value.strip()) ) {
			throw new IllegalArgumentException();
		}

		this.value = value;
	}

	/**
	 * Generates a new EventId using a random UUIDv4.
	 * <p>
	 * Suitable for in-memory storage and testing. Production storage implementations
	 * (e.g., PostgreSQL) should generate their own EventIds using UUIDv7 generators
	 * for better B-tree index locality.
	 *
	 * @return a new EventId with a random UUID as its value
	 */
	public static EventId create ( ) {
		return new EventId ( UUID.randomUUID().toString() );
	}

	/**
	 * Creates an EventId from a string value, with null-safe handling.
	 * <p>
	 * If the value is null or blank, this method returns null instead of throwing an exception.
	 *
	 * @param value the unique identifier string (can be null or blank)
	 * @return an EventId with the specified value, or null if the value is null/blank
	 */
	public static EventId of ( String value ) {
		return ( value == null || "".equals(value.strip()) ) ? null : new EventId ( value );
	}


}
