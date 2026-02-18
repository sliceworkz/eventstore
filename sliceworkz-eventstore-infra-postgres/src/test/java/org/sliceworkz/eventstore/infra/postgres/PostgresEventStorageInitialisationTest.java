/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.sliceworkz.eventstore.infra.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;

// this test uses a different prefix per test, so one container can be started/stopped and reused for all tests
public class PostgresEventStorageInitialisationTest {
	
	@Test
	public void testInitializeTwice ( ) {
		EventStorage storage = PostgresEventStorage.newBuilder()
		.name("unit-test")
		.prefix("inittwice_")
		.dataSource(PostgresContainer.dataSource())
		.initializeDatabase()
		.build();

		((PostgresEventStorageImpl)storage).stop();

		// second time, should drop/create once again

		storage = PostgresEventStorage.newBuilder()
		.name("unit-test")
		.prefix("inittwice_")
		.dataSource(PostgresContainer.dataSource())
		.initializeDatabase()
		.build();

		((PostgresEventStorageImpl)storage).stop();

		PostgresContainer.closeDataSource();
	}

	@Test
	public void testEnsureDatabase ( ) {
		// first time: ensure creates everything
		EventStorage storage = PostgresEventStorage.newBuilder()
		.name("unit-test")
		.prefix("ensure_")
		.dataSource(PostgresContainer.dataSource())
		.ensureDatabase()
		.build();

		((PostgresEventStorageImpl)storage).stop();

		// second time: ensure leaves existing objects untouched
		storage = PostgresEventStorage.newBuilder()
		.name("unit-test")
		.prefix("ensure_")
		.dataSource(PostgresContainer.dataSource())
		.ensureDatabase()
		.build();

		((PostgresEventStorageImpl)storage).stop();

		PostgresContainer.closeDataSource();
	}

	@Test
	public void testValidateDatabase ( ) {
		EventStorageException e = assertThrows ( EventStorageException.class, () -> {
			PostgresEventStorage.newBuilder()
			.name("unit-test")
			.prefix("check_")
			.dataSource(PostgresContainer.dataSource())
			.validateDatabase()
			.build();
		});
		assertEquals("Required table 'check_events' does not exist", e.getMessage());

		PostgresContainer.closeDataSource();
	}

	@Test
	public void testDefaultModeIsEnsure ( ) {
		// default mode (ENSURE) should create schema on empty database
		EventStorage storage = PostgresEventStorage.newBuilder()
		.name("unit-test")
		.prefix("defaultmode_")
		.dataSource(PostgresContainer.dataSource())
		.build();

		((PostgresEventStorageImpl)storage).stop();

		PostgresContainer.closeDataSource();
	}

	
	@BeforeAll
	public static void setUpBeforeAll ( ) {
		PostgresContainer.start();
	}

	@AfterAll
	public static void tearDownAfterAll ( ) {
		PostgresContainer.stop();
		PostgresContainer.cleanup();
	}

}
