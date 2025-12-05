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
package org.sliceworkz.eventstore.projection;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Thrown when an error occurs during projection execution.
 * <p>
 * This exception wraps any throwable that occurs during projection processing, including:
 * <ul>
 *   <li>Exceptions thrown from {@link Projection#when(org.sliceworkz.eventstore.events.Event)}</li>
 *   <li>Exceptions thrown from {@link BatchAwareProjection#beforeBatch()}</li>
 *   <li>Exceptions thrown while querying or streaming events</li>
 * </ul>
 * <p>
 * The original exception can be retrieved via {@link #getCause()}.
 * <p>
 * When a projection fails, the {@link Projector} will call {@link BatchAwareProjection#cancelBatch()}
 * if the projection implements that interface, and then throw this exception to the caller.
 *
 * @see Projector
 * @see Projection
 * @see BatchAwareProjection
 */
public class ProjectorException extends RuntimeException {

	private EventReference eventReference;
	
	/**
	 * Creates a new ProjectorException wrapping the given throwable.
	 * <p>
	 * The wrapped throwable will be set as the cause and can be retrieved via {@link #getCause()}.
	 * The event reference identifies which event was being processed when the failure occurred,
	 * enabling precise error tracking and recovery strategies.
	 *
	 * @param wrapped the underlying exception that caused the projection to fail
	 * @param eventReference the reference to the event that was being processed when the exception occurred, or null if not applicable
	 */
	public ProjectorException( Throwable wrapped, EventReference eventReference ) {
		super(wrapped);
		this.eventReference = eventReference;
	}

	/**
	 * Returns the reference to the event that was being processed when this exception occurred.
	 * <p>
	 * This reference can be used to:
	 * <ul>
	 *   <li>Identify the exact event that caused the projection failure</li>
	 *   <li>Log detailed error information for troubleshooting</li>
	 *   <li>Implement retry strategies starting from a known position</li>
	 *   <li>Skip problematic events in error handling logic</li>
	 * </ul>
	 *
	 * @return the event reference of the event being processed, or null if the exception occurred outside event processing
	 */
	public EventReference getEventReference ( ) {
		return eventReference;
	}

}