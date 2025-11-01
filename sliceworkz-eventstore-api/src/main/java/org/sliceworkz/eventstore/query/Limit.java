package org.sliceworkz.eventstore.query;

import java.awt.Event;

/**
 * Represents the maximum number of {@link Event}s to query from the store.
 */
public record Limit ( Long value ) {

	public Limit ( Long value ) {
		if ( value != null ) {
			if ( value <= 0 ) {
				throw new IllegalArgumentException(String.format("limit %d is invalid, should be larger than 0", value));
			}
		}
		this.value = value;
	}
	
	public boolean isSet ( ) {
		return !isNotSet();
	}
	
	public boolean isNotSet ( ) {
		return value == null;
	}

	public static Limit none ( ) {
		return new Limit ( null );
	}
	
	public static Limit to ( long value ) {
		return new Limit(value);
	}
	
	public static Limit to ( int value ) {
		return to ( (long) value );
	}

}
