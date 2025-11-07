package org.sliceworkz.eventstore.query;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.sliceworkz.eventstore.events.EventType;

public record EventTypesFilter ( Set<EventType> eventTypes ) {

	public boolean matches ( EventType eventType ) {
		// if we don't specify specific types, we accept all
		return eventTypes.isEmpty() || eventTypes.contains(eventType);
	}
	
	public static final EventTypesFilter any ( ) {
		return of(new Class[] {});
	}
	
	public static final EventTypesFilter of ( Class<?>... eventClasses ) {
		return of(Arrays.asList(eventClasses));
	}
	
	public static final EventTypesFilter of ( List<Class<?>> eventClasses ) {
		return new EventTypesFilter(eventClasses.stream().map(EventType::of).collect(Collectors.<EventType>toSet()));
	}

	public static final EventTypesFilter of ( Set<EventType> eventTypes ) {
		return new EventTypesFilter(eventTypes);
	}
	
}
