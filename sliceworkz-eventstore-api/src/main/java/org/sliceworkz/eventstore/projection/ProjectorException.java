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
package org.sliceworkz.eventstore.projection;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * Thrown when a projection run fails.
 * <p>
 * Wraps whatever the run caught: an exception from
 * {@link Projection#when(org.sliceworkz.eventstore.events.Event)}, for an event of the main query and
 * for a savepoint found by {@link Projection#initQuery()} alike; one from
 * {@link BatchAwareProjection#beforeBatch()} or {@link BatchAwareProjection#afterBatch(java.util.Optional)};
 * one from reading a page of events, an {@link org.sliceworkz.eventstore.events.EventDeserializationException}
 * included. The original exception is {@link #getCause()}, and its type is the only thing that separates
 * a poison event from a store that was briefly unavailable.
 * <p>
 * When a projection fails, the {@link Projector} calls {@link BatchAwareProjection#cancelBatch()} if the
 * projection implements that interface, takes its cursor back to where the failed batch started, and
 * then throws this exception to the caller.
 *
 * <h2>Which event {@link #getEventReference()} names</h2>
 * The last event handed to the projection's {@code when} before the failure, which is not always the
 * event that failed:
 * <ul>
 *   <li>when {@code when} itself threw, it is that event;</li>
 *   <li>when a page could not be read, no event of that page reached the projection, so it is the last
 *       event of an <em>earlier</em> batch, or null when there was none. The event that could not be read
 *       is named by the cause's own
 *       {@link org.sliceworkz.eventstore.events.EventDeserializationException#getReference() reference};</li>
 *   <li>when {@code beforeBatch} or {@code afterBatch} threw, it is the last event handled before the
 *       hook ran, which is the last event of the previous batch or of the batch being committed.</li>
 * </ul>
 * The alternative -- reporting a reference only when it is the failing event -- loses because a
 * projection recording its own position wants the last handled event on every failure, whatever the
 * hook that failed. So the reference is always the last handled event, and this is the one place that
 * says so.
 *
 * @see Projector
 * @see Projection
 * @see BatchAwareProjection
 */
public class ProjectorException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final EventReference eventReference;

	/**
	 * Creates a new ProjectorException wrapping the given throwable.
	 * <p>
	 * The wrapped throwable is the cause, retrievable via {@link #getCause()}.
	 *
	 * @param wrapped the underlying exception that caused the projection to fail
	 * @param eventReference the reference of the last event handed to the projection before the failure,
	 *        or null if none was
	 */
	public ProjectorException( Throwable wrapped, EventReference eventReference ) {
		super(wrapped);
		this.eventReference = eventReference;
	}

	/**
	 * The reference of the last event handed to the projection before the failure.
	 * <p>
	 * This is the event that failed only when {@link Projection#when} threw. For a page that could not be
	 * read, or a batch hook that threw, it is the event handled last before that, which is the last
	 * event of an earlier batch -- see the class documentation for the cases. It is null when no event
	 * had been handed to the projection yet.
	 * <p>
	 * Because the projector takes its cursor back to where the failed batch started, this reference
	 * is not where the next run resumes: it is what the projection had seen, for a projection that
	 * records its own position or for a log line saying how far the run got.
	 *
	 * @return the reference of the last event handed to the projection, or null if none was
	 */
	public EventReference getEventReference ( ) {
		return eventReference;
	}

}