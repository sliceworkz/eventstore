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

/**
 * Handles events in projections with full access to event metadata including timestamps, tags, and references.
 * <p>
 * This is the base handler interface for processing events in the event store framework. Unlike
 * {@link EventHandler}, which only provides access to business event data, this interface gives
 * handlers complete access to the {@link Event} wrapper containing all metadata such as:
 * <ul>
 *   <li>Event timestamp - when the event was persisted</li>
 *   <li>Event reference - unique identifier and position in stream</li>
 *   <li>Event tags - key-value pairs for querying and correlation</li>
 *   <li>Event stream - which stream the event belongs to</li>
 *   <li>Event types - current and stored type information (relevant for upcasting)</li>
 * </ul>
 * <p>
 * EventWithMetaDataHandler is marked as {@code @FunctionalInterface}, enabling lambda expressions
 * and method references for concise handler implementations.
 * <p>
 * Key use cases:
 * <ul>
 *   <li>Temporal queries - building time-based projections or analytics</li>
 *   <li>Event correlation - using tags to link related events</li>
 *   <li>Audit logging - recording when events occurred and their identifiers</li>
 *   <li>Debugging - inspecting full event context during troubleshooting</li>
 *   <li>Event versioning - distinguishing between current and upcasted events</li>
 * </ul>
 *
 * <h2>When to Use EventWithMetaDataHandler vs EventHandler:</h2>
 * <ul>
 *   <li><b>Use EventWithMetaDataHandler:</b> When you need timestamps, tags, references, or stream information</li>
 *   <li><b>Use EventHandler:</b> When building simple projections that only care about business state</li>
 * </ul>
 *
 * <h2>Basic Example - Temporal Projection:</h2>
 * <pre>{@code
 * public class CustomerTimelineProjection implements Projection<CustomerEvent> {
 *     private List<TimelineEntry> timeline = new ArrayList<>();
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         // Access to full event metadata
 *         timeline.add(new TimelineEntry(
 *             event.timestamp(),
 *             event.reference(),
 *             event.data().getClass().getSimpleName(),
 *             event.data().toString()
 *         ));
 *     }
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.matchAll();
 *     }
 *
 *     public List<TimelineEntry> getTimelineSince(LocalDateTime since) {
 *         return timeline.stream()
 *             .filter(entry -> entry.timestamp().isAfter(since))
 *             .toList();
 *     }
 * }
 * }</pre>
 *
 * <h2>Tag-Based Correlation:</h2>
 * <pre>{@code
 * public class CustomerActivityProjection implements Projection<CustomerEvent> {
 *     private Map<String, List<Event<CustomerEvent>>> eventsByCustomer = new HashMap<>();
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         // Extract customer ID from tags
 *         event.tags().tag("customer").ifPresent(customerTag -> {
 *             String customerId = customerTag.value();
 *             eventsByCustomer
 *                 .computeIfAbsent(customerId, k -> new ArrayList<>())
 *                 .add(event);
 *         });
 *     }
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.forEvents(EventTypesFilter.any(), Tags.none());
 *     }
 *
 *     public List<Event<CustomerEvent>> getEventsForCustomer(String customerId) {
 *         return eventsByCustomer.getOrDefault(customerId, List.of());
 *     }
 * }
 * }</pre>
 *
 * <h2>Audit Logging:</h2>
 * <pre>{@code
 * public class AuditLogProjection implements EventWithMetaDataHandler<CustomerEvent> {
 *     private List<AuditEntry> auditLog = new ArrayList<>();
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         auditLog.add(new AuditEntry(
 *             event.reference().id(),              // Unique event ID
 *             event.stream().toString(),           // Which stream
 *             event.type().name(),                 // Event type
 *             event.timestamp(),                   // When it occurred
 *             event.tags().toStrings(),            // Associated tags
 *             extractUserId(event.tags())          // Who triggered it
 *         ));
 *     }
 *
 *     private String extractUserId(Tags tags) {
 *         return tags.tag("userId")
 *             .map(Tag::value)
 *             .orElse("SYSTEM");
 *     }
 * }
 * }</pre>
 *
 * <h2>Lambda-Based Implementation:</h2>
 * <pre>{@code
 * // Simple lambda handler for logging
 * EventWithMetaDataHandler<CustomerEvent> logger = event -> {
 *     System.out.printf("[%s] %s: %s%n",
 *         event.timestamp(),
 *         event.type().name(),
 *         event.data()
 *     );
 * };
 *
 * // Method reference
 * EventWithMetaDataHandler<CustomerEvent> processor = this::processEventWithMetadata;
 *
 * private void processEventWithMetadata(Event<CustomerEvent> event) {
 *     // Implementation with full access to metadata
 *     logToExternalSystem(event.timestamp(), event.data());
 * }
 * }</pre>
 *
 * <h2>Distinguishing Upcasted Events:</h2>
 * <pre>{@code
 * public class EventMigrationReportProjection implements EventWithMetaDataHandler<CustomerEvent> {
 *     private int upcastedEventCount = 0;
 *     private int nativeEventCount = 0;
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         // Compare current type with stored type to detect upcasting
 *         if (!event.type().equals(event.storedType())) {
 *             upcastedEventCount++;
 *             System.out.printf("Upcasted: %s -> %s%n",
 *                 event.storedType().name(),
 *                 event.type().name()
 *             );
 *         } else {
 *             nativeEventCount++;
 *         }
 *     }
 *
 *     public void printReport() {
 *         System.out.printf("Native events: %d, Upcasted events: %d%n",
 *             nativeEventCount, upcastedEventCount);
 *     }
 * }
 * }</pre>
 *
 * <h2>Time-Windowed Aggregation:</h2>
 * <pre>{@code
 * public class HourlyRegistrationsProjection implements EventWithMetaDataHandler<CustomerEvent> {
 *     private Map<LocalDateTime, Integer> registrationsByHour = new HashMap<>();
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         if (event.data() instanceof CustomerRegistered) {
 *             // Round timestamp to hour
 *             LocalDateTime hour = event.timestamp()
 *                 .truncatedTo(ChronoUnit.HOURS);
 *
 *             registrationsByHour.merge(hour, 1, Integer::sum);
 *         }
 *     }
 *
 *     public Map<LocalDateTime, Integer> getRegistrationsByHour() {
 *         return Collections.unmodifiableMap(registrationsByHour);
 *     }
 * }
 * }</pre>
 *
 * <h2>Relationship with EventHandler:</h2>
 * <p>
 * {@link EventHandler} extends this interface and provides a default implementation that extracts
 * the business data from the event wrapper. This creates a convenient abstraction hierarchy:
 * <pre>
 * EventWithMetaDataHandler (full metadata access)
 *         ^
 *         |
 *   EventHandler (business data only)
 * </pre>
 * <p>
 * This design allows projections to choose the appropriate level of abstraction:
 * <ul>
 *   <li>Implement {@code EventWithMetaDataHandler} directly for full control</li>
 *   <li>Implement {@code EventHandler} for simplified business logic</li>
 *   <li>Both are compatible with the projection framework</li>
 * </ul>
 *
 * @param <EVENT_TYPE> the sealed interface type defining the domain events to handle
 * @see EventHandler
 * @see Event
 * @see org.sliceworkz.eventstore.projection.Projection
 * @see org.sliceworkz.eventstore.projection.Projector
 * @see Tags
 * @see EventReference
 */
@FunctionalInterface
public interface EventWithMetaDataHandler<EVENT_TYPE> {

	/**
	 * Processes an event with full access to metadata.
	 * <p>
	 * This method is called for each event matching the projection's {@link org.sliceworkz.eventstore.query.EventQuery}.
	 * Implementations receive the complete {@link Event} wrapper containing both business data and metadata.
	 * <p>
	 * Common patterns include:
	 * <ul>
	 *   <li>Pattern matching on {@code event.data()} to handle different event types</li>
	 *   <li>Extracting tags using {@code event.tags().tag("key")} for correlation</li>
	 *   <li>Using {@code event.timestamp()} for temporal queries or analytics</li>
	 *   <li>Accessing {@code event.reference()} for event identification and ordering</li>
	 *   <li>Comparing {@code event.type()} and {@code event.storedType()} to detect upcasting</li>
	 * </ul>
	 * <p>
	 * This method should be idempotent when possible, as events may be replayed during projection rebuilds
	 * or recovery scenarios.
	 *
	 * @param eventWithMeta the complete event including metadata (never null)
	 */
	void when ( Event<EVENT_TYPE> eventWithMeta );

}
