package org.sliceworkz.eventstore;

import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public interface EventStore {

	<DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Class<?>... eventRootClass );
	
}