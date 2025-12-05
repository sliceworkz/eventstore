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
package org.sliceworkz.eventstore.stream;

import java.util.Optional;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * Exception thrown when an optimistic locking check fails during event append operations.
 * <p>
 * This exception is a core component of the Dynamic Consistency Boundary (DCB) pattern. It indicates
 * that the {@link AppendCriteria} specified for an append operation were violated because new relevant
 * facts (events) were found in the event store that were not present when the business decision was made.
 * <p>
 * In DCB, business decisions are based on querying relevant historical events using an {@link EventQuery}.
 * When appending the resulting events, the same query is provided along with a reference to the last
 * relevant event that was considered. If new events matching the query have been appended after that
 * reference by the time the append executes, this exception is thrown.
 *
 * <h2>DCB Workflow:</h2>
 * <ol>
 *   <li>Query relevant events: {@code stream.query(eventQuery)}</li>
 *   <li>Make business decision based on those events</li>
 *   <li>Note the reference of the last relevant event</li>
 *   <li>Attempt to append new events with {@link AppendCriteria} containing the query and reference</li>
 *   <li>If new matching events exist after the reference, this exception is thrown</li>
 *   <li>Retry: re-query, re-decide, and re-attempt append</li>
 * </ol>
 *
 * <h2>Exception Scenarios:</h2>
 * <ul>
 *   <li><strong>Concurrent modification</strong>: Another process appended relevant events between query and append</li>
 *   <li><strong>Empty stream violation</strong>: Expected an empty result but found matching events (when reference is empty)</li>
 *   <li><strong>Stale decision</strong>: The decision was based on outdated information</li>
 * </ul>
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
 * EventStreamId streamId = EventStreamId.forContext("account").withPurpose("123");
 * EventStream<AccountEvent> stream = eventStore.getEventStream(streamId, AccountEvent.class);
 *
 * // Define query for relevant events (account transactions)
 * EventQuery query = EventQuery.forEvents(
 *     EventTypesFilter.of(MoneyDeposited.class, MoneyWithdrawn.class),
 *     Tags.of("account", "123")
 * );
 *
 * try {
 *     // Query relevant events and make decision
 *     List<Event<AccountEvent>> events = stream.query(query).toList();
 *     BigDecimal balance = calculateBalance(events);
 *     EventReference lastRef = events.isEmpty() ? null : events.getLast().reference();
 *
 *     // Attempt withdrawal based on current balance
 *     if (balance.compareTo(amount) >= 0) {
 *         stream.append(
 *             AppendCriteria.of(query, Optional.ofNullable(lastRef)),
 *             Event.of(new MoneyWithdrawn(amount), Tags.of("account", "123"))
 *         );
 *     }
 * } catch (OptimisticLockingException e) {
 *     // New relevant events were appended concurrently
 *     System.out.println("Concurrent modification detected: " + e.getMessage());
 *     System.out.println("Expected last reference: " + e.getExpectedLastEventReference());
 *     System.out.println("Query used: " + e.getQuery());
 *
 *     // Retry the operation with fresh data
 *     retryWithdrawal();
 * }
 * }</pre>
 *
 * <h2>Retry Pattern:</h2>
 * <pre>{@code
 * int maxRetries = 3;
 * for (int attempt = 0; attempt < maxRetries; attempt++) {
 *     try {
 *         // Query, decide, and append with criteria
 *         List<Event<AccountEvent>> events = stream.query(query).toList();
 *         EventReference lastRef = events.isEmpty() ? null : events.getLast().reference();
 *
 *         // Make decision and append
 *         Event<AccountEvent> newEvent = makeDecision(events);
 *         stream.append(
 *             AppendCriteria.of(query, Optional.ofNullable(lastRef)),
 *             newEvent
 *         );
 *         break; // Success
 *
 *     } catch (OptimisticLockingException e) {
 *         if (attempt == maxRetries - 1) {
 *             throw new RuntimeException("Failed after " + maxRetries + " retries", e);
 *         }
 *         // Retry with fresh data
 *     }
 * }
 * }</pre>
 *
 * @see AppendCriteria
 * @see EventSink#append(AppendCriteria, List)
 * @see EventQuery
 * @see EventReference
 */
public class OptimisticLockingException extends RuntimeException {

	private final EventQuery query;
    private final Optional<EventReference> expectedLastEventReference;

    /**
     * Constructs a new OptimisticLockingException with the query and expected reference that failed.
     * <p>
     * This constructor creates an exception with a detailed message indicating why the optimistic
     * locking check failed. The message differs based on whether an event reference was expected:
     * <ul>
     *   <li>If reference is present: indicates the specific reference that was expected to be last</li>
     *   <li>If reference is empty: indicates an empty stream was expected but events were found</li>
     * </ul>
     *
     * @param query the EventQuery used in the AppendCriteria that failed, never null
     * @param expectedLastEventReference the expected last event reference, or Optional.empty() if an empty stream was expected
     */
    public OptimisticLockingException(EventQuery query, Optional<EventReference> expectedLastEventReference ) {
        super(
        	expectedLastEventReference != null && expectedLastEventReference.isPresent()?
		            "Optimistic locking failed. Expected last Event with EventReference %s/%d, was not last anymore for EventQuery: %s".formatted(
		            expectedLastEventReference.get().id() != null ? expectedLastEventReference.get().id().value() : -1,
		            expectedLastEventReference.get().position(),
		            query):
	    		            "Optimistic locking failed. Empty EventStream expected and Events found for EventQuery: %s".formatted(query)
        );
        this.query = query;
        this.expectedLastEventReference = expectedLastEventReference;
    }

    /**
     * Returns the EventQuery that was used in the failed AppendCriteria.
     * <p>
     * This query defines which events were considered relevant for the business decision.
     * It can be used to re-query the stream and retry the operation with fresh data.
     *
     * @return the EventQuery from the AppendCriteria, never null
     */
	public EventQuery getQuery() {
		return query;
	}

	/**
	 * Returns the expected last event reference from the failed AppendCriteria.
	 * <p>
	 * This reference indicates the last relevant event that was considered when making the
	 * business decision. If empty, it means an empty stream (no matching events) was expected.
	 * <p>
	 * The actual stream contained new matching events after this reference (or any events if empty),
	 * causing the optimistic locking check to fail.
	 *
	 * @return Optional containing the expected last EventReference, or empty if no events were expected
	 */
	public Optional<EventReference> getExpectedLastEventReference() {
		return expectedLastEventReference;
	}

}
