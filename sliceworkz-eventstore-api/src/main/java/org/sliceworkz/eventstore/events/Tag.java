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

public record Tag ( String key, String value ) {

	public static Tag of ( String key ) {
		return new Tag(key, null);
	}

	public static Tag of ( String key, String value ) {
		return new Tag(key, value);
	}

	public static Tag parse ( String string ) {
		Tag result = null;
		if ( string != null ) {
			String key = null;
			String value = null;
			int index = string.indexOf(':');
			if ( index >= 0 ) {
				key = string.substring(0, index).strip();
				if ( key != null && key.length() == 0 ) {
					key = null;
				}
				value = string.length() > index ? string.substring(index + 1).strip() : null;
				if ( value != null && value.length() == 0 ) {
					value = null;
				}
			} else {
				key = (string.strip().length() > 0) ? string.strip() : null;
				if ( key != null && key.length() == 0 ) {
					key = null;
				}
			}
			
			if ( key != null || value != null ) {
				result = Tag.of(key, value);
			}
		}
		return result;
	}
	
	public String toString ( ) {
		StringBuilder sb = new StringBuilder ( );
		if ( key != null ) {
			sb.append(key);
		}
		if ( value != null ) {
			sb.append(":");
			sb.append(value);
		}
		return sb.toString();
	}
	
}
