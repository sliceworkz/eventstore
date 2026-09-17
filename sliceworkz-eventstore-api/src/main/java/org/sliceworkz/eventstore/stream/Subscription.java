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
 * One listener's subscription to an {@link EventSource}: the handle {@link EventSource#subscribe(AppendListener)}
 * and {@link EventSource#subscribe(BookmarkListener)} return, and the way to end that subscription alone.
 * <p>
 * A source can carry several listeners, and closing the source ends them all. This handle ends one of
 * them: after {@link #close()} the listener it stands for is not notified again, and every other
 * listener on the source keeps going. What the source holds with the storage follows from what is
 * subscribed — a source is registered with the storage exactly while at least one of its subscriptions
 * is live — so closing the last subscription of a source releases the source, as closing the source
 * does, and nothing needs the source's own {@code close()} to be reachable. The alternative — a source
 * registered from its first subscription until its own {@code close()}, whatever its handles do — loses
 * because a caller holding only the handle then has no way to release the source, and a source whose
 * every subscription was closed stays held by the storage for the life of the storage, the exact leak
 * the handle exists to avoid.
 * <pre>{@code
 * Subscription subscription = stream.subscribe(atLeastUntil -> { ...; return atLeastUntil; });
 * ...
 * subscription.close();   // this listener only; stream.close() would end every subscription on the stream
 * }</pre>
 * <p>
 * {@link #close()} is idempotent, and closing a subscription the source already ended — by its own
 * {@code close()}, or by the store it came from closing — does nothing. A notification already being
 * dispatched when the subscription is closed may still reach the listener; no new one is dispatched
 * to it afterwards, which is the same promise the storage makes for an unsubscribed listener.
 * <p>
 * A {@link org.sliceworkz.eventstore.projection.Projector} built with
 * {@link org.sliceworkz.eventstore.projection.Projector.Builder#subscribe() subscribe()} subscribes itself
 * and keeps no handle, so its subscription ends with its source. To hold the handle for a projector,
 * build it without {@code subscribe()} and subscribe it yourself: a projector is an {@link AppendListener}.
 *
 * @see EventSource#subscribe(AppendListener)
 * @see EventSource#subscribe(BookmarkListener)
 * @see EventSource#close()
 */
public interface Subscription extends AutoCloseable {

	/**
	 * Ends this subscription: the listener it stands for is not notified again, and the other listeners
	 * on the source are unaffected. Releases the source from the storage when this was the source's last
	 * live subscription.
	 * <p>
	 * Idempotent, and a no-op for a subscription the source has already ended. Declared without a
	 * checked exception, unlike {@link AutoCloseable#close()}, so that try-with-resources needs no catch
	 * block.
	 */
	@Override
	void close ( );

	/**
	 * Whether this subscription still delivers notifications: {@code true} from {@code subscribe} until
	 * {@link #close()}, or until the source or the store it came from is closed.
	 *
	 * @return {@code true} while the listener is subscribed
	 */
	boolean isActive ( );

}
