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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Erasable;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.PartlyErasable;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.mock.GdprErasable;
import org.sliceworkz.eventstore.mock.GdprErasable.Category;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.ErasableEventDataTest.CustomerEvent.Address;
import org.sliceworkz.eventstore.stream.ErasableEventDataTest.CustomerEvent.CustomerRegistered;

public class ErasableEventDataTest {
	
	EventStorage eventStorage;
	
	@BeforeEach
	public void setUp ( ) {
		this.eventStorage = createEventStorage();
	}
	
	@AfterEach
	public void tearDown ( ) {
		destroyEventStorage(eventStorage);
	}
	
	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().build();
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}
		
	
	@Test
	void testSaveAndRetrieveWithPersonalData ( ) throws Exception {
		
		CustomerEvent e = new CustomerRegistered("123", "John", "john@doe.com", new Address("someStreet", "42", "XY-1234"));

		EventStream<CustomerEvent> s = EventStoreFactory.get().eventStore(eventStorage).getEventStream(EventStreamId.forContext("someContext"), CustomerEvent.class);
		
		s.append(AppendCriteria.none(), Event.of(e,  Tags.none()));
		
		Event<CustomerEvent> retrieved = s.query(EventQuery.matchAll()).findFirst().get();
		
		findAllGdprErasableFields(CustomerRegistered.class).forEach(System.out::println);
		findAllGdprErasableFields(Address.class).forEach(System.out::println);
		
		// Verify it matches the original
		assert retrieved.data().equals(e);
		
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
	        return String.format("Class: %s, Field: %s, Type: %s, Category: %s, Purpose: %s",
	            className, fieldName, fieldType, category, purpose);
	    }
	}
}
