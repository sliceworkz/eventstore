package org.sliceworkz.eventstore.stream;

import java.util.List;

import org.sliceworkz.eventstore.events.Event;

@FunctionalInterface
public interface EventStreamConsistentAppendListener<DOMAIN_EVENT_TYPE> {

	void eventsAppended ( List<? extends Event<? extends DOMAIN_EVENT_TYPE>> events );

}
