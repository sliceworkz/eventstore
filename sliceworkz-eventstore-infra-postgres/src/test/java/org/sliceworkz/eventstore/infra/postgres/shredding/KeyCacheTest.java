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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore.KeyResolution;

/**
 * The key store's cache without a database: the ttl, the size bound, and which entry the bound evicts.
 */
public class KeyCacheTest {

	private static final Duration TTL = Duration.ofHours(1);

	/**
	 * A clock the test advances by hand, so lapsing needs no sleep.
	 */
	private static final class ManualClock extends Clock {

		private Instant now = Instant.parse("2026-01-01T00:00:00Z");

		@Override
		public Instant instant ( ) {
			return now;
		}

		@Override
		public ZoneOffset getZone ( ) {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone ( java.time.ZoneId zone ) {
			return this;
		}

		void advance ( Duration by ) {
			now = now.plus(by);
		}
	}

	private static KeyId key ( int n ) {
		return KeyId.of("k-" + n);
	}

	private static KeyResolution resolved ( int n ) {
		byte[] material = new byte[32];
		material[0] = (byte) n;
		return new KeyResolution.Resolved(new SecretKeySpec(material, "AES"));
	}

	@Test
	public void testACachedResolutionComesBackUntilItLapses ( ) {
		ManualClock clock = new ManualClock();
		KeyCache cache = new KeyCache(TTL, 10, clock);
		KeyResolution resolution = resolved(1);

		cache.put(key(1), resolution);
		assertEquals(resolution, cache.get(key(1)).orElseThrow());

		clock.advance(TTL.minusSeconds(1));
		assertEquals(resolution, cache.get(key(1)).orElseThrow(), "an entry inside its ttl is trusted");

		clock.advance(Duration.ofSeconds(1));
		assertTrue(cache.get(key(1)).isEmpty(), "an entry at its ttl is no longer trusted");
		assertEquals(0, cache.size(), "a lapsed entry is dropped when it is asked for");
	}

	@Test
	public void testADenialIsCachedLikeAKey ( ) {
		KeyCache cache = new KeyCache(TTL, 10, new ManualClock());
		KeyResolution denied = new KeyResolution.Denied("no SELECT on key_material");

		cache.put(key(1), denied);
		assertEquals(denied, cache.get(key(1)).orElseThrow());
	}

	@Test
	public void testAZeroTtlCachesNothing ( ) {
		KeyCache cache = new KeyCache(Duration.ZERO, 10, new ManualClock());

		cache.put(key(1), resolved(1));
		assertTrue(cache.get(key(1)).isEmpty());
		assertEquals(0, cache.size());
	}

	@Test
	public void testTheBoundHoldsAndTheLeastRecentlyUsedEntryGoes ( ) {
		KeyCache cache = new KeyCache(TTL, 3, new ManualClock());

		cache.put(key(1), resolved(1));
		cache.put(key(2), resolved(2));
		cache.put(key(3), resolved(3));
		assertEquals(3, cache.size());

		// a read keeps an entry alive: 1 is now the most recently used, 2 the least
		assertTrue(cache.get(key(1)).isPresent());

		cache.put(key(4), resolved(4));
		assertEquals(3, cache.size(), "the bound is the most entries held at once");
		assertTrue(cache.get(key(2)).isEmpty(), "the least recently used entry is the one evicted");
		assertTrue(cache.get(key(1)).isPresent(), "an entry read since the others were put stays");
		assertTrue(cache.get(key(3)).isPresent());
		assertTrue(cache.get(key(4)).isPresent());
	}

	@Test
	public void testAReplacedEntryDoesNotCountTwice ( ) {
		KeyCache cache = new KeyCache(TTL, 2, new ManualClock());

		cache.put(key(1), resolved(1));
		cache.put(key(2), resolved(2));
		cache.put(key(1), resolved(3));

		assertEquals(2, cache.size());
		assertEquals(resolved(3), cache.get(key(1)).orElseThrow(), "a put replaces what was cached for the key");
		assertTrue(cache.get(key(2)).isPresent(), "replacing an entry evicts nothing");
	}

	@Test
	public void testTheSizeNeverExceedsTheBoundHoweverManyKeysAreResolved ( ) {
		KeyCache cache = new KeyCache(TTL, 100, new ManualClock());

		for ( int n = 0; n < 10_000; n++ ) {
			cache.put(key(n), resolved(n));
			assertTrue(cache.size() <= 100, "size " + cache.size() + " after " + (n + 1) + " puts");
		}
		assertEquals(100, cache.size());
		assertTrue(cache.get(key(0)).isEmpty(), "the oldest keys are the ones gone");
		assertTrue(cache.get(key(9_999)).isPresent(), "the newest keys are the ones kept");
	}

	@Test
	public void testLapsedEntriesCountTowardsTheBoundUntilAskedFor ( ) {
		// this is what the bound is for: a lapsed entry nobody asks for again is dropped by nothing
		// but the bound, so without it a process that has resolved a key per subject keeps them all
		ManualClock clock = new ManualClock();
		KeyCache cache = new KeyCache(TTL, 5, clock);

		for ( int n = 0; n < 5; n++ ) {
			cache.put(key(n), resolved(n));
		}
		clock.advance(TTL.plusSeconds(1));
		assertEquals(5, cache.size(), "lapsing on its own drops nothing");

		for ( int n = 5; n < 10; n++ ) {
			cache.put(key(n), resolved(n));
		}
		assertEquals(5, cache.size(), "the bound is what makes room, lapsed or not");
		for ( int n = 0; n < 5; n++ ) {
			assertTrue(cache.get(key(n)).isEmpty());
		}
	}

	@Test
	public void testRemoveAndClear ( ) {
		KeyCache cache = new KeyCache(TTL, 10, new ManualClock());

		cache.put(key(1), resolved(1));
		cache.put(key(2), resolved(2));

		cache.remove(key(1));
		assertTrue(cache.get(key(1)).isEmpty());
		assertTrue(cache.get(key(2)).isPresent());
		cache.remove(key(42));   // never cached: a no-op

		cache.clear();
		assertEquals(0, cache.size());
		assertTrue(cache.get(key(2)).isEmpty());
	}

	@Test
	public void testTheBoundHoldsUnderConcurrentUse ( ) throws Exception {
		KeyCache cache = new KeyCache(TTL, 50, new ManualClock());
		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> work = new ArrayList<>();
			for ( int t = 0; t < threads; t++ ) {
				int offset = t * 1000;
				work.add(pool.submit(() -> {
					start.await();
					for ( int n = 0; n < 1000; n++ ) {
						cache.put(key(offset + n), resolved(n));
						cache.get(key(offset + n / 2));
						assertTrue(cache.size() <= 50);
					}
					return null;
				}));
			}
			start.countDown();
			for ( Future<?> f : work ) {
				f.get();
			}
		} finally {
			pool.shutdownNow();
		}
		assertEquals(50, cache.size());
	}

	@Test
	public void testTheArgumentsAreValidated ( ) {
		Clock clock = Clock.systemUTC();
		assertThrows(IllegalArgumentException.class, () -> new KeyCache(null, 10, clock));
		assertThrows(IllegalArgumentException.class, () -> new KeyCache(Duration.ofSeconds(-1), 10, clock));
		assertThrows(IllegalArgumentException.class, () -> new KeyCache(TTL, 0, clock));
		assertThrows(IllegalArgumentException.class, () -> new KeyCache(TTL, -1, clock));
		assertThrows(IllegalArgumentException.class, () -> new KeyCache(TTL, 10, null));
		assertFalse(new KeyCache(Duration.ZERO, 1, clock).ttl().isNegative(), "zero is the documented way to disable the cache");
	}

}
