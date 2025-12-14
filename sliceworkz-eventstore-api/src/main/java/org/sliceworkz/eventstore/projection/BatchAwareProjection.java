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
package org.sliceworkz.eventstore.projection;

import java.util.Optional;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * A projection that receives batch lifecycle callbacks when processing events.
 * <p>
 * This interface extends {@link Projection} with additional hooks that are invoked before and after
 * processing batches of events. This enables projections to perform batch-level operations such as:
 * <ul>
 *   <li>Opening and committing database transactions around a batch</li>
 *   <li>Accumulating changes and flushing them as a batch</li>
 *   <li>Resource management at batch boundaries</li>
 *   <li>Batch-level error handling and rollback</li>
 * </ul>
 * <p>
 * The {@link Projector} will invoke these methods at the following times:
 * <ul>
 *   <li>{@link #beforeBatch()} - Called once before processing the first event in a batch</li>
 *   <li>{@link #afterBatch(Optional)} - Called after successfully processing all events in a batch</li>
 *   <li>{@link #cancelBatch()} - Called if an exception occurs during batch processing</li>
 * </ul>
 * <p>
 * A batch corresponds to a single query execution as determined by the projector's batch size
 * configuration. If the batch size is 500 (default), up to 500 events may be processed between
 * {@code beforeBatch()} and {@code afterBatch()} calls.
 *
 * <p>Example - Transactional Batch Processing:
 * <pre>{@code
 * class CustomerListProjection implements BatchAwareProjection<CustomerEvent> {
 *     private final EntityManager em;
 *     private EntityTransaction tx;
 *
 *     @Override
 *     public void beforeBatch() {
 *         // Start transaction at batch boundary
 *         tx = em.getTransaction();
 *         tx.begin();
 *     }
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         // Process event within transaction
 *         if (event.data() instanceof CustomerRegistered reg) {
 *             em.persist(new CustomerEntity(reg.id(), reg.name()));
 *         }
 *     }
 *
 *     @Override
 *     public void afterBatch(Optional<EventReference> lastEventReference) {
 *         // Commit transaction after successful batch
 *         tx.commit();
 *     }
 *
 *     @Override
 *     public void cancelBatch() {
 *         // Rollback transaction on error
 *         if (tx != null && tx.isActive()) {
 *             tx.rollback();
 *         }
 *     }
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.forEvents(EventTypesFilter.of(CustomerRegistered.class), Tags.none());
 *     }
 * }
 * }</pre>
 *
 * @param <EVENT_TYPE> the type of domain events processed by this projection
 * @see Projection
 * @see Projector
 */
public interface BatchAwareProjection<EVENT_TYPE> extends Projection<EVENT_TYPE> {

	/**
	 * Called before processing the first event in a batch.
	 * <p>
	 * This method is invoked once per batch, before any calls to {@link #when(org.sliceworkz.eventstore.events.Event)}.
	 * Use this to initialize resources, start transactions, or prepare for batch processing.
	 * <p>
	 * If no events match the projection's query in the batch, this method will not be called for that batch.
	 * <p>
	 * This method should not throw exceptions. If an exception is thrown, the batch will be aborted
	 * and {@link #cancelBatch()} will be called.
	 *
	 * @see #afterBatch(Optional)
	 * @see #cancelBatch()
	 */
	void beforeBatch ( );

	/**
	 * Called if an exception occurs during batch processing.
	 * <p>
	 * This method is invoked if any exception is thrown during {@link #beforeBatch()},
	 * {@link #when(org.sliceworkz.eventstore.events.Event)}, or while streaming events.
	 * Use this to clean up resources, rollback transactions, or perform error handling.
	 * <p>
	 * After this method is called, the exception will be wrapped in a {@link ProjectorException}
	 * and propagated to the caller.  No call to {@link #afterBatch(Optional)} will follow after this one.
	 * <p>
	 * This method should handle exceptions gracefully and should not throw exceptions itself.
	 * Any exceptions thrown from this method will be logged but otherwise ignored.
	 *
	 * @see #beforeBatch()
	 * @see #afterBatch(Optional)
	 */
	void cancelBatch ( );

	/**
	 * Called after successfully processing all events in a batch.
	 * <p>
	 * This method is invoked once per batch, after all events have been processed by
	 * {@link #when(org.sliceworkz.eventstore.events.Event)}. Use this to commit transactions,
	 * flush accumulated changes, or clean up resources.
	 * <p>
	 * This method is not called if {@link #cancelBatch()} was called due to an error. 
	 *
	 * @param lastEventReference the reference of the last event processed in this batch,
	 *                          or empty if no events matched the projection's query
	 * @see #beforeBatch()
	 * @see #cancelBatch()
	 */
	void afterBatch ( Optional<EventReference> lastEventReference );

}
