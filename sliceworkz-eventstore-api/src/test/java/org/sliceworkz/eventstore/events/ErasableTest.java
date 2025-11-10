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
package org.sliceworkz.eventstore.events;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.ErasableTest.CustomerEvent.Address;
import org.sliceworkz.eventstore.events.ErasableTest.CustomerEvent.CustomerRegistered;
import org.sliceworkz.eventstore.events.GdprErasable.Category;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ErasableTest {
	
	private JsonMapper immutableDataMapper;
	private JsonMapper erasableDataMapper;
	
	private ErasableAnnotationIntrospector introspector;
	
	@BeforeEach
	void setUp ( ) {
		this.immutableDataMapper = JsonMapper.builder().build();
		this.erasableDataMapper = JsonMapper.builder().disable(MapperFeature.DEFAULT_VIEW_INCLUSION).build();
		this.introspector = new ErasableAnnotationIntrospector();
	}
	
	@Test
	void testSaveAndRetrieveWithPersonalData ( ) throws Exception {
		
		CustomerEvent e = new CustomerRegistered("123", "John", "john@doe.com", new Address("someStreet", "42", "XY-1234"));

		String immutableData = immutableDataMapper
				.setAnnotationIntrospector(introspector)
                .writerWithView(JsonViewTags.ImmutableData.class)
				.writeValueAsString(e);
		
        String erasableData = erasableDataMapper
				.setAnnotationIntrospector(introspector)
                .writerWithView(JsonViewTags.ErasableData.class)
                .writeValueAsString(e);
        
        System.out.println(immutableData);
        System.out.println(erasableData);
        
        
		// reconstruct the full object by merging
		ObjectNode nodeImmutableData = (ObjectNode) immutableDataMapper.readTree(immutableData);
		ObjectNode nodeErasableData = (ObjectNode) erasableDataMapper.readTree(erasableData);
		
		// Merge erasable data into immutable data
		deepMerge(nodeImmutableData, nodeErasableData);
		
		String mergedData = immutableDataMapper.writeValueAsString(nodeImmutableData);
		System.out.println("Merged : " + mergedData);
		
		// deserialize back to object
		CustomerRegistered reconstructed = immutableDataMapper.readValue(
				mergedData, 
				CustomerRegistered.class
		);
		
		System.out.println("Reconstructed: " + reconstructed);
		
		
		findAllGdprErasableFields(CustomerRegistered.class).forEach(System.out::println);
		findAllGdprErasableFields(Address.class).forEach(System.out::println);
		
		// Verify it matches the original
		assert reconstructed.equals(e);
		
		
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
	
	private void deepMerge(ObjectNode target, ObjectNode source) {
		source.properties().forEach(entry -> {
			String key = entry.getKey();
			var value = entry.getValue();
			
			if (value.isObject() && target.has(key) && target.get(key).isObject()) {
				// Recursively merge nested objects
				deepMerge((ObjectNode) target.get(key), (ObjectNode) value);
			} else {
				// Replace or add the field
				target.set(key, value);
			}
		});
	}
	
	// Custom introspector that automatically assigns views to split between Immutable and Erasable data
	public static class ErasableAnnotationIntrospector extends JacksonAnnotationIntrospector {
		
		@Override
		public Class<?>[] findViews(Annotated member) {
			// Check for explicit @JsonView first
			Class<?>[] views = super.findViews(member);
			if (views != null) {
				return views;
			}

			Erasable annotatedWithErasable = findAnnotationOnMember(member, Erasable.class);
			PartlyErasable annotatedWithPartlyErasable = findAnnotationOnMember(member, PartlyErasable.class);
			
			if (annotatedWithErasable == null && annotatedWithPartlyErasable == null) {
				return new Class<?>[] { JsonViewTags.ImmutableData.class };
				
			} else if ( annotatedWithPartlyErasable != null ) {
				return new Class<?>[] { JsonViewTags.ImmutableData.class, JsonViewTags.ErasableData.class };
				
			} else {
				return new Class<?>[] { JsonViewTags.ErasableData.class };
			}
		}
	}
	
	private static <A extends Annotation> A findAnnotationOnMember(Annotated member, Class<A> annotationType) {
	    // First check if the member has the annotation directly
	    A annotation = member.getAnnotation(annotationType);
	    if (annotation != null) {
	        return annotation;
	    }
	    
	    AnnotatedElement element = member.getAnnotated();
	    if (element != null) {
	        // For records, Jackson introspects methods but annotations are on record components
	        if (element instanceof java.lang.reflect.Method) {
	            java.lang.reflect.Method method = (java.lang.reflect.Method) element;
	            Class<?> declaringClass = method.getDeclaringClass();
	            
	            // Check if this is a record
	            if (declaringClass.isRecord()) {
	                // Find the corresponding record component
	                try {
	                    java.lang.reflect.RecordComponent component = 
	                        java.util.Arrays.stream(declaringClass.getRecordComponents())
	                            .filter(rc -> rc.getName().equals(method.getName()))
	                            .findFirst()
	                            .orElse(null);
	                    
	                    if (component != null) {
	                        // Check direct annotation on record component
	                        annotation = component.getAnnotation(annotationType);
	                        if (annotation != null) {
	                            return annotation;
	                        }
	                        
	                        // Check meta-annotations on record component
	                        for (Annotation ann : component.getAnnotations()) {
	                            A metaAnnotation = ann.annotationType().getAnnotation(annotationType);
	                            if (metaAnnotation != null) {
	                                return metaAnnotation;
	                            }
	                        }
	                    }
	                } catch (Exception e) {
	                    // Fall through to regular handling
	                }
	            }
	        }
	        
	        // Fallback: search through annotations on the element itself
	        for (Annotation ann : element.getAnnotations()) {
	            A metaAnnotation = ann.annotationType().getAnnotation(annotationType);
	            if (metaAnnotation != null) {
	                return metaAnnotation;
	            }
	        }
	    }
	    
	    return null;
	}
	
	static class JsonViewTags {
		public static class ImmutableData {}
		public static class ErasableData {}
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
