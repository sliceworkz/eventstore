package org.sliceworkz.eventstore.mock;

import java.util.List;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.stream.EventStreamConsistentAppendListener;

public class MockConsistentAppendListener<T> implements EventStreamConsistentAppendListener<T> {

	private int count;
	
	@Override
	public void eventsAppended(List<? extends Event<? extends T>> events) {
		count++;
	}
	
	public int count ( ) {
		return count;
	}

}
