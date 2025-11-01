package org.sliceworkz.eventstore.events;

public record EventType ( String name ) {

	public static final EventType of ( Object object ) {
		return of(object.getClass());
	}
	
	public static final EventType ofType ( String type ) {
		return new EventType(type);
	}

	public static final EventType of ( Class<?> clazz ) {
		return new EventType(clazz.getSimpleName()); 
	}
	
}
