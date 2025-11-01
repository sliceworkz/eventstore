package org.sliceworkz.eventstore;

import java.util.ServiceLoader;

import org.sliceworkz.eventstore.spi.EventStorage;

public interface EventStoreFactory {
	
	EventStore eventStore ( EventStorage eventStorage );

	static EventStoreFactory get ( ) {
		return ServiceLoader.load(EventStoreFactory.class).findFirst().get();
	}
	
}
