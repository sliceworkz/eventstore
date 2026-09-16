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
package org.sliceworkz.eventstore.infra.postgres.shredding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore.KeyResolution;

/**
 * The key store's in-memory cache: what a key id resolved to, trusted for a ttl, and never more than a
 * fixed number of entries.
 * <p>
 * Two bounds, for two different reasons. The <b>ttl</b> bounds how long an erasure performed by another
 * instance goes unnoticed here: a lapsed entry is dropped when it is next asked for, and the database
 * answers instead. The <b>size</b> bounds the heap: a lapsed entry that is never asked for again is
 * dropped by nothing else, so a process that has resolved a key per subject over its lifetime — a
 * projection replaying a stream of a million subjects, a service that has seen every customer once —
 * would otherwise hold every one of them for good. When the cache is full the least recently used entry
 * goes, so a key read in a burst (a subject's events replayed together) stays for the burst.
 * <p>
 * Guarded by one monitor. A lookup under it costs tens of nanoseconds against the microsecond the AES-GCM
 * decryption behind it costs, and the alternative — a {@code ConcurrentHashMap} — cannot say which entry
 * is the least recently used, so it can only evict an arbitrary one, which is as likely the hottest key
 * as a lapsed one.
 * <p>
 * A ttl of {@link Duration#ZERO} disables the cache: nothing is stored and every lookup misses.
 */
final class KeyCache {

	private final Duration ttl;
	private final int maxEntries;
	private final Clock clock;
	private final Map<KeyId, Entry> entries;

	/**
	 * A resolution and the moment it stops being trusted.
	 */
	private record Entry ( KeyResolution resolution, Instant expiresAt ) {
		private boolean isLive ( Instant now ) {
			return now.isBefore(expiresAt);
		}
	}

	/**
	 * @param ttl        how long an entry stays trusted; {@link Duration#ZERO} disables the cache
	 * @param maxEntries the most entries held at once; the least recently used is evicted beyond it
	 * @param clock      what "now" is, for the ttl
	 * @throws IllegalArgumentException if the ttl is null or negative, the bound is not positive, or the
	 *                                  clock is null
	 */
	KeyCache ( Duration ttl, int maxEntries, Clock clock ) {
		if ( ttl == null || ttl.isNegative() ) {
			throw new IllegalArgumentException("cacheTtl cannot be null or negative; use Duration.ZERO to resolve every key from the database");
		}
		if ( maxEntries <= 0 ) {
			throw new IllegalArgumentException("maxCachedKeys must be positive; use a cacheTtl of Duration.ZERO to disable the cache");
		}
		if ( clock == null ) {
			throw new IllegalArgumentException("clock cannot be null");
		}
		this.ttl = ttl;
		this.maxEntries = maxEntries;
		this.clock = clock;
		// access order, so that iteration starts at the least recently used entry and a get keeps an
		// entry alive; the capacity is a hint, the bound is enforced by removeEldestEntry
		this.entries = new LinkedHashMap<>(16, 0.75f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry ( Map.Entry<KeyId, Entry> eldest ) {
				return size() > KeyCache.this.maxEntries;
			}
		};
	}

	/**
	 * What the key resolved to, if it was cached and the entry is still trusted.
	 * <p>
	 * A lapsed entry is dropped here rather than returned: lapsed is not wrong, but the database is where
	 * an erasure by another instance has been recorded, and it is asked next.
	 *
	 * @param key the key id
	 * @return the cached resolution, or empty for a key not cached or cached too long ago
	 */
	Optional<KeyResolution> get ( KeyId key ) {
		synchronized ( entries ) {
			Entry entry = entries.get(key);
			if ( entry == null ) {
				return Optional.empty();
			}
			if ( !entry.isLive(clock.instant()) ) {
				entries.remove(key);
				return Optional.empty();
			}
			return Optional.of(entry.resolution());
		}
	}

	/**
	 * Caches what a key resolved to, for the ttl. Evicts the least recently used entry when the cache is
	 * full. Does nothing when the cache is disabled.
	 *
	 * @param key        the key id
	 * @param resolution what it resolved to
	 */
	void put ( KeyId key, KeyResolution resolution ) {
		if ( ttl.isZero() ) {
			return;
		}
		Entry entry = new Entry(resolution, clock.instant().plus(ttl));
		synchronized ( entries ) {
			entries.put(key, entry);
		}
	}

	/**
	 * Drops a key, whether or not it was cached.
	 *
	 * @param key the key id
	 */
	void remove ( KeyId key ) {
		synchronized ( entries ) {
			entries.remove(key);
		}
	}

	/**
	 * Drops every entry.
	 */
	void clear ( ) {
		synchronized ( entries ) {
			entries.clear();
		}
	}

	/**
	 * @return how many entries are held, lapsed ones included
	 */
	int size ( ) {
		synchronized ( entries ) {
			return entries.size();
		}
	}

	/**
	 * @return the most entries held at once
	 */
	int maxEntries ( ) {
		return maxEntries;
	}

	/**
	 * @return how long an entry stays trusted; zero for a disabled cache
	 */
	Duration ttl ( ) {
		return ttl;
	}

}
