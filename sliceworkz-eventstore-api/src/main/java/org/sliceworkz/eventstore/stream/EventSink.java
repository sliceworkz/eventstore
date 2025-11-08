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
package org.sliceworkz.eventstore.stream;

import java.util.Collections;
import java.util.List;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EphemeralEvent;

public interface EventSink<DOMAIN_EVENT_TYPE> {

	// returns Events with filled in position in stream
	List<Event<DOMAIN_EVENT_TYPE>> append ( AppendCriteria appendCriteria, List<EphemeralEvent<? extends DOMAIN_EVENT_TYPE>> events );
	
	default List<Event<DOMAIN_EVENT_TYPE>> append ( AppendCriteria appendCriteria, EphemeralEvent<? extends DOMAIN_EVENT_TYPE> event ) {
		return append(appendCriteria, Collections.singletonList(event));
	}

}
