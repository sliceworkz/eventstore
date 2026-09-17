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
import java.util.Optional;
import java.util.Set;

import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.ErasureReport;
import org.sliceworkz.eventstore.shredding.ShreddingAudit;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.SubjectErasureReport;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * The main entry point for interacting with an event store.
 * <p>
 * An EventStore provides access to {@link EventStream}s which allow reading and writing domain events.
 * An EventStore is built on an {@link EventStorage} with {@link #on(EventStorage)}, or obtained from a
 * storage builder's {@code buildStore()}, which does the same and hands back one handle for both.
 * <p>
 * This implementation is fully compliant with the Dynamic Consistency Boundary (DCB) specification,
 * supporting dynamic event tagging, flexible querying, and optimistic locking based on relevant historical facts.
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Create event store with in-memory storage
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 *
 * // Or build one on a storage you hold, with a registry and options of your own
 * EventStore eventStore = EventStore.on(storage).meterRegistry(registry).build();
 *
 * // Get an event stream for a specific context and purpose
 * EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 *
 * // Append events and query them
 * stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("John"), Tags.none()));
 * List<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll());
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
	 * try ( EventStore eventStore = EventStore.owning(EventStore.on(storage).build(), storage) ) {
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
	 * Starts building an EventStore on the given storage.
	 * <p>
	 * This is the one entry point for turning an {@link EventStorage} into a store. Everything else a
	 * store can be given — the {@link MeterRegistry} its meters go to, the {@link MeterOptions} bounding
	 * them, a {@link ShreddingCodec} of its own — is a call on the {@link Builder}, each with the default
	 * the storage builders use, so the shortest form is a store with the same settings
	 * {@code buildStore()} would have given it:
	 * <pre>{@code
	 * EventStorage storage = PostgresEventStorage.newBuilder().build();
	 *
	 * EventStore eventStore = EventStore.on(storage).build();
	 *
	 * EventStore reporting = EventStore.on(storage)
	 *     .meterRegistry(registry)
	 *     .meterOptions(MeterOptions.withoutPurposeBreakdown())
	 *     .shredding(ShreddingCodec.withholdingAll())
	 *     .build();
	 * }</pre>
	 * The store is created by the {@link EventStoreFactory} the {@link java.util.ServiceLoader} finds,
	 * and that factory stays the SPI an implementation provides; the alternative — calling it directly,
	 * as {@code EventStoreFactory.get().eventStore(storage, registry, options, codec)} — loses because
	 * it puts the ServiceLoader lookup and four positional arguments, one of them a {@code null} meaning
	 * "the storage's own codec", at every call site that wants anything but the defaults.
	 * <p>
	 * The built store does not own the storage: closing it closes the store alone, for the reasons on
	 * {@link #close()}. Wrap it with {@link #owning(EventStore, EventStorage)} for one handle on both.
	 *
	 * @param eventStorage the storage the store reads and writes through; must not be null
	 * @return a builder for a store on that storage
	 * @throws IllegalArgumentException if the storage is null
	 * @see Builder
	 */
	static Builder on ( EventStorage eventStorage ) {
		return new Builder(eventStorage);
	}

	/**
	 * Builds an {@link EventStore} on an {@link EventStorage}, obtained from {@link EventStore#on(EventStorage)}.
	 * <p>
	 * Mirrors the storage builders: the same setter names, the same defaults — {@link Metrics#globalRegistry},
	 * {@link MeterOptions#defaults()} and the storage's own codec — so a store built here and one from
	 * {@code buildStore()} differ only in who owns the storage. Every setter refuses {@code null}, since each
	 * has a default and a null could only be a mistake. {@link #build()} may be called more than once; each
	 * call is a further store on the same storage, independent of the others.
	 */
	final class Builder {

		private final EventStorage eventStorage;
		private MeterRegistry meterRegistry = Metrics.globalRegistry;
		private MeterOptions meterOptions = MeterOptions.defaults();
		private ShreddingCodec shreddingCodec;

		private Builder ( EventStorage eventStorage ) {
			if ( eventStorage == null ) {
				throw new IllegalArgumentException("eventStorage cannot be null");
			}
			this.eventStorage = eventStorage;
		}

		/**
		 * The registry the store's meters are registered in.
		 * <p>
		 * Defaults to {@link Metrics#globalRegistry}: a composite with no children until the application
		 * adds one, so meters registered there cost a map entry and record nothing, and an application
		 * that binds its real registry to it gets the store's series without configuring anything here.
		 *
		 * @param meterRegistry the registry; must not be null
		 * @return this builder
		 * @throws IllegalArgumentException if the registry is null
		 */
		public Builder meterRegistry ( MeterRegistry meterRegistry ) {
			if ( meterRegistry == null ) {
				throw new IllegalArgumentException("meterRegistry cannot be null.  Leave it unset for Metrics.globalRegistry");
			}
			this.meterRegistry = meterRegistry;
			return this;
		}

		/**
		 * How much detail the store's meters may carry.
		 * <p>
		 * Defaults to {@link MeterOptions#defaults()}, which caps the {@code purpose} tag at
		 * {@link MeterOptions#DEFAULT_MAX_PURPOSE_TAG_VALUES} distinct values; see {@link MeterOptions}
		 * for what an uncapped tag costs and when to turn the breakdown off altogether.
		 *
		 * @param meterOptions the options; must not be null
		 * @return this builder
		 * @throws IllegalArgumentException if the options are null
		 */
		public Builder meterOptions ( MeterOptions meterOptions ) {
			if ( meterOptions == null ) {
				throw new IllegalArgumentException("meterOptions cannot be null.  Leave it unset for MeterOptions.defaults()");
			}
			this.meterOptions = meterOptions;
			return this;
		}

		/**
		 * The codec that seals and unseals {@link org.sliceworkz.eventstore.shredding.Shreddable} values
		 * in this store, taking precedence over the one the storage was configured with.
		 * <p>
		 * Left unset, the store uses the storage's own ({@link EventStorage#shreddingCodec()}), which is
		 * what a storage builder's {@code .shredding(...)} put there and is empty for a storage built
		 * without shredding. Set it for a store that must read the same storage differently — a reporting
		 * service on {@link ShreddingCodec#withholdingAll()}, or a codec restricted to the categories this
		 * reader is entitled to.
		 *
		 * @param shreddingCodec the codec; must not be null
		 * @return this builder
		 * @throws IllegalArgumentException if the codec is null
		 */
		public Builder shredding ( ShreddingCodec shreddingCodec ) {
			if ( shreddingCodec == null ) {
				throw new IllegalArgumentException("shreddingCodec cannot be null.  Leave it unset for the storage's own codec");
			}
			this.shreddingCodec = shreddingCodec;
			return this;
		}

		/**
		 * Builds the store, through the {@link EventStoreFactory} found on the classpath.
		 *
		 * @return a new EventStore on the storage
		 * @throws org.sliceworkz.eventstore.spi.EventStorageException if no EventStore implementation is on the classpath
		 */
		public EventStore build ( ) {
			return EventStoreFactory.get().eventStore(eventStorage, meterRegistry, meterOptions, shreddingCodec);
		}
	}

	/**
	 * Retrieves an event stream with full configuration for current and legacy event types.
	 * <p>
	 * This is the primary method for obtaining an event stream. Event root classes define the sealed interfaces
	 * or base types for current domain events. Legacy event root classes define the types annotated
	 * {@link org.sliceworkz.eventstore.events.LegacyEvent}, which are upcast to current types on the read.
	 * <p>
	 * An empty set of event root classes opens the stream in raw mode; {@link #getRawEventStream(EventStreamId)}
	 * is the way to ask for that, with a return type that says what comes back.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream
	 * @param eventStreamId the identifier for the event stream (context and optional purpose)
	 * @param eventRootClasses the set of root classes/interfaces for current domain events
	 * @param legacyEventRootClasses the set of root classes/interfaces for legacy events requiring upcasting
	 * @return an EventStream for reading and writing domain events
	 */
	<DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> legacyEventRootClasses );

	/**
	 * Retrieves an event stream with current event root classes only.
	 * <p>
	 * Use this method when you only need to work with current event types and no upcasting is required.
	 * <p>
	 * The type parameter is free here, as on the three-argument overload: the roots are a set, so no
	 * single class can fix it. This is deliberately the way to open a stream typed wider than its roots —
	 * an {@code EventStream<Object>} over several unrelated hierarchies — where the single-class
	 * overloads refuse to. A stream with no roots at all is not opened here but through
	 * {@link #getRawEventStream(EventStreamId)}, whose type says what such a stream reads and that it
	 * cannot append.
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
	 * <p>
	 * The root class fixes the stream's type parameter: {@code getEventStream(id, CustomerEvent.class)} is an
	 * {@code EventStream<CustomerEvent>} and nothing else, so assigning it to an {@code EventStream<OrderEvent>}
	 * is a compile error rather than an append that fails at runtime with an event type the stream was never
	 * given a mapping for. The alternative — a {@code Class<?>} parameter, with the type parameter inferred
	 * from the assignment target alone — loses because it lets the declared type and the registered mapping
	 * disagree silently. A stream deliberately typed wider than its root (an {@code EventStream<Object>} over
	 * one root class, say) is opened through {@link #getEventStream(EventStreamId, Set)}, whose element type
	 * carries no such constraint.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream, fixed by {@code eventRootClass}
	 * @param eventStreamId the identifier for the event stream
	 * @param eventRootClass the root class/interface for domain events (typically a sealed interface)
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Class<DOMAIN_EVENT_TYPE> eventRootClass ) {
		return getEventStream(eventStreamId, Collections.singleton(eventRootClass), Collections.emptySet());
	}

	/**
	 * Retrieves an event stream with both a current and a legacy event root class.
	 * <p>
	 * Convenience method for the common case of a single current event type and a single legacy event type
	 * that requires upcasting.
	 *
	 * The current root class fixes the stream's type parameter, as in
	 * {@link #getEventStream(EventStreamId, Class)}. The legacy root class does not: legacy events are
	 * upcast into current ones and never surface under their own type, so it may be any class.
	 *
	 * @param <DOMAIN_EVENT_TYPE> the type of domain events in this stream, fixed by {@code eventRootClass}
	 * @param eventStreamId the identifier for the event stream
	 * @param eventRootClass the root class/interface for current domain events
	 * @param legacyEventRootClass the root class/interface for legacy events requiring upcasting
	 * @return an EventStream for reading and writing domain events
	 */
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Class<DOMAIN_EVENT_TYPE> eventRootClass, Class<?> legacyEventRootClass ) {
		return getEventStream(eventStreamId, Collections.singleton(eventRootClass), Collections.singleton(legacyEventRootClass));
	}

	/**
	 * Opens a stream in raw mode: no event root classes, so no type mapping, and every stored event read
	 * as the JSON document it is stored as.
	 * <p>
	 * <b>It is an {@link EventSource}, not an {@link EventStream}, because a raw stream cannot append.</b>
	 * An append is admitted only for an event type the stream holds a mapping for, and a raw stream holds
	 * none, so the type says what the stream can do instead of leaving every append to fail at runtime.
	 * The whole read side works — {@link EventSource#query query}, {@link EventSource#head head},
	 * {@link EventSource#getEventById getEventById}, subscriptions and bookmarks — over any stream id,
	 * concrete or wildcard.
	 * <p>
	 * <b>{@code data()} is the stored JSON document, a {@link String}</b>, as the storage answers it:
	 * the same text {@link org.sliceworkz.eventstore.spi.EventStorage.StoredEvent#payload()}
	 * carries and an import writes, parsed by nothing on the way out. It need not be byte for byte what
	 * was appended — PostgreSQL hands back its {@code jsonb} rendering, and a file-backed store re-renders
	 * what it reloaded — but it is the same document. A caller that wants to look inside parses it with
	 * the JSON library of its choice; most callers of a raw stream never do. Nothing is upcast, so a
	 * legacy event comes back under its stored type in its stored shape, and nothing is decrypted: a
	 * {@link org.sliceworkz.eventstore.shredding.Shreddable} value comes back as the sealed envelope it
	 * is stored as, which is what lets an export or an import move it without keys.
	 * <p>
	 * The alternative — an {@code EventSource<Object>} whose value is a parsed JSON tree — loses because
	 * this module carries no JSON library and so cannot name the type: the value was a Jackson 3 node
	 * behind an {@code Object}, of use only to a caller importing Jackson 3, and of none to an
	 * application on Jackson 2 or another library; and because it paid a parse for every event on the
	 * one read path that looks inside almost none of them.
	 * <p>
	 * What it is for: inspecting a stored event a typed stream cannot read (an
	 * {@link org.sliceworkz.eventstore.events.EventDeserializationException} names it by reference, and
	 * {@code getEventById} here reads it whatever its type), following every append in a store, and
	 * checking whether an event is present before an import — without the domain classes, and with no
	 * mapping that could fail on the way.
	 * <pre>{@code
	 * EventSource<String> everything = eventStore.getRawEventStream(EventStreamId.anyContext());
	 * List<Event<String>> stored = everything.getEventById(reference.id()).orElseThrow();
	 * String json = stored.getFirst().data();
	 * }</pre>
	 * The alternative — a {@code getEventStream(EventStreamId)} overload with a free type parameter —
	 * loses because the caller then writes {@code EventStream<CustomerEvent> s = store.getEventStream(id)},
	 * gets a JSON document under that type, and finds out at the first {@code switch} over {@code data()},
	 * as a {@code ClassCastException}; nothing at compile time objects. Here the type parameter is fixed,
	 * so that assignment does not compile. A stream deliberately typed wider than its roots, and able
	 * to append, is not a raw stream: that is {@link #getEventStream(EventStreamId, Set)} with the roots
	 * it should carry.
	 *
	 * @param eventStreamId the identifier for the event stream, concrete or wildcard
	 * @return a read-only stream over the stored events, with no type mapping, each event's data the
	 *         stored JSON document
	 * @see EventSource#getEventById(org.sliceworkz.eventstore.events.EventId)
	 */
	default EventSource<String> getRawEventStream ( EventStreamId eventStreamId ) {
		return this.<String>getEventStream(eventStreamId, Collections.emptySet(), Collections.emptySet());
	}

	/**
	 * Erases a data subject's personal data by destroying every key that protects it, under every
	 * category.
	 * <p>
	 * The whole-person erasure, and the one to reach for by default. An art.17 request names a person,
	 * not a retention category, and the caller answering it should not have to know which categories
	 * the person's data was ever written under: a subject whose data sits under {@code "default"},
	 * {@code "marketing"} and {@code "financial"} loses all three here. Every
	 * {@link org.sliceworkz.eventstore.shredding.Shreddable} value sealed for the subject becomes
	 * permanently unreadable, and reads return
	 * {@link org.sliceworkz.eventstore.shredding.Shreddable.Shredded} in its place. Everything else on
	 * those events is untouched: the non-personal payload, the tags, the timestamps and the pseudonymous
	 * identifiers all keep working, so ledgers still reconcile and the audit trail still holds.
	 * <pre>{@code
	 * SubjectErasureReport report = eventStore.erase(
	 *         "customer", "alice-42",
	 *         ErasureReason.of("GDPR art.17 request #4711"));
	 *
	 * report.categoriesErased();   // [default, marketing]
	 * report.keysShredded();       // 2
	 * }</pre>
	 *
	 * <p>
	 * <b>It takes the subject's type and id and no category, so it cannot be narrowed by accident.</b>
	 * Erasing one category and leaving the rest readable is
	 * {@link #eraseCategory(DataSubject, ErasureReason)}, which takes a
	 * {@link org.sliceworkz.eventstore.shredding.DataSubject} because a subject always names one
	 * category. The alternative — this method taking a {@code DataSubject} too and erasing the person
	 * it belongs to — loses because the category the argument carries would then be either ignored or
	 * refused: ignored, {@code erase(alice.withCategory("marketing"), reason)} destroys the financial
	 * history a retention rule says to keep; refused, a subject read off a {@code Shredded} value cannot
	 * be handed straight to it. Two parameter shapes keep the two erasures apart at compile time, so a
	 * call that meant one category cannot silently become the whole person, or the other way round.
	 *
	 * <p>
	 * <b>Nothing in the events table is written.</b> The stored events stay byte-identical. That is what makes this an erasure rather than an overwrite:
	 * there is no new row version to vacuum, nothing new in the write-ahead log, and the ciphertext
	 * already sitting in replicas, archives and last night's backup becomes unreadable at the same
	 * instant, with nothing to chase. It also means the append-only log stays append-only, so nothing
	 * disturbs event ordering, outstanding bookmarks or the physical layout the indexes assume.
	 *
	 * <p>
	 * <b>Idempotent.</b> Erasing a subject that holds no live keys under any category — never appended
	 * for, or erased already — reports {@link SubjectErasureReport#isNoop()} rather than failing, and a
	 * category that never held a key, or whose keys were shredded already, is absent from the report.
	 * Data appended for the subject <em>after</em> an erasure gets fresh keys and is readable; only what
	 * was sealed under the destroyed keys is gone.
	 *
	 * <p>
	 * <b>Erasure notifies nothing.</b> Read models, caches, search indexes and downstream systems that already
	 * copied the personal data keep their copies, and projections hold bookmarks so they will not re-read
	 * the affected events on their own. Re-projecting anything that materialised the erased data is the
	 * application's responsibility.
	 *
	 * @param subjectType what kind of subject, e.g. {@code "customer"} — the
	 *                    {@link DataSubject#type() type} of the subjects the data was sealed for
	 * @param subjectId   the pseudonymous identifier of the subject within that type
	 * @param reason      why, recorded alongside every destroyed key — the events record nothing about
	 *                    the erasure, so this is the whole audit trail
	 * @return what was destroyed, per category
	 * @throws UnsupportedOperationException if this store has no
	 *         {@link org.sliceworkz.eventstore.shredding.ShreddingCodec} configured, and so holds no keys,
	 *         or its codec or key store cannot erase across categories
	 * @throws org.sliceworkz.eventstore.shredding.ShreddingException if the key store cannot be reached
	 * @throws IllegalArgumentException if any argument is null or blank
	 * @see #eraseCategory(DataSubject, ErasureReason)
	 * @see org.sliceworkz.eventstore.shredding.Shreddable
	 */
	default SubjectErasureReport erase ( String subjectType, String subjectId, ErasureReason reason ) {
		throw new UnsupportedOperationException(
				"this event store has no ShreddingCodec configured, so it holds no keys to destroy; configure shredding on the storage builder or via EventStoreFactory.eventStore(...)");
	}

	/**
	 * Erases a data subject's personal data under one category only, by destroying the keys held for
	 * that category.
	 * <p>
	 * The narrow, deliberate erasure. A {@link org.sliceworkz.eventstore.shredding.DataSubject} always
	 * names one {@link DataSubject#category() category}, and this destroys the keys of that category and
	 * no other: whatever the same person holds under another category stays readable, and the call
	 * reports success, because the erasure it names was performed. That is what "erase marketing, retain
	 * financial for the statutory period" needs, and it is the wrong call for a request to erase a
	 * <em>person</em> — {@code DataSubject.of("customer", "alice-42")} is the subject under
	 * {@link org.sliceworkz.eventstore.shredding.DataSubject#DEFAULT_CATEGORY}, so erasing it leaves
	 * whatever Alice holds under {@code "marketing"} readable. The whole-person erasure is
	 * {@link #erase(String, String, ErasureReason)}.
	 * <pre>{@code
	 * ErasureReport report = eventStore.eraseCategory(
	 *         DataSubject.of("customer", "alice-42").withCategory("marketing"),
	 *         ErasureReason.of("consent withdrawn, ticket #4711"));
	 *
	 * report.keysShredded();   // 1
	 * }</pre>
	 * Everything said of {@link #erase(String, String, ErasureReason)} holds here too: nothing in the
	 * events table is written, the erasure is idempotent ({@link ErasureReport#isNoop()} for a subject
	 * holding no live key under the category), data appended for the subject afterwards gets a fresh key
	 * and is readable, and nothing is notified.
	 *
	 * @param subject whose data to erase, and under which category; the unit of erasure is a
	 *                {@link org.sliceworkz.eventstore.shredding.DataSubject}, not a field or an event
	 * @param reason  why, recorded alongside the destroyed key
	 * @return what was destroyed
	 * @throws UnsupportedOperationException if this store has no
	 *         {@link org.sliceworkz.eventstore.shredding.ShreddingCodec} configured, and so holds no keys
	 * @throws org.sliceworkz.eventstore.shredding.ShreddingException if the key store cannot be reached
	 * @throws IllegalArgumentException if either argument is null
	 * @see #erase(String, String, ErasureReason)
	 */
	default ErasureReport eraseCategory ( DataSubject subject, ErasureReason reason ) {
		throw new UnsupportedOperationException(
				"this event store has no ShreddingCodec configured, so it holds no keys to destroy; configure shredding on the storage builder or via EventStoreFactory.eventStore(...)");
	}

	/**
	 * Reading which data subjects hold protected data and which erasures have happened.
	 * <p>
	 * The events record nothing about an erasure — they are never rewritten — so the key store is the
	 * only account of it, and this is how a console or a compliance report reads that account. It hands
	 * out no key material and cannot decrypt anything.
	 * <p>
	 * Empty on a store with no shredding configured, or whose key store cannot enumerate.
	 *
	 * @return the audit view, or empty if there is none
	 * @see ShreddingAudit
	 */
	default Optional<ShreddingAudit> shreddingAudit ( ) {
		return Optional.empty();
	}

}