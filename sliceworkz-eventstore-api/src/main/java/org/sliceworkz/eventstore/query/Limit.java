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

import org.sliceworkz.eventstore.events.Event;

/**
 * Represents the maximum number of {@link Event}s to query from the store.
 *
 * <p>Limit allows you to restrict the number of events returned from a query.
 * A limit of null means no limit is applied (all matching events are returned).
 * A positive limit value restricts the result set to that many events.
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // No limit - return all matching events
 * Limit noLimit = Limit.none();
 *
 * // Return at most 100 events
 * Limit maxHundred = Limit.to(100);
 *
 * // Return at most 10 events (using int)
 * Limit maxTen = Limit.to(10);
 *
 * // Check if a limit is set
 * if (limit.isSet()) {
 *     System.out.println("Limit is: " + limit.value());
 * }
 * }</pre>
 *
 * @param value the maximum number of events to return (null for no limit, must be positive if set)
 *
 * @see EventQuery
 */
public record Limit ( Long value ) {

	public Limit ( Long value ) {
		if ( value != null ) {
			if ( value <= 0 ) {
				throw new IllegalArgumentException("limit %d is invalid, should be larger than 0".formatted(value));
			}
		}
		this.value = value;
	}
	
	/**
	 * Checks if a limit is set (value is not null).
	 *
	 * @return true if a limit value is set, false if no limit is applied
	 */
	public boolean isSet ( ) {
		return !isNotSet();
	}

	/**
	 * Checks if no limit is set (value is null).
	 *
	 * @return true if no limit is applied, false if a limit value is set
	 */
	public boolean isNotSet ( ) {
		return value == null;
	}

	/**
	 * Creates a Limit with no restriction (returns all matching events).
	 *
	 * @return a Limit representing no limit
	 */
	public static Limit none ( ) {
		return new Limit ( null );
	}

	/**
	 * Creates a Limit with the specified maximum number of events.
	 *
	 * @param value the maximum number of events to return (must be positive)
	 * @return a Limit with the specified value
	 * @throws IllegalArgumentException if value is less than or equal to 0
	 */
	public static Limit to ( long value ) {
		return new Limit(value);
	}

	/**
	 * Creates a Limit with the specified maximum number of events.
	 * Convenience method that accepts an int parameter.
	 *
	 * @param value the maximum number of events to return (must be positive)
	 * @return a Limit with the specified value
	 * @throws IllegalArgumentException if value is less than or equal to 0
	 */
	public static Limit to ( int value ) {
		return to ( (long) value );
	}

}
