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

import java.util.Set;

/**
 * A request on a document that its history does not allow: an unknown version, a paragraph that is not
 * there, a merge whose sides changed the same thing. Nothing is appended.
 */
public class DocumentRejectedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final Set<String> conflicts;

	public DocumentRejectedException ( String message ) {
		this(message, Set.of());
	}

	public DocumentRejectedException ( String message, Set<String> conflicts ) {
		super(message);
		this.conflicts = Set.copyOf(conflicts);
	}

	/** For a refused merge: what both sides changed (such as {@code paragraph:p2}); empty otherwise. */
	public Set<String> conflicts ( ) {
		return conflicts;
	}

}
