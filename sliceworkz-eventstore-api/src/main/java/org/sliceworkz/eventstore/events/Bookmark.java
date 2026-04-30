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

/**
 * A bookmark recording a named reader's position in the event store, together with
 * the metadata supplied when the bookmark was placed.
 * <p>
 * Bookmarks are produced by {@code placeBookmark(reader, reference, tags)} on the
 * {@link org.sliceworkz.eventstore.stream.EventSource} and surfaced as a list via
 * {@code getBookmarks()} on both the public API and the storage SPI.
 *
 * @param reader    the unique name/identifier of the reader that owns this bookmark
 * @param reference the event reference the reader has progressed to (the last processed event)
 * @param tags      tags supplied at placement time; never {@code null} ({@link Tags#none()} when absent)
 * @param updatedAt the instant at which the bookmark was last placed; never {@code null}
 */
public record Bookmark ( String reader, EventReference reference, Tags tags, Instant updatedAt ) {

	public Bookmark {
		Objects.requireNonNull(reader, "reader must not be null");
		Objects.requireNonNull(reference, "reference must not be null");
		Objects.requireNonNull(tags, "tags must not be null (use Tags.none())");
		Objects.requireNonNull(updatedAt, "updatedAt must not be null");
	}

}
