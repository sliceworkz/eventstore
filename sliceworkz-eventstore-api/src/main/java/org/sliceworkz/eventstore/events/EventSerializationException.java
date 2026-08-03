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
 * Thrown when an event payload cannot be written to its stored form.
 * <p>
 * This is the append-side counterpart of {@link EventDeserializationException}, and it surfaces from
 * {@link org.sliceworkz.eventstore.stream.EventSink#append} while the event being appended is converted
 * to JSON. The event has <em>not</em> been stored when this is thrown.
 *
 * <h2>What it means, and what it does not</h2>
 * A serialization failure is a property of the payload class, not of the store: the same event will fail
 * the same way on every attempt, on every backend. <strong>It is never worth retrying.</strong> That is
 * the distinction this type exists to make — a
 * {@link org.sliceworkz.eventstore.spi.EventStorageException} from the same {@code append} call may well
 * be transient (a dropped connection, a pool timeout) and is worth retrying, and before this type existed
 * the two could only be told apart by matching on message text.
 * <p>
 * The usual causes are a record component Jackson cannot write, a derived accessor that emits a property,
 * or a custom serializer rejecting a value. The underlying Jackson failure is always available via
 * {@link #getCause()}; it carries the field path and is normally the most informative part.
 *
 * <h2>Handling</h2>
 * <pre>{@code
 * try {
 *     stream.append(criteria, Event.of(payload, tags));
 * } catch (OptimisticLockingException e) {
 *     // a new relevant fact -- re-read, re-decide, retry
 * } catch (EventSerializationException e) {
 *     // permanent: this payload cannot be stored. Alert, do not retry.
 *     LOGGER.error("unstorable event of type {}", e.getEventType(), e);
 *     throw e;
 * } catch (EventStorageException e) {
 *     // possibly transient -- retry with backoff
 * }
 * }</pre>
 *
 * @see EventDeserializationException
 * @see org.sliceworkz.eventstore.spi.EventStorageException
 */
public class EventSerializationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient EventType eventType;

	/**
	 * Constructs a new EventSerializationException for the given event type.
	 *
	 * @param eventType the type of the event that could not be serialized, or null if it could not be determined
	 * @param message the detail message explaining what failed
	 * @param cause the underlying failure, typically a Jackson exception
	 */
	public EventSerializationException ( EventType eventType, String message, Throwable cause ) {
		super(message, cause);
		this.eventType = eventType;
	}

	/**
	 * Returns the type of the event that could not be serialized.
	 * <p>
	 * This is {@link EventType#of(Object)} of the payload — that is, the simple name of its class, which is
	 * also the name it would have been stored under.
	 *
	 * @return the event type, or null when the payload's type could not be determined (a null payload)
	 */
	public EventType getEventType ( ) {
		return eventType;
	}

}
