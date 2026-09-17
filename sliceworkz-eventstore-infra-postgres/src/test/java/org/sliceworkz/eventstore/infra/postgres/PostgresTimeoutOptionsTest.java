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
package org.sliceworkz.eventstore.infra.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.query.Limit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The two timeouts that bound a wait nothing else bounds — the advisory lock wait and the silence on a
 * monitoring connection — have defaults that are bounds, refuse values that would not be, and can be
 * set on the builder and on the storage alike. No database: this is about the settings, not their effect.
 */
class PostgresTimeoutOptionsTest {

	@Test
	void theDefaultsAreBounds ( ) {
		assertTrue(PostgresEventStorage.Builder.DEFAULT_LOCK_TIMEOUT.compareTo(Duration.ZERO) > 0,
			"a zero default lock timeout is 'wait forever', which is the failure being bounded");
		assertTrue(PostgresEventStorage.Builder.DEFAULT_NOTIFICATION_PROBE_INTERVAL.compareTo(Duration.ZERO) > 0);
		assertTrue(PostgresEventStorageImpl.NOTIFICATION_PROBE_TIMEOUT.compareTo(Duration.ZERO) > 0);
		// a probe that may take longer than the interval between probes would queue up behind itself
		assertTrue(PostgresEventStorageImpl.NOTIFICATION_PROBE_TIMEOUT.compareTo(PostgresEventStorage.Builder.DEFAULT_NOTIFICATION_PROBE_INTERVAL) < 0);
	}

	@Test
	void aStorageNobodyConfiguredCarriesTheDefaults ( ) {
		try ( PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl("opts", null, null, Limit.none(), "", false, new SimpleMeterRegistry()) ) {
			assertEquals(PostgresEventStorage.Builder.DEFAULT_LOCK_TIMEOUT, storage.lockTimeout());
			assertEquals(PostgresEventStorage.Builder.DEFAULT_NOTIFICATION_PROBE_INTERVAL, storage.notificationProbeInterval());
		}
	}

	@Test
	void theStorageSettersValidateAndNullRestoresTheDefault ( ) {
		try ( PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl("opts", null, null, Limit.none(), "", false, new SimpleMeterRegistry()) ) {
			assertEquals(Duration.ofSeconds(3), storage.lockTimeout(Duration.ofSeconds(3)).lockTimeout());
			assertEquals(Duration.ZERO, storage.lockTimeout(Duration.ZERO).lockTimeout(), "zero is PostgreSQL's 'no lock_timeout' and is allowed");
			assertEquals(PostgresEventStorage.Builder.DEFAULT_LOCK_TIMEOUT, storage.lockTimeout(null).lockTimeout());
			assertThrows(IllegalArgumentException.class, () -> storage.lockTimeout(Duration.ofMillis(-1)));
			assertThrows(IllegalArgumentException.class, () -> storage.lockTimeout(Duration.ofDays(30)),
				"a value lock_timeout cannot hold must be refused here, not on the first conditional append");

			assertEquals(Duration.ofSeconds(7), storage.notificationProbeInterval(Duration.ofSeconds(7)).notificationProbeInterval());
			assertEquals(PostgresEventStorage.Builder.DEFAULT_NOTIFICATION_PROBE_INTERVAL, storage.notificationProbeInterval(null).notificationProbeInterval());
			assertThrows(IllegalArgumentException.class, () -> storage.notificationProbeInterval(Duration.ZERO),
				"a zero interval would probe on every poll slice; there is no 'never probe' on purpose");
			assertThrows(IllegalArgumentException.class, () -> storage.notificationProbeInterval(Duration.ofSeconds(-1)));
		}
	}

	@Test
	void theBuilderRefusesTheSameValuesBeforeAnythingIsBuilt ( ) {
		assertThrows(IllegalArgumentException.class, () -> PostgresEventStorage.newBuilder().lockTimeout(Duration.ofSeconds(-1)));
		assertThrows(IllegalArgumentException.class, () -> PostgresEventStorage.newBuilder().notificationProbeInterval(Duration.ZERO));
		// and accepts what the storage accepts, null included
		PostgresEventStorage.newBuilder().lockTimeout(null).lockTimeout(Duration.ZERO).notificationProbeInterval(null);
	}
}
