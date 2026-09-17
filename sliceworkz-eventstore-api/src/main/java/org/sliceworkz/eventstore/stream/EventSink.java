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

import java.util.Collections;
import java.util.List;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EphemeralEvent;

/**
 * Interface for writing events to an event stream.
 * <p>
 * EventSink provides the capability to append events to the event store with support for
 * optimistic locking through {@link AppendCriteria}. This is a key component of the
 * Dynamic Consistency Boundary (DCB) pattern, enabling conditional appends based on
 * relevant historical facts.
 * <p>
 * When events are appended, they are transformed from {@link EphemeralEvent}s (lightweight events
 * without position information) to full {@link Event}s with assigned references, timestamps, and
 * stream associations.
 * <p>
 * EventSink is typically accessed through {@link EventStream}, which combines reading and writing capabilities.
 *
 * <h2>Append Modes:</h2>
 * <ul>
 *   <li><strong>Unconditional append</strong>: {@link #append(List)} and {@link #append(EphemeralEvent)} append
 *       without any condition; they are {@code append(AppendCriteria.none(), ...)} spelled without the
 *       criteria, and the two spellings are interchangeable</li>
 *   <li><strong>Conditional append (DCB)</strong>: Use {@code AppendCriteria.of(query, lastRef)} to implement
 *       optimistic locking based on relevant facts. The append will fail if new events matching the query
 *       have been added since the last reference.</li>
 * </ul>
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(
 *     EventStreamId.forContext("customer").withPurpose("123"),
 *     CustomerEvent.class
 * );
 *
 * // Unconditional append: no boundary to check, so no criteria to pass
 * List<Event<CustomerEvent>> appended = stream.append(
 *     Event.of(new CustomerRegistered("John Doe"), Tags.of("region", "EU"))
 * );
 *
 * // Conditional append with optimistic locking (DCB pattern)
 * // First, pin the boundary at the stream head and query the relevant facts up to it. An absent
 * // head is an empty stream, and a valid boundary, so a new customer needs no special case
 * EventQuery customer = EventQuery.forTags(Tags.of("customer", "123"));
 * EventReference head = stream.head().orElse(null);
 * List<Event<CustomerEvent>> relevantEvents = stream.query(customer.until(head));
 *
 * // Make decision based on relevant facts, then attempt the conditional append - it fails if
 * // new relevant facts have emerged after the boundary
 * try {
 *     stream.append(
 *         AppendCriteria.of(customer, head),
 *         Event.of(new CustomerNameChanged("Jane Doe"), Tags.of("customer", "123"))
 *     );
 * } catch (OptimisticLockingException e) {
 *     // New relevant facts have emerged - retry decision
 * }
 *
 * // Batch append multiple events, unconditionally
 * List<Event<CustomerEvent>> batchAppended = stream.append(List.of(
 *     Event.of(new CustomerAddressChanged("123 Main St"), Tags.of("customer", "123")),
 *     Event.of(new CustomerEmailChanged("john@example.com"), Tags.of("customer", "123"))
 * ));
 * }</pre>
 *
 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream (typically a sealed interface)
 * @see EventStream
 * @see EventSource
 * @see AppendCriteria
 * @see EphemeralEvent
 * @see Event
 */
public interface EventSink<DOMAIN_EVENT_TYPE> {

	/**
	 * Appends a list of events to the stream with conditional logic based on append criteria.
	 * <p>
	 * This is the primary append method. Events are provided as {@link EphemeralEvent}s and are
	 * converted to full {@link Event}s upon successful append, with assigned references, timestamps,
	 * and position information.
	 * <p>
	 * When using {@link AppendCriteria} with optimistic locking, the append will only succeed if
	 * no new events matching the criteria's query have been added since the expected last event reference.
	 * If conflicting events are detected, an {@link OptimisticLockingException}
	 * is thrown.
	 * <p>
	 * <b>Idempotency keys make the batch a unit of de-duplication.</b> Each event carries its own key
	 * ({@link EphemeralEvent#withIdempotencyKey(String)}), scoped to the stream, and the keys of one
	 * batch must be distinct — a batch repeating a key is rejected with {@link IllegalArgumentException}
	 * before anything is stored. A batch is stored atomically, so a retry of it finds every key
	 * already stored, and that is the one shape that is swallowed: nothing is stored, an empty list is
	 * returned, counted on {@code sliceworkz.eventstore.append.deduplicated}. A batch of which some
	 * keys are stored and some are not is not a retry of anything the store holds — one of its events
	 * collides with a different event holding its key — and is refused with
	 * {@link IdempotencyKeyConflictException}, nothing stored. So a command producing several events
	 * is made idempotent by deriving a key per event from the command's id, and should key every
	 * event: an event without a key rides along with the keyed ones, and a batch reusing a key with
	 * different unkeyed events cannot be told from a retry. The alternative — storing the events
	 * whose keys are new and skipping the rest — loses because it leaves the caller believing the
	 * colliding fact landed too, and because it is not an answer every backend can give: Postgres
	 * writes a batch as one multi-row insert and pairs the rows it returns with the input by
	 * position, so it cannot insert a subset.
	 * <p>
	 * <b>A stream appends to itself, and only a specific stream can be appended to.</b> The events land
	 * in the stream this sink is bound to, so the stream id is the whole of where they go. A wildcard
	 * stream — any context, or any purpose within a context — is a source: it reads across the streams
	 * it matches and is refused as a target with {@link IllegalArgumentException}, nothing stored, since
	 * an event is stored in exactly one stream and a wildcard names none. To write to a stream a
	 * wildcard reads across, open that stream: {@code getEventStream} is a cheap handle that shares its
	 * serde with every stream of the same mappings, and a stream per operation is the intended usage.
	 * The alternative — an append through a wildcard stream naming its target stream as a further
	 * argument — loses three times over: a stream is then a sink for some targets and not for others,
	 * decided per call rather than per stream; the store meters every append under the tags of the
	 * stream it went through, so a write landing in {@code customer#123} would be counted under the
	 * wildcard's purpose and never under its own; and it buys nothing the shared serde does not already
	 * give, at the price of a second relation on {@link EventStreamId} beside
	 * {@link EventStreamId#covers(EventStreamId)}, saying which stream may write to which.
	 * <p>
	 * <b>The batch is taken as {@code List<? extends EphemeralEvent<? extends DOMAIN_EVENT_TYPE>>}, so an
	 * ordinary list of one event type fits.</b> A caller mapping its domain events into ephemeral ones
	 * holds a {@code List<EphemeralEvent<CustomerRegistered>>}, or a {@code List<EphemeralEvent<CustomerEvent>>},
	 * and {@code List} is invariant: without the outer wildcard neither is a
	 * {@code List<EphemeralEvent<? extends CustomerEvent>>}, so every such call site has to name the
	 * parameter type it is producing — {@code .<EphemeralEvent<? extends CustomerEvent>>map(...)} — to say
	 * something the signature could have said once. The wildcard costs nothing here because the batch is
	 * only ever read: this method serializes each event and hands the payloads to storage, and nothing
	 * writes into the list. The events themselves keep the inner wildcard, so a batch may still mix event
	 * types of the hierarchy; what the outer one adds is that a batch of one type needs no witness.
	 *
	 * @param appendCriteria the criteria determining whether the append should proceed (use AppendCriteria.none() for unconditional append)
	 * @param events the list of ephemeral events to append
	 * @return a list of fully-formed Events with assigned references and metadata; empty when the batch
	 *         was de-duplicated on an idempotency key
	 * @throws OptimisticLockingException if append criteria are violated (new relevant facts detected)
	 * @throws IllegalArgumentException if this stream is a wildcard stream, if an event's type is not one this
	 *         stream has a mapping for, if two events of the batch carry the same idempotency key, or if
	 *         the criteria's filter names a legacy event type of this stream — a boundary names current
	 *         types, exactly as a query does, and counts the legacy events that upcast into them
	 * @throws IdempotencyKeyConflictException if some of the batch's idempotency keys are already stored on
	 *         the stream and others are not; nothing is stored
	 * @throws org.sliceworkz.eventstore.events.EventSerializationException if an event's payload cannot be
	 *         written; nothing is stored. A property of the payload class, so never worth retrying —
	 *         unlike an {@link org.sliceworkz.eventstore.spi.EventStorageException} from the same call
	 * @see AppendCriteria
	 */
	List<Event<DOMAIN_EVENT_TYPE>> append ( AppendCriteria appendCriteria, List<? extends EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> events );

	/**
	 * Appends a single event to the stream with conditional logic based on append criteria.
	 * <p>
	 * Convenience method for appending a single event. Delegates to {@link #append(AppendCriteria, List)}
	 * with a single-element list.
	 *
	 * @param appendCriteria the criteria determining whether the append should proceed
	 * @param event the ephemeral event to append
	 * @return a list containing the single fully-formed Event with assigned reference and metadata
	 * @throws OptimisticLockingException if append criteria are violated
	 * @throws org.sliceworkz.eventstore.events.EventSerializationException if the event's payload cannot be written; nothing is stored
	 */
	default List<Event<DOMAIN_EVENT_TYPE>> append ( AppendCriteria appendCriteria, EphemeralEvent<? extends DOMAIN_EVENT_TYPE> event ) {
		return append(appendCriteria, Collections.singletonList(event));
	}

	/**
	 * Appends a list of events to the stream unconditionally.
	 * <p>
	 * The same append as {@link #append(AppendCriteria, List)} with {@link AppendCriteria#none()}, and
	 * nothing else: no consistency boundary is checked, so nothing can raise
	 * {@link OptimisticLockingException}, and on a backend that serializes conditional appends per stream
	 * no lock is taken. Everything else the criteria-taking append does — the idempotency-key rules, the
	 * serialization failure, the typed events with their assigned references handed back — is the same.
	 * <p>
	 * This is the append for an event that records a fact no decision was made on: an import, a fixture
	 * seeding history, a log of things that happened elsewhere. An append that <em>follows</em> a read
	 * of the stream is the other kind, whether or not it looks like it — the read was the decision, and
	 * its boundary belongs in the criteria; see the class documentation for the shape. The alternative —
	 * having {@code AppendCriteria.none()} be the only spelling, so the absence of a boundary is written
	 * out — loses because the criteria a DCB append needs are not a flag but a filter and a reference the
	 * caller already holds from its read, so the check is opted into by having something to present, and
	 * an argument that is always {@code none()} where there is nothing to present marks nothing.
	 *
	 * @param events the list of ephemeral events to append
	 * @return a list of fully-formed Events with assigned references and metadata; empty when the batch
	 *         was de-duplicated on an idempotency key
	 * @throws IllegalArgumentException if two events of the batch carry the same idempotency key
	 * @throws IdempotencyKeyConflictException if some of the batch's idempotency keys are already stored on
	 *         the stream and others are not; nothing is stored
	 * @throws org.sliceworkz.eventstore.events.EventSerializationException if an event's payload cannot be
	 *         written; nothing is stored
	 * @see #append(AppendCriteria, List)
	 */
	default List<Event<DOMAIN_EVENT_TYPE>> append ( List<? extends EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> events ) {
		return append(AppendCriteria.none(), events);
	}

	/**
	 * Appends a single event to the stream unconditionally.
	 * <p>
	 * Convenience method for appending a single event without criteria. Delegates to {@link #append(List)}
	 * with a single-element list, which is {@link #append(AppendCriteria, List)} with
	 * {@link AppendCriteria#none()}.
	 *
	 * @param event the ephemeral event to append
	 * @return a list containing the single fully-formed Event with assigned reference and metadata; empty
	 *         when the event was de-duplicated on its idempotency key
	 * @throws org.sliceworkz.eventstore.events.EventSerializationException if the event's payload cannot be written; nothing is stored
	 * @see #append(List)
	 */
	default List<Event<DOMAIN_EVENT_TYPE>> append ( EphemeralEvent<? extends DOMAIN_EVENT_TYPE> event ) {
		return append(Collections.singletonList(event));
	}

}
