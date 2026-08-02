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
 * <h2>The string form is the wire format</h2>
 * {@link #toString()} is not a debugging convenience: it is how a tag is persisted and how it is
 * matched. The PostgreSQL backend stores {@link Tags#toStrings()} into a {@code text[]} column and
 * answers a tag query with {@code event_tags @> ARRAY[...]} built from the same {@code toString()},
 * so a query is an <em>exact string comparison</em> against the stored form, and
 * {@link #parse(String)} is what a caller gets back when reading tags off an {@link Event}.
 * <p>
 * That only works if {@code parse(tag.toString())} returns the same tag it started with, and for a
 * tag whose key contains {@code ':'}, or whose key or value is empty or carries surrounding
 * whitespace, it does not — {@code parse} splits on the <em>first</em> {@code ':'}, strips both
 * halves and maps an empty half to {@code null}. {@code Tag.of("a:b", "c")} renders as
 * {@code "a:b:c"}, which reads back as key {@code "a"} and value {@code "b:c"}: a different tag,
 * and the same string a genuine {@code Tag.of("a", "b:c")} renders to. The failure such a tag
 * produces is a query returning nothing, or matching something it should not, with no exception
 * raised anywhere.
 * <p>
 * The constructor therefore <b>rejects</b> exactly the tags that cannot survive the round trip
 * (see {@link #Tag(String, String)}), which makes {@code toString}/{@code parse} a bijection over
 * every tag that can be constructed. {@link #parse(String)} deliberately stays lenient, because it
 * has to keep reading tags written before this was enforced.
 *
 * <h2>Matching is exact, not by key prefix</h2>
 * A tag matches a query tag when <em>both</em> the key and the value are equal. {@code Tag.of("customer")}
 * and {@code Tag.of("customer", "123")} are two different tags, and a query for the first does
 * <b>not</b> return events carrying the second — there is no key-prefix, key-only or wildcard
 * matching anywhere in the store. To find every event for a customer, tag them all with
 * {@code Tag.of("customer", id)} and query for that; a bare {@code Tag.of("customer")} is a flag,
 * useful when the presence of the tag is itself the fact.
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
	 * The character separating key from value in the string form, and therefore the one character a
	 * key may not contain.
	 */
	public static final char SEPARATOR = ':';

	/**
	 * Constructs a tag, rejecting the shapes that do not survive the string round trip.
	 * <p>
	 * The string form produced by {@link #toString()} is what gets persisted and what a tag query is
	 * matched against, so a tag that {@link #parse(String)} reads back as something else is a query
	 * that silently returns the wrong events. Rather than escape the string form — which would change
	 * what is already in the databases — construction refuses the four shapes that are ambiguous:
	 * <ul>
	 *   <li><b>a key containing {@value #SEPARATOR}</b>, because {@code parse} splits on the first
	 *       one: {@code Tag.of("a:b", "c")} would render as {@code "a:b:c"} and read back as
	 *       {@code Tag.of("a", "b:c")}, which is also what a genuine {@code Tag.of("a", "b:c")}
	 *       renders to. Values may contain {@value #SEPARATOR} freely — everything after the first
	 *       one is the value.</li>
	 *   <li><b>a key or value with leading or trailing whitespace</b>, because {@code parse} strips
	 *       both halves: {@code Tag.of("k", " v ")} would read back as {@code Tag.of("k", "v")}.
	 *       Whitespace <em>inside</em> a key or value is fine. Whitespace is rejected rather than
	 *       silently stripped so the mistake surfaces where it is made; a caller handling untrusted
	 *       input should {@code strip()} it themselves.</li>
	 *   <li><b>an empty key or an empty value</b>, because {@code parse} maps an empty half to
	 *       {@code null}: {@code Tag.of("k", "")} would render as {@code "k:"} and read back as
	 *       {@code Tag.of("k")}, so an event visibly carrying tag {@code k} would not be found by a
	 *       query for {@code Tag.of("k")}. Use {@link #of(String)} when there is no value.</li>
	 *   <li><b>a tag that is entirely null</b>, which renders as the empty string and is read back as
	 *       no tag at all.</li>
	 * </ul>
	 * A {@code null} key with a value is legal and renders as {@code ":value"}; {@link #parse(String)}
	 * still produces such tags from history, so they have to remain constructible.
	 *
	 * @param key the tag key, may be null
	 * @param value the tag value, may be null
	 * @throws IllegalArgumentException if the tag cannot be rendered and parsed back unchanged
	 */
	public Tag {
		if ( key == null && value == null ) {
			throw new IllegalArgumentException(
					"a tag needs a key or a value: a tag with neither renders as the empty string and cannot be read back");
		}
		if ( key != null && key.indexOf(SEPARATOR) >= 0 ) {
			throw new IllegalArgumentException(
					"tag key must not contain '" + SEPARATOR + "': it separates key from value in the stored form, so \""
							+ key + "\" would be read back as a different tag");
		}
		checkRoundTrippable("key", key);
		checkRoundTrippable("value", value);
	}

	private static void checkRoundTrippable ( String what, String s ) {
		if ( s == null ) {
			return;
		}
		if ( s.isEmpty() ) {
			throw new IllegalArgumentException(
					"tag " + what + " must not be empty (it is stored as, and read back as, no " + what + " at all)"
							+ ("value".equals(what) ? " — use Tag.of(key) for a tag without a value" : ""));
		}
		if ( !s.equals(s.strip()) ) {
			throw new IllegalArgumentException(
					"tag " + what + " must not have leading or trailing whitespace (it is stripped when read back, so \""
							+ s + "\" would become \"" + s.strip() + "\"");
		}
	}

	/**
	 * Creates a tag with only a key and no value.
	 * <p>
	 * This is useful for boolean-style tags or flags where the presence of the tag
	 * itself is meaningful, without requiring a specific value.
	 * <p>
	 * Note that this is a <em>different tag</em> from {@code Tag.of(key, someValue)}, not a prefix of
	 * it: a query for {@code Tag.of("customer")} does not match events tagged
	 * {@code Tag.of("customer", "123")}. See the class javadoc.
	 *
	 * @param key the tag key
	 * @return a new Tag with the specified key and null value
	 * @throws IllegalArgumentException if the key is null, empty, or has leading or trailing
	 *         whitespace, or contains {@value #SEPARATOR}
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
	 * @throws IllegalArgumentException if the tag cannot be rendered and parsed back unchanged —
	 *         see {@link #Tag(String, String)}
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
	 * <b>This is deliberately lenient, and deliberately not symmetric with construction.</b> Tags
	 * written before the constructor started rejecting ambiguous shapes are in databases that cannot
	 * be rewritten, so the read path has to keep accepting them; it normalises them instead. A stored
	 * {@code "k: v "} comes back as {@code Tag.of("k", "v")}, a stored {@code "k:"} as
	 * {@code Tag.of("k")}, and a stored {@code "a:b:c"} as {@code Tag.of("a", "b:c")} whichever tag
	 * wrote it. Note what that means for legacy data: the tag you read off an event may not be the
	 * tag that was appended, and re-tagging a new event with it stores a different string.
	 * <p>
	 * Everything {@code parse} returns is a tag the constructor accepts — the key it produces never
	 * contains {@value #SEPARATOR}, both halves are stripped, and an empty half becomes {@code null} —
	 * so reading legacy data never throws, and for any constructible tag
	 * {@code parse(tag.toString()).equals(tag)}.
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
	 * <b>This is the persisted and matched form</b>, not just a debugging rendering — see the class
	 * javadoc. It is unescaped, and it is kept that way because escaping would change the form
	 * already sitting in every existing database; the constructor rejects the tags that would need
	 * escaping instead.
	 * <p>
	 * The format is:
	 * <ul>
	 *   <li>{@code "key"} if value is null</li>
	 *   <li>{@code "key:value"} if both key and value are present</li>
	 *   <li>{@code ":value"} if key is null but value is present</li>
	 * </ul>
	 * The fourth case the constructor used to allow — both key and value null, rendering as the empty
	 * string — is now rejected, since nothing can read it back.
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
