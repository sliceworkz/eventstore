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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class UUIDv7Test {

	@Test
	void shouldGenerateValidUUIDv7WithVersion7 ( ) {
		UUID uuid = UUIDv7.generate();
		assertEquals ( 7, uuid.version(), "UUID version should be 7" );
	}

	@Test
	void shouldGenerateValidUUIDv7WithVariant2 ( ) {
		UUID uuid = UUIDv7.generate();
		assertEquals ( 2, uuid.variant(), "UUID variant should be 2 (RFC 4122)" );
	}

	@Test
	void shouldGenerateUniqueIds ( ) {
		Set<UUID> ids = new HashSet<>();
		for ( int i = 0; i < 10_000; i++ ) {
			ids.add ( UUIDv7.generate() );
		}
		assertEquals ( 10_000, ids.size(), "All generated UUIDs should be unique" );
	}

	@Test
	void shouldBeMonotonicallyIncreasingOverTime ( ) throws InterruptedException {
		UUID first = UUIDv7.generate();
		Thread.sleep ( 2 );
		UUID second = UUIDv7.generate();

		// UUIDv7 with later timestamp should compare greater (unsigned MSB comparison)
		assertTrue (
			first.getMostSignificantBits() < second.getMostSignificantBits(),
			"UUIDv7 generated later should have a greater MSB (time-ordered)"
		);
	}

	@Test
	void shouldEmbedTimestampWithinTolerance ( ) {
		Instant before = Instant.now();
		UUID uuid = UUIDv7.generate();
		Instant after = Instant.now();

		Instant extracted = UUIDv7.extractTimestamp ( uuid );

		assertTrue (
			! extracted.isBefore ( before.minusMillis ( 1 ) ) && ! extracted.isAfter ( after.plusMillis ( 1 ) ),
			"Extracted timestamp should be within tolerance of generation time"
		);
	}

	@Test
	void shouldProduceValidStringRepresentation ( ) {
		String uuidStr = UUIDv7.generateString();
		UUID parsed = UUID.fromString ( uuidStr );
		assertEquals ( 7, parsed.version() );
		assertEquals ( 2, parsed.variant() );
	}

	@Test
	void eventIdCreateShouldUseUUIDv7 ( ) {
		EventId eventId = EventId.create();
		UUID parsed = UUID.fromString ( eventId.value() );
		assertEquals ( 7, parsed.version(), "EventId.create() should generate UUIDv7" );
	}

}
