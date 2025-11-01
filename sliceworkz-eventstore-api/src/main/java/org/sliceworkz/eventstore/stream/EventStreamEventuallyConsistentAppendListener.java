package org.sliceworkz.eventstore.stream;

import org.sliceworkz.eventstore.events.EventReference;

@FunctionalInterface
public interface EventStreamEventuallyConsistentAppendListener {

	void eventsAppended ( EventReference atLeastUntil );

}
