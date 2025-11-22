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

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;

/**
 * An immutable collection of {@link Tag}s that can be attached to events for dynamic querying.
 * <p>
 * Tags are central to the Dynamic Consistency Boundary (DCB) pattern, enabling events to be
 * queried and correlated across different event types based on business concepts rather than
 * technical structure. By tagging events with domain identifiers (such as customer IDs, order numbers,
 * regions, etc.), you can dynamically define consistency boundaries and retrieve all relevant facts
 * for making business decisions.
 * <p>
 * Key capabilities:
 * <ul>
 *   <li>Attach multiple tags to events to enable multi-dimensional querying</li>
 *   <li>Query events across different event types using shared tags</li>
 *   <li>Define dynamic consistency boundaries for optimistic locking via {@link AppendCriteria}</li>
 *   <li>Merge tag collections to combine filtering criteria</li>
 *   <li>Check for tag presence and containment relationships</li>
 * </ul>
 *
 * <h2>Basic Usage:</h2>
 * <pre>{@code
 * // Creating tags for an event
 * Tags tags = Tags.of("customer", "123", "region", "EU", "priority", "high");
 *
 * // Creating from individual Tag objects
 * Tags tags = Tags.of(
 *     Tag.of("customer", "123"),
 *     Tag.of("region", "EU")
 * );
 *
 * // Using with events
 * Event.of(new CustomerRegistered("John"), Tags.of("customer", "123"));
 *
 * // Empty tags
 * Tags noTags = Tags.none();
 * }</pre>
 *
 * <h2>Querying with Tags:</h2>
 * <pre>{@code
 * // Find all events for a specific customer across all event types
 * EventQuery query = EventQuery.forEvents(
 *     EventTypesFilter.any(),
 *     Tags.of("customer", "123")
 * );
 * Stream<Event<CustomerEvent>> events = stream.query(query);
 *
 * // Find events matching multiple tags (EU customers with high priority)
 * EventQuery query = EventQuery.forEvents(
 *     EventTypesFilter.any(),
 *     Tags.of("region", "EU", "priority", "high")
 * );
 * }</pre>
 *
 * <h2>DCB Pattern - Optimistic Locking:</h2>
 * <pre>{@code
 * // Define which events are relevant for our decision
 * Tags relevantTags = Tags.of("customer", "123");
 * EventQuery query = EventQuery.forEvents(EventTypesFilter.any(), relevantTags);
 *
 * // Query relevant events and make a business decision
 * List<Event<CustomerEvent>> events = stream.query(query).toList();
 * EventReference lastRef = events.getLast().reference();
 *
 * // Append new events only if no new relevant events appeared
 * stream.append(
 *     AppendCriteria.of(query, Optional.of(lastRef)),
 *     Event.of(new CustomerUpdated("Jane"), relevantTags)
 * );
 * // If new events with matching tags were added after lastRef,
 * // append fails with OptimisticLockingException
 * }</pre>
 *
 * <h2>Advanced Operations:</h2>
 * <pre>{@code
 * // Merging tags from different sources
 * Tags customerTags = Tags.of("customer", "123");
 * Tags metadataTags = Tags.of("region", "EU", "source", "web");
 * Tags combined = customerTags.merge(metadataTags);
 *
 * // Checking for specific tags
 * Optional<Tag> customerTag = tags.tag("customer");
 * if (customerTag.isPresent()) {
 *     String customerId = customerTag.get().value();
 * }
 *
 * // Checking if tags contain all required tags
 * Tags required = Tags.of("customer", "123", "region", "EU");
 * Tags actual = Tags.of("customer", "123", "region", "EU", "priority", "high");
 * boolean hasAll = actual.containsAll(required); // true
 * }</pre>
 *
 * <h2>Parsing Tags:</h2>
 * <pre>{@code
 * // Parse tags from string array
 * Tags tags = Tags.parse("customer:123", "region:EU", "important");
 * // Results in: Tag("customer", "123"), Tag("region", "EU"), Tag("important", null)
 * }</pre>
 *
 * @param tags the immutable set of tags (required, never null)
 * @see Tag
 * @see Event
 * @see EventQuery
 * @see AppendCriteria
 */
public record Tags ( Set<Tag> tags ) {

	/**
	 * Constructs a Tags instance with validation.
	 * <p>
	 * The tags set must not be null. For empty tags, use {@link #none()} instead.
	 * This constructor is typically not called directly; use the static factory methods
	 * such as {@link #of(Tag...)}, {@link #of(String, String)}, or {@link #none()}.
	 *
	 * @param tags the set of tags (required, must not be null)
	 * @throws IllegalArgumentException if tags is null
	 */
	public Tags ( Set<Tag> tags ) {
		if ( tags == null ) {
			throw new IllegalArgumentException();
		}
		this.tags = tags;
	}

	/**
	 * Retrieves a tag by its key name.
	 * <p>
	 * If multiple tags share the same key (which shouldn't happen in normal usage due to Set semantics),
	 * any matching tag may be returned.
	 *
	 * @param name the key name to search for
	 * @return an Optional containing the tag if found, or empty if no tag with the specified key exists
	 */
	public Optional<Tag> tag ( String name ) {
		return tags.stream().filter(t->(name==null?"":name).equals(t.key())).findAny();
	}

	/**
	 * Checks whether this Tags instance contains all tags from another Tags instance.
	 * <p>
	 * This is useful for verifying that events have all required tags, or that query results
	 * match expected criteria. Tag equality is based on both key and value.
	 *
	 * @param other the Tags to check for containment
	 * @return true if this Tags contains all tags from the other Tags, false otherwise
	 */
	public boolean containsAll ( Tags other ) {
		return tags.containsAll(other.tags);
	}

	/**
	 * Merges this Tags instance with another, creating a new Tags containing all tags from both.
	 * <p>
	 * If both Tags instances contain a tag with the same key and value, only one copy is included
	 * in the result (Set semantics). This is useful for combining tags from different sources,
	 * such as merging domain tags with metadata tags.
	 * <p>
	 * <b>Example:</b>
	 * <pre>{@code
	 * Tags customerTags = Tags.of("customer", "123");
	 * Tags metadataTags = Tags.of("region", "EU", "source", "web");
	 * Tags combined = customerTags.merge(metadataTags);
	 * // Result: Tags with customer:123, region:EU, source:web
	 * }</pre>
	 *
	 * @param other the Tags to merge with this instance
	 * @return a new Tags instance containing all tags from both instances
	 */
	public Tags merge ( Tags other ) {
		Set<Tag> merged = new HashSet<>();
		merged.addAll(tags);
		merged.addAll(other.tags);
		return new Tags(merged);
	}

	/**
	 * Creates a Tags instance from an array of Tag objects.
	 * <p>
	 * This is the primary factory method for creating Tags from individual Tag objects.
	 * Duplicate tags (same key and value) are automatically eliminated due to Set semantics.
	 *
	 * @param tags the tags to include (if null, returns Tags.none())
	 * @return a new Tags instance containing the specified tags
	 */
	public static Tags of ( Tag... tags ) {
		if ( tags != null ) {
			return new Tags ( Set.of(tags));
		} else {
			return Tags.none();
		}

	}

	/**
	 * Convenience method to create Tags from alternating key-value pairs.
	 * <p>
	 * This method is particularly useful for creating tags inline with readable syntax.
	 * The parameters should be provided as alternating key-value pairs. If an odd number
	 * of arguments is provided, the last key will have a null value.
	 * <p>
	 * Note: Despite the method signature showing only two parameters, this is typically
	 * used with overloaded versions or in combination with {@link #of(Tag...)} for
	 * creating a single key-value tag.
	 * <p>
	 * <b>Example:</b>
	 * <pre>{@code
	 * // Single tag
	 * Tags tags = Tags.of("customer", "123");
	 *
	 * // This is equivalent to:
	 * Tags tags = Tags.of(Tag.of("customer", "123"));
	 * }</pre>
	 *
	 * @param key the tag key
	 * @param value the tag value
	 * @return a new Tags instance containing a single tag with the specified key and value
	 */
	public static Tags of ( String key, String value ) {
		return of(Tag.of(key, value));
	}

	/**
	 * Creates an empty Tags instance with no tags.
	 * <p>
	 * This is preferred over creating Tags with an empty set directly, and is commonly used
	 * when events don't require any tags for filtering or correlation.
	 *
	 * @return a Tags instance containing no tags
	 */
	public static Tags none ( ) {
		return new Tags (Collections.emptySet());
	}

	/**
	 * Parses multiple string representations into Tags.
	 * <p>
	 * Each string is parsed using {@link Tag#parse(String)} and the resulting tags are
	 * combined into a single Tags instance. Null or unparseable strings are ignored.
	 * Strings should be in the format "key" or "key:value".
	 * <p>
	 * <b>Example:</b>
	 * <pre>{@code
	 * Tags tags = Tags.parse("customer:123", "region:EU", "important");
	 * // Results in three tags:
	 * //   Tag(key="customer", value="123")
	 * //   Tag(key="region", value="EU")
	 * //   Tag(key="important", value=null)
	 *
	 * // Null and empty strings are ignored
	 * Tags tags = Tags.parse("customer:123", null, "", "region:EU");
	 * // Results in two tags: customer:123 and region:EU
	 * }</pre>
	 *
	 * @param values the string representations to parse
	 * @return a new Tags instance containing all successfully parsed tags
	 * @see Tag#parse(String)
	 */
	public static Tags parse ( String... values ) {
		Set<Tag> tags = new HashSet<>();
		for ( String v: values ) {
			Tag t = Tag.parse(v);
			if ( t != null ) {
				tags.add(t);
			}
		}
		return Tags.of(tags.toArray(new Tag[tags.size()]));
	}

	/**
	 * Converts all tags to their string representations.
	 * <p>
	 * Each tag is converted using {@link Tag#toString()}, resulting in strings in the
	 * format "key" or "key:value". The result is returned as a Set to maintain uniqueness.
	 * <p>
	 * <b>Example:</b>
	 * <pre>{@code
	 * Tags tags = Tags.of(
	 *     Tag.of("customer", "123"),
	 *     Tag.of("region", "EU"),
	 *     Tag.of("important")
	 * );
	 * Set<String> strings = tags.toStrings();
	 * // Result: ["customer:123", "region:EU", "important"]
	 * }</pre>
	 *
	 * @return a Set of string representations of all tags
	 * @see Tag#toString()
	 */
	public Set<String> toStrings ( ) {
		return tags.stream().map(Tag::toString).collect(Collectors.toSet());
	}
	
}
