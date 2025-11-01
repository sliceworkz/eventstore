package org.sliceworkz.eventstore.impl;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.spi.EventStorage;

public class EventStoreFactoryImpl implements EventStoreFactory {

	@Override
	public EventStore eventStore(EventStorage eventStorage) {
		return new EventStoreImpl(eventStorage);
	}

}
