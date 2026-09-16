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
 * <strong>Purpose is optional.</strong> If you don't need to distinguish multiple streams within a
 * context, simply use {@link #forContext(String)} and ignore purpose: it defaults to the constant
 * {@code "default"}, giving every context a single well-defined stream. Purpose only becomes relevant
 * when a context needs more than one stream (e.g. per-instance streams, or separating event kinds).
 * <p>
 * <strong>Purpose has a metrics cost when it is an entity id.</strong> The store tags every meter with
 * the stream's purpose, and a registry never evicts a meter, so one purpose per entity means one
 * permanent set of meters per entity. The store therefore caps how many distinct purposes get their own
 * series and pools the rest; see {@link org.sliceworkz.eventstore.MeterOptions} for the measurements and
 * for how to turn the breakdown off entirely where purpose is known to be an id.
 * <p>
 * EventStreamId supports wildcards for querying multiple streams. Both context and purpose can be null,
 * which acts as a wildcard matching any value. This enables flexible stream queries:
 * <ul>
 *   <li>Specific stream (default purpose): {@code EventStreamId.forContext("customer")}</li>
 *   <li>Specific stream (explicit purpose): {@code EventStreamId.forContext("customer").withPurpose("123")}</li>
 *   <li>All streams in a context: {@code EventStreamId.forContext("customer").anyPurpose()}</li>
 *   <li>All streams across all contexts: {@code EventStreamId.anyContext()}</li>
 * </ul>
 * <p>
 * <strong>A wildcard names streams to read, never a stream to write.</strong> An event is stored in
 * exactly one stream, so a stream opened on a wildcard id is a source: it reads across every stream
 * the wildcard {@linkplain #canRead(EventStreamId) matches}, and an append through it is refused with
 * {@link IllegalArgumentException} (see {@link EventSink#append(AppendCriteria, java.util.List)}). To
 * write to one of the streams it reads, open that stream by its own id. Whether an id is a wildcard is
 * a property of its value rather than of its type, which is why the refusal is at runtime and a
 * wildcard stream is still an {@link EventStream}: the same handle reads a context, and reads one of
 * its streams, depending only on the id it was opened with.
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

	/**
	 * The purpose given to a stream created with {@link #forContext(String)} or {@link #defaultPurpose()}:
	 * {@code "default"}.
	 * <p>
	 * Public because it is a storage-level value, not only a Java one. It is what the library binds into
	 * the {@code stream_purpose} column for a context that never sets a purpose, and what the PostgreSQL
	 * DDL carries as that column's default — so anyone writing rows by hand, building an interop layer,
	 * or querying the events table directly needs the exact string this library agrees on rather than a
	 * literal copied from documentation.
	 * <p>
	 * Note that this is a compile-time constant, so a reference to it is inlined into the calling class.
	 * Changing it would therefore not be a drop-in replacement — but it is stored data (see the
	 * {@code stream_purpose} notes in the project documentation), so it is not going to change.
	 */
	public static final String DEFAULT_PURPOSE = "default";

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
