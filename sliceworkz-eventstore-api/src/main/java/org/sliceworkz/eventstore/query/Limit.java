/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
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
 * Represents how many {@link Event}s to read from the store in one query.
 *
 * <p>Limit allows you to restrict the number of events read by a query.
 * A limit of null means no limit is applied (all matching events are read).
 * A positive limit value restricts the query to that many events.
 *
 * <p>The limit is pushed into the storage query itself — a SQL {@code LIMIT} on the PostgreSQL
 * backend — rather than applied to its result, so it bounds the work done and the memory used, not
 * just what you end up looking at. That is the point of setting one: a storage query materialises
 * its whole result set before returning it, so an unlimited query over a large stream is a heap
 * problem, not a slow one.
 *
 * <p><strong>A limit counts stored events.</strong> Normally that is also the number of events you
 * get back, because a stored event yields exactly one. Upcasting is where the two part company: an
 * {@link org.sliceworkz.eventstore.events.Upcast @Upcast} method may turn one stored event into
 * several, or into none, and the limit is spent before any of that happens. So
 * {@code EventQuery.matchAll().limit(1)} over a stored event that upcasts into two returns two
 * events, and over one that upcasts into none returns zero — while having read exactly one stored
 * event either way, which is what was asked for. Trimming the surplus would hand back a fragment of
 * a stored event and leave a cursor pointing into its middle, so the count read is what the limit
 * governs. Where the distinction matters, apply your own {@code .limit(n)} to the returned
 * {@link java.util.stream.Stream}: cheap, since it operates on events already read.
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // No limit - read all matching events
 * Limit noLimit = Limit.none();
 *
 * // Read 100 stored events
 * Limit hundred = Limit.to(100);
 *
 * // Read 10 stored events (using int)
 * Limit ten = Limit.to(10);
 *
 * // Check if a limit is set
 * if (limit.isSet()) {
 *     System.out.println("Limit is: " + limit.value());
 * }
 * }</pre>
 *
 * @param value how many stored events to read (null for no limit, must be positive if set)
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
	 * Returns a Limit with the given value if it is lower than the current limit,
	 * or if no limit is currently set. Otherwise returns this Limit unchanged.
	 *
	 * <p>This is useful for imposing an upper bound on an existing limit. For example,
	 * when a system-wide maximum should cap a user-specified limit:
	 * <pre>{@code
	 * Limit userLimit = Limit.to(500);
	 * Limit capped = userLimit.orIfLower(100); // Limit.to(100)
	 *
	 * Limit noLimit = Limit.none();
	 * Limit bounded = noLimit.orIfLower(100); // Limit.to(100)
	 * }</pre>
	 *
	 * @param value the candidate limit value (must be positive)
	 * @return a Limit with the lower of the two values, or the given value if no limit is currently set
	 * @throws IllegalArgumentException if value is less than or equal to 0
	 */
	public Limit orIfLower ( long value ) {
		if ( this.value == null || this.value > value ) {
			return Limit.to(value);
		} else {
			return this;
		}
	}

	/**
	 * Returns the Limit with the lower value between this and the given Limit.
	 * If the given Limit has no value set, this Limit is returned unchanged.
	 * If this Limit has no value set, the given Limit is returned.
	 *
	 * <pre>{@code
	 * Limit a = Limit.to(500);
	 * Limit b = Limit.to(100);
	 * Limit result = a.orIfLower(b); // Limit.to(100)
	 *
	 * Limit noLimit = Limit.none();
	 * Limit bounded = noLimit.orIfLower(Limit.to(100)); // Limit.to(100)
	 *
	 * Limit kept = Limit.to(50).orIfLower(Limit.none()); // Limit.to(50)
	 * }</pre>
	 *
	 * @param limit the candidate Limit to compare against
	 * @return the Limit with the lower value, or this Limit if the given Limit has no value set
	 */
	public Limit orIfLower ( Limit limit ) {
		if ( limit.isNotSet() ) {
			return this;
		}
		return orIfLower(limit.value());
	}

	/**
	 * Creates a Limit with no restriction (reads all matching events).
	 *
	 * @return a Limit representing no limit
	 */
	public static Limit none ( ) {
		return new Limit ( null );
	}

	/**
	 * Creates a Limit reading the specified number of stored events.
	 *
	 * @param value how many stored events to read (must be positive)
	 * @return a Limit with the specified value
	 * @throws IllegalArgumentException if value is less than or equal to 0
	 */
	public static Limit to ( long value ) {
		return new Limit(value);
	}

	/**
	 * Creates a Limit reading the specified number of stored events.
	 * Convenience method that accepts an int parameter.
	 *
	 * @param value how many stored events to read (must be positive)
	 * @return a Limit with the specified value
	 * @throws IllegalArgumentException if value is less than or equal to 0
	 */
	public static Limit to ( int value ) {
		return to ( (long) value );
	}

}
