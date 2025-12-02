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
package org.sliceworkz.eventstore.events;

import java.util.List;
import java.util.stream.Stream;

/**
 * Handles domain events in projections, processing only the business event data without metadata.
 * <p>
 * This is a convenience interface that extends {@link EventWithMetaDataHandler} and automatically
 * extracts the domain event data from the {@link Event} wrapper, allowing handlers to focus solely
 * on business logic without dealing with timestamps, references, tags, or other event metadata.
 * <p>
 * EventHandler is marked as {@code @FunctionalInterface}, making it ideal for use with lambda expressions
 * and method references. This enables concise, readable projection implementations that react to
 * specific event types.
 * <p>
 * Key characteristics:
 * <ul>
 *   <li>Simplifies event handling by hiding metadata concerns</li>
 *   <li>Functional interface - can be implemented with lambdas</li>
 *   <li>Type-safe through generic parameter</li>
 *   <li>Commonly used in {@link org.sliceworkz.eventstore.projection.Projection} implementations</li>
 *   <li>Use {@link EventWithMetaDataHandler} when you need access to timestamps, tags, or references</li>
 * </ul>
 *
 * <h2>When to Use EventHandler vs EventWithMetaDataHandler:</h2>
 * <ul>
 *   <li><b>Use EventHandler:</b> When building projections/read models that only care about business state</li>
 *   <li><b>Use EventWithMetaDataHandler:</b> When you need temporal information, tags, or event correlation</li>
 * </ul>
 *
 * <h2>Basic Example - Projection with Pattern Matching:</h2>
 * <pre>{@code
 * public class CustomerProjection implements Projection<CustomerEvent> {
 *     private String customerId;
 *     private CustomerSummary summary;
 *
 *     public CustomerProjection(String customerId) {
 *         this.customerId = customerId;
 *     }
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         // Pattern matching on business event data only
 *         switch(event.data()) {
 *             case CustomerRegistered r ->
 *                 summary = new CustomerSummary(r.name(), false);
 *             case CustomerRenamed n ->
 *                 summary = summary.withName(n.name());
 *             case CustomerChurned c ->
 *                 summary = summary.withChurned(true);
 *         }
 *     }
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.forEvents(
 *             EventTypesFilter.any(),
 *             Tags.of("customer", customerId)
 *         );
 *     }
 * }
 * }</pre>
 *
 * <h2>Lambda-Based Event Handling:</h2>
 * <pre>{@code
 * // Create a simple counter projection using a lambda
 * EventHandler<CustomerEvent> counter = event -> {
 *     if (event instanceof CustomerRegistered) {
 *         registrationCount++;
 *     }
 * };
 *
 * // Or with method reference
 * EventHandler<CustomerEvent> logger = this::logCustomerEvent;
 *
 * private void logCustomerEvent(CustomerEvent event) {
 *     System.out.println("Event: " + event.getClass().getSimpleName());
 * }
 * }</pre>
 *
 * <h2>Processing Multiple Event Types:</h2>
 * <pre>{@code
 * public class OrderProjection implements Projection<OrderEvent> {
 *     private Map<String, Order> orders = new HashMap<>();
 *
 *     @Override
 *     public void when(Event<OrderEvent> event) {
 *         // Only work with business data, no metadata needed
 *         switch(event.data()) {
 *             case OrderPlaced placed -> {
 *                 orders.put(placed.orderId(), new Order(placed.orderId(), placed.items()));
 *             }
 *             case OrderShipped shipped -> {
 *                 Order order = orders.get(shipped.orderId());
 *                 orders.put(shipped.orderId(), order.markAsShipped());
 *             }
 *             case OrderCancelled cancelled -> {
 *                 orders.remove(cancelled.orderId());
 *             }
 *         }
 *     }
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.matchAll();
 *     }
 * }
 * }</pre>
 *
 * <h2>Contrast with EventWithMetaDataHandler:</h2>
 * <pre>{@code
 * // EventHandler - when you only need business data
 * class SimpleHandler implements EventHandler<CustomerEvent> {
 *     @Override
 *     public void when(CustomerEvent event) {
 *         // Just the domain event, no metadata
 *         if (event instanceof CustomerRegistered reg) {
 *             System.out.println("Customer: " + reg.name());
 *         }
 *     }
 * }
 *
 * // EventWithMetaDataHandler - when you need metadata
 * class TimestampedHandler implements EventWithMetaDataHandler<CustomerEvent> {
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         // Access to full event including metadata
 *         System.out.println("Event at " + event.timestamp() + ": " + event.data());
 *
 *         // Can access tags for correlation
 *         event.tags().tag("customer").ifPresent(tag ->
 *             System.out.println("Customer ID: " + tag.value())
 *         );
 *     }
 * }
 * }</pre>
 *
 * <h2>Implementation Detail:</h2>
 * <p>
 * The {@code EventHandler} interface provides a default implementation of
 * {@link EventWithMetaDataHandler#when(Event)} that extracts the business data using
 * {@code event.data()} and delegates to the simpler {@code when(TRIGGERING_EVENT_TYPE)} method.
 * This allows projections to implement only the business-focused method while remaining
 * compatible with the event store framework's metadata-aware processing pipeline.
 *
 * @param <EVENT_TYPE> the sealed interface type defining the domain events to handle
 * @see EventWithMetaDataHandler
 * @see Event
 * @see org.sliceworkz.eventstore.projection.Projection
 * @see org.sliceworkz.eventstore.projection.Projector
 */
@FunctionalInterface
public interface EventHandler<EVENT_TYPE> extends EventWithMetaDataHandler<EVENT_TYPE> {

	/**
	 * Processes a domain event for business logic purposes.
	 * <p>
	 * This method is called for each event matching the projection's {@link org.sliceworkz.eventstore.query.EventQuery}.
	 * Implementations should update internal state, build read models, or perform other business logic
	 * based on the event data.
	 * <p>
	 * The event parameter contains only the business data (typically a record implementing a sealed interface),
	 * without metadata such as timestamps, references, or tags. If you need access to metadata,
	 * implement {@link EventWithMetaDataHandler} instead.
	 * <p>
	 * This method should be idempotent when possible, as events may be replayed during projection rebuilds
	 * or recovery scenarios.
	 *
	 * @param event the domain event data to process (never null)
	 */
	void when ( EVENT_TYPE event );

	
	/**
	 * Default implementation that extracts business data from the event wrapper.
	 * <p>
	 * This method is called by the event store framework when processing events. It automatically
	 * extracts the domain event data using {@code event.data()} and delegates to the simpler
	 * {@link #when(Object)} method, hiding metadata concerns from the implementation.
	 * <p>
	 * Implementations should not override this method; instead, implement {@link #when(Object)}
	 * to define business logic.
	 *
	 * @param eventWithMeta the complete event including metadata (never null)
	 */
	default void when ( Event<EVENT_TYPE> eventWithMeta ) {
		when(eventWithMeta.data());
	}

	/**
	 * Processes a batch of events, extracting business data from each.
	 * <p>
	 * This default method provides a convenient way to process multiple events at once. The default
	 * implementation extracts the business data from each event and delegates to the single-event
	 * {@link #when(Object)} method for each event in the list, processing them sequentially in order.
	 * <p>
	 * Implementations may override this method to provide batch-optimized processing of business events,
	 * such as:
	 * <ul>
	 *   <li>Bulk database operations for better performance</li>
	 *   <li>Aggregated calculations across multiple events</li>
	 *   <li>Batch updates to read models or projections</li>
	 *   <li>Transactional processing of event batches</li>
	 * </ul>
	 * <p>
	 * Example of custom batch processing:
	 * <pre>{@code
	 * @Override
	 * public void when(List<Event<CustomerEvent>> eventsWithMeta) {
	 *     // Extract all domain events
	 *     List<CustomerEvent> events = eventsWithMeta.stream()
	 *         .map(Event::data)
	 *         .toList();
	 *
	 *     // Batch process registrations
	 *     List<CustomerRegistered> registrations = events.stream()
	 *         .filter(e -> e instanceof CustomerRegistered)
	 *         .map(e -> (CustomerRegistered) e)
	 *         .toList();
	 *
	 *     if (!registrations.isEmpty()) {
	 *         customerRepository.batchInsert(registrations);
	 *     }
	 * }
	 * }</pre>
	 * <p>
	 * Note: If you need access to event metadata (timestamps, tags, references) during batch processing,
	 * override {@link EventWithMetaDataHandler#when(List)} instead.
	 *
	 * @param eventsWithMeta the list of events to process, business data will be extracted from each (never null)
	 */
	default void when ( List<Event<EVENT_TYPE>> eventsWithMeta ) {
		eventsWithMeta.forEach(this::when); // don't delegate to the stream-version to avoid stream creation overhead
	}

	/**
	 * Processes a stream of events, extracting business data from each.
	 * <p>
	 * This default method provides a convenient way to process a stream of events by extracting
	 * the business data from each event and delegating to the single-event {@link #when(Object)} method.
	 * The default implementation processes events sequentially as they are consumed from the stream.
	 * <p>
	 * This method is particularly useful when working with query results from {@link org.sliceworkz.eventstore.stream.EventStream#query(org.sliceworkz.eventstore.query.EventQuery)},
	 * which returns a {@link java.util.stream.Stream} of events. It allows event handlers to process query results directly
	 * without manually iterating or collecting the stream, while focusing only on business data.
	 * <p>
	 * Implementations may override this method to provide stream-optimized processing of business events,
	 * such as:
	 * <ul>
	 *   <li>Streaming aggregations without materializing the full event list</li>
	 *   <li>Lazy evaluation and processing of large event sets</li>
	 *   <li>Memory-efficient handling of unbounded event streams</li>
	 *   <li>Parallel stream processing for performance</li>
	 * </ul>
	 * <p>
	 * Example usage with query results:
	 * <pre>{@code
	 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
	 * Stream<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll());
	 *
	 * EventHandler<CustomerEvent> handler = new EventHandler<>() {
	 *     @Override
	 *     public void when(CustomerEvent event) {
	 *         System.out.println("Event: " + event);
	 *     }
	 * };
	 *
	 * // Process the stream directly - business data will be extracted automatically
	 * handler.when(events);
	 * }</pre>
	 * <p>
	 * Note: Streams are consumed during processing. If you need to process the same events multiple times,
	 * consider collecting to a list first or querying again.
	 *
	 * @param eventsWithMeta the stream of events to process, business data will be extracted from each (never null)
	 */
	default void when ( Stream<Event<EVENT_TYPE>> eventsWithMeta ) {
		eventsWithMeta.forEach(this::when);
	}

}
