package org.sliceworkz.eventstore.stream;

import org.sliceworkz.eventstore.events.EventReference;

@FunctionalInterface
public interface EventStreamEventuallyConsistentBookmarkListener {

	void bookmarkUpdated ( String reader, EventReference processedUntil );
	
}
