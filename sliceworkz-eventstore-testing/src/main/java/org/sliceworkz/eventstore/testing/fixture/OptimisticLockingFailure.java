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

import java.util.Optional;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * A rejected conditional append, with assertions on <em>which</em> consistency boundary rejected it.
 * <p>
 * That distinction matters: a decision guarded by the wrong filter still throws
 * {@code OptimisticLockingException}, and a test asserting only "it threw" passes on a boundary that
 * is too wide (spurious conflicts) or too narrow (real conflicts missed) alike.
 */
public final class OptimisticLockingFailure {

	private final OptimisticLockingException exception;

	OptimisticLockingFailure ( OptimisticLockingException exception ) {
		this.exception = exception;
	}

	/**
	 * @return the captured exception
	 */
	public OptimisticLockingException exception ( ) {
		return exception;
	}

	/**
	 * @return the filter that defined the violated consistency boundary
	 */
	public EventFilter filter ( ) {
		return exception.getFilter();
	}

	/**
	 * Asserts the violated boundary was scoped by this tag.
	 *
	 * @param key   tag key
	 * @param value tag value
	 * @return this
	 */
	public OptimisticLockingFailure matchingTags ( String key, String value ) {
		return matchingTags(Tags.of(key, value));
	}

	/**
	 * Asserts the violated boundary was scoped by all of these tags.
	 *
	 * @param expected the tags expected on the filter
	 * @return this
	 */
	public OptimisticLockingFailure matchingTags ( Tags expected ) {
		boolean found = filter().items().stream().anyMatch(item -> item.tags().containsAll(expected));
		if ( !found ) {
			throw new AssertionError("""
					the conflicting append was not guarded by the expected tags
					  expected: %s
					  filter  : %s""".formatted(expected.tags(), filter()));
		}
		return this;
	}

	/**
	 * Asserts the append expected this reference to still be the last relevant one.
	 *
	 * @param expected the reference the decision was taken from
	 * @return this
	 */
	public OptimisticLockingFailure expectedLastReference ( EventReference expected ) {
		Optional<EventReference> actual = exception.getExpectedLastEventReference();
		if ( actual.isEmpty() || !actual.get().equals(expected) ) {
			throw new AssertionError("expected the append to be conditional on %s, but it was conditional on %s"
					.formatted(expected, actual.map(Object::toString).orElse("no reference (expecting an empty boundary)")));
		}
		return this;
	}

	/**
	 * Asserts the append expected the consistency boundary to be empty — the "create if nothing
	 * exists yet" case.
	 *
	 * @return this
	 */
	public OptimisticLockingFailure expectedAnEmptyBoundary ( ) {
		Optional<EventReference> actual = exception.getExpectedLastEventReference();
		if ( actual.isPresent() ) {
			throw new AssertionError("expected the append to require an empty boundary, but it was conditional on " + actual.get());
		}
		return this;
	}

}
