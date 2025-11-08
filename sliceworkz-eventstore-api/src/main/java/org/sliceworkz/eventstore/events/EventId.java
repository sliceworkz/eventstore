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
package org.sliceworkz.eventstore.events;

import java.util.UUID;

public record EventId ( String value ) {
	
	public EventId ( String value ) {
		
		if ( value == null || "".equals(value.strip()) ) {
			throw new IllegalArgumentException();
		}
		
		this.value = value;
	}

	public static EventId create ( ) {
		return new EventId ( UUID.randomUUID().toString() );
	}
	
	public static EventId of ( String value ) {
		return ( value == null || "".equals(value.strip()) ) ? null : new EventId ( value );
	}
	
}
