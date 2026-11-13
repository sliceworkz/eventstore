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

/**
 * How many tags an event must carry under one key, declared by a {@link Tagged} event.
 * <p>
 * The store checks every requirement on every append, against the tags the event is stored with —
 * those it states itself through {@link Tagged#eventTags()} merged with those the caller passed —
 * and refuses the append when one is not met. A requirement therefore describes the shape of the
 * stored event, whoever supplied the tags: "a transfer is about exactly two accounts" holds whether
 * both come from the payload or one is passed by the caller, and catches a third one passed by
 * mistake.
 * <p>
 * <b>A count is of distinct tags.</b> {@link Tags} is a set, so two equal tags are one: a transfer from
 * account A to account A carries a single {@code account:A}, and {@code exactly(2)} refuses it. Where
 * that case is legitimate, require {@code between(1, 2)}, or give each role a tag key of its own
 * ({@code from-account}, {@code to-account}) beside the entity's key and require those instead.
 * <p>
 * Built from {@link #of(String)}, which means one or more, and narrowed with {@link #exactlyOne()},
 * {@link #exactly(int)}, {@link #between(int, int)} or {@link #atMostOne()}:
 * <pre>{@code
 * Set.of(TagRequirement.of("transfer").exactlyOne(),
 *        TagRequirement.of("account").between(1, 2),
 *        TagRequirement.of("channel"))                 // one or more
 * }</pre>
 * The constructor is strict: unlike a record carried inside an event payload, a requirement is never
 * stored and never read back, so a declaration that makes no sense fails where it is written.
 *
 * @param key the tag key; must be a valid {@link Tag} key and must not start with {@code x-}, the
 *            prefix of the tags a framework adds to every event (tracing, rule tags)
 * @param min the fewest distinct tags the key must carry, at least 0
 * @param max the most distinct tags the key may carry, at least 1 and at least {@code min};
 *            {@link #UNBOUNDED} for no upper bound
 * @see Tagged
 */
public record TagRequirement ( String key, int min, int max ) {

	/**
	 * The {@code max} of a requirement without an upper bound.
	 */
	public static final int UNBOUNDED = Integer.MAX_VALUE;

	/**
	 * Constructs a requirement, refusing one that cannot be met or names a key no tag can carry.
	 *
	 * @throws IllegalArgumentException if the key is null, is not a valid tag key, starts with
	 *         {@code x-}, or the occurrence is not {@code 0 <= min <= max, max >= 1}
	 */
	public TagRequirement {
		if ( key == null ) {
			throw new IllegalArgumentException("a tag requirement needs a key");
		}
		new Tag(key, null); // the key rules of a tag: no ':', not empty, no surrounding whitespace
		if ( key.startsWith("x-") ) {
			throw new IllegalArgumentException(
					"a tag requirement cannot name '%s': x- tags are added by a framework, not stated by an event".formatted(key));
		}
		if ( min < 0 || max < 1 || max < min ) {
			throw new IllegalArgumentException(
					"invalid occurrence for tag '%s': min %d, max %d (need 0 <= min <= max and max >= 1)".formatted(key, min, max));
		}
	}

	/**
	 * A requirement of one or more tags under a key, the default; narrow it with the other methods.
	 *
	 * @param key the tag key
	 * @return the requirement
	 */
	public static TagRequirement of ( String key ) {
		return new TagRequirement(key, 1, UNBOUNDED);
	}

	/**
	 * @return a requirement of exactly one tag under this key
	 */
	public TagRequirement exactlyOne ( ) {
		return new TagRequirement(key, 1, 1);
	}

	/**
	 * @param n the number of distinct tags, at least 1
	 * @return a requirement of exactly {@code n} tags under this key
	 */
	public TagRequirement exactly ( int n ) {
		return new TagRequirement(key, n, n);
	}

	/**
	 * @param min the fewest distinct tags
	 * @param max the most distinct tags
	 * @return a requirement of between {@code min} and {@code max} tags under this key, both inclusive
	 */
	public TagRequirement between ( int min, int max ) {
		return new TagRequirement(key, min, max);
	}

	/**
	 * @return a requirement of no tag or one under this key: optional, but never two
	 */
	public TagRequirement atMostOne ( ) {
		return new TagRequirement(key, 0, 1);
	}

	/**
	 * Whether these tags meet the requirement: the number of distinct tags carrying the key lies
	 * between {@code min} and {@code max}.
	 *
	 * @param tags the tags an event is stored with
	 * @return true if they meet it
	 */
	public boolean isMetBy ( Tags tags ) {
		int n = tags.tags(key).size();
		return n >= min && n <= max;
	}

	/**
	 * The occurrence in words, as an error message states it: {@code "exactly 2"}, {@code "1 or more"},
	 * {@code "at most 1"}, {@code "between 1 and 3"}.
	 *
	 * @return the occurrence in words
	 */
	public String occurrence ( ) {
		if ( min == max ) {
			return "exactly " + min;
		}
		if ( max == UNBOUNDED ) {
			return min + " or more";
		}
		if ( min == 0 ) {
			return "at most " + max;
		}
		return "between " + min + " and " + max;
	}

}
