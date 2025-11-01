package org.sliceworkz.eventstore.infra.inmem;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.spi.EventStorage;

public interface InMemoryEventStorage {
	
	public static Builder newBuilder ( ) {
		return InMemoryEventStorage.Builder.newBuilder();
	}

	public static class Builder {
		
		private Builder ( ) {
			
		}

		public static Builder newBuilder ( ) {
			return new Builder();
		}
		
		public EventStorage build ( ) {
			return new InMemoryEventStorageImpl();
		}
		
		public EventStore buildStore ( ) {
			return EventStoreFactory.get().eventStore(build());
		}
	}
	
}
