/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventstore;

import java.util.Collections;
import java.util.Set;

import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public interface EventStore {

	<DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses, Set<Class<?>> historicalEventRootClasses );

	
	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId ) {
		return getEventStream(eventStreamId, Collections.emptySet(), Collections.emptySet());
	}

	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Set<Class<?>> eventRootClasses ) {
		return getEventStream(eventStreamId, eventRootClasses, Collections.emptySet());
	}

	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Class<?> eventRootClass ) {
		return getEventStream(eventStreamId, Collections.singleton(eventRootClass), Collections.emptySet());
	}

	default <DOMAIN_EVENT_TYPE> EventStream<DOMAIN_EVENT_TYPE> getEventStream ( EventStreamId eventStreamId, Class<?> eventRootClass, Class<?> historicalEventRootClass ) {
		return getEventStream(eventStreamId, Collections.singleton(eventRootClass), Collections.singleton(historicalEventRootClass));
	}
	
}