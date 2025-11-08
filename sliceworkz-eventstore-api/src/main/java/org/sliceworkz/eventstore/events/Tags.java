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

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record Tags ( Set<Tag> tags ) {
	
	public Tags ( Set<Tag> tags ) {
		if ( tags == null ) {
			throw new IllegalArgumentException();			
		}
		this.tags = tags;
	}
	
	public Optional<Tag> tag ( String name ) {
		return tags.stream().filter(t->(name==null?"":name).equals(t.key())).findAny();
	}

	public boolean containsAll ( Tags other ) {
		return tags.containsAll(other.tags);
	}
	
	public Tags merge ( Tags other ) {
		Set<Tag> merged = new HashSet<>();
		merged.addAll(tags);
		merged.addAll(other.tags);
		return new Tags(merged);
	}
	
	public static Tags of ( Tag... tags ) {
		if ( tags != null ) {
			return new Tags ( Set.of(tags));
		} else {
			return Tags.none();
		}
		
	}
	
	public static Tags of ( String key, String value ) {
		return of(Tag.of(key, value));
	}
	
	public static Tags none ( ) {
		return new Tags (Collections.emptySet());
	}
	
	public static Tags parse ( String... values ) {
		Set<Tag> tags = new HashSet<>();
		for ( String v: values ) {
			Tag t = Tag.parse(v);
			if ( t != null ) {
				tags.add(t);
			}
		}
		return Tags.of(tags.toArray(new Tag[tags.size()]));
	}
	
	public Set<String> toStrings ( ) {
		return tags.stream().map(Tag::toString).collect(Collectors.toSet());
	}
	
}
