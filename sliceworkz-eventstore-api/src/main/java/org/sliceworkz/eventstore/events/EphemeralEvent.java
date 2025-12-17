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

import java.time.LocalDateTime;

import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A lightweight event representation before it is persisted to an event stream.
 * <p>
 * EphemeralEvents are used when creating new events to append to a stream. Unlike a full {@link Event},
 * an EphemeralEvent does not have:
 * <ul>
 *   <li>A stream association (assigned when appended)</li>
 *   <li>A reference with ID and position (generated during append)</li>
 *   <li>A timestamp (set to the append time)</li>
 * </ul>
 * <p>
 * When an EphemeralEvent is appended to an {@link org.sliceworkz.eventstore.stream.EventStream},
 * it is converted to a full {@link Event} with all metadata populated.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create an ephemeral event
 * EphemeralEvent<CustomerEvent> event = EphemeralEvent.of(
 *     new CustomerRegistered("John Doe"),
 *     Tags.of("customer", "123", "region", "EU")
 * );
 *
 * // Append to a stream (converts to full Event)
 * stream.append(AppendCriteria.none(), event);
 * }</pre>
 *
 * @param <DOMAIN_EVENT_TYPE> the type of the domain event data
 * @param type the event type
 * @param data the actual domain event data (typically a record implementing a sealed interface)
 * @param tags the tags attached to this event for querying and consistency boundaries
 * @see Event
 * @see Tags
 */
public record EphemeralEvent<DOMAIN_EVENT_TYPE> ( EventType type, DOMAIN_EVENT_TYPE data, Tags tags, String idempotencyKey ) {

	/**
	 * Constructs an EphemeralEvent with validation of all required fields.
	 * <p>
	 * All parameters are required and must not be null. Prefer using the static factory method
	 * {@link #of(Object, Tags)} which automatically determines the event type from the data.
	 *
	 * @param type the event type (required)
	 * @param data the domain event data (required)
	 * @param tags the tags for this event (required, use Tags.none() if no tags)
	 * @throws IllegalArgumentException if any required parameter is null
	 */
	public EphemeralEvent ( EventType type, DOMAIN_EVENT_TYPE data, Tags tags, String idempotencyKey ) {
		if ( type == null ) {
			throw new IllegalArgumentException("type is required on event");
		}
		if ( data == null ) {
			throw new IllegalArgumentException("data is required on event");
		}
		if ( tags == null ) {
			throw new IllegalArgumentException("tags is required on event");
		}
		this.type = type;
		this.data = data;
		this.tags = tags;
		this.idempotencyKey = idempotencyKey;
	}

	/**
	 * Creates a copy of this ephemeral event with different tags.
	 * <p>
	 * All other properties remain unchanged. This is useful for enriching events with additional
	 * tags before appending them to a stream.
	 *
	 * @param tags the new tags to attach to the event
	 * @return a new EphemeralEvent instance with the specified tags
	 */
	public EphemeralEvent<DOMAIN_EVENT_TYPE> withTags ( Tags tags ) {
		return new EphemeralEvent<>(type, data, tags, idempotencyKey);
	}

	/**
	 * Creates a copy of this ephemeral event with a different idempotency key.
	 * <p>
	 * All other properties remain unchanged. An idempotency key ensures that the same event
	 * is not appended multiple times if the append operation is retried. When an event with
	 * an idempotency key is appended a second time, the storage will silently ignore it
	 * rather than creating a duplicate event.
	 * <p>
	 * <b>Important:</b> When appending multiple events in a single batch, none of them may
	 * have an idempotency key. Idempotency keys can only be used with single-event appends.
	 *
	 * @param idempotencyKey the idempotency key to attach to the event, or null for no idempotency check
	 * @return a new EphemeralEvent instance with the specified idempotency key
	 * @see org.sliceworkz.eventstore.stream.EventStream#append(org.sliceworkz.eventstore.stream.AppendCriteria, java.util.List)
	 */
	public EphemeralEvent<DOMAIN_EVENT_TYPE> withIdempotencyKey ( String idempotencyKey ) {
		return new EphemeralEvent<>(type, data, tags, idempotencyKey);
	}

	/**
	 * Static factory method for creating an ephemeral event.
	 * <p>
	 * The event type is automatically determined from the data object's class.
	 * This is the preferred way to create ephemeral events for appending.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of the domain event data
	 * @param data the domain event data (required)
	 * @param tags the tags to attach to the event
	 * @return a new EphemeralEvent instance ready to be appended
	 */
	public static final <DOMAIN_EVENT_TYPE> EphemeralEvent<DOMAIN_EVENT_TYPE> of ( DOMAIN_EVENT_TYPE data, Tags tags) {
		return new EphemeralEvent<DOMAIN_EVENT_TYPE>(EventType.of(data), data, tags, null);
	}

	/**
	 * Converts this ephemeral event to a full {@link Event} by adding metadata.
	 * <p>
	 * This method is used internally when appending events to a stream. It assigns the stream,
	 * reference (ID and position), and timestamp to create a persisted event.
	 *
	 * @param stream the event stream this event will belong to
	 * @param reference the unique reference for this event
	 * @param timestamp the timestamp when this event is being persisted
	 * @return a new Event instance with all metadata populated
	 */
	public Event<DOMAIN_EVENT_TYPE> positionAt ( EventStreamId stream, EventReference reference, LocalDateTime timestamp ) {
		return Event.of(stream, reference, type, type, data, tags, timestamp);
	}
	
}
