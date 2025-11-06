package org.sliceworkz.eventstore.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.ErasableTest.CustomerEvent.CustomerRegistered;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public class ErasableTest {
	
	private JsonMapper publicMapper;
	private JsonMapper gdprMapper;
	
	@BeforeEach
	void setUp ( ) {
		this.publicMapper = JsonMapper.builder().build();
		this.gdprMapper = JsonMapper.builder().disable(MapperFeature.DEFAULT_VIEW_INCLUSION).build();
	}
	
	@Test
	void testSaveAndRetrieveWithPersonalData ( ) throws Exception {
		
		CustomerEvent e = new CustomerRegistered("123", "John", "john@doe.com");

		String publicData = publicMapper
				.writeValueAsString(e);
		
        String gdprData = gdprMapper
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
	
}
