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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
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
 * Example Usage - Basic Projection:
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
 * Example Usage - Incremental Updates:
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
 * Example Usage - Bounded Processing:
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
 * Example Usage - Custom Batch Size:
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
	
	private String bookmarkReader;
	private Tags bookmarkTags;
	private BookmarkReadFrequency bookmarkReadFrequency;
	
	private Optional<EventReference> lastEventReference = null;

	private ProjectorMetrics accumulatedMetrics;
	
	private Projector ( EventSource<CONSUMED_EVENT_TYPE> es, Projection<CONSUMED_EVENT_TYPE> projection, EventReference after, int maxEventsPerQuery, String bookmarkReader, Tags bookmarkTags, BookmarkReadFrequency bookmarkReadFrequency ) {
		this.es = es;
		this.projection = projection;
		this.accumulatedMetrics = ProjectorMetrics.skipUntil(after);
		this.maxEventsPerQuery = maxEventsPerQuery;
		this.bookmarkReader = bookmarkReader;
		this.bookmarkTags = bookmarkTags;
		this.bookmarkReadFrequency = bookmarkReadFrequency;
		this.lastEventReference =  ( after == null ) ? null : Optional.ofNullable(after); // keep it to null to detect first run if needed
		if ( bookmarkReadFrequency == BookmarkReadFrequency.AT_CREATION ) {
			readBookmark();
		}
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
		return runUntilInternal(null, false);
	}

	/**
	 * Runs the projection for a single batch of events from the last processed event.
	 * <p>
	 * Unlike {@link #run()} which processes all available events in multiple batches,
	 * this method processes only one batch of events (sized according to the configured
	 * batch size). This is useful for:
	 * <ul>
	 *   <li>Fine-grained control over projection execution</li>
	 *   <li>Implementing cooperative multitasking between multiple projectors</li>
	 *   <li>Testing projection behavior batch by batch</li>
	 *   <li>Rate-limiting projection updates</li>
	 * </ul>
	 * <p>
	 * Multiple calls to this method will incrementally process the event stream one batch at a time.
	 * The projector maintains its position across calls, so subsequent invocations will continue
	 * from where the previous batch ended.
	 *
	 * @return metrics about this projection run (events streamed, handled, queries done, and last event reference)
	 * @see #run()
	 * @see #runUntil(EventReference)
	 * @see #accumulatedMetrics()
	 */
	public ProjectorMetrics runSingleBatch ( ) {
		return runUntilInternal(null, true);
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
		return runUntilInternal(until, false);
	}

	private ProjectorMetrics runUntilInternal ( EventReference until, boolean singleBatch ) {
		ProjectorMetrics metrics = new ProjectorRun().execute(until, singleBatch);
		accumulatedMetrics = accumulatedMetrics.add(metrics);
		return metrics;
	}

	/**
	 * Manually reads the bookmark from the event source and updates the projector's position.
	 * <p>
	 * This method retrieves the bookmark associated with the configured reader name and uses it
	 * to set the last processed event reference. This is useful when:
	 * <ul>
	 *   <li>The projector is configured with {@code readOnManualTriggerOnly()}</li>
	 *   <li>You want to synchronize the projector's position with an externally managed bookmark</li>
	 *   <li>You need to reset the projector to a previously saved position</li>
	 * </ul>
	 * <p>
	 * If no bookmark is found, the projector's position remains unchanged. The bookmark must
	 * have been previously placed using {@link EventSource#placeBookmark(String, EventReference, Tags)}.
	 * <p>
	 * Depending on the configured {@code BookmarkReadFrequency}, this method may be called
	 * automatically at different times (at creation, before first execution, or before each execution).
	 * When configured for manual trigger only, this is the only way to load the bookmark.
	 *
	 * @return this projector for method chaining
	 * @see Builder.BookmarkBuilder#readOnManualTriggerOnly()
	 * @see Builder.BookmarkBuilder#readAtCreationOnly()
	 * @see Builder.BookmarkBuilder#readBeforeFirstExecution()
	 * @see Builder.BookmarkBuilder#readBeforeEachExecution()
	 */
	public Projector<CONSUMED_EVENT_TYPE> readBookmark ( ) {
		Optional<EventReference> bookmarkReference = Optional.empty();
		if ( bookmarkReader != null ) {
			bookmarkReference = es.getBookmark(bookmarkReader);
			lastEventReference = bookmarkReference;
		}
		return this;
	}
	
	protected class ProjectorRun {

		private long eventsStreamed = 0;
		private long eventsHandled = 0;
		private long queriesDone = 0;

		protected ProjectorMetrics execute ( EventReference until, boolean singleBatch ) {
			boolean done = false;
			
			if ( ( bookmarkReadFrequency == BookmarkReadFrequency.BEFORE_EACH_EXECUTION) || 
				 ((bookmarkReadFrequency == BookmarkReadFrequency.BEFORE_FIRST_EXECUTION) && (lastEventReference==null) )
				) {
				readBookmark();
			}
			
			Optional<EventReference> lastReadAtStart = lastEventReference;
			
			// in order to avoid memory issues, we'll loop in batches om MAX_EVENTS_PER_QUERY, until no more events are found in the stream
			Limit limit = Limit.to(maxEventsPerQuery);
	
			
			EventQuery effectiveQuery = projection.eventQuery().untilIfEarlier ( until );
			
			EventReference lastRead = lastEventReference==null?null:lastEventReference.orElse(null);
			
			while ( !done ) {
			
				queriesDone++;
				long eventsStreamBeforeThisIteration = eventsStreamed;
				
				lastRead = es.query(effectiveQuery, lastRead, limit).map(e->offerEventToProjection(e, projection, until)).map(e->e.reference()).reduce((first, second) -> second).orElse(null);
				
				// if we still read data, keep the reference
				if ( lastRead != null ) {
					lastEventReference = Optional.of(lastRead); 
				} else {
					// otherwise, we were at the end of the stream
					done = true;
				}
				
				// if we got less events than we could, we reached the end of the stream
				if ( eventsStreamed - eventsStreamBeforeThisIteration < maxEventsPerQuery ) {
					done = true;
				}
				
				if ( singleBatch ) {
					break;
				}
			}
			
			// if we have something new to put in the bookmark
			if ( bookmarkReader != null && lastEventReference != null && lastEventReference.isPresent() ) {
				if ( !lastEventReference.equals(lastReadAtStart)) {
					es.placeBookmark(bookmarkReader, lastEventReference.get(), bookmarkTags);
				}
			}
			
			LOGGER.debug("readmodel {} updated until {} with {} queries", projection, lastEventReference, queriesDone);
			
			return  new ProjectorMetrics ( eventsStreamed, eventsHandled, queriesDone, lastEventReference.orElse(null) );
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
	 * Example:
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

		private BookmarkBuilder bookmarkBuilder = new BookmarkBuilder(this);

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
		 * Provides access to the bookmark configuration builder.
		 * <p>
		 * Bookmarking allows the projector to automatically save and restore its position
		 * in the event stream, enabling projectors to resume from where they left off across
		 * application restarts or different instances.
		 * <p>
		 * Example usage:
		 * <pre>{@code
		 * Projector<CustomerEvent> projector = Projector.from(stream)
		 *     .towards(projection)
		 *     .bookmarkProgress()
		 *         .withReader("customer-list-projection")
		 *         .withTags(Tags.of("tenant", "acme"))
		 *         .readBeforeEachExecution()
		 *         .done()
		 *     .build();
		 * }</pre>
		 *
		 * @return the BookmarkBuilder for configuring bookmark behavior
		 * @see BookmarkBuilder
		 */
		public BookmarkBuilder bookmarkProgress ( ) {
			return bookmarkBuilder;
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
			return new Projector<>(eventSource, projection, after, maxEventsPerQuery, bookmarkBuilder.readerName, bookmarkBuilder.tags, bookmarkBuilder.bookmarkReadFrequency);
		}

		/**
		 * Builder for configuring bookmark-based progress tracking in projectors.
		 * <p>
		 * Bookmarking enables projectors to automatically save and restore their position in the event stream,
		 * allowing them to resume processing from where they left off. This is essential for:
		 * <ul>
		 *   <li>Projectors that run across application restarts</li>
		 *   <li>Distributed projectors running on multiple instances</li>
		 *   <li>Long-running projections that process events incrementally</li>
		 *   <li>Projections that need to synchronize position across systems</li>
		 * </ul>
		 * <p>
		 * A bookmark consists of:
		 * <ul>
		 *   <li>A reader name - unique identifier for this projector's bookmark</li>
		 *   <li>An event reference - the position in the event stream</li>
		 *   <li>Optional tags - additional metadata for filtering or organizing bookmarks</li>
		 * </ul>
		 * <p>
		 * The bookmark can be read at different frequencies:
		 * <ul>
		 *   <li>{@link #readOnManualTriggerOnly()} - Only via explicit {@link Projector#readBookmark()} calls</li>
		 *   <li>{@link #readAtCreationOnly()} - Once when the projector is created</li>
		 *   <li>{@link #readBeforeFirstExecution()} - Before the first run, but not subsequent runs</li>
		 *   <li>{@link #readBeforeEachExecution()} - Before every run (default)</li>
		 * </ul>
		 * <p>
		 * Bookmarks are automatically saved after each projection run if new events were processed.
		 *
		 * Example - Basic Bookmarking:
		 * <pre>{@code
		 * Projector<CustomerEvent> projector = Projector.from(stream)
		 *     .towards(projection)
		 *     .bookmarkProgress()
		 *         .withReader("customer-list")
		 *         .done()
		 *     .build();
		 *
		 * // First run processes all historical events and saves bookmark
		 * projector.run();
		 *
		 * // Later runs only process new events since the bookmark
		 * projector.run();
		 * }</pre>
		 *
		 * Example - Multi-tenant Bookmarking:
		 * <pre>{@code
		 * Projector<OrderEvent> projector = Projector.from(stream)
		 *     .towards(projection)
		 *     .bookmarkProgress()
		 *         .withReader("order-summary")
		 *         .withTags(Tags.of("tenant", "acme-corp"))
		 *         .done()
		 *     .build();
		 * }</pre>
		 *
		 * Example - Manual Bookmark Control:
		 * <pre>{@code
		 * Projector<PaymentEvent> projector = Projector.from(stream)
		 *     .towards(projection)
		 *     .bookmarkProgress()
		 *         .withReader("payment-processor")
		 *         .readOnManualTriggerOnly()
		 *         .done()
		 *     .build();
		 *
		 * // Manually control when to read the bookmark
		 * projector.readBookmark();
		 * projector.run();
		 * }</pre>
		 */
		public class BookmarkBuilder {

			private Builder<EVENT_TYPE> parent;
			private BookmarkReadFrequency bookmarkReadFrequency = BookmarkReadFrequency.BEFORE_EACH_EXECUTION;
			private String readerName = null; // by default, no bookmarking is done
			private Tags tags = Tags.none();

			public BookmarkBuilder ( Builder<EVENT_TYPE> builder ) {
				this.parent = builder;
			}

			/**
			 * Configures the unique name for this projector's bookmark.
			 * <p>
			 * The reader name uniquely identifies this projector's position in the event stream.
			 * Multiple projectors can have different reader names to maintain independent positions,
			 * while projectors with the same reader name will share their position.
			 * <p>
			 * This is required when using bookmarking - the {@link #done()} method will throw
			 * an exception if no reader name is configured.
			 *
			 * @param readerName the unique identifier for this projector's bookmark (must not be null)
			 * @return this builder for method chaining
			 */
			public BookmarkBuilder withReader ( String readerName ) {
				this.readerName = readerName;
				return this;
			}

			/**
			 * Configures additional tags to store with the bookmark.
			 * <p>
			 * Tags provide additional metadata for organizing and filtering bookmarks.
			 * Common use cases include:
			 * <ul>
			 *   <li>Tenant identification in multi-tenant systems</li>
			 *   <li>Environment labels (dev, staging, production)</li>
			 *   <li>Version tracking for projection schema changes</li>
			 *   <li>Instance identification in distributed systems</li>
			 * </ul>
			 *
			 * @param tags the tags to associate with the bookmark (defaults to Tags.none())
			 * @return this builder for method chaining
			 */
			public BookmarkBuilder withTags ( Tags tags ) {
				this.tags = tags;
				return this;
			}

			/**
			 * Configures the projector to only read bookmarks when explicitly triggered.
			 * <p>
			 * With this setting, bookmarks are never read automatically. The application must
			 * explicitly call {@link Projector#readBookmark()} to load the bookmark position.
			 * This provides maximum control over when the projector's position is synchronized.
			 * <p>
			 * Bookmarks are still automatically saved after each run if new events were processed.
			 * <p>
			 * Use this when:
			 * <ul>
			 *   <li>You need explicit control over position synchronization</li>
			 *   <li>The bookmark may be managed by external systems</li>
			 *   <li>You want to prevent automatic position updates</li>
			 * </ul>
			 *
			 * @return this builder for method chaining
			 * @see Projector#readBookmark()
			 */
			public BookmarkBuilder readOnManualTriggerOnly ( ) {
				this.bookmarkReadFrequency = BookmarkReadFrequency.MANUAL_TRIGGER;
				return this;
			}

			/**
			 * Configures the projector to read the bookmark only when created.
			 * <p>
			 * The bookmark is read once during projector construction. Subsequent runs will not
			 * re-read the bookmark, even if it's updated externally. This is useful when:
			 * <ul>
			 *   <li>The projector's position is set once at startup</li>
			 *   <li>You want to prevent mid-run position changes</li>
			 *   <li>The bookmark is only used for initial positioning</li>
			 * </ul>
			 * <p>
			 * Bookmarks are still automatically saved after each run if new events were processed.
			 *
			 * @return this builder for method chaining
			 */
			public BookmarkBuilder readAtCreationOnly ( ) {
				this.bookmarkReadFrequency = BookmarkReadFrequency.AT_CREATION;
				return this;
			}

			/**
			 * Configures the projector to read the bookmark before the first execution only.
			 * <p>
			 * The bookmark is read before the first call to {@link Projector#run()}, {@link Projector#runSingleBatch()},
			 * or {@link Projector#runUntil(EventReference)}. Subsequent executions will not re-read the bookmark.
			 * <p>
			 * This differs from {@link #readAtCreationOnly()} because it delays reading until the first actual
			 * execution, allowing the bookmark to be updated between construction and first run.
			 * <p>
			 * Bookmarks are still automatically saved after each run if new events were processed.
			 *
			 * @return this builder for method chaining
			 */
			public BookmarkBuilder readBeforeFirstExecution ( ) {
				this.bookmarkReadFrequency = BookmarkReadFrequency.BEFORE_FIRST_EXECUTION;
				return this;
			}

			/**
			 * Configures the projector to read the bookmark before each execution (default).
			 * <p>
			 * The bookmark is re-read before every call to {@link Projector#run()}, {@link Projector#runSingleBatch()},
			 * or {@link Projector#runUntil(EventReference)}. This ensures the projector always starts from the
			 * most recent bookmark position, which is useful for:
			 * <ul>
			 *   <li>Distributed projectors where position may be updated by other instances</li>
			 *   <li>Projections that may be rewound by external systems</li>
			 *   <li>Coordinated projector restarts across a cluster</li>
			 * </ul>
			 * <p>
			 * This is the default behavior when bookmarking is enabled.
			 * <p>
			 * Bookmarks are still automatically saved after each run if new events were processed.
			 *
			 * @return this builder for method chaining
			 */
			public BookmarkBuilder readBeforeEachExecution ( ) {
				this.bookmarkReadFrequency = BookmarkReadFrequency.BEFORE_EACH_EXECUTION;
				return this;
			}

			/**
			 * Completes bookmark configuration and returns to the main builder.
			 * <p>
			 * This method validates that a reader name has been configured and returns control
			 * to the parent {@link Builder} to continue configuring the projector.
			 *
			 * @return the parent Builder for method chaining
			 * @throws IllegalArgumentException if no reader name was configured via {@link #withReader(String)}
			 */
			public Builder<EVENT_TYPE> done ( ) {
				if ( readerName == null ) {
					throw new IllegalArgumentException("bookmarking requires a reader name");
				}
				return parent;
			}

		}
		
	}

	/**
	 * Defines when a projector should read its bookmark to determine the starting position.
	 * <p>
	 * This enum controls the timing of bookmark reads, allowing fine-grained control over
	 * when the projector synchronizes its position with the persisted bookmark. Different
	 * frequencies suit different use cases:
	 * <ul>
	 *   <li>{@link #MANUAL_TRIGGER} - For explicit control via {@link Projector#readBookmark()}</li>
	 *   <li>{@link #AT_CREATION} - For single startup synchronization</li>
	 *   <li>{@link #BEFORE_FIRST_EXECUTION} - For delayed initial synchronization</li>
	 *   <li>{@link #BEFORE_EACH_EXECUTION} - For continuous synchronization (default)</li>
	 * </ul>
	 * <p>
	 * Regardless of the read frequency, bookmarks are always written after each successful
	 * projection run that processes new events.
	 *
	 * @see Projector.Builder.BookmarkBuilder
	 */
	private enum BookmarkReadFrequency {
		/**
		 * Bookmarks are only read when explicitly triggered via {@link Projector#readBookmark()}.
		 * <p>
		 * No automatic bookmark reading occurs. The application has full control over when
		 * the projector's position is synchronized with the persisted bookmark.
		 *
		 * @see Projector.Builder.BookmarkBuilder#readOnManualTriggerOnly()
		 */
		MANUAL_TRIGGER,

		/**
		 * The bookmark is read once when the projector is constructed.
		 * <p>
		 * Subsequent runs will not re-read the bookmark, even if it's updated externally.
		 * The projector's position is fixed at construction time.
		 *
		 * @see Projector.Builder.BookmarkBuilder#readAtCreationOnly()
		 */
		AT_CREATION,

		/**
		 * The bookmark is read before the first execution only.
		 * <p>
		 * The first call to {@link Projector#run()}, {@link Projector#runSingleBatch()},
		 * or {@link Projector#runUntil(EventReference)} will read the bookmark.
		 * Subsequent executions will not re-read it.
		 *
		 * @see Projector.Builder.BookmarkBuilder#readBeforeFirstExecution()
		 */
		BEFORE_FIRST_EXECUTION,

		/**
		 * The bookmark is re-read before every execution (default).
		 * <p>
		 * Every call to {@link Projector#run()}, {@link Projector#runSingleBatch()},
		 * or {@link Projector#runUntil(EventReference)} will read the current bookmark,
		 * ensuring the projector always starts from the most recent persisted position.
		 *
		 * @see Projector.Builder.BookmarkBuilder#readBeforeEachExecution()
		 */
		BEFORE_EACH_EXECUTION
	}

}
