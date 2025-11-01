package org.sliceworkz.eventstore.infra.postgres;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.sliceworkz.eventstore.EventStorePerformanceTest;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.spi.EventStorage;

public class PostgresEventStorePerformanceTest extends EventStorePerformanceTest {
	
	@Override
	public EventStorage createEventStorage ( ) {
		return new PostgresEventStorageImpl("unit-test", PostgresContainer.dataSource()).initializeDatabase();
	}	
	
	@Override
	public void destroyEventStorage ( EventStorage storage ) {
		((PostgresEventStorageImpl)storage).stop();
		PostgresContainer.closeDataSource();
	}
	
	@BeforeAll
	public static void setUpBeforeAll ( ) {
		PostgresContainer.start();
	}

	@AfterAll
	public static void tearDownAfterAll ( ) {
		PostgresContainer.dumpEventsInTable();
		PostgresContainer.stop();
		PostgresContainer.cleanup();
	}
	
}
