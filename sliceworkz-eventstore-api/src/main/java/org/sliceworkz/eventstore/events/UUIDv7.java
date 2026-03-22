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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generator for RFC 9562 UUIDv7 identifiers.
 * <p>
 * UUIDv7 encodes a Unix epoch millisecond timestamp in the most significant 48 bits,
 * making generated IDs monotonically increasing over time. This yields better B-tree index
 * locality compared to random UUID v4, which is especially beneficial for append-only
 * workloads like event stores.
 * <p>
 * The remaining bits are filled with cryptographically secure random data, ensuring
 * global uniqueness without coordination.
 *
 * <h2>Layout (128 bits):</h2>
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       unix_ts_ms (32 high)                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |   unix_ts_ms (16 low) | ver=7 |        rand_a (12 bits)      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var=10|              rand_b (62 bits)                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       rand_b (continued)                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * // Generate a new UUIDv7
 * UUID id = UUIDv7.generate();
 *
 * // As a string
 * String idStr = UUIDv7.generateString();
 *
 * // Extract the embedded timestamp
 * Instant ts = UUIDv7.extractTimestamp(id);
 * }</pre>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9562#section-5.7">RFC 9562 Section 5.7</a>
 */
public final class UUIDv7 {

	private static final SecureRandom RANDOM = new SecureRandom();

	private UUIDv7 ( ) { }

	/**
	 * Generates a new UUIDv7.
	 *
	 * @return a new time-ordered UUID
	 */
	public static UUID generate ( ) {

		long timestamp = System.currentTimeMillis();

		// Random bits for rand_a (12 bits) and rand_b (62 bits)
		long randomHigh = RANDOM.nextLong();
		long randomLow = RANDOM.nextLong();

		// MSB: 48 bits timestamp | 4 bits version (0111) | 12 bits rand_a
		long msb = ( timestamp << 16 )                  // shift timestamp to top 48 bits
				| ( 0x7L << 12 )                        // version 7
				| ( randomHigh & 0x0FFFL );             // 12 bits of randomness

		// LSB: 2 bits variant (10) | 62 bits rand_b
		long lsb = ( 0b10L << 62 )                     // variant bits
				| ( randomLow & 0x3FFFFFFFFFFFFFFFL );  // 62 bits of randomness

		return new UUID ( msb, lsb );
	}

	/**
	 * Generates a new UUIDv7 as a string.
	 *
	 * @return the canonical string representation of a new time-ordered UUID
	 */
	public static String generateString ( ) {
		return generate().toString();
	}

	/**
	 * Extracts the embedded Unix epoch millisecond timestamp from a UUIDv7.
	 * <p>
	 * This is useful for debugging and operational tooling — you can determine approximately
	 * when an event was created just from its ID.
	 *
	 * @param uuid a UUIDv7 (behavior is undefined for non-v7 UUIDs)
	 * @return the {@link Instant} embedded in the UUID
	 */
	public static Instant extractTimestamp ( UUID uuid ) {
		long millis = uuid.getMostSignificantBits() >>> 16;
		return Instant.ofEpochMilli ( millis );
	}

}
