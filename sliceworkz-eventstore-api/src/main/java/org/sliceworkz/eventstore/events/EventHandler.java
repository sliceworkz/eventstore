package org.sliceworkz.eventstore.events;

@FunctionalInterface
public interface EventHandler<TRIGGERING_EVENT_TYPE> {

	default void when ( TRIGGERING_EVENT_TYPE event, EventReference reference ) {
		when(event);
	}

	// TODO what about removing this one?
	void when ( TRIGGERING_EVENT_TYPE event );
	
}
