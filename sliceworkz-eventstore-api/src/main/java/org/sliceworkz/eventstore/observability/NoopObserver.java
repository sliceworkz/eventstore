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
package org.sliceworkz.eventstore.observability;

/**
 * {@link EventStoreObserver#NOOP}: one observer and one scope, shared by everything that observes nothing.
 */
final class NoopObserver implements EventStoreObserver {

	static final NoopObserver INSTANCE = new NoopObserver();

	@SuppressWarnings("rawtypes")
	private static final Observation.Scope SCOPE = new Observation.Scope() {

		@Override
		public void completed ( Outcome outcome ) { }

		@Override
		public void failed ( Throwable failure ) { }

		@Override
		public void close ( ) { }

	};

	private NoopObserver ( ) { }

	@SuppressWarnings("unchecked")
	static <O extends Outcome> Observation.Scope<O> scope ( ) {
		return SCOPE;
	}

	@Override
	public <O extends Outcome> Observation.Scope<O> start ( Observation<O> observation ) {
		return scope();
	}

	@Override
	public String toString ( ) {
		return "EventStoreObserver.NOOP";
	}

}
