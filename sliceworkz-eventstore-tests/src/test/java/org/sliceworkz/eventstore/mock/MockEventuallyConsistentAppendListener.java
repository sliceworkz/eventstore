package org.sliceworkz.eventstore.mock;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;

public class MockEventuallyConsistentAppendListener implements EventStreamEventuallyConsistentAppendListener {

	private EventReference lastReference;
	private int count;
	
	@Override
	public void eventsAppended(EventReference atLeastUntil) {
		count++;
		lastReference = atLeastUntil;
	}
	
	public int count ( ) {
		return count;
	}
	
	public EventReference lastReference ( ) {
		return lastReference;
	}

}
