package org.sliceworkz.eventstore.stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.spi.EventStorage;

public class PostgresOptimisticLockingTest extends OptimisticLockingTest {
	
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
