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
package org.sliceworkz.eventstore;

import java.util.Collections;
import java.util.Set;

import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.ErasureReport;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The main entry point for interacting with an event store.
 * <p>
 * An EventStore provides access to {@link EventStream}s which allow reading and writing domain events.
 * EventStore instances are obtained via {@link EventStoreFactory} by providing an {@link org.sliceworkz.eventstore.spi.EventStorage} implementation.
 * <p>
 * This implementation is fully compliant with the Dynamic Consistency Boundary (DCB) specification,
 * supporting dynamic event tagging, flexible querying, and optimistic locking based on relevant historical facts.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create event store with in-memory storage
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 *
 * // Get an event stream for a specific context and purpose
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * // Append events and query them
 * stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("John"), Tags.none()));
 * List<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll()).toList();
 * }</pre>
 *
 * <h2>Lifecycle:</h2>
 * An EventStore owns background machinery of its own, and — when obtained from a storage builder's
 * {@code buildStore()} — is the only handle on the storage backing it. {@link #close() Close} it when
 * the application is done with it, either explicitly or with try-with-resources:
 * <pre>{@code
 * try ( EventStore eventStore = PostgresEventStorage.newBuilder().buildStore() ) {
 *     ...
 * }
 * }</pre>
 * A store that lives as long as the process needs no explicit close; one created per tenant, per test
 * or per reload does.
 *
 * @see EventStoreFactory
 * @see EventStream
 * @see EventStreamId
 */
public interface EventStore extends AutoCloseable {

	/**
	 * Closes this event store, shutting down the notification machinery it started.
	 * <p>
	 * <b>The {@link org.sliceworkz.eventstore.spi.EventStorage} is not closed.</b> An EventStore is
	 * always handed a storage it did not create, and the storage is the expensive object — connection
	 * pool, notification threads — which can back several stores and usually outlives them. Closing it
	 * is the caller's business, once every store built on it has been closed.
	 * <p>
	 * The single exception is a store obtained from a storage builder's {@code buildStore()}: that one
	 * created the storage and hands back no other reference to it, so closing it closes both. See
	 * {@link #owning(EventStore, EventStorage)}, which is how such a store is composed.
	 * <p>
	 * Idempotent, and bounded: it waits briefly for in-flight listener notifications rather than
	 * abandoning them. After closing, {@link #getEventStream} and every operation on streams already
	 * obtained from this store throw {@link org.sliceworkz.eventstore.spi.EventStorageClosedException} —
	 * a store whose notifications have stopped must not keep serving reads as if nothing happened, since
	 * that strands anything subscribed to it. Streams from <em>other</em> stores on the same storage are
	 * unaffected.
	 * <p>
	 * Closing a store also closes its streams, ending their subscriptions and handing their registrations
	 * back to the storage — see {@link org.sliceworkz.eventstore.stream.EventSource#close()}. That one
	 * operation on a stream keeps working afterwards, as closing something twice must.
	 * <p>
	 * Declared without a checked exception, unlike {@link AutoCloseable#close()}, so that
	 * try-with-resources needs no catch block. The default implementation does nothing.
	 */
	@Override
	default void close ( ) {
		// no resources to release
	}

	/**
	 * Returns an EventStore that behaves exactly like {@code eventStore}, but also closes
	 * {@code eventStorage} when it is closed.
	 * <p>
	 * Closing an EventStore never closes a storage handed to it, for the reasons on {@link #close()}.
	 * This composes the two into a single handle for the one case where that reasoning does not apply:
	 * a {@code buildStore()} that created both and returns only the store, leaving the caller nothing
	 * else to close. The storage builders use it for exactly that; application code can use it wherever
	 * it wants one closable handle for a storage and store it created together:
	 * <pre>{@code
	 * EventStorage storage = PostgresEventStorage.newBuilder().build();
	 * try ( EventStore eventStore = EventStore.owning(EventStoreFactory.get().eventStore(storage), storage) ) {
	 *     ...
	 * }   // store shut down, then storage closed
	 * }</pre>
	 * Both closes are idempotent, so closing the result and the parts, in any order, is harmless.
	 *
	 * @param eventStore the store to delegate to; must not be null
	 * @param eventStorage the storage to close along with it; must not be null
	 * @return an EventStore that closes both
	 * @throws IllegalArgumentException if either argument is null
	 */
	static EventStore owning ( EventStore eventStore, EventStorage eventStorage ) {
		return new OwningEventStore(eventStore, eventStorage);
	}

	/**
	 * Retrieves an event stream with full configuration for event types and historical event types.
	 * <p>
	 * This is the primary method for obtaining an event stream. Event root classes define the sealed interfaces
	 * or base types for current domain events. Historical event root classes define types for legacy events
	 * that may need upcasting to current types.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream (context and optional purpose)
	 * @param eventRootClasses the set of root classes/interfaces for current domain events
	 * @param historicalEventRootClasses the set of root classes/interfaces for historical events requiring upcasting
	 * @return an EventStream for reading and writing domain events
	 */
	<DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses );

	/**
	 * Retrieves an event stream without specifying event root classes.
	 * <p>
	 * Use this method when working with raw events or when event types are not statically known.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId ) {
		return getEventStream(eventStreamId, Collections.emptySet(), Collections.emptySet());
	}

	/**
	 * Retrieves an event stream with current event root classes only.
	 * <p>
	 * Use this method when you only need to work with current event types and no historical upcasting is required.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream
	 * @param eventRootClasses the set of root classes/interfaces for current domain events
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses ) {
		return getEventStream(eventStreamId, eventRootClasses, Collections.emptySet());
	}

	/**
	 * Retrieves an event stream for a single event root class.
	 * <p>
	 * Convenience method for the common case of a single sealed interface or base class for domain events.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream
	 * @param eventRootClass the root class/interface for domain events (typically a sealed interface)
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Class<?> eventRootClass ) {
		return getEventStream(eventStreamId, Collections.singleton(eventRootClass), Collections.emptySet());
	}

	/**
	 * Retrieves an event stream with both current and historical event root classes.
	 * <p>
	 * Convenience method for the common case of a single current event type and a single historical event type
	 * that requires upcasting.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream
	 * @param eventRootClass the root class/interface for current domain events
	 * @param historicalEventRootClass the root class/interface for historical events requiring upcasting
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Class<?> eventRootClass, Class<?> historicalEventRootClass ) {
		return getEventStream(eventStreamId, Collections.singleton(eventRootClass), Collections.singleton(historicalEventRootClass));
	}

	/**
	 * Erases a data subject's personal data by destroying the keys that protect it.
	 * <p>
	 * Every {@link org.sliceworkz.eventstore.shredding.Shreddable} value sealed for this subject becomes
	 * permanently unreadable, and reads return
	 * {@link org.sliceworkz.eventstore.shredding.Shreddable.Shredded} in its place. Everything else on
	 * those events is untouched: the non-personal payload, the tags, the timestamps and the pseudonymous
	 * identifiers all keep working, so ledgers still reconcile and the audit trail still holds.
	 * <pre>{@code
	 * ErasureReport report = eventStore.erase(
	 *         DataSubject.of("customer", "alice-42"),
	 *         ErasureReason.of("GDPR art.17 request #4711"));
	 *
	 * report.keysShredded();   // 1
	 * }</pre>
	 *
	 * <p>
	 * <b>Nothing in the events table is written.</b> The stored events stay byte-identical. That is what makes this an erasure rather than an overwrite:
	 * there is no new row version to vacuum, nothing new in the write-ahead log, and the ciphertext
	 * already sitting in replicas, archives and last night's backup becomes unreadable at the same
	 * instant, with nothing to chase. It also means the append-only log stays append-only, so nothing
	 * disturbs event ordering, outstanding bookmarks or the physical layout the indexes assume.
	 *
	 * <p>
	 * <b>Idempotent.</b> Erasing a subject that holds no keys — never appended for, or erased already — reports
	 * {@link ErasureReport#isNoop()} rather than failing. Data appended for the subject <em>after</em> an
	 * erasure gets a fresh key and is readable; only what was sealed under the destroyed keys is gone.
	 *
	 * <p>
	 * <b>Erasure notifies nothing.</b> Read models, caches, search indexes and downstream systems that already
	 * copied the personal data keep their copies, and projections hold bookmarks so they will not re-read
	 * the affected events on their own. Re-projecting anything that materialised the erased data is the
	 * application's responsibility.
	 *
	 * @param subject whose data to erase; the unit of erasure is a
	 *                {@link org.sliceworkz.eventstore.shredding.DataSubject}, not a field or an event
	 * @param reason  why, recorded alongside the destroyed key — the events record nothing about the
	 *                erasure, so this is the whole audit trail
	 * @return what was destroyed
	 * @throws UnsupportedOperationException if this store has no
	 *         {@link org.sliceworkz.eventstore.shredding.ShreddingCodec} configured, and so holds no keys
	 * @throws org.sliceworkz.eventstore.shredding.ShreddingException if the key store cannot be reached
	 * @throws IllegalArgumentException if either argument is null
	 * @see org.sliceworkz.eventstore.shredding.Shreddable
	 */
	default ErasureReport erase ( DataSubject subject, ErasureReason reason ) {
		throw new UnsupportedOperationException(
				"this event store has no ShreddingCodec configured, so it holds no keys to destroy; configure shredding on the storage builder or via EventStoreFactory.eventStore(...)");
	}


}