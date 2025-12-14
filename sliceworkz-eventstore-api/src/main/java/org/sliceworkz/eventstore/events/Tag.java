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
package org.sliceworkz.eventstore.events;

import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;

/**
 * A key-value pair that can be attached to events for dynamic querying and consistency boundaries.
 * <p>
 * Tags are fundamental to the Dynamic Consistency Boundary (DCB) pattern, enabling events to be
 * queried and correlated based on business concepts rather than just event types. Each tag consists
 * of an optional key and an optional value, allowing for flexible categorization and filtering.
 * <p>
 * Tags serve multiple purposes in the event store:
 * <ul>
 *   <li>Enable querying events across different event types based on business identifiers</li>
 *   <li>Support the DCB pattern by defining dynamic consistency boundaries</li>
 *   <li>Allow correlation of related events using domain-specific attributes</li>
 *   <li>Facilitate optimistic locking via {@link AppendCriteria} by identifying relevant facts</li>
 * </ul>
 *
 * <h2>Tag Format:</h2>
 * Tags can be represented in string format as:
 * <ul>
 *   <li>{@code "key"} - a tag with only a key (value is null)</li>
 *   <li>{@code "key:value"} - a tag with both key and value</li>
 * </ul>
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * // Creating tags with key and value
 * Tag customerTag = Tag.of("customer", "123");
 * Tag regionTag = Tag.of("region", "EU");
 *
 * // Creating a tag with only a key
 * Tag flagTag = Tag.of("important");
 *
 * // Parsing tags from strings
 * Tag parsedTag1 = Tag.parse("customer:123");  // key="customer", value="123"
 * Tag parsedTag2 = Tag.parse("important");     // key="important", value=null
 *
 * // Using tags with events
 * Event.of(new CustomerRegistered("John"), Tags.of(customerTag, regionTag));
 *
 * // Using tags in queries to find related events
 * EventQuery query = EventQuery.forEvents(
 *     EventTypesFilter.any(),
 *     Tags.of(Tag.of("customer", "123"))
 * );
 * }</pre>
 *
 * <h2>DCB Pattern Integration:</h2>
 * <pre>{@code
 * // Tags identify the consistency boundary for optimistic locking
 * Tag customerTag = Tag.of("customer", "123");
 *
 * // Query events with the customer tag to make a business decision
 * EventQuery query = EventQuery.forEvents(EventTypesFilter.any(), Tags.of(customerTag));
 * List<Event<CustomerEvent>> events = stream.query(query).toList();
 * EventReference lastRef = events.getLast().reference();
 *
 * // Append new events only if no new customer events appeared
 * stream.append(
 *     AppendCriteria.of(query, Optional.of(lastRef)),
 *     Event.of(new CustomerUpdated("Jane"), Tags.of(customerTag))
 * );
 * }</pre>
 *
 * @param key the tag key, may be null
 * @param value the tag value, may be null
 * @see Tags
 * @see EventQuery
 * @see AppendCriteria
 */
public record Tag ( String key, String value ) {

	/**
	 * Creates a tag with only a key and no value.
	 * <p>
	 * This is useful for boolean-style tags or flags where the presence of the tag
	 * itself is meaningful, without requiring a specific value.
	 *
	 * @param key the tag key
	 * @return a new Tag with the specified key and null value
	 */
	public static Tag of ( String key ) {
		return new Tag(key, null);
	}

	/**
	 * Creates a tag with both a key and a value.
	 * <p>
	 * This is the most common way to create tags for identifying domain concepts
	 * such as customer IDs, order numbers, regions, etc.
	 *
	 * @param key the tag key
	 * @param value the tag value
	 * @return a new Tag with the specified key and value
	 */
	public static Tag of ( String key, String value ) {
		return new Tag(key, value);
	}

	/**
	 * Creates a tag with a key and an integer value.
	 * <p>
	 * This is a convenience method that converts the integer value to its string representation.
	 * Useful for numeric identifiers such as customer IDs, order numbers, or counters.
	 *
	 * @param key the tag key
	 * @param value the integer value to be converted to string
	 * @return a new Tag with the specified key and the string representation of the value
	 */
	public static Tag of ( String key, int value ) {
		return of(key,String.valueOf(value));
	}

	/**
	 * Creates a tag with a key and a long value.
	 * <p>
	 * This is a convenience method that converts the long value to its string representation.
	 * Useful for numeric identifiers such as timestamps, large IDs, or sequence numbers.
	 *
	 * @param key the tag key
	 * @param value the long value to be converted to string
	 * @return a new Tag with the specified key and the string representation of the value
	 */
	public static Tag of ( String key, long value ) {
		return of(key,String.valueOf(value));
	}

	/**
	 * Parses a tag from a string representation.
	 * <p>
	 * The string format is expected to be either:
	 * <ul>
	 *   <li>{@code "key"} - creates a tag with the key and null value</li>
	 *   <li>{@code "key:value"} - creates a tag with both key and value</li>
	 * </ul>
	 * Whitespace is trimmed from both key and value. Empty strings are converted to null.
	 * <p>
	 * <b>Examples:</b>
	 * <pre>{@code
	 * Tag.parse("customer:123")      // Tag(key="customer", value="123")
	 * Tag.parse("region:EU")         // Tag(key="region", value="EU")
	 * Tag.parse("important")         // Tag(key="important", value=null)
	 * Tag.parse("key:")              // Tag(key="key", value=null)
	 * Tag.parse(":value")            // Tag(key=null, value="value")
	 * Tag.parse(null)                // null
	 * Tag.parse("")                  // null
	 * }</pre>
	 *
	 * @param string the string to parse
	 * @return a Tag parsed from the string, or null if the string is null, empty, or contains only whitespace
	 */
	public static Tag parse ( String string ) {
		Tag result = null;
		if ( string != null ) {
			String key = null;
			String value = null;
			int index = string.indexOf(':');
			if ( index >= 0 ) {
				key = string.substring(0, index).strip();
				if ( key != null && key.length() == 0 ) {
					key = null;
				}
				value = string.length() > index ? string.substring(index + 1).strip() : null;
				if ( value != null && value.length() == 0 ) {
					value = null;
				}
			} else {
				key = (string.strip().length() > 0) ? string.strip() : null;
				if ( key != null && key.length() == 0 ) {
					key = null;
				}
			}
			
			if ( key != null || value != null ) {
				result = Tag.of(key, value);
			}
		}
		return result;
	}

	/**
	 * Converts this tag to its string representation.
	 * <p>
	 * The format is:
	 * <ul>
	 *   <li>{@code "key"} if value is null</li>
	 *   <li>{@code "key:value"} if both key and value are present</li>
	 *   <li>{@code ":value"} if key is null but value is present</li>
	 *   <li>{@code ""} (empty string) if both key and value are null</li>
	 * </ul>
	 *
	 * @return the string representation of this tag
	 */
	public String toString ( ) {
		StringBuilder sb = new StringBuilder ( );
		if ( key != null ) {
			sb.append(key);
		}
		if ( value != null ) {
			sb.append(":");
			sb.append(value);
		}
		return sb.toString();
	}
	
}
