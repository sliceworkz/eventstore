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
 * The one seam through which an event store, its storage and its projectors report what they do.
 * <p>
 * The library names no metrics or tracing library: it reports <em>what happened</em>, in its own
 * terms, and an implementation of this interface decides what to make of it — meters, spans, log
 * lines, an audit trail, a recorder in a test. Observation is opt-in: a store or storage not given an
 * observer uses {@link #NOOP}, which records nothing and allocates nothing.
 * <pre>{@code
 * EventStoreObserver observer = ...;   // e.g. a Micrometer or OpenTelemetry binding
 *
 * EventStore store = PostgresEventStorage.newBuilder().observer(observer).buildStore();
 *
 * // or, on a storage built without one
 * EventStore store = EventStore.on(storage).observer(observer).build();
 * }</pre>
 *
 * <h2>Operations: a scope from start to close</h2>
 * Every operation the store performs on behalf of a caller — an append, a query or page, a lookup by id,
 * a head lookup, a bookmark read or write, an erasure, a projector batch — is reported through
 * {@link #start(Observation)}, which describes what is about to be done and returns an
 * {@link Observation.Scope}. The store then reports exactly one of
 * {@link Observation.Scope#completed(Outcome) completed} or {@link Observation.Scope#failed(Throwable) failed},
 * and closes the scope in a {@code finally}. All three happen synchronously, on the thread that started
 * the operation, so an implementation may keep thread-local context between {@code start} and
 * {@code close} — a span made current, so that work done inside the operation (the JDBC calls, a key-store
 * lookup, a projector batch's page query) nests beneath it — without propagating anything itself.
 * <p>
 * A completion is an <em>answer</em>, not only a success: an append that found new facts at its
 * consistency boundary completes as {@link Outcome.Conflicted}, and a retried append swallowed whole as
 * {@link Outcome.Duplicated}. Both are how the store is meant to be used, not failures of it.
 * {@link Observation.Scope#failed(Throwable) failed} is for an operation that could not answer: a storage
 * error, an event that cannot be read, a key store that is down.
 *
 * <h2>Lifecycle and health</h2>
 * The other methods report things that are <em>held</em> — a started storage, a live subscription — so
 * that an implementation can count them and release what it registered for them, and the state of a
 * storage's notification channels. Each has an empty default, so an implementation overrides what it
 * cares about.
 *
 * <h2>What an implementation must honour</h2>
 * <ul>
 *   <li><b>Thread-safe.</b> A store is used from many threads at once, and so is its observer.</li>
 *   <li><b>Cheap.</b> Every call is on the caller's thread, inside the operation it describes: a slow
 *       observer is a slow store. Anything expensive belongs on a thread of the observer's own.</li>
 *   <li><b>Never throwing.</b> An operation never fails because its observation did: the library wraps
 *       every observer it is given with {@link #contained(EventStoreObserver)}, which catches and logs
 *       what escapes. That is a guard, not a licence — an observer that throws loses what it was
 *       recording.</li>
 * </ul>
 * <p>
 * The alternative — a meter facade naming counters, timers and gauges, bound to a metrics library —
 * loses because it rebuilds a weaker version of the library it binds to and can express nothing but
 * meters: a tracer needs to know where an operation starts and ends, and what it answered, which is
 * what an observation is.
 *
 * @see Observation
 * @see Outcome
 */
public interface EventStoreObserver {

	/**
	 * The observer that observes nothing: every scope it returns is one shared instance, and nothing is
	 * allocated or recorded. The default wherever no observer is given.
	 */
	EventStoreObserver NOOP = NoopObserver.INSTANCE;

	/**
	 * An operation starts. Called synchronously on the caller's thread, before the operation does
	 * anything; the returned scope is current on that thread until it is closed.
	 *
	 * @param <O> what the operation reports when it completes
	 * @param observation what is about to be done
	 * @return the scope of this one operation, never null
	 */
	<O extends Outcome> Observation.Scope<O> start ( Observation<O> observation );

	/**
	 * A storage started: reported by a backend once it is ready for use, at the end of its builder's
	 * {@code build()}. A storage that fails to start reports nothing.
	 *
	 * @param storage the storage's name
	 */
	default void storageStarted ( String storage ) { }

	/**
	 * A storage closed. Its notification channels, if it has any, have been reported down before this,
	 * and nothing is reported for it afterwards — so this is where an implementation releases what it
	 * registered for the storage.
	 *
	 * @param storage the storage's name
	 */
	default void storageClosed ( String storage ) { }

	/**
	 * A stream took on a subscription — an {@link org.sliceworkz.eventstore.stream.AppendListener} or a
	 * {@link org.sliceworkz.eventstore.stream.BookmarkListener}, a subscribed projector among them — and
	 * is held by the storage until its last subscription ends.
	 *
	 * @param stream the stream subscribed to
	 */
	default void subscriptionOpened ( StreamInfo stream ) { }

	/**
	 * A subscription ended: its handle was closed, or the stream it was made on, or the store that stream
	 * came from. Reported once per subscription, so opened minus closed is the number of live
	 * subscriptions — and a number that only rises is a subscribed stream nobody closes.
	 *
	 * @param stream the stream the subscription was made on
	 */
	default void subscriptionClosed ( StreamInfo stream ) { }

	/**
	 * A notification channel of a storage went up or down.
	 * <p>
	 * A backend whose notifications travel over a channel — PostgreSQL's LISTEN/NOTIFY — reports each of
	 * its channels as down from its constructor, up once its listener is registered, down whenever it
	 * loses it (a dropped connection, or one found silently dead) and up again when it recovers, and down
	 * when the storage closes. While a channel is down, appends succeed but no subscriber is woken, so
	 * every subscribed projection quietly stops advancing: this is the signal to alert on. A backend that
	 * notifies in-process has no channels and never calls this.
	 *
	 * @param storage the storage's name
	 * @param channel which notifications the channel carries
	 * @param listening whether the channel is up
	 */
	default void notificationChannelChanged ( String storage, NotificationChannel channel, boolean listening ) { }

	/**
	 * A stream handle was opened. Handles are cheap and opened per operation, and one used only to query
	 * and append is never closed — it holds nothing — so there is deliberately no counterpart: what a
	 * stream holds is a subscription, reported by {@link #subscriptionOpened(StreamInfo)}.
	 *
	 * @param stream the stream opened
	 */
	default void streamOpened ( StreamInfo stream ) { }

	/**
	 * Wraps an observer so that nothing it throws reaches the operation it observes.
	 * <p>
	 * The library applies this to every observer it is given; an implementation has no need to call it.
	 * A throwable escaping the observer is caught and logged — at ERROR the first time for that observer,
	 * with its stack trace, and at DEBUG afterwards, so a broken observer is reported without every
	 * operation of the store writing a stack trace. A scope whose {@code start} threw is replaced by a
	 * no-op one. {@link #NOOP} is returned as it is, and so is an observer that is already contained.
	 *
	 * @param observer the observer to contain; must not be null
	 * @return an observer that never throws
	 * @throws IllegalArgumentException if the observer is null
	 */
	static EventStoreObserver contained ( EventStoreObserver observer ) {
		if ( observer == null ) {
			throw new IllegalArgumentException("observer cannot be null.  Use EventStoreObserver.NOOP to observe nothing");
		}
		if ( observer == NOOP || observer instanceof ContainedObserver ) {
			return observer;
		}
		return new ContainedObserver(observer);
	}

}
