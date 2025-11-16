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

public record EventReference ( EventId id, Long position ) {

	public EventReference ( EventId id, Long position  ) {
		if ( id == null ) {
			throw new IllegalArgumentException("event id in reference cannot be null");
		}
		if ( position == null ) {
			throw new IllegalArgumentException("position in reference cannot be null");
		} else if ( position <= 0 ) {
			throw new IllegalArgumentException("position %d is invalid, should be larger than 0".formatted(position));
		}
		
		this.id = id;
		this.position = position;
	}
	
	public static EventReference of ( EventId id, long position ) {
		return new EventReference(id, position);
	}
	
	public static EventReference create ( long position ) {
		return of ( EventId.create(), position );
	}
	
	public static EventReference none ( ) {
		return null;
	}
	
}
