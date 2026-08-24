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

import java.util.Optional;

/**
 * Thrown when a stored event cannot be read back through the event types a stream was opened with.
 *
 * <h2>What it means, and what it does not</h2>
 * <strong>This is a poison event, not a broken store.</strong> The storage read succeeded — the row is
 * there, and the bytes came back. What failed is the conversion of one event's payload into a domain
 * object, which makes it a property of that event plus this stream's type mappings. Reading it again
 * changes nothing, and neither does reading it from another instance: <strong>it is never worth
 * retrying.</strong> A {@link org.sliceworkz.eventstore.spi.EventStorageException} from the same call
 * may well be transient and is worth retrying, and that distinction is the reason this type exists.
 * <p>
 * It is also not necessarily a bug. The realistic causes are configuration and history:
 * <ul>
 *   <li>the stream was opened without the event root class that covers this stored type, so nothing
 *       knows how to read it — {@code getEventType()} names the type that has no mapping;</li>
 *   <li>the record has since lost a component the stored JSON still carries (the store enables
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES} deliberately), or an event class was renamed, so the old
 *       name has no current class — see "Event type names are wire format" in the project docs;</li>
 *   <li>an {@link Upcast} threw on legacy data that does not satisfy a current validation rule.</li>
 * </ul>
 *
 * <h2>Where it surfaces</h2>
 * Deserialization is lazy: it happens as the {@link java.util.stream.Stream} returned by
 * {@code query()} is consumed, so this is thrown from the caller's terminal operation, not from
 * {@code query()} itself. It also surfaces from
 * {@link org.sliceworkz.eventstore.stream.EventSink#append} (which reads the appended events back) and
 * from {@link org.sliceworkz.eventstore.stream.EventSource#getEventById}.
 * <p>
 * Through a {@link org.sliceworkz.eventstore.projection.Projector} it arrives wrapped: {@code run()}
 * throws {@link org.sliceworkz.eventstore.projection.ProjectorException}, whose {@code getCause()} is
 * this exception. Note that
 * {@link org.sliceworkz.eventstore.projection.ProjectorException#getEventReference()} then names the
 * last event the projection successfully <em>handled</em> — the offending event never reached it, so it
 * cannot be the one reported there. {@link #getReference()} is what names the event that failed.
 *
 * <h2>Handling: skipping a poison event</h2>
 * <pre>{@code
 * try {
 *     projector.run();
 * } catch (ProjectorException e) {
 *     if ( e.getCause() instanceof EventDeserializationException poison ) {
 *         EventReference ref = poison.getReference().orElseThrow();
 *         deadLetter(ref, poison.getEventType());
 *         // resume past it: a projector's position is set at build time
 *         projector = Projector.from(stream).towards(projection).startingAfter(ref).build();
 *     }
 * }
 * }</pre>
 * {@link #getReference()} carries an {@link EventReference}, whose {@link EventReference#id()} can be
 * handed to {@link org.sliceworkz.eventstore.stream.EventSource#getEventById} on a stream opened in raw
 * mode ({@code eventStore.getEventStream(EventStreamId.anyContext())}) to inspect the stored JSON
 * without needing a mapping for it.
 *
 * @see EventSerializationException
 * @see org.sliceworkz.eventstore.spi.EventStorageException
 */
public class EventDeserializationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	// Both are serializable records, so this exception survives a process boundary intact -- which
	// matters more here than for most: the type and the reference are the entire diagnostic value of
	// this exception, and an instance that arrives without them says only that something could not be
	// read.
	private final EventType eventType;
	private final EventReference reference;

	/**
	 * Constructs a new EventDeserializationException for the given stored event type, with no reference.
	 * <p>
	 * This is the form the serialization layer throws: it is handed a type and a payload, and does not
	 * know which stored event they came from. The stream layer attaches the reference via
	 * {@link #withReference(EventReference)} before the exception reaches the caller.
	 *
	 * @param eventType the type the event was stored under, never null
	 * @param message the detail message explaining what failed
	 */
	public EventDeserializationException ( EventType eventType, String message ) {
		this(eventType, message, null, null);
	}

	/**
	 * Constructs a new EventDeserializationException for the given stored event type, with no reference.
	 *
	 * @param eventType the type the event was stored under, never null
	 * @param message the detail message explaining what failed
	 * @param cause the underlying failure, typically a Jackson exception or a failure thrown by an
	 *        {@link Upcast}
	 */
	public EventDeserializationException ( EventType eventType, String message, Throwable cause ) {
		this(eventType, message, cause, null);
	}

	/**
	 * Constructs a new EventDeserializationException naming both the stored event type and the event.
	 *
	 * @param eventType the type the event was stored under, never null
	 * @param message the detail message explaining what failed
	 * @param cause the underlying failure, or null
	 * @param reference the reference of the stored event that could not be read, or null if not known
	 */
	public EventDeserializationException ( EventType eventType, String message, Throwable cause, EventReference reference ) {
		super(message, cause);
		this.eventType = eventType;
		this.reference = reference;
	}

	/**
	 * Returns the type the event was stored under.
	 * <p>
	 * This is the name in storage, which is not necessarily a type any class on the classpath still
	 * claims — a renamed or retired event class is exactly the case where that happens.
	 *
	 * @return the stored event type, never null
	 */
	public EventType getEventType ( ) {
		return eventType;
	}

	/**
	 * Returns the reference of the stored event that could not be read.
	 * <p>
	 * Present whenever the exception reached the caller through a stream operation, which is every path
	 * an application sees. It is empty only when the serialization layer is driven directly.
	 *
	 * @return the reference of the offending stored event, or empty if it is not known
	 */
	public Optional<EventReference> getReference ( ) {
		return Optional.ofNullable(reference);
	}

	/**
	 * Returns a copy of this exception naming the stored event it came from.
	 * <p>
	 * Used by the stream layer, which knows which stored event was being read and the serialization layer
	 * does not. Message, cause and stack trace are carried over, so nothing about the original failure is
	 * lost — only the reference is added.
	 *
	 * @param eventReference the reference of the stored event that could not be read
	 * @return a copy carrying the reference, or this exception unchanged if it already has one
	 */
	public EventDeserializationException withReference ( EventReference eventReference ) {
		if ( this.reference != null ) {
			return this;
		}
		EventDeserializationException copy =
				new EventDeserializationException(eventType, getMessage(), getCause(), eventReference);
		copy.setStackTrace(getStackTrace());
		return copy;
	}

}
