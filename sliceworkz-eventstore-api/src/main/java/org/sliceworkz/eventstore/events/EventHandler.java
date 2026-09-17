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
package org.sliceworkz.eventstore.events;

/**
 * Handles events, one at a time, each with its metadata.
 * <p>
 * This is the one handler interface in the event store: a {@link org.sliceworkz.eventstore.projection.Projection}
 * is a handler with an {@link org.sliceworkz.eventstore.query.EventQuery}, and a
 * {@link org.sliceworkz.eventstore.projection.Projector} hands every matching event to {@link #when(Event)},
 * in order, one call per event. The {@link Event} carries the domain event as {@link Event#data()} next
 * to the stream it sits in, its {@link Event#reference()}, its {@link Event#tags()}, its
 * {@link Event#timestamp()} and the {@link Event#type()} and {@link Event#storedType()} it was read
 * under. A handler that needs only the domain event switches on {@code event.data()}:
 * <pre>{@code
 * public class CustomerProjection implements Projection<CustomerEvent> {
 *
 *     private CustomerSummary summary;
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", customerId));
 *     }
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         switch (event.data()) {
 *             case CustomerRegistered r -> summary = new CustomerSummary(r.name(), false);
 *             case CustomerRenamed n    -> summary = summary.withName(n.name());
 *             case CustomerChurned c    -> summary = summary.withChurned(true);
 *         }
 *     }
 * }
 * }</pre>
 * and one that needs the metadata reads it off the same argument:
 * <pre>{@code
 * @Override
 * public void when(Event<CustomerEvent> event) {
 *     LocalDate day = LocalDate.ofInstant(event.timestamp(), ZoneId.of("Europe/Brussels"));
 *     if (event.data() instanceof CustomerRegistered registered) {
 *         registrationsByDay.merge(day, 1L, Long::sum);
 *     }
 * }
 * }</pre>
 * <p>
 * <b>There is deliberately one {@code when}, and it takes the {@link Event}.</b> The alternative — a
 * second handler interface whose {@code when} takes the domain event alone, with a default
 * {@code when(Event)} unwrapping into it — loses on several counts. Two methods of one name on one
 * object, one of which a caller is meant to call and the other meant to implement, is a distinction
 * the compiler does not enforce: nothing objects to a class implementing the payload-only one and
 * overriding the unwrapping default too, and an {@code @Override} on the wrong one is only found at
 * runtime, by the events that never arrive. For a handler over {@code Object} — a raw stream, a
 * store-wide projection — the two collapse into {@code when(Object)} and {@code when(Event<Object>)},
 * where an {@code Event} <i>is</i> an {@code Object} and which one runs depends on the static type
 * at the call site. And every interface built on the pair has to exist twice as well, a
 * with-metadata and a without-metadata variant, each naming the split differently. What the
 * convenience bought was one {@code .data()} call per handler. A payload-only convenience, if one is
 * ever wanted, belongs under a distinct method name, never under an overload of this one.
 * <p>
 * <b>There is no batch method either.</b> A {@link org.sliceworkz.eventstore.projection.Projector} calls this method per event and
 * commits a batch through {@link org.sliceworkz.eventstore.projection.BatchAwareProjection}, which
 * is the seam for batch-level work — a transaction, a bulk write. A {@code when(List)} or
 * {@code when(Stream)} default beside this method would be a second entry point the projector never
 * uses, so an override of it would run for nobody; a caller holding a list of events already read
 * writes {@code events.forEach(handler::when)}.
 * <p>
 * This method should be idempotent where it can be: events are replayed on a rebuild and re-offered
 * after a failed batch.
 *
 * @param <EVENT_TYPE> the type of the domain events handled, typically a sealed interface
 * @see Event
 * @see org.sliceworkz.eventstore.projection.Projection
 * @see org.sliceworkz.eventstore.projection.Projector
 */
@FunctionalInterface
public interface EventHandler<EVENT_TYPE> {

	/**
	 * Handles one event.
	 * <p>
	 * Called once per event matching the projection's {@link org.sliceworkz.eventstore.query.EventQuery},
	 * in stream order. The domain event is {@code event.data()}; the metadata sits beside it on the
	 * same argument.
	 *
	 * @param event the event, with its metadata (never null)
	 */
	void when ( Event<EVENT_TYPE> event );

}
