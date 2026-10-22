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
package org.sliceworkz.eventstore.testing.tck.spi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.observability.HealthCheck;
import org.sliceworkz.eventstore.observability.HealthStatus;
import org.sliceworkz.eventstore.observability.StorageHealth;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * The health contract on {@link EventStorage#health(Duration)}, which every backend must honour: a
 * storage in working order answers {@link HealthStatus#UP} with {@link HealthCheck#OPEN} among its checks,
 * and a closed one answers {@link HealthStatus#DOWN} — rather than throwing as its operations do, since a
 * health endpoint whose own call fails says nothing about what failed.
 * <p>
 * What else a backend checks is its own business, and pinned beside the backend; this holds every
 * backend to the part a health endpoint relies on whatever is underneath.
 */
public class StorageHealthTest extends AbstractEventStoreTest {

	@ForEachBackend
	void aStorageInWorkingOrderIsUpOnEveryCheck ( ) {
		EventStorage storage = eventStorage();
		EventStreamId stream = EventStreamId.forContext("health").withPurpose("1");
		storage.append(AppendCriteria.none(), stream, List.of(new EventToStore(stream, EventType.named("Checked"), "{}", Tags.none(), null)));

		StorageHealth health = storage.health();

		assertEquals(storage.name(), health.storage(), "the health names the storage it is about");
		assertEquals(HealthStatus.UP, health.status(), "a storage in working order is up: " + health);
		assertEquals(HealthStatus.UP, health.check(HealthCheck.OPEN).orElseThrow().status(), "every backend reports whether it is open");
		health.checks().forEach(check -> assertEquals(HealthStatus.UP, check.status(), "every check of a storage in working order is up: " + check));
	}

	@ForEachBackend
	void aClosedStorageIsDownWithoutThrowing ( ) {
		EventStorage storage = eventStorage();
		storage.close();

		StorageHealth health = assertDoesNotThrow(() -> storage.health(), "health is the one question a closed storage still answers");

		assertEquals(HealthStatus.DOWN, health.status());
		HealthCheck open = health.check(HealthCheck.OPEN).orElseThrow();
		assertEquals(HealthStatus.DOWN, open.status());
		assertTrue(!open.message().isBlank(), "a check that is down says why");
	}

	@ForEachBackend
	void healthAnswersRepeatedlyAndWithinItsTimeout ( ) {
		EventStorage storage = eventStorage();
		long started = System.nanoTime();
		for ( int i = 0; i < 20; i++ ) {
			assertEquals(HealthStatus.UP, storage.health(Duration.ofSeconds(1)).status(), "asked again and again, as a probe asks it");
		}
		assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(20)) < 0,
			"twenty health checks of a working storage stay well within twenty timeouts");
	}

	@ForEachBackend
	void aTimeoutThatIsNotPositiveIsRefused ( ) {
		EventStorage storage = eventStorage();
		assertThrows(IllegalArgumentException.class, () -> storage.health(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> storage.health(Duration.ofSeconds(-1)));
		assertThrows(NullPointerException.class, () -> storage.health(null));
	}

}
