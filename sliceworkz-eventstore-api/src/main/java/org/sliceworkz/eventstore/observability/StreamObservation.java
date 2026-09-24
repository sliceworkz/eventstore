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
package org.sliceworkz.eventstore.observability;

/**
 * How a source is observed: the observer its store reports to, and the stream the source reads.
 * <p>
 * Answered by {@link org.sliceworkz.eventstore.stream.EventSource#observation()}, and what lets a
 * {@link org.sliceworkz.eventstore.projection.Projector} report its batches to the same observer as the
 * reads it makes, with nothing to configure on the projector.
 *
 * @param observer the observer, never null
 * @param stream the stream the source reads, never null
 */
public record StreamObservation ( EventStoreObserver observer, StreamInfo stream ) {

	/**
	 * Creates the observation, refusing a missing observer or stream.
	 *
	 * @param observer the observer
	 * @param stream the stream
	 */
	public StreamObservation {
		if ( observer == null ) {
			throw new IllegalArgumentException("observer cannot be null");
		}
		if ( stream == null ) {
			throw new IllegalArgumentException("stream cannot be null");
		}
	}

}
