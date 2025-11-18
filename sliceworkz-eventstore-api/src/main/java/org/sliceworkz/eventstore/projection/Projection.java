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
package org.sliceworkz.eventstore.projection;

import org.sliceworkz.eventstore.events.EventWithMetaDataHandler;
import org.sliceworkz.eventstore.query.EventQuery;

/**
 * A Projection combines an {@link EventQuery} with an {@link EventWithMetaDataHandler}, 
 * allowing all events that comply with the criteria of the query to be handled.
 */
public interface Projection<CONSUMED_EVENT_TYPE> extends EventWithMetaDataHandler<CONSUMED_EVENT_TYPE> {

	/*
	 * Returns the EventQuery for the Events the Projection depends on.  
	 */
	EventQuery eventQuery ( ); 
	
}
