package org.sliceworkz.eventstore.events;

import java.util.UUID;

public record EventId ( String value ) {
	
	public EventId ( String value ) {
		
		if ( value == null || "".equals(value.strip()) ) {
			throw new IllegalArgumentException();
		}
		
		this.value = value;
	}

	public static EventId create ( ) {
		return new EventId ( UUID.randomUUID().toString() );
	}
	
	public static EventId of ( String value ) {
		return ( value == null || "".equals(value.strip()) ) ? null : new EventId ( value );
	}
	
}
