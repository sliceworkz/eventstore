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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class EventIdTest {

	@Test
	void shouldGenerateValidUUIDv7WithVersion7 ( ) {
		UUID uuid = UUID.fromString ( EventId.create().value() );
		assertEquals ( 7, uuid.version() );
	}

	@Test
	void shouldGenerateValidUUIDv7WithVariant2 ( ) {
		UUID uuid = UUID.fromString ( EventId.create().value() );
		assertEquals ( 2, uuid.variant() );
	}

	@Test
	void shouldGenerateUniqueIds ( ) {
		Set<String> ids = new HashSet<>();
		for ( int i = 0; i < 10_000; i++ ) {
			ids.add ( EventId.create().value() );
		}
		assertEquals ( 10_000, ids.size() );
	}

	@Test
	void shouldBeMonotonicallyIncreasingOverTime ( ) throws InterruptedException {
		EventId first = EventId.create();
		Thread.sleep ( 2 );
		EventId second = EventId.create();
		assertTrue ( second.value().compareTo ( first.value() ) > 0 );
	}

	@Test
	void shouldProduceValidUUIDStringRepresentation ( ) {
		EventId id = EventId.create();
		assertNotNull ( id.value() );
		UUID parsed = UUID.fromString ( id.value() );
		assertNotNull ( parsed );
	}

}
