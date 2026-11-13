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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An event that states the tags it is about, and how many tags it must carry under a key.
 * <p>
 * Appending an event whose data implements {@code Tagged} stores it with the tags of
 * {@link #eventTags()} merged with the tags the caller passed: a union, so a tag given both ways is
 * stored once, and nothing the caller passed is lost. The store then checks every
 * {@link #tagRequirements() requirement} against those merged tags, for the whole batch before anything
 * is written, and refuses the append with {@link IllegalArgumentException} when one is not met.
 * <pre>{@code
 * record MoneyTransferred ( String transferId, String from, String to, long amount ) implements BankEvent, Tagged {
 *
 *     public Tags eventTags ( ) {
 *         return Tags.of(Tag.of("transfer", transferId), Tag.of("account", from), Tag.of("account", to),
 *                        Tag.of("from-account", from), Tag.of("to-account", to));
 *     }
 *
 *     public Set<TagRequirement> tagRequirements ( ) {
 *         return Set.of(TagRequirement.of("account").between(1, 2),
 *                       TagRequirement.of("channel").exactlyOne());   // not in the payload: the caller passes it
 *     }
 * }
 *
 * stream.append(Event.of(new MoneyTransferred("t1", "A", "B", 100), Tags.of("channel", "atm")));
 * }</pre>
 * <ul>
 *   <li><b>Tags are stored with the event, and only then.</b> Changing {@code eventTags()} or the
 *       requirements changes the tags of events appended afterwards; events already stored keep the
 *       tags they were stored with, and nothing checks them. Tagging an event type that has history in
 *       a new way is a migration of that history, or a consistency boundary over the new tag misses the
 *       old events. An import copies stored events as they are, and derives and checks nothing.</li>
 *   <li><b>One tag key per kind of thing, several values for several of them.</b> An event about two
 *       accounts carries {@code account} twice. A role ({@code from-account}) is a tag of its own
 *       <em>beside</em> the entity's, never instead of it: a boundary on everything about an account
 *       reads the entity's key, and would miss the events where the account only had a role key.</li>
 *   <li><b>No personal data in a tag.</b> Tags are stored in the clear, indexed, and survive the erasure
 *       of a {@code Shreddable} value. Never tag an event with a value it seals.</li>
 *   <li><b>A missing id is not an error by itself.</b> {@code Tag.of(key, null)} is a legal flag tag,
 *       so an event whose id field is null would be stored with a bare {@code account} tag and match no
 *       boundary on any account. Refuse it in {@code eventTags()} ({@code Objects.requireNonNull}), or
 *       build the tag through something that refuses a null value.</li>
 *   <li><b>A failure is the payload's.</b> When {@code eventTags()} throws or returns {@code null}, the
 *       append fails with {@link EventSerializationException} and nothing of the batch is stored —
 *       not worth retrying, like any failure to write the payload.</li>
 *   <li><b>Neither method is part of the payload.</b> They carry no {@code get} prefix and are no record
 *       components, so a JSON mapper does not write them.</li>
 * </ul>
 *
 * @see TagRequirement
 * @see Tags#merge(Tags)
 */
public interface Tagged {

	/**
	 * The tags this event states from its own payload.
	 *
	 * @return the tags, {@link Tags#none()} for an event about nothing in particular; never null
	 */
	Tags eventTags ( );

	/**
	 * How many tags the stored event must carry per key, counting its own tags and the ones the caller
	 * passed. A key whose value is not in the payload is required here and passed by the caller.
	 * At most one requirement per key.
	 *
	 * @return the requirements, empty by default
	 */
	default Set<TagRequirement> tagRequirements ( ) {
		return Set.of();
	}

	/**
	 * The tags an event's data states: its {@link #eventTags()} when it is {@code Tagged}, none otherwise.
	 *
	 * @param data the event data
	 * @return the tags it states; never null
	 * @throws IllegalStateException if {@code eventTags()} returns null
	 * @throws RuntimeException whatever {@code eventTags()} throws, unchanged
	 */
	static Tags tagsStatedBy ( Object data ) {
		if ( !(data instanceof Tagged tagged) ) {
			return Tags.none();
		}
		Tags tags = tagged.eventTags();
		if ( tags == null ) {
			throw new IllegalStateException("eventTags() returned null; an event about nothing in particular returns Tags.none()");
		}
		return tags;
	}

	/**
	 * Checks the tags an event is stored with against the requirements its data declares, and describes
	 * every requirement they do not meet.
	 *
	 * @param data the event data
	 * @param tags the tags it would be stored with: its own merged with the caller's
	 * @return the unmet requirements in words — {@code "'account' tags required: exactly 2, found 1 [account:A]"},
	 *         joined by {@code "; "} in key order — or empty when every requirement is met or the data is not {@code Tagged}
	 * @throws IllegalArgumentException if the data declares a null set, a null requirement, or two
	 *         requirements for one key
	 */
	static Optional<String> unmetTagRequirements ( Object data, Tags tags ) {
		if ( !(data instanceof Tagged tagged) ) {
			return Optional.empty();
		}
		Set<TagRequirement> requirements = tagged.tagRequirements();
		if ( requirements == null ) {
			throw new IllegalArgumentException("tagRequirements() returned null; an event without requirements returns Set.of()");
		}
		List<TagRequirement> inKeyOrder = new ArrayList<>();
		for ( TagRequirement requirement : requirements ) {
			if ( requirement == null ) {
				throw new IllegalArgumentException("tagRequirements() holds a null requirement");
			}
			inKeyOrder.add(requirement);
		}
		inKeyOrder.sort(Comparator.comparing(TagRequirement::key));
		Set<String> keys = new HashSet<>();
		List<String> unmet = new ArrayList<>();
		for ( TagRequirement requirement : inKeyOrder ) {
			if ( !keys.add(requirement.key()) ) {
				throw new IllegalArgumentException("tagRequirements() holds more than one requirement for tag '%s'".formatted(requirement.key()));
			}
			if ( !requirement.isMetBy(tags) ) {
				Set<Tag> found = tags.tags(requirement.key());
				unmet.add("'%s' tags required: %s, found %d%s".formatted(requirement.key(), requirement.occurrence(), found.size(),
						found.isEmpty() ? "" : " " + found.stream().map(Tag::toString).sorted().toList()));
			}
		}
		return unmet.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", unmet));
	}

}
