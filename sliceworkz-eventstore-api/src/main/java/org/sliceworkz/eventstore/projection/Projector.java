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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventSource;

/**
 * Processes a {@link Projection} by efficiently streaming events from an {@link EventSource} and applying them to the projection handler.
 * <p>
 * The Projector is the execution engine for projections. It queries events in configurable batches to avoid memory issues,
 * tracks progress via {@link EventReference}s, and provides detailed metrics about the projection execution.
 * Projectors can be run incrementally, processing only new events since the last run.
 * <p>
 * Key features:
 * <ul>
 *   <li>Batch processing - Queries events in configurable batch sizes (default 500) to prevent memory exhaustion</li>
 *   <li>Progress tracking - Maintains the last processed event reference for incremental updates</li>
 *   <li>Metrics - Returns detailed statistics about events streamed, events handled, and queries executed</li>
 *   <li>Resumable - Can continue from a specific event reference to process only new events</li>
 *   <li>Bounded execution - Can process events up to a specific point in time</li>
 * </ul>
 *
 * <h2>Example Usage - Basic Projection:</h2>
 * <pre>{@code
 * // Create a projection
 * class CustomerList implements ProjectionWithoutMetaData<CustomerEvent> {
 *     private final List<String> customers = new ArrayList<>();
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.forEvents(EventTypesFilter.of(CustomerRegistered.class), Tags.none());
 *     }
 *
 *     @Override
 *     public void when(CustomerEvent event) {
 *         if (event instanceof CustomerRegistered reg) {
 *             customers.add(reg.name());
 *         }
 *     }
 *
 *     public List<String> getCustomers() { return customers; }
 * }
 *
 * // Process the projection
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 * CustomerList projection = new CustomerList();
 *
 * ProjectorMetrics metrics = Projector.from(stream)
 *     .towards(projection)
 *     .build()
 *     .run();
 *
 * System.out.println("Processed " + metrics.eventsHandled() + " events in " + metrics.queriesDone() + " queries");
 * System.out.println("Customers: " + projection.getCustomers());
 * }</pre>
 *
 * <h2>Example Usage - Incremental Updates:</h2>
 * <pre>{@code
 * // Build a projector that remembers progress
 * EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class);
 * CustomerList projection = new CustomerList();
 *
 * Projector<CustomerEvent> projector = Projector.from(stream)
 *     .towards(projection)
 *     .build();
 *
 * // Initial run - process all historical events
 * ProjectorMetrics metrics1 = projector.run();
 * System.out.println("Initial: " + metrics1.eventsHandled() + " events");
 *
 * // ... time passes, new events are added ...
 *
 * // Incremental run - process only new events since last run
 * ProjectorMetrics metrics2 = projector.run();
 * System.out.println("Incremental: " + metrics2.eventsHandled() + " new events");
 *
 * // View accumulated metrics
 * ProjectorMetrics total = projector.accumulatedMetrics();
 * System.out.println("Total: " + total.eventsHandled() + " events");
 * }</pre>
 *
 * <h2>Example Usage - Bounded Processing:</h2>
 * <pre>{@code
 * // Process events up to a specific point in time
 * EventReference checkpoint = // ... get reference from somewhere ...
 *
 * ProjectorMetrics metrics = Projector.from(stream)
 *     .towards(projection)
 *     .build()
 *     .runUntil(checkpoint);
 *
 * System.out.println("Processed events up to: " + metrics.lastEventReference());
 * }</pre>
 *
 * <h2>Example Usage - Custom Batch Size:</h2>
 * <pre>{@code
 * // Process in smaller batches for fine-grained control
 * Projector<CustomerEvent> projector = Projector.from(stream)
 *     .towards(projection)
 *     .inBatchesOf(100)  // Default is 500
 *     .build();
 *
 * projector.run();
 * }</pre>
 *
 * @param <CONSUMED_EVENT_TYPE> the type of domain events processed by the projection
 * @see Projection
 * @see ProjectionWithoutMetaData
 * @see ProjectorMetrics
 * @see EventSource
 */
public class Projector<CONSUMED_EVENT_TYPE> {

	private static final Logger LOGGER = LoggerFactory.getLogger(Projector.class);

	private int maxEventsPerQuery;
	
	private EventSource<CONSUMED_EVENT_TYPE> es;
	private Projection<CONSUMED_EVENT_TYPE> projection;
	
	private ProjectorMetrics accumulatedMetrics;
	
	private Projector ( EventSource<CONSUMED_EVENT_TYPE> es, Projection<CONSUMED_EVENT_TYPE> projection, EventReference after, int maxEventsPerQuery ) {
		this.es = es;
		this.projection = projection;
		this.accumulatedMetrics = ProjectorMetrics.skipUntil(after);
		this.maxEventsPerQuery = maxEventsPerQuery;
	}

	/**
	 * Runs the projection from the last processed event to the end of the event stream.
	 * <p>
	 * This method processes all new events that have been added since the last run. On the first run,
	 * it processes all historical events. The projector maintains state across multiple runs, allowing
	 * for efficient incremental updates.
	 *
	 * @return metrics about this projection run (events streamed, handled, queries done, and last event reference)
	 * @see #runUntil(EventReference)
	 * @see #accumulatedMetrics()
	 */
	public ProjectorMetrics run ( ) {
		return runUntil(null);
	}

	/**
	 * Runs the projection from the last processed event up to a specific event reference.
	 * <p>
	 * This method allows bounded processing of events, useful for:
	 * <ul>
	 *   <li>Creating point-in-time projections (snapshots)</li>
	 *   <li>Testing projections up to a known state</li>
	 *   <li>Controlled incremental updates</li>
	 * </ul>
	 *
	 * @param until the event reference to process up to (inclusive), or null to process to the end
	 * @return metrics about this projection run (events streamed, handled, queries done, and last event reference)
	 * @see #run()
	 */
	public ProjectorMetrics runUntil ( EventReference until ) {
		ProjectorMetrics metrics = new ProjectorRun().execute(accumulatedMetrics.lastEventReference, until);
		accumulatedMetrics = accumulatedMetrics.add(metrics);
		return metrics;
	}
	
	protected class ProjectorRun {

		private long eventsStreamed = 0;
		private long eventsHandled = 0;
		private long queriesDone = 0;
		private EventReference lastEventReference = null;

		protected ProjectorMetrics execute ( EventReference lastRead, EventReference until ) {
			boolean done = false;
			
			lastEventReference = lastRead;
			
			Limit limit = Limit.to(maxEventsPerQuery);
	
			// in order to avoid memory issues, we'll loop in batches om MAX_EVENTS_PER_QUERY, until no more events are found in the stream
			
			EventQuery effectiveQuery = projection.eventQuery().untilIfEarlier ( until );
			
			while ( !done ) {
			
				queriesDone++;
				long eventsStreamBeforeThisIteration = eventsStreamed;
				
				lastRead = es.query(effectiveQuery, lastRead, limit).map(e->offerEventToProjection(e, projection, until)).map(e->e.reference()).reduce((first, second) -> second).orElse(null);
				
				// if we still read data, keep the reference
				if ( lastRead != null ) {
					lastEventReference = lastRead; 
				} else {
					// otherwise, we were at the end of the stream
					done = true;
				}
				
				// if we got less events than we could, we reached the end of the stream
				if ( eventsStreamed - eventsStreamBeforeThisIteration < maxEventsPerQuery ) {
					done = true;
				}
			}
			
			LOGGER.debug("readmodel {} updated until {} with {} queries", projection, lastEventReference, queriesDone);
			
			return  new ProjectorMetrics ( eventsStreamed, eventsHandled, queriesDone, lastEventReference );
		}
	
		private Event<CONSUMED_EVENT_TYPE> offerEventToProjection ( Event<CONSUMED_EVENT_TYPE> e, Projection<CONSUMED_EVENT_TYPE> projection, EventReference until ) {
			this.eventsStreamed++;
			if ( until == null || until.position() >= e.reference().position() ) {
				if ( projection.eventQuery().matches(e) ) {
					projection.when(e);
					this.eventsHandled++;
				}
			}
			return e;
		}
		
	}

	/**
	 * Returns the event query used by the projection.
	 * <p>
	 * This is a convenience method that delegates to the underlying projection's eventQuery() method.
	 *
	 * @return the EventQuery defining which events the projection processes
	 */
	public EventQuery eventQuery ( ) {
		return projection.eventQuery();
	}

	/**
	 * Returns the accumulated metrics across all runs of this projector.
	 * <p>
	 * This includes the total count of events streamed, events handled, and queries done across
	 * all invocations of {@link #run()} or {@link #runUntil(EventReference)}. The last event reference
	 * indicates the current position in the event stream.
	 *
	 * @return accumulated metrics from all projection runs
	 * @see ProjectorMetrics
	 */
	public ProjectorMetrics accumulatedMetrics ( ) {
		return accumulatedMetrics;
	}
	
	/**
	 * Metrics about projection execution.
	 * <p>
	 * Provides detailed statistics about a projection run, including:
	 * <ul>
	 *   <li>eventsStreamed - Total events retrieved from the event source (may be filtered out)</li>
	 *   <li>eventsHandled - Events actually processed by the projection handler</li>
	 *   <li>queriesDone - Number of batch queries executed</li>
	 *   <li>lastEventReference - Reference to the last processed event (for resumption)</li>
	 * </ul>
	 * The difference between eventsStreamed and eventsHandled indicates how many events were filtered out
	 * by the projection's event query.
	 *
	 * @param eventsStreamed the total number of events streamed from the event source
	 * @param eventsHandled the number of events actually processed by the projection handler
	 * @param queriesDone the number of batch queries executed against the event source
	 * @param lastEventReference the reference to the last event processed, or null if no events were processed
	 */
	public record ProjectorMetrics ( long eventsStreamed, long eventsHandled, long queriesDone, EventReference lastEventReference ) {

		/**
		 * Combines these metrics with another set of metrics.
		 * <p>
		 * Used internally to accumulate metrics across multiple projection runs.
		 * The lastEventReference from the other metrics is used (as it's the most recent).
		 *
		 * @param other the metrics to add to these metrics
		 * @return a new ProjectorMetrics with combined values
		 */
		public ProjectorMetrics add ( ProjectorMetrics other) {
			return new ProjectorMetrics(this.eventsStreamed+other.eventsStreamed, this.eventsHandled + other.eventsHandled, this.queriesDone + other.queriesDone, other.lastEventReference);
		}

		/**
		 * Creates empty metrics with all counts at zero and no last event reference.
		 *
		 * @return empty ProjectorMetrics
		 */
		public static ProjectorMetrics empty ( ) {
			return new ProjectorMetrics(0, 0, 0, null);
		}

		/**
		 * Creates metrics indicating a starting position without any processing done.
		 * <p>
		 * Used to initialize a projector that should skip events up to a specific reference.
		 *
		 * @param lastEventReference the reference to start from
		 * @return ProjectorMetrics with zero counts and the specified reference
		 */
		public static ProjectorMetrics skipUntil ( EventReference lastEventReference ) {
			return new ProjectorMetrics(0, 0, 0, lastEventReference);
		}

	}
	
	
	
	/**
	 * Creates a new builder for constructing a Projector.
	 *
	 * @param <EVENT_TYPE> the type of events to be processed
	 * @return a new Builder instance
	 */
	public static <EVENT_TYPE> Builder<EVENT_TYPE> newBuilder ( ) {
		return new Builder<EVENT_TYPE>( );
	}

	/**
	 * Creates a new builder with the event source pre-configured.
	 * <p>
	 * Convenience method equivalent to {@code newBuilder().from(eventSource)}.
	 *
	 * @param <EVENT_TYPE> the type of events to be processed
	 * @param eventSource the event source to read events from
	 * @return a new Builder instance with the event source configured
	 */
	public static <EVENT_TYPE> Builder<EVENT_TYPE> from ( EventSource<EVENT_TYPE> eventSource ) {
		return new Builder<EVENT_TYPE> ( ).from(eventSource);
	}

	/**
	 * Builder for constructing Projector instances with fluent API.
	 * <p>
	 * Allows configuration of:
	 * <ul>
	 *   <li>Event source - where to read events from</li>
	 *   <li>Projection - what to do with the events</li>
	 *   <li>Starting position - where in the event stream to begin</li>
	 *   <li>Batch size - how many events to query at once</li>
	 * </ul>
	 *
	 * <h2>Example:</h2>
	 * <pre>{@code
	 * Projector<CustomerEvent> projector = Projector.<CustomerEvent>newBuilder()
	 *     .from(eventStream)
	 *     .towards(myProjection)
	 *     .startingAfter(lastProcessedRef)
	 *     .inBatchesOf(100)
	 *     .build();
	 * }</pre>
	 *
	 * @param <EVENT_TYPE> the type of events to be processed
	 */
	public static class Builder<EVENT_TYPE> {

		/**
		 * Default maximum number of events to query in a single batch.
		 */
		public static final int DEFAULT_MAX_EVENTS_PER_QUERY = 500;

		private EventSource<EVENT_TYPE> eventSource;
		private Projection<EVENT_TYPE> projection;
		private EventReference after;
		private int maxEventsPerQuery = DEFAULT_MAX_EVENTS_PER_QUERY;

		/**
		 * Configures the event source from which to read events.
		 *
		 * @param eventSource the event source (typically an EventStream)
		 * @return this builder for method chaining
		 */
		public Builder<EVENT_TYPE> from ( EventSource<EVENT_TYPE> eventSource ) {
			this.eventSource = eventSource;
			return this;
		}

		/**
		 * Configures the projection to process events.
		 *
		 * @param projection the projection that defines the query and event handler
		 * @return this builder for method chaining
		 */
		public Builder<EVENT_TYPE> towards ( Projection<EVENT_TYPE> projection ) {
			this.projection = projection;
			return this;
		}

		/**
		 * Configures the starting position in the event stream.
		 * <p>
		 * Events before this reference will be skipped. Useful for resuming projection from a checkpoint
		 * or processing only recent events.
		 *
		 * @param after the event reference to start after, or null to start from the beginning
		 * @return this builder for method chaining
		 */
		public Builder<EVENT_TYPE> startingAfter ( EventReference after ) {
			this.after = after;
			return this;
		}

		/**
		 * Configures the batch size for querying events.
		 * <p>
		 * The projector queries events in batches to avoid memory issues with large event streams.
		 * Smaller batch sizes reduce memory usage but increase the number of queries.
		 * The default is {@value #DEFAULT_MAX_EVENTS_PER_QUERY}.
		 *
		 * @param maxEventsPerQuery the maximum number of events to query in each batch
		 * @return this builder for method chaining
		 */
		public Builder<EVENT_TYPE> inBatchesOf ( int maxEventsPerQuery ) {
			this.maxEventsPerQuery = maxEventsPerQuery;
			return this;
		}

		/**
		 * Builds the Projector instance.
		 *
		 * @return a new Projector configured with the builder's settings
		 */
		public Projector<EVENT_TYPE> build ( ) {
			return new Projector<>(eventSource, projection, after, maxEventsPerQuery);
		}

	}

}
