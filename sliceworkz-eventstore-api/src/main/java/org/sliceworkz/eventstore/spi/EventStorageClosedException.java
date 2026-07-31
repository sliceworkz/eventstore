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
package org.sliceworkz.eventstore.spi;

/**
 * Thrown when an operation is attempted on an {@link EventStorage} or
 * {@link org.sliceworkz.eventstore.EventStore} that has been closed.
 * <p>
 * Closing is terminal: there is no reopening, so this exception always indicates a lifecycle bug in
 * the calling code — a storage closed too early, or a reference kept past its owner's shutdown —
 * rather than a transient condition worth retrying.
 * <p>
 * Backends must throw this rather than silently continuing to work. A storage whose notification
 * machinery has been shut down can often still read and write (its connection pool may belong to the
 * caller and still be open), but any projection or subscriber attached to it has stopped receiving
 * events. Failing loudly turns that silent stall into an immediate, locatable error.
 *
 * @see EventStorage#close()
 */
public class EventStorageClosedException extends EventStorageException {

	private static final long serialVersionUID = 1L;

	/**
	 * Constructs a new EventStorageClosedException with the specified detail message.
	 *
	 * @param message the detail message, naming the storage and the attempted operation where possible
	 */
	public EventStorageClosedException ( String message ) {
		super(message);
	}

}
