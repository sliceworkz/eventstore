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

import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Which stream an observation is about.
 * <p>
 * The stream id is the real one, purpose included and uncapped. How many distinct values a dimension may
 * take is a concern of the implementation recording it — a metrics implementation bounds the purpose
 * before it becomes a tag, since every distinct tag value is a series it keeps for good, while a tracer or
 * a log line wants the stream as it is.
 *
 * @param storage the name of the storage the stream reads and writes through
 * @param stream the stream's id, which may be a wildcard for a stream that only reads
 * @param typed whether the stream maps events onto classes, {@code false} for a raw stream
 */
public record StreamInfo ( String storage, EventStreamId stream, boolean typed ) {

	/**
	 * Creates the info, refusing a missing storage name or stream.
	 *
	 * @param storage the name of the storage
	 * @param stream the stream's id
	 * @param typed whether the stream is typed
	 */
	public StreamInfo {
		if ( storage == null ) {
			throw new IllegalArgumentException("storage cannot be null");
		}
		if ( stream == null ) {
			throw new IllegalArgumentException("stream cannot be null");
		}
	}

}
