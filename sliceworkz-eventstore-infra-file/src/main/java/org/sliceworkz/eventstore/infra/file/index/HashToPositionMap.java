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
package org.sliceworkz.eventstore.infra.file.index;

import java.nio.charset.StandardCharsets;
import java.util.function.LongPredicate;

/**
 * Maps a 64-bit hash of a key to the position of the event that carries it.
 *
 * <h2>Why a hash and not the key</h2>
 * The obvious structure is a {@code HashMap<EventId, Long>}, and it costs about 130 bytes an event once
 * the {@code String}, the record wrapping it, the boxed {@code Long} and the map's own entry are
 * counted — 130 MB at a million events, which is more than the index has any business costing when the
 * events themselves are on disk. This is 16 bytes an event, in two flat arrays.
 *
 * <h2>Collisions are handled by confirming, not by hoping</h2>
 * A 64-bit hash makes a collision unlikely, and this class does not rely on that. A lookup hands every
 * candidate position to a predicate that reads the record and compares the actual key, so a collision
 * costs one extra read and never a wrong answer. That matters: this index decides whether an event id
 * already exists and whether an idempotency key is a duplicate, and a false match there would silently
 * drop a caller's event.
 *
 * <h2>Not thread-safe on its own</h2>
 * As with the rest of the index, mutation and lookup both happen under the storage's writer lock.
 */
public final class HashToPositionMap {

	/**
	 * Separates the parts of a composite key, as an explicit constant rather than a literal.
	 * <p>
	 * A raw control character in a source file is invisible in most editors and does not survive every
	 * tool that touches the file. It is the unit separator because it cannot occur in a stream context,
	 * purpose or idempotency key that came from anything but deliberate abuse -- and even then a
	 * collision costs a confirming read, never a wrong answer.
	 */
	private static final char SEPARATOR = 0x1F;

	private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;

	/** Grow at this load factor: linear probing degrades sharply past about three quarters full. */
	private static final double MAX_LOAD_FACTOR = 0.6;

	/** No event holds position zero, so zero marks an empty slot without needing a second array. */
	private static final long EMPTY = 0;

	private long[] hashes;
	private long[] positions;
	private int size;
	private int mask;

	/**
	 * Creates an empty map.
	 *
	 * @param initialCapacity a hint; rounded up to a power of two, minimum 16
	 */
	public HashToPositionMap ( int initialCapacity ) {
		int capacity = Integer.highestOneBit(Math.max(16, initialCapacity - 1)) * 2;
		this.hashes = new long[capacity];
		this.positions = new long[capacity];
		this.mask = capacity - 1;
	}

	/**
	 * Records that the event at a position carries a key with this hash.
	 *
	 * @param hash the key's hash
	 * @param position the event's position, which must be positive
	 */
	public void put ( long hash, long position ) {
		if ( position <= 0 ) {
			throw new IllegalArgumentException("position must be positive, got " + position);
		}
		if ( ( size + 1 ) > hashes.length * MAX_LOAD_FACTOR ) {
			grow();
		}
		insert(hashes, positions, mask, hash, position);
		size++;
	}

	/**
	 * Finds the position of the event carrying a key, confirming each candidate.
	 *
	 * @param hash the key's hash
	 * @param confirm reads the event at a candidate position and answers whether its key really matches
	 * @return the confirmed position, or {@code 0} if no event carries the key
	 */
	public long find ( long hash, LongPredicate confirm ) {
		int slot = (int) ( mix(hash) & mask );
		while ( positions[slot] != EMPTY ) {
			if ( hashes[slot] == hash && confirm.test(positions[slot]) ) {
				return positions[slot];
			}
			slot = ( slot + 1 ) & mask;
		}
		return 0;
	}

	/**
	 * How many keys this map holds.
	 *
	 * @return the number of indexed keys
	 */
	public int size ( ) {
		return size;
	}

	/**
	 * Hashes a string with FNV-1a over its UTF-8 bytes.
	 * <p>
	 * {@link String#hashCode()} is deliberately not used: it is 32 bits, so collisions arrive at tens of
	 * thousands of keys rather than billions, and it is trivially poor for the structured keys this map
	 * holds — an idempotency key is usually a prefix plus a counter, which is close to the worst case
	 * for a hash that multiplies by 31.
	 *
	 * @param value the string to hash; must not be null
	 * @return a 64-bit hash
	 */
	public static long hash ( String value ) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		long h = FNV_OFFSET_BASIS;
		for ( byte b : bytes ) {
			h ^= ( b & 0xff );
			h *= FNV_PRIME;
		}
		return h;
	}

	/**
	 * Hashes several parts as one key, separated so that different splits hash differently.
	 *
	 * @param parts the parts, none null
	 * @return a 64-bit hash of the parts as a unit
	 */
	public static long hash ( String... parts ) {
		StringBuilder combined = new StringBuilder();
		for ( String part : parts ) {
			combined.append(part).append(SEPARATOR);
		}
		return hash(combined.toString());
	}

	private void grow ( ) {
		int capacity = hashes.length * 2;
		long[] newHashes = new long[capacity];
		long[] newPositions = new long[capacity];
		int newMask = capacity - 1;
		for ( int i = 0; i < hashes.length; i++ ) {
			if ( positions[i] != EMPTY ) {
				insert(newHashes, newPositions, newMask, hashes[i], positions[i]);
			}
		}
		this.hashes = newHashes;
		this.positions = newPositions;
		this.mask = newMask;
	}

	private static void insert ( long[] hashes, long[] positions, int mask, long hash, long position ) {
		int slot = (int) ( mix(hash) & mask );
		while ( positions[slot] != EMPTY ) {
			slot = ( slot + 1 ) & mask;
		}
		hashes[slot] = hash;
		positions[slot] = position;
	}

	/**
	 * Spreads a hash before it is masked down to a slot.
	 * <p>
	 * FNV-1a's low bits are its weakest, and masking keeps only the low bits — so without this, keys
	 * differing in one trailing character would cluster into adjacent slots and linear probing would
	 * spend its time walking past them.
	 */
	private static long mix ( long hash ) {
		long h = hash;
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		return h & Long.MAX_VALUE;
	}

}
