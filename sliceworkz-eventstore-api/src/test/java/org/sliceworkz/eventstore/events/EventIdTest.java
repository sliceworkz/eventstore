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
package org.sliceworkz.eventstore.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class EventIdTest {

	@Test
	void shouldGenerateUniqueIds ( ) {
		Set<String> ids = new HashSet<>();
		for ( int i = 0; i < 10_000; i++ ) {
			ids.add ( EventId.create().value() );
		}
		assertEquals ( 10_000, ids.size() );
	}

	@Test
	void shouldProduceValidUUIDStringRepresentation ( ) {
		EventId id = EventId.create();
		assertNotNull ( id.value() );
		UUID parsed = UUID.fromString ( id.value() );
		assertNotNull ( parsed );
	}

	@Test
	void ofIsTheConstructor ( ) {
		assertEquals ( new EventId ( "evt-1" ), EventId.of ( "evt-1" ) );
	}

	/**
	 * A factory that answers {@code null} for a value it cannot accept hides the mistake until the id
	 * is used, one call later and with a message naming nothing about the blank string. Both entry
	 * points reject the same values, the same way, with a message.
	 */
	@Test
	void ofRejectsWhatTheConstructorRejects ( ) {
		for ( String bad : new String[] { null, "", " ", "\t\n" } ) {
			IllegalArgumentException viaFactory = assertThrows ( IllegalArgumentException.class, () -> EventId.of ( bad ), String.valueOf ( bad ) );
			IllegalArgumentException viaConstructor = assertThrows ( IllegalArgumentException.class, () -> new EventId ( bad ), String.valueOf ( bad ) );
			assertNotNull ( viaFactory.getMessage() );
			assertEquals ( viaConstructor.getMessage(), viaFactory.getMessage() );
		}
	}

}
