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
package org.sliceworkz.eventstore.migration;

import java.time.Duration;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * The outcome of an {@link EventStoreImporter} run.
 * <p>
 * Returned when the run completes, and handed to the progress callback after every batch as a running
 * total. A report is never written anywhere by the importer — recording it is the application's choice.
 *
 * <h2>Resuming and catching up</h2>
 * {@link #sourceTo()} is the boundary the run was bounded by: the source's head at the moment the run
 * started. Events appended to the source afterwards are deliberately outside the run. Feeding that value
 * into the next run picks up exactly where this one stopped:
 * <pre>{@code
 * ImportReport first = EventStoreImporter.from(source).to(target).run();
 *
 * // later, to bring across whatever the source has accumulated since
 * ImportReport catchUp = EventStoreImporter.from(source).to(target)
 *     .after(first.sourceTo())
 *     .run();
 * }</pre>
 * Without {@link EventStoreImporter#after(EventReference)} a follow-up run rescans the source from the
 * beginning, which is correct but costs a full pass.
 *
 * @param read number of source events read and offered to the transformation
 * @param dropped number of events the transformation discarded
 * @param imported number of events written into the target
 * @param skipped number of events the target already held, under {@link org.sliceworkz.eventstore.spi.EventStorage.ImportMode#SKIP_EXISTING_ID}
 * @param sourceFrom the cursor the run started after, or null if it started at the beginning of the source
 * @param sourceTo the source head the run was bounded by, or null if the source held no events
 * @param firstTargetReference the reference the first imported event received in the target, or null if nothing was imported
 * @param lastTargetReference the reference the last imported event received in the target, or null if nothing was imported
 * @param duration wall-clock time elapsed so far
 * @see EventStoreImporter
 */
public record ImportReport ( long read, long dropped, long imported, long skipped,
		EventReference sourceFrom, EventReference sourceTo,
		EventReference firstTargetReference, EventReference lastTargetReference,
		Duration duration ) {

	/**
	 * Returns a human-readable one-line summary, handy for a progress callback that just logs.
	 *
	 * @return a summary of the counters and the source boundary
	 */
	@Override
	public String toString ( ) {
		return "read=%d dropped=%d imported=%d skipped=%d sourceFrom=%s sourceTo=%s duration=%s"
				.formatted(read, dropped, imported, skipped, sourceFrom, sourceTo, duration);
	}

}
