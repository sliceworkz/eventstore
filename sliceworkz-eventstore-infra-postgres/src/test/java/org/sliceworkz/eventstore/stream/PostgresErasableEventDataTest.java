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
package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.PreparedStatement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.ErasableEventDataTest.CustomerEvent.Address;
import org.sliceworkz.eventstore.stream.ErasableEventDataTest.CustomerEvent.CustomerRegistered;

public class PostgresErasableEventDataTest extends ErasableEventDataTest {
	
	DataSource dataSource;
	
	// testcase that only runs against a postgres database, as it manipulates the data behing the eventstore to verify correct handling of the eventstore.
	@Test
	void testSaveAndRetrieveWithPersonalDataDeleteFromDatabase ( ) throws Exception {
		
		CustomerEvent eventIncludingPersonalInfo = new CustomerRegistered("123", "John", "john@doe.com", new Address("someStreet", "42", "XY-1234"));

		EventStream<CustomerEvent> s = EventStoreFactory.get().eventStore(eventStorage).getEventStream(EventStreamId.forContext("someContext"), CustomerEvent.class);
		
		List<Event<CustomerEvent>> storedEvents = s.append(AppendCriteria.none(), Event.of(eventIncludingPersonalInfo,  Tags.none()));

		// run a query to get the event back, including immutable as well as personal information

		Event<CustomerEvent> retrieved = s.query(EventQuery.matchAll()).findFirst().get();
		
		findAllGdprErasableFields(CustomerRegistered.class).forEach(System.out::println);
		findAllGdprErasableFields(Address.class).forEach(System.out::println);
		
		assertEquals(eventIncludingPersonalInfo, retrieved.data());
		
		/*
		 * DELETE THE ERASABLE INFORMATION FROM THE DATABASE AND QUERY THE EVENTS AGAIN 
		 */
		PreparedStatement statement = dataSource.getConnection().prepareStatement("update events set event_erasable_data = null where event_id = ?::uuid");
		statement.setString(1, storedEvents.iterator().next().reference().id().value() );
		statement.execute();
		
		
		// run the query again now the database is updated and the event doesn't contain personal information anymore
		
		Event<CustomerEvent> retrievedAfterErasedData = s.query(EventQuery.matchAll()).findFirst().get();
		assertNotEquals(retrievedAfterErasedData.data(), eventIncludingPersonalInfo);
		
		CustomerEvent eventWithoutPersonalInfo = new CustomerRegistered("123", null, null, new Address(null, null, "XY-1234"));
		assertEquals(eventWithoutPersonalInfo, retrievedAfterErasedData.data());

	
		/*
		 * UPDATE THE ERASABLE INFORMATION WITH BOGUS INFO AND QUERY THE EVENTS AGAIN 
		 */
		statement = dataSource.getConnection().prepareStatement("update events set event_erasable_data = '{\"name\":\"***\", \"email\":null, \"address\":{\"street\":\"***\", \"number\": null}}' where event_id = ?::uuid");
		statement.setString(1, storedEvents.iterator().next().reference().id().value() );
		statement.execute();
		
		Event<CustomerEvent> retrievedAfterReplacedData = s.query(EventQuery.matchAll()).findFirst().get();
		assertNotEquals(retrievedAfterReplacedData.data(), eventIncludingPersonalInfo);
		
		CustomerEvent eventWithoutReplacedInfo = new CustomerRegistered("123", "***", null, new Address("***", null, "XY-1234"));
		assertEquals(eventWithoutReplacedInfo, retrievedAfterReplacedData.data());

	}

	
	@Override
	public EventStorage createEventStorage ( ) {
		this.dataSource = PostgresContainer.dataSource();
		return PostgresEventStorage.newBuilder()
				.name("unit-test")
				.dataSource(dataSource)
				.initializeDatabase()
				.build();
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
		PostgresContainer.stop();
		PostgresContainer.cleanup();
	}
	
}
