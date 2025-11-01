package org.sliceworkz.eventstore.stream;

public interface EventStream<DOMAIN_EVENT_TYPE> extends EventSource<DOMAIN_EVENT_TYPE>, EventSink<DOMAIN_EVENT_TYPE> {

	EventStreamId id ( );
	
}
