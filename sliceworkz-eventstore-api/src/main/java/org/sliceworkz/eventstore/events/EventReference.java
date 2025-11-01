package org.sliceworkz.eventstore.events;

public record EventReference ( EventId id, Long position ) {

	public EventReference ( EventId id, Long position  ) {
		if ( id == null ) {
			throw new IllegalArgumentException("event id in reference cannot be null");
		}
		if ( position == null ) {
			throw new IllegalArgumentException("position in reference cannot be null");
		} else if ( position <= 0 ) {
			throw new IllegalArgumentException(String.format("position %d is invalid, should be larger than 0", position));
		}
		
		this.id = id;
		this.position = position;
	}
	
	public static EventReference of ( EventId id, long position ) {
		return new EventReference(id, position);
	}
	
	public static EventReference create ( long position ) {
		return of ( EventId.create(), position );
	}
	
	public static EventReference none ( ) {
		return null;
	}
	
}
