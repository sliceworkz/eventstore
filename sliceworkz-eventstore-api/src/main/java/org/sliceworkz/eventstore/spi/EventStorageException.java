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
package org.sliceworkz.eventstore.spi;

/**
 * Exception thrown when an error occurs during event storage operations.
 * <p>
 * This runtime exception wraps storage-layer errors that occur during event persistence,
 * retrieval, or management operations. It serves as the primary error signaling mechanism
 * for {@link EventStorage} implementations, allowing storage-specific exceptions to be
 * propagated to the application layer.
 * <p>
 * Common scenarios that may trigger this exception:
 * <ul>
 *   <li>Database connection failures</li>
 *   <li>Query execution errors</li>
 *   <li>Data serialization/deserialization failures</li>
 *   <li>Storage capacity or constraint violations</li>
 *   <li>Transaction rollback or commit failures</li>
 *   <li>Bookmark persistence errors</li>
 * </ul>
 * <p>
 * This exception is distinct from {@link org.sliceworkz.eventstore.stream.OptimisticLockingException},
 * which is thrown specifically for append criteria violations. {@code EventStorageException}
 * represents infrastructure-level failures rather than business logic conflicts.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * public class PostgresEventStorage implements EventStorage {
 *     @Override
 *     public Stream<StoredEvent> query(EventQuery query, ...) {
 *         try {
 *             // Execute database query
 *             return executeQuery(query);
 *         } catch (SQLException e) {
 *             throw new EventStorageException("Failed to query events", e);
 *         }
 *     }
 *
 *     @Override
 *     public List<StoredEvent> append(AppendCriteria criteria, ...) {
 *         try {
 *             // Persist events
 *             return persistEvents(criteria);
 *         } catch (SQLException e) {
 *             throw new EventStorageException("Failed to append events", e);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see EventStorage
 * @see org.sliceworkz.eventstore.stream.OptimisticLockingException
 */
public class EventStorageException extends RuntimeException {

	/**
	 * Constructs a new EventStorageException with the specified detail message.
	 * <p>
	 * Use this constructor when the error can be described with a message but there
	 * is no underlying cause exception.
	 *
	 * @param message the detail message explaining the error condition
	 */
	public EventStorageException ( String message ) {
		super(message);
	}

	/**
	 * Constructs a new EventStorageException with the specified cause.
	 * <p>
	 * Use this constructor to wrap lower-level exceptions (such as SQLException,
	 * IOException, etc.) that occurred during storage operations. The cause's message
	 * will be used as this exception's message.
	 *
	 * @param cause the underlying exception that caused this storage error
	 */
	public EventStorageException ( Throwable cause ) {
		super(cause);
	}

	/**
	 * Constructs a new EventStorageException with the specified detail message and cause.
	 * <p>
	 * Use this constructor to provide both a custom error message and the underlying
	 * cause exception. This is the most informative form, allowing you to add context
	 * while preserving the original exception.
	 *
	 * @param message the detail message explaining the error condition
	 * @param cause the underlying exception that caused this storage error
	 */
	public EventStorageException ( String message, Throwable cause ) {
		super(message, cause);
	}

}
