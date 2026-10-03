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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A bookmark recording a named reader's position in the event store, together with
 * the metadata supplied when the bookmark was placed.
 * <p>
 * Bookmarks are produced by {@code placeBookmark(reader, reference, readUpTo, tags)} and
 * {@code placeReadPosition(reader, readUpTo, tags)} on the {@link org.sliceworkz.eventstore.stream.EventSource}
 * and surfaced as a list via {@code getBookmarks()} on both the public API and the storage SPI.
 * <p>
 * <b>A bookmark holds two positions, for two different questions.</b> {@link #reference()} is the last
 * event the reader <em>handled</em>, and it is where the reader resumes. {@link #readUpTo()} is the event
 * up to which the reader has <em>read</em> the stream, relevant to it or not: a reader whose query names a
 * few event types never handles the others, so its reference stays behind every event it is not
 * interested in, while having read past them. Counting "events after the reference" as its backlog
 * therefore counts events it will never handle; counting from {@link #readUpToOrReference()} counts only
 * what it has not read yet.
 * <p>
 * Either position may be absent, but not both: a bookmark always says something.
 * <ul>
 *   <li>No read position: a bookmark placed without one — by a writer that does not record it, or by a
 *       storage that does not store it. {@link #readUpToOrReference()} then falls back to the reference,
 *       which is exactly what such a bookmark says. The next placement that carries one fills it in.</li>
 *   <li>No handled reference: "read up to here, handled nothing yet". A reader whose query selects event
 *       types that have not occurred has read the stream without handling anything; without this, it
 *       would have no bookmark at all, and its whole stream would count as backlog although nothing in it
 *       concerns it. Such a reader still resumes from the beginning, exactly as one without a bookmark
 *       does, since the read position is never a resume point. The first placement that names a handled
 *       event fills the reference in, and a placement of a read position alone never clears one.</li>
 * </ul>
 * <p>
 * The read position is <em>never</em> a resume point. A reader resuming from it would skip, for good, every
 * event of a type its query gains later that sits between the two positions — and it would buy nothing,
 * since the typed query skips irrelevant events through the index anyway. It exists for lag and for
 * "has this reader seen that event yet", and nothing else.
 *
 * @param reader    the unique name/identifier of the reader that owns this bookmark
 * @param reference the last event the reader handled, where it resumes; empty when it has handled nothing
 *                  yet, never {@code null}
 * @param readUpTo  the event up to which the reader has read the stream, handled or not; empty when none was
 *                  recorded, never {@code null}
 * @param tags      tags supplied at placement time; never {@code null} ({@link Tags#none()} when absent)
 * @param updatedAt the instant at which the bookmark was last placed; never {@code null}
 */
public record Bookmark ( String reader, Optional<EventReference> reference, Optional<EventReference> readUpTo, Tags tags, Instant updatedAt ) {

	/**
	 * @throws NullPointerException     if {@code reader}, {@code reference}, {@code tags} or {@code updatedAt}
	 *                                  is {@code null} (a {@code null} {@code readUpTo} reads as empty)
	 * @throws IllegalArgumentException if both positions are empty: a bookmark names a handled event, a read
	 *                                  position, or both
	 */
	public Bookmark {
		Objects.requireNonNull(reader, "reader must not be null");
		Objects.requireNonNull(reference, "reference must not be null (use Optional.empty() for a reader that has handled nothing yet)");
		readUpTo = readUpTo == null ? Optional.empty() : readUpTo;
		Objects.requireNonNull(tags, "tags must not be null (use Tags.none())");
		Objects.requireNonNull(updatedAt, "updatedAt must not be null");
		if ( reference.isEmpty() && readUpTo.isEmpty() ) {
			throw new IllegalArgumentException("a bookmark for reader '%s' names a handled event, a read position, or both; it has neither".formatted(reader));
		}
	}

	/**
	 * A bookmark naming the last event handled, with or without a read position.
	 *
	 * @param reader    the reader
	 * @param reference the last event handled, not {@code null}
	 * @param readUpTo  the event up to which the reader has read the stream, empty for none
	 * @param tags      tags supplied at placement time
	 * @param updatedAt when the bookmark was last placed
	 */
	public Bookmark ( String reader, EventReference reference, Optional<EventReference> readUpTo, Tags tags, Instant updatedAt ) {
		this(reader, Optional.of(Objects.requireNonNull(reference, "reference must not be null")), readUpTo, tags, updatedAt);
	}

	/**
	 * A bookmark naming the last event handled, without a read position.
	 *
	 * @param reader    the reader
	 * @param reference the last event handled, not {@code null}
	 * @param tags      tags supplied at placement time
	 * @param updatedAt when the bookmark was last placed
	 */
	public Bookmark ( String reader, EventReference reference, Tags tags, Instant updatedAt ) {
		this(reader, reference, Optional.empty(), tags, updatedAt);
	}

	/**
	 * The event up to which the reader has read the stream: {@link #readUpTo()} when it was recorded, the
	 * {@link #reference()} otherwise. What a reader's backlog is counted from. Always present, since a
	 * bookmark names at least one of the two.
	 *
	 * @return the read position, never {@code null}
	 */
	public EventReference readUpToOrReference ( ) {
		return readUpTo.or(() -> reference).orElseThrow();
	}

}
