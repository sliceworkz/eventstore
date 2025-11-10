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

public record EventStreamId ( String context, String purpose ) {

	private static final String DEFAULT_PURPOSE = "default";
	
	public static EventStreamId forContext ( String context ) {
		return new EventStreamId(context, DEFAULT_PURPOSE);
	}

	public static EventStreamId anyContext ( ) {
		return new EventStreamId(null, null);
	}
	
	public EventStreamId withPurpose ( String purpose ) {
		return new EventStreamId(context, purpose);
	}

	public EventStreamId anyPurpose (  ) {
		return new EventStreamId(context, null);
	}

	public boolean isAnyContext ( ) {
		return context == null;
	}
	
	public boolean isAnyPurpose ( ) {
		return purpose == null;
	}
	
	public boolean canRead ( EventStreamId actualStreamId ) {
		boolean result = true;
		if ( !this.isAnyContext() && !this.context().equals(actualStreamId.context()) ) {
			result = false;
		} else if ( !this.isAnyPurpose() && !this.purpose().equals(actualStreamId.purpose())){
			result = false;
		}
		return result;
	}

	public String toString ( ) {
		StringBuilder result = new StringBuilder();
		if ( context != null ) {
			result.append(context);
		}
		if ( purpose != null ) {
			result.append("#");
			result.append(purpose);
		}
		return result.toString();
	}

}
