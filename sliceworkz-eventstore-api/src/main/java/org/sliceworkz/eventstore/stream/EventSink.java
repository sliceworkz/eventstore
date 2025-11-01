package org.sliceworkz.eventstore.stream;

import java.util.Collections;
import java.util.List;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EphemeralEvent;

public interface EventSink<DOMAIN_EVENT_TYPE> {

	// returns Events with filled in position in stream
	List<Event<DOMAIN_EVENT_TYPE>> append ( AppendCriteria appendCriteria, List<EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> events );
	
	default List<Event<DOMAIN_EVENT_TYPE>> append ( AppendCriteria appendCriteria, EphemeralEvent<? extends DOMAIN_EVENT_TYPE> event ) {
		return append(appendCriteria, Collections.singletonList(event));
	}

}
