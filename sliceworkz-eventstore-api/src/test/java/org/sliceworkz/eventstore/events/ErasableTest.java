package org.sliceworkz.eventstore.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.ErasableTest.CustomerEvent.Address;
import org.sliceworkz.eventstore.events.ErasableTest.CustomerEvent.CustomerRegistered;

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
		
		// Verify it matches the original
		assert reconstructed.equals(e);
		
		
	}
	
	public sealed interface CustomerEvent {
		
		public record CustomerRegistered (  

				String id,
				
				@Erasable
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
			
			@Erasable
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

			Erasable annotatedWithErasable = member.getAnnotation(Erasable.class);
			PartlyErasable annotatedWithPartlyErasable = member.getAnnotation(PartlyErasable.class);
			
			if (annotatedWithErasable == null && annotatedWithPartlyErasable == null) {
				return new Class<?>[] { JsonViewTags.ImmutableData.class };
				
			} else if ( annotatedWithPartlyErasable != null ) {
				return new Class<?>[] { JsonViewTags.ImmutableData.class, JsonViewTags.ErasableData.class };
				
			} else {
				return new Class<?>[] { JsonViewTags.ErasableData.class };
			}
		}
	}
	
	static class JsonViewTags {
		public static class ImmutableData {}
		public static class ErasableData {}
	}
	
}
