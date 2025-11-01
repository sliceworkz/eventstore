package org.sliceworkz.eventstore.spi;

public class EventStorageException extends RuntimeException {

	public EventStorageException ( String message ) {
		super(message);
	}
	
	public EventStorageException ( Throwable cause ) {
		super(cause);
	}

	public EventStorageException ( String message, Throwable cause ) {
		super(message, cause);
	}

}
