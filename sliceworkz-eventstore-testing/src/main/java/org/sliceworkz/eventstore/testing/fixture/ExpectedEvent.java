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
package org.sliceworkz.eventstore.testing.fixture;

import java.util.LinkedHashSet;
import java.util.Set;

import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;

/**
 * A domain event and its tags — used both to seed history and to assert on what a decider appended,
 * so the two read alike:
 * <pre>{@code
 * fixture.given( event(new CourseDefined("Java basics", 12)).tagged("course", "abc001") )
 *        .when(stream -> subscribe(stream, "123", "abc001"))
 *        .expectAppended( event(new StudentSubscribed("123", "abc001"))
 *                             .tagged("student", "123").tagged("course", "abc001") );
 * }</pre>
 * When matching, only the payload and the tags are compared. Stream, reference and timestamp are
 * assigned by the store and are not predictable from a test — see the fixture package
 * documentation on why timestamps in particular cannot be asserted on.
 */
public final class ExpectedEvent {

	private final Object data;
	private final Tags tags;

	private ExpectedEvent ( Object data, Tags tags ) {
		this.data = data;
		this.tags = tags;
	}

	/**
	 * An untagged event carrying {@code data}.
	 *
	 * @param data the domain event
	 * @return the expected event
	 */
	public static ExpectedEvent event ( Object data ) {
		if ( data == null ) {
			throw new IllegalArgumentException("event data is required");
		}
		return new ExpectedEvent(data, Tags.none());
	}

	/**
	 * @param key   tag key
	 * @param value tag value
	 * @return a copy carrying one more tag
	 */
	public ExpectedEvent tagged ( String key, String value ) {
		return tagged(Tags.of(key, value));
	}

	/**
	 * @param additional tags to add
	 * @return a copy carrying the additional tags
	 */
	public ExpectedEvent tagged ( Tags additional ) {
		Set<Tag> merged = new LinkedHashSet<>(tags.tags());
		merged.addAll(additional.tags());
		return new ExpectedEvent(data, Tags.of(merged.toArray(new Tag[0])));
	}

	/**
	 * @return the domain event
	 */
	public Object data ( ) {
		return data;
	}

	/**
	 * @return the tags
	 */
	public Tags tags ( ) {
		return tags;
	}

	/**
	 * @param <E> the stream's domain event type
	 * @return this as an appendable event
	 */
	@SuppressWarnings("unchecked")
	public <E> EphemeralEvent<E> toEphemeralEvent ( ) {
		return Event.of((E) data, tags);
	}

	/**
	 * Whether {@code actual} carries the same payload and exactly the same tags.
	 *
	 * @param actual an event read back from the store
	 * @return {@code true} on a match
	 */
	public boolean matches ( Event<?> actual ) {
		return data.equals(actual.data()) && tags.tags().equals(actual.tags().tags());
	}

	@Override
	public String toString ( ) {
		return tags.tags().isEmpty() ? data.toString() : "%s %s".formatted(data, tags.tags());
	}

}
