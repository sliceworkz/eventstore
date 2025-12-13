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
package org.sliceworkz.eventstore.stream;

/**
 * Identifies an event stream within the event store by context and optional purpose.
 * <p>
 * An EventStreamId consists of two components:
 * <ul>
 *   <li><strong>context</strong>: The primary identifier for the stream (e.g., "customer", "order", "payment")</li>
 *   <li><strong>purpose</strong>: An optional secondary identifier to distinguish multiple streams within the same context (e.g., customer ID, order number)</li>
 * </ul>
 * <p>
 * EventStreamId supports wildcards for querying multiple streams. Both context and purpose can be null,
 * which acts as a wildcard matching any value. This enables flexible stream queries:
 * <ul>
 *   <li>Specific stream: {@code EventStreamId.forContext("customer").withPurpose("123")}</li>
 *   <li>All streams in a context: {@code EventStreamId.forContext("customer").anyPurpose()}</li>
 *   <li>All streams across all contexts: {@code EventStreamId.anyContext()}</li>
 * </ul>
 * <p>
 * The string representation follows the format "context#purpose", where the '#' separator is omitted
 * if purpose is null (default). Examples: "customer#123", "customer", "" (empty for anyContext).
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create a stream ID for a specific customer
 * EventStreamId customerId = EventStreamId.forContext("customer").withPurpose("123");
 *
 * // Create a stream ID with default purpose
 * EventStreamId defaultStream = EventStreamId.forContext("order"); // uses "default" purpose
 *
 * // Create wildcard stream IDs for querying
 * EventStreamId allCustomers = EventStreamId.forContext("customer").anyPurpose();
 * EventStreamId allStreams = EventStreamId.anyContext();
 *
 * // Check if a wildcard can read a specific stream
 * boolean canRead = allCustomers.canRead(customerId); // true
 * }</pre>
 *
 * @param context the primary identifier for the stream, or null for wildcard matching any context
 * @param purpose the optional secondary identifier, or null for wildcard matching any purpose
 * @see EventStream
 * @see org.sliceworkz.eventstore.EventStore#getEventStream(EventStreamId)
 */
public record EventStreamId ( String context, String purpose ) {

	private static final String DEFAULT_PURPOSE = "default";

	/**
	 * Creates an EventStreamId for a specific context with the default purpose.
	 * <p>
	 * The default purpose is "default". This is the most common way to create a stream ID
	 * when you don't need to distinguish between multiple purposes within a context.
	 *
	 * @param context the context identifier (required, must not be null)
	 * @return an EventStreamId with the specified context and default purpose
	 */
	public static EventStreamId forContext ( String context ) {
		return new EventStreamId(context, DEFAULT_PURPOSE);
	}

	/**
	 * Creates a wildcard EventStreamId that matches any context and any purpose.
	 * <p>
	 * This is useful for querying events across all streams in the event store,
	 * regardless of their context or purpose.
	 *
	 * @return an EventStreamId that matches all streams
	 */
	public static EventStreamId anyContext ( ) {
		return new EventStreamId(null, null);
	}

	/**
	 * Returns a new EventStreamId with the same context but a different purpose.
	 * <p>
	 * This is typically used to specify a particular instance within a context.
	 * For example, starting with {@code forContext("customer")} and then calling
	 * {@code withPurpose("123")} creates a stream for customer 123.
	 *
	 * @param purpose the purpose identifier (required, must not be null)
	 * @return a new EventStreamId with the specified purpose
	 */
	public EventStreamId withPurpose ( String purpose ) {
		return new EventStreamId(context, purpose);
	}

	/**
	 * Returns a new EventStreamId with the same context but wildcard purpose.
	 * <p>
	 * This creates a wildcard stream ID that matches all purposes within the current context.
	 * Useful for querying all streams within a specific context.
	 *
	 * @return a new EventStreamId that matches any purpose within the current context
	 */
	public EventStreamId anyPurpose (  ) {
		return new EventStreamId(context, null);
	}

	/**
	 * Returns a new EventStreamId with the same context but default purpose.
	 * <p>
	 *
	 * @return a new EventStreamId that references default purpose within the current context
	 */
	public EventStreamId defaultPurpose (  ) {
		return new EventStreamId(context, DEFAULT_PURPOSE);
	}

	/**
	 * Checks if this stream ID represents a wildcard for any context.
	 *
	 * @return true if context is null (wildcard), false otherwise
	 */
	public boolean isAnyContext ( ) {
		return context == null;
	}

	/**
	 * Checks if this stream ID represents a wildcard for any purpose.
	 *
	 * @return true if purpose is null (wildcard), false otherwise
	 */
	public boolean isAnyPurpose ( ) {
		return purpose == null;
	}

	/**
	 * Checks if this stream ID represents a specific stream that can accept appended events.
	 * <p>
	 * Only specific streams (with both context and purpose defined) can accept appends.
	 * Wildcard streams (anyContext or anyPurpose) are read-only.
	 *
	 * @return true if both context and purpose are specified (not wildcards), false otherwise
	 * @see #isReadOnly()
	 */
	public boolean canAppend ( ) {
		return !isAnyContext() && !isAnyPurpose();
	}

	/**
	 * Checks if events can be appended to the specified target stream ID from this stream ID.
	 * <p>
	 * This method determines if this stream ID is compatible with the target stream for append operations.
	 * Compatibility is established when:
	 * <ul>
	 *   <li>The two stream IDs are exactly equal, or</li>
	 *   <li>This stream ID concretizes the target stream (fills in a wildcard purpose)</li>
	 * </ul>
	 * <p>
	 * This is typically used when working with anyPurpose streams that need to append to specific
	 * stream instances while maintaining stream identity constraints.
	 *
	 * @param eventStreamId the target stream ID to check compatibility with
	 * @return true if events can be appended to the target stream, false otherwise
	 * @see #concretizes(EventStreamId)
	 */
	public boolean canAppendTo ( EventStreamId eventStreamId ) {
		// only when the same specific stream, or concretization (filled in purpose) of anyPurpose-stream
		return this.equals(eventStreamId) || this.concretizes(eventStreamId);
	}

	/**
	 * Checks if this stream ID is read-only (cannot accept appends).
	 * <p>
	 * A stream is read-only if it contains any wildcards (anyContext or anyPurpose).
	 * Only specific streams with both context and purpose defined can accept appends.
	 *
	 * @return true if this stream is read-only (has wildcards), false if it can accept appends
	 * @see #canAppend()
	 */
	public boolean isReadOnly ( ) {
		return !canAppend();
	}

	/**
	 * Determines if this stream ID can read from the specified actual stream ID.
	 * <p>
	 * This method implements the wildcard matching logic:
	 * <ul>
	 *   <li>If this context is a wildcard (null), it matches any context</li>
	 *   <li>If this purpose is a wildcard (null), it matches any purpose</li>
	 *   <li>Otherwise, both context and purpose must match exactly</li>
	 * </ul>
	 * <p>
	 * This is primarily used internally to determine stream access permissions.
	 *
	 * @param actualStreamId the actual stream ID to check against
	 * @return true if this stream ID can read from the actual stream ID, false otherwise
	 */
	public boolean canRead ( EventStreamId actualStreamId ) {
		boolean result = true;
		if ( !this.isAnyContext() && !this.context().equals(actualStreamId.context()) ) {
			result = false;
		} else if ( !this.isAnyPurpose() && !this.purpose().equals(actualStreamId.purpose())){
			result = false;
		}
		return result;
	}

	/**
	 * Checks if this stream ID concretizes (fills in the wildcard purpose of) another stream ID.
	 * <p>
	 * A stream ID concretizes another stream ID when:
	 * <ul>
	 *   <li>The other stream has a wildcard purpose (anyPurpose)</li>
	 *   <li>The other stream has a specific context (not anyContext)</li>
	 *   <li>This stream has the same context as the other stream</li>
	 *   <li>This stream has a specific purpose (not a wildcard)</li>
	 * </ul>
	 * <p>
	 * Example: {@code EventStreamId.forContext("customer").withPurpose("123")} concretizes
	 * {@code EventStreamId.forContext("customer").anyPurpose()}.
	 * <p>
	 * This is useful for scenarios where a general stream (e.g., "customer#anyPurpose") needs to
	 * append events to specific instances (e.g., "customer#123").
	 *
	 * @param otherStreamId the stream ID to check if this stream concretizes it
	 * @return true if this stream ID concretizes the other stream ID, false otherwise
	 * @see #canAppendTo(EventStreamId)
	 */
	public boolean concretizes ( EventStreamId otherStreamId ) {
		// if the other stream is of the type "<businessObject>#<anyPurpose>" and this is "<businessObject>#<id>"
		return otherStreamId.isAnyPurpose() && !otherStreamId.isAnyContext() && otherStreamId.context().equals(this.context);
	}

	/**
	 * Returns a string representation of this stream ID in the format "context#purpose".
	 * <p>
	 * The format varies based on the presence of context and purpose:
	 * <ul>
	 *   <li>Both present: "customer#123"</li>
	 *   <li>Only context: "customer"</li>
	 *   <li>Neither (anyContext): "" (empty string)</li>
	 * </ul>
	 *
	 * @return the string representation of this stream ID
	 */
	public String toString ( ) {
		StringBuilder result = new StringBuilder();
		if ( context != null ) {
			result.append(context);
		}
		if ( purpose != null ) {
			result.append("#");
			result.append(purpose);
		}
		return result.toString();
	}

}
