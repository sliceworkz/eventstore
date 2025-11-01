package org.sliceworkz.eventstore.projection;

import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * A Projection combines an {@link EventQuery} with an {@link EventHandler}, 
 * allowing all events that comply with the criteria of the query to be handled.
 */
public interface Projection<CONSUMED_EVENT_TYPE> extends EventHandler<CONSUMED_EVENT_TYPE> {

	/*
	 * Returns the EventQuery for the Events the Projection depends on.  
	 */
	EventQuery eventQuery ( ); 
	
}
