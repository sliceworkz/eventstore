package org.sliceworkz.eventstore.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.ErasableTest.CustomerEvent.CustomerRegistered;
import org.sliceworkz.eventstore.events.ErasableTest.Views.Public;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class ErasableTest {
	
	private JsonMapper publicMapper;
	private JsonMapper gdprMapper;
	
	private PIIAnnotationIntrospector introspector;
	
	@BeforeEach
	void setUp ( ) {
		this.publicMapper = JsonMapper.builder().build();
		this.gdprMapper = JsonMapper.builder().disable(MapperFeature.DEFAULT_VIEW_INCLUSION).build();
		this.introspector = new PIIAnnotationIntrospector();
	}
	
	@Test
	void testSaveAndRetrieveWithPersonalData ( ) throws Exception {
		
		CustomerEvent e = new CustomerRegistered("123", "John", "john@doe.com");

		String publicData = publicMapper
				.setAnnotationIntrospector(introspector)
                .writerWithView(Public.class)
				.writeValueAsString(e);
		
        String gdprData = gdprMapper
				.setAnnotationIntrospector(introspector)
                .writerWithView(Erasable.class)
                .writeValueAsString(e);
        
        System.out.println(publicData);
        System.out.println(gdprData);
		
	}
	
	public sealed interface CustomerEvent {
		
		public record CustomerRegistered (  

				String id,
				
				@JsonView(Erasable.class)
				String name,
				
				@JsonView(Erasable.class)
				String email
				
				) implements CustomerEvent {
			
		}
		
	}
	
	// Custom introspector that automatically assigns views
	public static class PIIAnnotationIntrospector extends JacksonAnnotationIntrospector {
		@Override
		public Class<?>[] findViews(Annotated member) {
			// Check for explicit @JsonView first
			Class<?>[] views = super.findViews(member);
			if (views != null) {
				return views;
			}
			
			// Check for @PIIData annotation
			Erasable piiData = member.getAnnotation(Erasable.class);
			if (piiData != null) {
				// PII fields are ONLY in Sensitive view
				return new Class<?>[] { Views.Sensitive.class };
			}
			
			// All other fields are in Public view by default
			return new Class<?>[] { Views.Public.class };
		}
	}
	
	public static class Views {
		public static class Public {}
		public static class Sensitive {}
	}
	
}
