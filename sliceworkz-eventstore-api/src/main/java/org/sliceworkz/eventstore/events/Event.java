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
 * A persisted event in the event store, containing both the domain event data and metadata.
 * <p>
 * Events are immutable records that represent business facts that have occurred in a domain.
 * Each event is uniquely identified by a {@link EventReference} and belongs to a specific {@link EventStreamId}.
 * Events are typically created as {@link EphemeralEvent}s before being appended to a stream, at which point
 * they receive their reference, timestamp, and stream association.
 * <p>
 * Events support tagging via {@link Tags}, which enables:
 * <ul>
 *   <li>Dynamic querying across different event types</li>
 *   <li>Implementation of Dynamic Consistency Boundaries (DCB)</li>
 *   <li>Flexible event correlation and retrieval</li>
 * </ul>
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Creating an ephemeral event to append
 * EphemeralEvent<CustomerEvent> ephemeralEvent = Event.of(
 *     new CustomerRegistered("John Doe"),
 *     Tags.of("customer", "123", "region", "EU")
 * );
 *
 * // After appending, it becomes a full Event with metadata
 * stream.append(AppendCriteria.none(), ephemeralEvent);
 * Event<CustomerEvent> persistedEvent = stream.query(EventQuery.matchAll()).findFirst().get();
 * }</pre>
 *
 * @param <DOMAIN_EVENT_TYPE> the type of the domain event data
 * @param stream the event stream this event belongs to
 * @param type the current runtime type of the event (may differ from storedType if upcasted)
 * @param storedType the event type as it was stored in the database
 * @param reference the unique reference containing ID and position in the stream
 * @param data the actual domain event data (typically a record implementing a sealed interface)
 * @param tags the tags attached to this event for querying and consistency boundaries
 * @param timestamp the time when this event was persisted to the store
 * @see EphemeralEvent
 * @see EventReference
 * @see Tags
 */
public record Event<DOMAIN_EVENT_TYPE> ( EventStreamId stream, EventType type, EventType storedType, EventReference reference, DOMAIN_EVENT_TYPE data, Tags tags, LocalDateTime timestamp ) {

	/**
	 * Constructs an Event with validation of all required fields.
	 * <p>
	 * All parameters are required and must not be null. This constructor is typically not called directly;
	 * instead, use the static factory method {@link #of(EventStreamId, EventReference, EventType, EventType, Object, Tags, LocalDateTime)}
	 * or create {@link EphemeralEvent}s that are converted to Events upon appending.
	 *
	 * @param stream the event stream this event belongs to (required)
	 * @param type the current runtime type of the event (required)
	 * @param storedType the event type as stored in the database (required)
	 * @param reference the unique reference for this event (required)
	 * @param data the domain event data (required)
	 * @param tags the tags for this event (required, use Tags.none() if no tags)
	 * @param timestamp the timestamp when this event was persisted
	 * @throws IllegalArgumentException if any required parameter is null
	 */
	public Event ( EventStreamId stream, EventType type, EventType storedType, EventReference reference, DOMAIN_EVENT_TYPE data, Tags tags, LocalDateTime timestamp ) {
		if ( stream == null ) {
			throw new IllegalArgumentException("stream is required on event");
		}
		if ( type == null ) {
			throw new IllegalArgumentException("type is required on event");
		}
		if ( storedType == null ) {
			throw new IllegalArgumentException("storedType is required on event");
		}
		if ( reference == null ) {
			throw new IllegalArgumentException("reference is required on event");
		}
		if ( data == null ) {
			throw new IllegalArgumentException("data is required on event");
		}
		if ( tags == null ) {
			throw new IllegalArgumentException("tags is required on event");
		}
		this.stream = stream;
		this.type = type;
		this.storedType = storedType;
		this.reference = reference;
		this.data = data;
		this.tags = tags;
		this.timestamp = timestamp;
	}

	/**
	 * Creates a copy of this event with different tags.
	 * <p>
	 * All other properties remain unchanged. This is useful for enriching events with additional
	 * tags during processing or querying.
	 *
	 * @param tags the new tags to attach to the event
	 * @return a new Event instance with the specified tags
	 */
	public Event<DOMAIN_EVENT_TYPE> withTags ( Tags tags ) {
		return new Event<>(stream, type, storedType, reference, data, tags, timestamp);
	}

	/**
	 * Casts this event to a more specific domain event type.
	 * <p>
	 * This method enables widening the type parameter when working with event hierarchies,
	 * particularly useful when querying events from streams with multiple event type hierarchies.
	 * For example, when a stream contains {@code LearningDomainEvent} but you need to process
	 * it as a more specific type like {@code StudentDomainEvent} or {@code CourseDomainEvent}.
	 * <p>
	 * The cast is safe because {@code OTHER_DOMAIN_EVENT_TYPE} is constrained to extend
	 * {@code DOMAIN_EVENT_TYPE}, ensuring type compatibility at compile time.
	 *
	 * <h2>Example Usage:</h2>
	 * <pre>{@code
	 * // Stream contains LearningDomainEvent
	 * EventStream<LearningDomainEvent> stream = eventStore.getEventStream(...);
	 *
	 * // Query returns Event<LearningDomainEvent>
	 * stream.query(EventQuery.matchAll())
	 *     .forEach(event -> {
	 *         // Cast to more specific type for processing
	 *         Event<StudentDomainEvent> studentEvent = event.cast();
	 *         student.when(studentEvent);
	 *     });
	 * }</pre>
	 *
	 * @param <OTHER_DOMAIN_EVENT_TYPE> the more specific domain event type to cast to,
	 *                                  must extend DOMAIN_EVENT_TYPE
	 * @return this event with the type parameter cast to OTHER_DOMAIN_EVENT_TYPE
	 */
	@SuppressWarnings("unchecked")
	public <OTHER_DOMAIN_EVENT_TYPE extends DOMAIN_EVENT_TYPE> Event<OTHER_DOMAIN_EVENT_TYPE> cast ( ) {
		return (Event<OTHER_DOMAIN_EVENT_TYPE>) this;
	}

	/**
	 * Static factory method for creating a fully-specified Event.
	 * <p>
	 * This method is primarily used internally when retrieving events from storage.
	 * For creating new events to append, use {@link #of(Object, Tags)} to create an {@link EphemeralEvent} instead.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of the domain event data
	 * @param stream the event stream this event belongs to
	 * @param reference the unique reference for this event
	 * @param type the current runtime type of the event
	 * @param storedType the event type as stored in the database
	 * @param data the domain event data
	 * @param tags the tags for this event
	 * @param timestamp the timestamp when this event was persisted
	 * @return a new Event instance
	 */
	public static final <DOMAIN_EVENT_TYPE> Event<DOMAIN_EVENT_TYPE> of ( EventStreamId stream, EventReference reference, EventType type, EventType storedType, DOMAIN_EVENT_TYPE data, Tags tags, LocalDateTime timestamp ) {
		return new Event<DOMAIN_EVENT_TYPE>(stream, type, storedType, reference, data, tags, timestamp);
	}

	/**
	 * Convenience factory method for creating an ephemeral event to be appended.
	 * <p>
	 * Ephemeral events are lightweight representations that don't yet have a stream, reference, or timestamp.
	 * These are converted to full Events when appended to a stream.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of the domain event data
	 * @param data the domain event data
	 * @param tags the tags to attach to the event
	 * @return a new EphemeralEvent instance ready to be appended
	 * @see EphemeralEvent
	 */
	public static final <DOMAIN_EVENT_TYPE> EphemeralEvent<DOMAIN_EVENT_TYPE> of ( DOMAIN_EVENT_TYPE data, Tags tags) {
		return EphemeralEvent.of(data, tags);
	}

	
}
