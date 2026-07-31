/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventstore.testing.tck.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Erasable;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.PartlyErasable;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.testing.tck.stream.ErasableEventDataTest.CustomerEvent.Address;
import org.sliceworkz.eventstore.testing.tck.stream.ErasableEventDataTest.CustomerEvent.CustomerRegistered;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.GdprErasable.Category;
import org.sliceworkz.eventstore.testing.tck.mock.GdprErasable;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;
import javax.sql.DataSource;
import java.sql.PreparedStatement;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class ErasableEventDataTest extends AbstractEventStoreTest {

	@ForEachBackend
	void testSaveAndRetrieveWithPersonalData ( ) throws Exception {

		CustomerEvent e = new CustomerRegistered("123", "John", "john@doe.com", new Address("someStreet", "42", "XY-1234"));

		EventStream<CustomerEvent> s = EventStoreFactory.get().eventStore(eventStorage()).getEventStream(EventStreamId.forContext("someContext"), CustomerEvent.class);

		s.append(AppendCriteria.none(), Event.of(e,  Tags.none()));

		Event<CustomerEvent> retrieved = s.query(EventQuery.matchAll()).findFirst().get();

		findAllGdprErasableFields(CustomerRegistered.class).forEach(System.out::println);
		findAllGdprErasableFields(Address.class).forEach(System.out::println);

		assertEquals(e, retrieved.data());
	}

	/**
	 * Erasure happens behind the store's back — an operator running an UPDATE, not an API call — so
	 * this scenario needs direct access to the underlying database and is skipped on backends that
	 * cannot offer it. It is the one part of the contract that cannot be expressed through the SPI.
	 */
	@ForEachBackend(requires = Capability.RAW_STORAGE_ACCESS)
	void testSaveAndRetrieveWithPersonalDataDeleteFromDatabase ( ) throws Exception {

		DataSource dataSource = dataSource().orElseThrow();

		CustomerEvent eventIncludingPersonalInfo = new CustomerRegistered("123", "John", "john@doe.com", new Address("someStreet", "42", "XY-1234"));

		EventStream<CustomerEvent> s = eventStore().getEventStream(EventStreamId.forContext("someContext"), CustomerEvent.class);

		List<Event<CustomerEvent>> storedEvents = s.append(AppendCriteria.none(), Event.of(eventIncludingPersonalInfo, Tags.none()));

		// run a query to get the event back, including immutable as well as personal information

		Event<CustomerEvent> retrieved = s.query(EventQuery.matchAll()).findFirst().get();

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

	public sealed interface CustomerEvent {

		public record CustomerRegistered (

				String id,

				// test with a custom @GdprErasable annotation including @Erasable
				@GdprErasable(category = Category.CONTACT, purpose = "required for personal communication")
				String name,

				@Erasable
				String email,

				@PartlyErasable
				Address address

				) implements CustomerEvent {

		}

		public record Address (

			@Erasable
			String street,

			@GdprErasable(category = Category.PERSONAL, purpose="sending snail mail")
			String number,

			String zip ) {

		}

	}

	public static List<GdprErasableField> findAllGdprErasableFields(Class<?> clazz) {
	    List<GdprErasableField> erasableFields = new ArrayList<>();

	    // Process all fields in the class
	    for (Field field : clazz.getDeclaredFields()) {
	        GdprErasable gdprErasable = findAnnotation(field, GdprErasable.class);
	        if (gdprErasable != null) {
	            erasableFields.add(new GdprErasableField(
	                clazz.getSimpleName(),
	                field.getName(),
	                field.getType().getName(),
	                gdprErasable.category(),
	                gdprErasable.purpose()
	            ));
	        }
	    }

	    // Recursively process nested classes (like your Address record)
	    for (Class<?> nestedClass : clazz.getDeclaredClasses()) {
	        erasableFields.addAll(findAllGdprErasableFields(nestedClass));
	    }

	    return erasableFields;
	}

	public static <A extends Annotation> A findAnnotation(AnnotatedElement element, Class<A> annotationType) {
	    A annotation = element.getAnnotation(annotationType);
	    if (annotation != null) return annotation;

	    // Search meta-annotations
	    for (Annotation ann : element.getAnnotations()) {
	        annotation = ann.annotationType().getAnnotation(annotationType);
	        if (annotation != null) return annotation;
	    }
	    return null;
	}

	public record GdprErasableField(
	    String className,
	    String fieldName,
	    String fieldType,
	    Category category,
	    String purpose
	) {
	    @Override
	    public String toString() {
	        return "Class: %s, Field: %s, Type: %s, Category: %s, Purpose: %s".formatted(
	            className, fieldName, fieldType, category, purpose);
	    }
	}
}
