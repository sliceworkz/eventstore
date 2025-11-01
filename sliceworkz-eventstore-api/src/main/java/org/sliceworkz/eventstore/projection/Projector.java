package org.sliceworkz.eventstore.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventSource;

public class Projector<CONSUMED_EVENT_TYPE> {

	private static final Logger LOGGER = LoggerFactory.getLogger(Projector.class);

	public static final int MAX_EVENTS_PER_QUERY = 500; // quite arbitrary, make configurable?  or not?
	
	private EventSource<CONSUMED_EVENT_TYPE> es;
	private Projection<CONSUMED_EVENT_TYPE> projection;
	
	private ProjectorMetrics accumulatedMetrics;
	
	public Projector ( EventSource<CONSUMED_EVENT_TYPE> es, Projection<CONSUMED_EVENT_TYPE> projection, EventReference after ) {
		this.es = es;
		this.projection = projection;
		this.accumulatedMetrics = ProjectorMetrics.skipUntil(after);
	}
	
	public Projector ( EventSource<CONSUMED_EVENT_TYPE> es, Projection<CONSUMED_EVENT_TYPE> projection ) {
		this(es, projection, null);
	}

	public ProjectorMetrics run ( ) {
		return runUntil(null);
	}
	
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
			
			Limit limit = Limit.to(MAX_EVENTS_PER_QUERY);
	
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
				if ( eventsStreamed - eventsStreamBeforeThisIteration < MAX_EVENTS_PER_QUERY ) {
					done = true;
				}
			}
			
			LOGGER.debug(String.format("readmodel %s updated until %s with %d queries", projection, lastEventReference, queriesDone));
			
			return  new ProjectorMetrics ( eventsStreamed, eventsHandled, queriesDone, lastEventReference );
		}
	
		private Event<CONSUMED_EVENT_TYPE> offerEventToProjection ( Event<CONSUMED_EVENT_TYPE> e, Projection<CONSUMED_EVENT_TYPE> projection, EventReference until ) {
			this.eventsStreamed++;
			if ( until == null || until.position() >= e.reference().position() ) {
				if ( projection.eventQuery().matches(e) ) {
					projection.when(e.data(), e.reference());
					this.eventsHandled++;
				}
			}
			return e;
		}
		
	}

	public EventQuery eventQuery ( ) {
		return projection.eventQuery();
	}
	
	public ProjectorMetrics accumulatedMetrics ( ) {
		return accumulatedMetrics;
	}
	
	public record ProjectorMetrics ( long eventsStreamed, long eventsHandled, long queriesDone, EventReference lastEventReference ) { 
		
		public ProjectorMetrics add ( ProjectorMetrics other) {
			return new ProjectorMetrics(this.eventsStreamed+other.eventsStreamed, this.eventsHandled + other.eventsHandled, this.queriesDone + other.queriesDone, other.lastEventReference);
		}
		
		public static ProjectorMetrics empty ( ) {
			return new ProjectorMetrics(0, 0, 0, null);
		}
		
		public static ProjectorMetrics skipUntil ( EventReference lastEventReference ) {
			return new ProjectorMetrics(0, 0, 0, lastEventReference);
		}

	}

}
