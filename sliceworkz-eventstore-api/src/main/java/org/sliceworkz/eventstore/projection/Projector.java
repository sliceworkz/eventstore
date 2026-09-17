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
package org.sliceworkz.eventstore.projection;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventPage;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.AppendListener;

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
 * class CustomerList implements Projection<CustomerEvent> {
 *     private final List<String> customers = new ArrayList<>();
 *
 *     @Override
 *     public EventQuery eventQuery() {
 *         return EventQuery.forEvents(EventTypesFilter.of(CustomerRegistered.class), Tags.none());
 *     }
 *
 *     @Override
 *     public void when(Event<CustomerEvent> event) {
 *         if (event.data() instanceof CustomerRegistered reg) {
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
 *     .into(projection)
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
 *     .into(projection)
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
 *     .into(projection)
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
 *     .into(projection)
 *     .inBatchesOf(100)  // Default is 500
 *     .build();
 *
 * projector.run();
 * }</pre>
 *
 * @param <CONSUMED_EVENT_TYPE> the type of domain events processed by the projection
 * @see Projection
 * @see ProjectorMetrics
 * @see EventSource
 */
public class Projector<CONSUMED_EVENT_TYPE> implements AppendListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(Projector.class);

	private int maxEventsPerQuery;
	
	private EventSource<CONSUMED_EVENT_TYPE> es;
	private Projection<CONSUMED_EVENT_TYPE> projection;
	
	private String bookmarkReader;
	private Tags bookmarkTags;
	private BookmarkRead bookmarkRead;
	
	private Optional<EventReference> lastEventReference = null;

	// published by a run and read from any thread, so a subscribed projector's metrics can be read
	// without waiting for the run in progress
	private volatile ProjectorMetrics accumulatedMetrics;
	
	private Projector ( EventSource<CONSUMED_EVENT_TYPE> es, Projection<CONSUMED_EVENT_TYPE> projection, EventReference after, int maxEventsPerQuery, String bookmarkReader, Tags bookmarkTags, BookmarkRead bookmarkRead ) {
		this.es = es;
		this.projection = projection;
		this.accumulatedMetrics = ProjectorMetrics.skipUntil(after);
		this.maxEventsPerQuery = maxEventsPerQuery;
		this.bookmarkReader = bookmarkReader;
		this.bookmarkTags = bookmarkTags;
		this.bookmarkRead = bookmarkRead;
		this.lastEventReference =  ( after == null ) ? null : Optional.ofNullable(after); // keep it to null to detect first run if needed
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
	 * The boundary is over <em>stored</em> events: every event the stored event at the boundary upcasts
	 * into is handled, whatever its index. So a reference obtained without upcasting — the stream's
	 * {@link org.sliceworkz.eventstore.stream.EventSource#head() head}, a bookmark read back — bounds a
	 * run without cutting the newest stored event in pieces.
	 *
	 * @param until the event reference to process up to (inclusive), or null to process to the end
	 * @return metrics about this projection run (events streamed, handled, queries done, and last event reference)
	 * @see #run()
	 */
	public ProjectorMetrics runUntil ( EventReference until ) {
		return runUntilInternal(until, false);
	}

	private synchronized ProjectorMetrics runUntilInternal ( EventReference until, boolean singleBatch ) {
		ProjectorRunResult result = new ProjectorRun().execute(until, singleBatch);
		accumulatedMetrics = accumulatedMetrics.add(result.metrics());
		
		if ( result.throwable() != null ) {
			throw result.throwable();
		}
		
		return result.metrics();
	}

	/**
	 * Manually reads the bookmark from the event source and updates the projector's position.
	 * <p>
	 * This method retrieves the bookmark associated with the configured reader name and uses it
	 * to set the last processed event reference. This is useful when:
	 * <ul>
	 *   <li>The projector is configured with {@link Builder#readBookmarkOnRequest()}</li>
	 *   <li>You want to synchronize the projector's position with an externally managed bookmark</li>
	 *   <li>You need to reset the projector to a previously saved position</li>
	 * </ul>
	 * <p>
	 * If no bookmark is found, the projector's position is reset to the start of the stream, so the
	 * next run replays it: a reader whose bookmark has been removed starts over, which is what removing
	 * a bookmark means. A bookmark is placed by the projector itself after every batch, or by hand using
	 * {@link EventSource#placeBookmark(String, EventReference, Tags)}.
	 * <p>
	 * A bookmarked projector calls this itself before every run, or before the first run only under
	 * {@link Builder#readBookmarkOnce()}; under {@link Builder#readBookmarkOnRequest()} this call is the
	 * only way the bookmark is read.
	 * <p>
	 * A run and a bookmark read never overlap: this method takes the same lock as {@link #run()},
	 * so called while a run is in progress -- a subscribed projector runs on the storage's notification
	 * thread -- it waits for that run to finish and then resets the position. The alternative -- moving
	 * the cursor while a run holds it -- loses because the run's next batch overwrites the reset with
	 * its own progress, so the read either has no effect or moves the cursor between two batches of
	 * one run, and nothing says which.
	 *
	 * @return this projector for method chaining
	 * @see Builder#bookmarkAs(String)
	 * @see Builder#readBookmarkOnce()
	 * @see Builder#readBookmarkOnRequest()
	 */
	public synchronized Projector<CONSUMED_EVENT_TYPE> readBookmark ( ) {
		Optional<EventReference> bookmarkReference = Optional.empty();
		if ( bookmarkReader != null ) {
			bookmarkReference = es.getBookmark(bookmarkReader);
			lastEventReference = bookmarkReference;
		}
		return this;
	}
	
	private class ProjectorRun {

		private long eventsStreamed = 0;
		private long eventsHandled = 0;
		private long queriesDone = 0;
		private EventReference currentEventReference; // required for identifying a poison event
		private EventReference mostRecentEventReference; // chronologically newest event seen (for optimistic locking)

		private ProjectorRunResult execute ( EventReference until, boolean singleBatch ) {
			boolean done = false;

			if ( bookmarkRead == BookmarkRead.BEFORE_EACH_RUN || ( bookmarkRead == BookmarkRead.ONCE && lastEventReference == null ) ) {
				readBookmark();
			}

			// Run initQuery on first execution only, if present and bookmarking is not enabled.
			// The initQuery enables the savepoint pattern: a backward query with limit 1 finds the most recent
			// savepoint event, initializing the read model without replaying the entire stream.
			// The main eventQuery then starts from that savepoint's reference.
			// On subsequent run() calls, lastEventReference is already set, so initQuery is skipped.
			EventQuery initQuery = projection.initQuery();
			if ( bookmarkReader == null && lastEventReference == null && initQuery != null && !initQuery.filter().isMatchNone() ) {
				// The boundary of a bounded run applies to the savepoint too: without it, runUntil() would
				// initialise the read model from the newest savepoint in the store -- possibly one written
				// after the requested point in time -- and then start the main query beyond the boundary,
				// reporting present-day state as a point-in-time projection.
				queriesDone++;
				try {
					es.query(initQuery.untilIfEarlier(until)).forEach(e -> {
						eventsStreamed++;
						eventsHandled++;
						if ( mostRecentEventReference == null || e.reference().happenedAfter(mostRecentEventReference) ) {
							mostRecentEventReference = e.reference();
						}
						currentEventReference = e.reference();
						projection.when(e);
						lastEventReference = Optional.of(e.reference());
					});
				} catch ( Throwable t ) {
					// A failing savepoint is reported like a failing batch: as a ProjectorException naming
					// the event, with the cursor back where the run started. Left where it was, a cursor
					// advanced by an earlier savepoint of the same query would make the next run skip the
					// init query and start the main query from a read model that was never initialised.
					lastEventReference = null;
					return result(new ProjectorException(t, currentEventReference));
				}
			}

			ProjectorException exception = null;

			Optional<EventReference> lastReadAtStart = lastEventReference;

			// what the bookmark already holds, so a batch that moved nothing places nothing
			Optional<EventReference> bookmarked = lastReadAtStart;

			// The projection's query, read once for the run. Storage is asked with it below and every
			// event of the run is matched against it, and reading it per event would let the two disagree
			// -- and cost a call per event for a projection that computes its query.
			EventQuery eventQuery = projection.eventQuery();
			if ( eventQuery == null ) {
				throw new IllegalStateException("projection %s answered null to eventQuery(); a projection has to say which events it depends on".formatted(projection));
			}

			// in order to avoid memory issues, we'll loop in pages of MAX_EVENTS_PER_QUERY stored events, until
			// no more events are found in the stream. The page size is the query's own limit where that is
			// lower: a projection asking for the newest 10 gets 10, not a page of 500 trimmed to 10
			Limit limit =  Limit.to(maxEventsPerQuery).orIfLower(eventQuery.limit());

			EventQuery effectiveQuery = eventQuery.untilIfEarlier ( until ).limit ( limit );

			Limit queryTotalLimit = eventQuery.limit();

			EventReference lastRead = lastEventReference==null?null:lastEventReference.orElse(null);

			while ( !done ) {

				Batch batch = new Batch(projection);

				// where this batch started. A batch that does not land takes the cursor back with it:
				// the projection rolled its own work back, so a cursor left beyond it would skip those
				// events for good on the next run
				Optional<EventReference> cursorBeforeBatch = lastEventReference;
				EventReference lastReadBeforeBatch = lastRead;

				try {

					queriesDone++;

					// A page is read whole, so a stored event this stream cannot read fails the batch
					// here, before any event of it reaches the projection; the catch below takes the
					// cursor back to where the batch started.
					EventPage<CONSUMED_EVENT_TYPE> page = es.page(effectiveQuery, lastRead);

					for ( Event<CONSUMED_EVENT_TYPE> e : page.events() ) {
						offerEventToProjection(e, eventQuery, until, batch);
					}

					if ( !page.events().isEmpty() ) {
						// the reference of the last event handed out, index included: what the run reports
						// and what the bookmark is placed at
						lastRead = page.events().getLast().reference();
						lastEventReference = Optional.of(lastRead);
					} else if ( page.lastStoredEventReference().isPresent() ) {
						// Storage returned stored events but upcasting produced zero enriched events
						// (e.g., all events in this page were filtered out by an upcaster returning List.of()).
						// Advance the cursor past these vanished events to avoid re-querying them.
						lastRead = page.lastStoredEventReference().get();
						lastEventReference = Optional.of(lastRead);
						// Do NOT set done — there may be more events beyond the vanished page.
					} else {
						// No stored events returned at all — we are truly at end of stream.
						done = true;
					}

					// If storage returned fewer stored events than the page size, we've exhausted the
					// stream — no need for another query that would return zero results.
					if ( !done && limit.isSet() && page.storedEventCount() < limit.value() ) {
						done = true;
					}

					// Ending the batch belongs inside the try, not in a finally. A projection commits
					// here, and a commit that fails means this batch did not land -- which has to reach
					// the catch below so the cursor goes back and the caller is told, rather than
					// escaping past the bookmark as a bare RuntimeException.
					batch.stopBatchIfNeeded(lastEventReference);

				} catch ( Throwable t ) {
					batch.failBatchIfNeeded(t);
					lastEventReference = cursorBeforeBatch;
					lastRead = lastReadBeforeBatch;
					exception = new ProjectorException(t, currentEventReference);
					break;
				}

				// The batch is durable now, so the bookmark may record it -- and does so per batch
				// rather than per run, because everything committed before a crash and not bookmarked
				// is projected a second time on restart.
				bookmarked = placeBookmarkIfMoved(bookmarked);

				if ( queryTotalLimit.isSet() && eventsStreamed >= queryTotalLimit.value() ) {
					break;
				}

				if ( singleBatch ) {
					break;
				}
			}

			if ( bookmarkReader != null && lastEventReference != null && lastEventReference.isPresent() ) {
				LOGGER.debug("readmodel {} updated until {} with {} queries", projection, lastEventReference, queriesDone);
			}


			return result(exception);
		}

		private ProjectorRunResult result ( ProjectorException exception ) {
			return new ProjectorRunResult ( new ProjectorMetrics ( eventsStreamed, eventsHandled, queriesDone, lastEventReference==null?null:lastEventReference.orElse(null), mostRecentEventReference), exception );
		}

		/**
		 * Writes the bookmark when the cursor has moved past what it already holds, and reports where
		 * it now stands.
		 * <p>
		 * Called once per committed batch. The ordering is load-bearing and one-directional: the batch
		 * is durable before the bookmark names it, so a crash in between costs a re-projection of that
		 * batch and never a silently skipped one. That is what makes projection at-least-once rather
		 * than at-most-once, and it is why a projection writing to a store of its own wants to record
		 * its own position inside its own transaction -- see {@link BatchAwareProjection#afterBatch}.
		 */
		private Optional<EventReference> placeBookmarkIfMoved ( Optional<EventReference> bookmarked ) {
			if ( bookmarkReader == null || lastEventReference == null || lastEventReference.isEmpty() ) {
				return bookmarked;
			}
			if ( lastEventReference.equals(bookmarked) ) {
				return bookmarked;
			}
			es.placeBookmark(bookmarkReader, lastEventReference.get(), bookmarkTags);
			return lastEventReference;
		}
	
		private void offerEventToProjection ( Event<CONSUMED_EVENT_TYPE> e, EventQuery eventQuery, EventReference until, Batch batch ) {
			this.eventsStreamed++;
			if ( mostRecentEventReference == null || e.reference().happenedAfter(mostRecentEventReference) ) {
				mostRecentEventReference = e.reference();
			}
			// the boundary is over stored events: every event the stored event at the boundary upcasts
			// into is at or before it, exactly as EventFilter.matches decides for the query itself
			if ( until == null || !e.reference().storedEventHappenedAfter(until) ) {
				if ( eventQuery.filter().matches(e) ) {
					batch.startBatchIfNeeded(e);
					currentEventReference = e.reference();
					projection.when(e);
					this.eventsHandled++;
				}
			}
		}

	}

	/**
	 * What a run came to: its metrics, and the failure if it did not complete. Both travel together
	 * because the metrics of a failed run are accumulated before the failure is thrown.
	 *
	 * @param metrics the metrics of the run, partial when it failed
	 * @param throwable the failure, or null when the run completed
	 */
	private record ProjectorRunResult ( ProjectorMetrics metrics, ProjectorException throwable ) {

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
	 * @param lastEventReference the reference to the last event processed (cursor position), or null if no events were processed
	 * @param mostRecentEventReference the chronologically newest event reference seen during processing, or null if no events were processed.
	 *        For forward queries this equals lastEventReference. For backward queries this is the first event returned (the newest),
	 *        which is the correct reference for optimistic locking via {@link org.sliceworkz.eventstore.stream.AppendCriteria}.
	 */
	public record ProjectorMetrics ( long eventsStreamed, long eventsHandled, long queriesDone, EventReference lastEventReference, EventReference mostRecentEventReference) {

		/**
		 * Combines these metrics with another set of metrics.
		 * <p>
		 * Used internally to accumulate metrics across multiple projection runs.
		 * The lastEventReference from the other metrics is used (as it's the most recent run).
		 * The mostRecentEventReference is the chronologically newest across both.
		 *
		 * @param other the metrics to add to these metrics
		 * @return a new ProjectorMetrics with combined values
		 */
		public ProjectorMetrics add ( ProjectorMetrics other) {
			EventReference newestMostRecent;
			if ( this.mostRecentEventReference == null ) {
				newestMostRecent = other.mostRecentEventReference;
			} else if ( other.mostRecentEventReference == null ) {
				newestMostRecent = this.mostRecentEventReference;
			} else {
				newestMostRecent = other.mostRecentEventReference.happenedAfter(this.mostRecentEventReference) ? other.mostRecentEventReference : this.mostRecentEventReference;
			}
			return new ProjectorMetrics(this.eventsStreamed+other.eventsStreamed, this.eventsHandled + other.eventsHandled, this.queriesDone + other.queriesDone, other.lastEventReference, newestMostRecent );
		}

		/**
		 * Creates empty metrics with all counts at zero and no event references.
		 *
		 * @return empty ProjectorMetrics
		 */
		public static ProjectorMetrics empty ( ) {
			return new ProjectorMetrics(0, 0, 0, null, null);
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
			return new ProjectorMetrics(0, 0, 0, lastEventReference, null);
		}

	}
	
	
	
	/**
	 * Starts building a projector over an event source.
	 * <p>
	 * The builder is flat: {@link Builder#into(Projection) into} names the projection, and everything else
	 * is optional -- {@link Builder#bookmarkAs(String) bookmarkAs}, {@link Builder#startingAfter(EventReference)
	 * startingAfter}, {@link Builder#inBatchesOf(int) inBatchesOf}, {@link Builder#subscribe() subscribe}.
	 *
	 * @param <EVENT_TYPE> the type of events to be processed
	 * @param eventSource the event source to read events from, typically an {@code EventStream}
	 * @return a builder over that source
	 * @throws IllegalArgumentException if the source is null
	 */
	public static <EVENT_TYPE> Builder<EVENT_TYPE> from ( EventSource<EVENT_TYPE> eventSource ) {
		if ( eventSource == null ) {
			throw new IllegalArgumentException("no event source: a projector reads from one, so from(...) needs it");
		}
		return new Builder<EVENT_TYPE>(eventSource);
	}

	/**
	 * Builds a {@link Projector}: a source, a projection, and optionally a bookmark, a starting position,
	 * a batch size and a subscription.
	 * <p>
	 * Every setting is a method on this one builder, so a projector reads as one chain:
	 * <pre>{@code
	 * Projector<CustomerEvent> projector = Projector.from(eventStream)
	 *     .into(myProjection)
	 *     .bookmarkAs("customer-list", Tags.of("tenant", "acme"))
	 *     .inBatchesOf(100)
	 *     .subscribe()
	 *     .build();
	 * }</pre>
	 *
	 * <h2>Bookmarking</h2>
	 * {@link #bookmarkAs(String)} names the reader whose bookmark records this projector's progress. The
	 * bookmark is written after every batch that moved the cursor, and read before every run, so a
	 * projector built with nothing but a reader name resumes where it left off after a restart and
	 * follows a bookmark rewound elsewhere. Two settings narrow when it is read:
	 * {@link #readBookmarkOnce()} reads it before the first run only, and {@link #readBookmarkOnRequest()}
	 * never reads it unless {@link Projector#readBookmark()} is called. Either without a reader is refused
	 * by {@link #build()}, since there is nothing to read.
	 *
	 * @param <EVENT_TYPE> the type of events to be processed
	 */
	public static class Builder<EVENT_TYPE> {

		/**
		 * Default maximum number of events to query in a single batch.
		 */
		public static final int DEFAULT_MAX_EVENTS_PER_QUERY = 500;

		private final EventSource<EVENT_TYPE> eventSource;
		private Projection<EVENT_TYPE> projection;
		private EventReference after;
		private boolean subscribe;
		private int maxEventsPerQuery = DEFAULT_MAX_EVENTS_PER_QUERY;

		private String bookmarkReader = null; // by default, no bookmarking is done
		private Tags bookmarkTags = Tags.none();
		private BookmarkRead bookmarkRead = BookmarkRead.BEFORE_EACH_RUN;
		private boolean bookmarkReadChosen = false;

		private Builder ( EventSource<EVENT_TYPE> eventSource ) {
			this.eventSource = eventSource;
		}

		/**
		 * Names the projection the events are projected into.
		 *
		 * @param projection the projection that defines the query and the event handler
		 * @return this builder for method chaining
		 */
		public Builder<EVENT_TYPE> into ( Projection<EVENT_TYPE> projection ) {
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
		 * @param maxEventsPerQuery the maximum number of events to query in each batch, at least 1
		 * @return this builder for method chaining
		 * @throws IllegalArgumentException if the batch size is not positive
		 */
		public Builder<EVENT_TYPE> inBatchesOf ( int maxEventsPerQuery ) {
			if ( maxEventsPerQuery < 1 ) {
				throw new IllegalArgumentException("a batch holds at least one event, not %d".formatted(maxEventsPerQuery));
			}
			this.maxEventsPerQuery = maxEventsPerQuery;
			return this;
		}

		/**
		 * Configures the projector to automatically subscribe to the event source for eventually consistent updates.
		 * <p>
		 * When enabled, the projector will automatically re-run whenever new events are appended to the event source,
		 * enabling near-real-time projection updates without manual polling. This is useful for:
		 * <ul>
		 *   <li>Live dashboards and read models that need to reflect recent changes quickly</li>
		 *   <li>Reactive projections that respond to events as they arrive</li>
		 *   <li>Systems where projection staleness needs to be minimized</li>
		 * </ul>
		 * <p>
		 * Note that the updates are <em>eventually consistent</em> - there may be a small delay between
		 * event append and projection update. The subscription mechanism is optimized for efficiency
		 * and may batch multiple events before triggering a projection run.
		 * <p>
		 * Without this setting, projections only update when explicitly triggered via {@link Projector#run()}.
		 * <p>
		 * <b>Lifetime.</b> Subscribing registers the event source with the storage, which then holds it —
		 * and this projector through it — until the source is closed. That is deliberate: it is what lets
		 * a live projection go on updating without the caller having to keep a variable alive for it. It
		 * also means the pair is released only by
		 * {@link org.sliceworkz.eventstore.stream.EventSource#close()}, or by closing the
		 * {@link org.sliceworkz.eventstore.EventStore} the source came from. Long-lived projections need
		 * nothing; one per request, per tenant or per test should close its source when done. The
		 * projector keeps no {@link org.sliceworkz.eventstore.stream.Subscription} handle of its own; to
		 * end its subscription without closing the source, build it without this setting and subscribe
		 * it yourself — {@code source.subscribe(projector)} — since a projector is an {@link AppendListener}.
		 *
		 * @return this builder for method chaining
		 * @see AppendListener
		 * @see org.sliceworkz.eventstore.stream.Subscription
		 * @see org.sliceworkz.eventstore.stream.EventSource#close()
		 */
		public Builder<EVENT_TYPE> subscribe ( ) {
			this.subscribe = true;
			return this;
		}

		/**
		 * Records this projector's progress as the bookmark of the named reader.
		 * <p>
		 * The reader name identifies the bookmark: projectors with different names keep independent
		 * positions, and two built with the same name share one -- which is what lets a restarted process
		 * resume where its predecessor left off. The bookmark is placed after every batch that moved the
		 * cursor, so a crash costs a re-projection of at most one batch, and it is read before every run
		 * unless {@link #readBookmarkOnce()} or {@link #readBookmarkOnRequest()} says otherwise.
		 * <p>
		 * A bookmarked projector ignores its projection's {@link Projection#initQuery() initQuery}: the
		 * bookmark already says where to resume, and a savepoint would be a second, competing answer.
		 *
		 * @param reader the name of the reader whose bookmark records the progress, not null and not blank
		 * @return this builder for method chaining
		 * @throws IllegalArgumentException if the reader name is null or blank
		 * @see EventSource#placeBookmark(String, EventReference, Tags)
		 * @see EventSource#getBookmark(String)
		 */
		public Builder<EVENT_TYPE> bookmarkAs ( String reader ) {
			if ( reader == null || reader.isBlank() ) {
				throw new IllegalArgumentException("bookmarking requires a reader name");
			}
			this.bookmarkReader = reader;
			return this;
		}

		/**
		 * Records this projector's progress as the bookmark of the named reader, stored with tags.
		 * <p>
		 * The tags are metadata on the bookmark -- a tenant, an environment, a version of the projection's
		 * schema, the instance that placed it -- and take no part in reading it back, which is by reader
		 * name alone. See {@link #bookmarkAs(String)} for what the reader name does.
		 *
		 * @param reader the name of the reader whose bookmark records the progress, not null and not blank
		 * @param tags the tags to store with the bookmark, {@link Tags#none()} for none
		 * @return this builder for method chaining
		 * @throws IllegalArgumentException if the reader name is null or blank, or the tags are null
		 */
		public Builder<EVENT_TYPE> bookmarkAs ( String reader, Tags tags ) {
			if ( tags == null ) {
				throw new IllegalArgumentException("bookmark tags must not be null; Tags.none() says there are none");
			}
			bookmarkAs(reader);
			this.bookmarkTags = tags;
			return this;
		}

		/**
		 * Reads the bookmark once, before the first run, and keeps the projector's own cursor from then on.
		 * <p>
		 * Where the default re-reads the bookmark before every run, this reads it at the first
		 * {@link Projector#run()}, {@link Projector#runSingleBatch()} or {@link Projector#runUntil(EventReference)}
		 * only, so a bookmark moved elsewhere afterwards is not followed. For a long-lived projector that is
		 * the only writer of its bookmark, that saves a lookup per run; the bookmark is still placed after
		 * every batch. {@link Projector#readBookmark()} re-reads it on demand.
		 *
		 * @return this builder for method chaining
		 * @see #bookmarkAs(String)
		 */
		public Builder<EVENT_TYPE> readBookmarkOnce ( ) {
			return readBookmark(BookmarkRead.ONCE);
		}

		/**
		 * Never reads the bookmark unless asked to, through {@link Projector#readBookmark()}.
		 * <p>
		 * The projector starts from {@link #startingAfter(EventReference)} -- or from the beginning -- and
		 * keeps its own cursor; the bookmark is still placed after every batch. This is the setting for a
		 * caller that holds the position itself, such as a projection recording its own position in its
		 * own store (see {@link BatchAwareProjection#afterBatch}), and for a test that wants to decide when
		 * the bookmark is consulted.
		 *
		 * @return this builder for method chaining
		 * @see Projector#readBookmark()
		 */
		public Builder<EVENT_TYPE> readBookmarkOnRequest ( ) {
			return readBookmark(BookmarkRead.ON_REQUEST);
		}

		private Builder<EVENT_TYPE> readBookmark ( BookmarkRead bookmarkRead ) {
			this.bookmarkRead = bookmarkRead;
			this.bookmarkReadChosen = true;
			return this;
		}

		/**
		 * Builds the Projector instance.
		 * <p>
		 * A projection is required, and is checked here rather than left to fail inside the first
		 * {@link Projector#run()}: a null there surfaces as a {@code NullPointerException} from the
		 * middle of a batch, after the bookmark has been read and, for a subscribed projector, after
		 * the source has been registered with the storage. A bookmark read setting without a reader is
		 * refused for the same reason: there is no bookmark to read, and a projector silently keeping
		 * its own cursor is not what the caller asked for.
		 *
		 * @return a new Projector configured with the builder's settings
		 * @throws IllegalStateException if no projection was configured, or a bookmark read setting was
		 *         chosen without a reader
		 */
		public Projector<EVENT_TYPE> build ( ) {
			if ( projection == null ) {
				throw new IllegalStateException("no projection configured, call into(...) before build()");
			}
			if ( bookmarkReadChosen && bookmarkReader == null ) {
				throw new IllegalStateException("no bookmark to read: call bookmarkAs(...) before choosing when the bookmark is read");
			}
			EventQuery initQuery = projection.initQuery();
			if ( bookmarkReader != null && initQuery != null && !initQuery.filter().isMatchNone() ) {
				LOGGER.warn("Projection has initQuery but bookmarking is enabled — initQuery will be ignored. Remove bookmarking for live-model use, or remove initQuery for full replay.");
			}
			Projector<EVENT_TYPE> projector = new Projector<>(eventSource, projection, after, maxEventsPerQuery, bookmarkReader, bookmarkTags, bookmarkRead);
			if ( subscribe ) {
				// subscribe for eventually consistent updates about event appends, so the projector will automatically trigger projection updates
				eventSource.subscribe(projector);
			}
			return projector;
		}

	}

	/**
	 * When a bookmarked projector reads its bookmark. Whichever is chosen, the bookmark is placed after
	 * every batch that moved the cursor.
	 */
	private enum BookmarkRead {
		/** Only on {@link Projector#readBookmark()}. */
		ON_REQUEST,
		/** Before the first run only. */
		ONCE,
		/** Before every run, the default. */
		BEFORE_EACH_RUN
	}
	
	/**
	 * Internal helper class that manages batch lifecycle callbacks for {@link BatchAwareProjection}s.
	 * <p>
	 * This class is responsible for detecting if a projection implements {@link BatchAwareProjection}
	 * and invoking the appropriate lifecycle methods at the correct times:
	 * <ul>
	 *   <li>{@link #startBatchIfNeeded(Event)} - Calls {@link BatchAwareProjection#beforeBatch()} before the first event</li>
	 *   <li>{@link #failBatchIfNeeded(Throwable)} - Calls {@link BatchAwareProjection#cancelBatch()} on error</li>
	 *   <li>{@link #stopBatchIfNeeded(Optional)} - Calls {@link BatchAwareProjection#afterBatch(Optional)} after the batch</li>
	 * </ul>
	 * <p>
	 * A batch is ended exactly once — by one of the last two, never both.
	 * <p>
	 * If the projection does not implement {@link BatchAwareProjection}, all methods are no-ops.
	 */
	class Batch {

		private BatchAwareProjection<CONSUMED_EVENT_TYPE> batchAwareProjection;
		private boolean started;

		// afterBatch or cancelBatch has been entered. A batch is ended exactly once, whichever way it
		// went: a projection whose commit threw has already released whatever it held, and rolling it
		// back afterwards would be a second ending of a transaction that no longer exists
		private boolean ended;

		/**
		 * Creates a new Batch instance for the given projection.
		 * <p>
		 * If the projection implements {@link BatchAwareProjection}, batch lifecycle callbacks
		 * will be invoked. Otherwise, this class acts as a no-op.
		 *
		 * @param projection the projection being processed
		 */
		public Batch ( Projection<CONSUMED_EVENT_TYPE> projection ) {
			if ( projection instanceof BatchAwareProjection<CONSUMED_EVENT_TYPE> bap ) {
				this.batchAwareProjection = bap;

			}
		}

		/**
		 * Starts the batch if needed by calling {@link BatchAwareProjection#beforeBatch()}.
		 * <p>
		 * This method is called before processing the first event in a batch. It will only
		 * invoke the callback once, even if called multiple times.
		 *
		 * @param e the first event to be processed
		 * @return the same event (for method chaining)
		 */
		Event<CONSUMED_EVENT_TYPE> startBatchIfNeeded ( Event<CONSUMED_EVENT_TYPE> e ) {
			if ( batchAwareProjection != null && !started ) {
				batchAwareProjection.beforeBatch();
				started = true;
			}
			return e;
		}

		/**
		 * Cancels the batch if needed by calling {@link BatchAwareProjection#cancelBatch()}.
		 * <p>
		 * This method is called when an exception occurs during batch processing.
		 * It will only invoke the callback if the batch was actually started and has not already
		 * been ended by {@link #stopBatchIfNeeded(Optional)}.
		 * <p>
		 * A {@code cancelBatch()} that throws is contained and attached to the failure that caused it
		 * as a suppressed exception, never allowed to replace it: a rollback failing is a consequence
		 * of whatever went wrong, and reporting the consequence instead of the cause is how a poison
		 * event comes to be reported as a rollback problem. This is what
		 * {@link BatchAwareProjection#cancelBatch()} has always documented.
		 *
		 * @param cause the failure being handled, which stays the one reported
		 */
		void failBatchIfNeeded ( Throwable cause ) {
			if ( batchAwareProjection != null && started && !ended ) {
				ended = true;
				try {
					batchAwareProjection.cancelBatch();
				} catch ( Throwable cancelFailure ) {
					LOGGER.error("cancelBatch of {} failed while handling {} -- reporting the original failure",
							projection, cause, cancelFailure);
					if ( cause != null && cancelFailure != cause ) {
						cause.addSuppressed(cancelFailure);
					}
				}
			}
		}

		/**
		 * Stops the batch if needed by calling {@link BatchAwareProjection#afterBatch(Optional)}.
		 * <p>
		 * This method is called after successfully processing all events in a batch.
		 * It will only invoke the callback if the batch was actually started and has not already
		 * been ended.
		 * <p>
		 * Anything it throws is the caller's to handle: it means the batch did not land.
		 *
		 * @param lastEventReference the reference of the last event processed, or empty if none
		 */
		void stopBatchIfNeeded ( Optional<EventReference> lastEventReference ) {
			if ( batchAwareProjection != null && started && !ended ) {
				ended = true;
				batchAwareProjection.afterBatch(lastEventReference);
			}
		}

	}

	/**
	 * Handles notifications of newly appended events when the projector is subscribed to an event source.
	 * <p>
	 * This method is called by the event source when new events are appended, triggering an automatic
	 * projection run to process the new events. It implements the {@link AppendListener}
	 * interface to enable reactive, near-real-time projection updates.
	 * <p>
	 * The method runs the projection and returns the reference of the last processed event. This allows
	 * the event source to track which events have been successfully processed by this projector.
	 * <p>
	 * This method is only invoked if the projector was configured with {@link Builder#subscribe()}.
	 *
	 * @param atLeastUntil the reference indicating events have been appended at least up to this point
	 * @return the reference of the last event processed by this projection run, or null if no events were processed
	 * @see Builder#subscribe()
	 */
	@Override
	public EventReference eventsAppended(EventReference atLeastUntil) {
		return run().lastEventReference();
	}

}
