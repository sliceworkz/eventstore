package org.sliceworkz.eventstore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.spi.EventStorage;

public abstract class AbstractEventStoreTest {
	
	private EventStorage eventStorage;
	private EventStore eventStore;
	
	@BeforeEach
	public void setUp ( ) {
		this.eventStorage = createEventStorage();
		this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
	}	
	
	@AfterEach
	public void tearDown ( ) {
		destroyEventStorage(eventStorage);
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}
	
	public EventStore eventStore ( ) {
		return eventStore;
	}
	
	public void waitBecauseOfEventualConsistency ( ) {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public abstract EventStorage createEventStorage ( );
	
}
