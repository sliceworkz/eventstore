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
package org.sliceworkz.eventstore.examples.documentversions;

import java.util.List;

import org.sliceworkz.eventstore.events.EventReference;

/**
 * The events of a versioned document.
 * <p>
 * Every event is tagged {@code document:<id>} and {@code version:<name>}: the document it belongs to and
 * the line of work it was written on. The content events name the paragraph they touch by a stable id,
 * never by a position, which is what makes changes made on two versions commute — and so what makes a
 * merge of two versions well defined (see {@link DocumentVersions#merge}).
 * <p>
 * Three events give the versions their structure: {@link DocumentCreated} starts the first version,
 * {@link VersionBranched} starts a version from a point in the history of another, and
 * {@link VersionsMerged} takes the history of other versions into one. None of them is ever retagged or
 * copied: a version shares its history with the versions it came from by reference.
 */
public sealed interface DocumentEvent {

	/** The document is created, and with it its first version (the one this event is tagged with). */
	record DocumentCreated ( String title ) implements DocumentEvent { }

	record TitleChanged ( String title ) implements DocumentEvent { }

	/**
	 * A paragraph is added after another one, or at the start when {@code after} is {@code null}.
	 * The anchor is an id, not an index: an insert on another version does not move it.
	 */
	record ParagraphAdded ( String paragraphId, String after, String text ) implements DocumentEvent { }

	record ParagraphTextReplaced ( String paragraphId, String text ) implements DocumentEvent { }

	record ParagraphRemoved ( String paragraphId ) implements DocumentEvent { }

	/**
	 * A version is started from a point in the history of another (tagged with the new version).
	 *
	 * @param version the new version
	 * @param parent the version the fork point was written on — always the version whose tag that event
	 *               carries, so the fork point lies in the parent's own events
	 * @param forkPoint the last event of the parent the new version includes
	 */
	record VersionBranched ( String version, String parent, EventReference forkPoint ) implements DocumentEvent { }

	/**
	 * The history of other versions, each up to an event of theirs, is taken into a version (tagged with
	 * that version). The events themselves are not copied: reading the version follows these links.
	 *
	 * @param into the version that takes them in
	 * @param sources each source version and the last of its events taken in
	 */
	record VersionsMerged ( String into, List<MergeSource> sources ) implements DocumentEvent { }

	/**
	 * One side of a merge: a version, and the last of its events taken in.
	 *
	 * @param version the version — when stored, the version whose tag {@code upTo} carries
	 * @param upTo the last event taken in; {@code null} when asking to merge, meaning the version's head
	 */
	record MergeSource ( String version, EventReference upTo ) { }

}
