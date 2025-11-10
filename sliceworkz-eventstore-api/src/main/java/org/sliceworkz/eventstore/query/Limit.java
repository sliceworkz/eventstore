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
