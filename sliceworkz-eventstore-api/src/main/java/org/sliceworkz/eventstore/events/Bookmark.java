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
 * Bookmarks are produced by {@code placeBookmark(reader, reference, readUpTo, tags)} on the
 * {@link org.sliceworkz.eventstore.stream.EventSource} and surfaced as a list via
 * {@code getBookmarks()} on both the public API and the storage SPI.
 * <p>
 * <b>A bookmark holds two positions, for two different questions.</b> {@link #reference()} is the last
 * event the reader <em>handled</em>, and it is where the reader resumes. {@link #readUpTo()} is the event
 * up to which the reader has <em>read</em> the stream, relevant to it or not: a reader whose query names a
 * few event types never handles the others, so its reference stays behind every event it is not
 * interested in, while having read past them. Counting "events after the reference" as its backlog
 * therefore counts events it will never handle; counting from {@link #readUpToOrReference()} counts only
 * what it has not read yet.
 * <p>
 * The read position is <em>never</em> a resume point. A reader resuming from it would skip, for good, every
 * event of a type its query gains later that sits between the two positions — and it would buy nothing,
 * since the typed query skips irrelevant events through the index anyway. It exists for lag and for
 * "has this reader seen that event yet", and nothing else.
 * <p>
 * The read position is absent for a bookmark placed without one — by a writer that does not record it, or
 * by a storage that does not store it — and then {@link #readUpToOrReference()} falls back to the
 * reference, which is exactly what such a bookmark says. The next placement that carries one fills it in.
 *
 * @param reader    the unique name/identifier of the reader that owns this bookmark
 * @param reference the event reference the reader has progressed to (the last processed event), where it resumes
 * @param readUpTo  the event up to which the reader has read the stream, handled or not; empty when none was
 *                  recorded, never {@code null}
 * @param tags      tags supplied at placement time; never {@code null} ({@link Tags#none()} when absent)
 * @param updatedAt the instant at which the bookmark was last placed; never {@code null}
 */
public record Bookmark ( String reader, EventReference reference, Optional<EventReference> readUpTo, Tags tags, Instant updatedAt ) {

	public Bookmark {
		Objects.requireNonNull(reader, "reader must not be null");
		Objects.requireNonNull(reference, "reference must not be null");
		readUpTo = readUpTo == null ? Optional.empty() : readUpTo;
		Objects.requireNonNull(tags, "tags must not be null (use Tags.none())");
		Objects.requireNonNull(updatedAt, "updatedAt must not be null");
	}

	/**
	 * A bookmark without a read position.
	 *
	 * @param reader    the reader
	 * @param reference the last event handled
	 * @param tags      tags supplied at placement time
	 * @param updatedAt when the bookmark was last placed
	 */
	public Bookmark ( String reader, EventReference reference, Tags tags, Instant updatedAt ) {
		this(reader, reference, Optional.empty(), tags, updatedAt);
	}

	/**
	 * The event up to which the reader has read the stream: {@link #readUpTo()} when it was recorded, the
	 * {@link #reference()} otherwise. What a reader's backlog is counted from.
	 *
	 * @return the read position, never {@code null}
	 */
	public EventReference readUpToOrReference ( ) {
		return readUpTo.orElse(reference);
	}

}
