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
package org.sliceworkz.eventstore.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.Bookmark;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The value rules of {@link StorageHealth}, {@link HealthCheck} and {@link HealthStatus}, and what a
 * storage reports when it does not implement {@link EventStorage#health(Duration)}.
 */
class StorageHealthValuesTest {

	@Test
	void theStatusOfAStorageIsTheWorstOfItsChecks ( ) {
		HealthCheck up = HealthCheck.up("a", Map.of());
		HealthCheck degraded = HealthCheck.of("b", HealthStatus.DEGRADED, "slow", Map.of());
		HealthCheck down = HealthCheck.of("c", HealthStatus.DOWN, "gone", Map.of());
		HealthCheck unknown = HealthCheck.of("d", HealthStatus.UNKNOWN, "", Map.of());

		assertEquals(HealthStatus.UP, new StorageHealth("s", List.of(up)).status());
		assertEquals(HealthStatus.UNKNOWN, new StorageHealth("s", List.of(up, unknown)).status());
		assertEquals(HealthStatus.DEGRADED, new StorageHealth("s", List.of(unknown, degraded, up)).status());
		assertEquals(HealthStatus.DOWN, new StorageHealth("s", List.of(down, degraded, unknown, up)).status());
		assertEquals(HealthStatus.UNKNOWN, new StorageHealth("s", List.of()).status(), "a storage that checked nothing says nothing");
	}

	@Test
	void statusesAreOrderedFromBestToWorst ( ) {
		assertTrue(HealthStatus.DOWN.isWorseThan(HealthStatus.DEGRADED));
		assertTrue(HealthStatus.DEGRADED.isWorseThan(HealthStatus.UNKNOWN));
		assertTrue(HealthStatus.UNKNOWN.isWorseThan(HealthStatus.UP));
		assertTrue(!HealthStatus.UP.isWorseThan(HealthStatus.UP));
	}

	@Test
	void aCheckIsFoundByName ( ) {
		StorageHealth health = new StorageHealth("s", List.of(HealthCheck.open(true), HealthCheck.up("database", Map.of())));
		assertEquals(Optional.of(HealthCheck.open(true)), health.check(HealthCheck.OPEN));
		assertEquals(Optional.empty(), health.check("missing"));
	}

	@Test
	void twoChecksWithOneNameAreRefused ( ) {
		assertThrows(IllegalArgumentException.class,
			() -> new StorageHealth("s", List.of(HealthCheck.up("a", Map.of()), HealthCheck.up("a", Map.of()))));
	}

	@Test
	void detailsKeepTheirOrderAndCannotBeChanged ( ) {
		Map<String, Object> given = new LinkedHashMap<>();
		given.put("z", 1);
		given.put("a", "two");
		given.put("m", true);
		HealthCheck check = HealthCheck.up("pool", given);
		given.put("later", 4L);

		assertEquals(List.of("z", "a", "m"), List.copyOf(check.details().keySet()), "in the order given, and a copy");
		assertThrows(UnsupportedOperationException.class, () -> check.details().put("x", 1));
	}

	@Test
	void detailsAreJsonScalars ( ) {
		assertThrows(IllegalArgumentException.class, () -> HealthCheck.up("a", Map.of("list", List.of(1))));
		Map<String, Object> withNull = new LinkedHashMap<>();
		withNull.put("missing", null);
		assertThrows(IllegalArgumentException.class, () -> HealthCheck.up("a", withNull));
	}

	@Test
	void aCheckHasANameAndAStatus ( ) {
		assertThrows(IllegalArgumentException.class, () -> HealthCheck.up(" ", Map.of()));
		assertThrows(NullPointerException.class, () -> HealthCheck.of("a", null, "", Map.of()));
		assertEquals("", HealthCheck.of("a", HealthStatus.UP, null, Map.of()).message(), "no message is an empty one");
	}

	@Test
	void theOpenCheckIsUpWhileOpenAndDownOnceClosed ( ) {
		assertEquals(HealthStatus.UP, HealthCheck.open(true).status());
		HealthCheck closed = HealthCheck.open(false);
		assertEquals(HealthStatus.DOWN, closed.status());
		assertEquals(HealthCheck.OPEN, closed.name());
		assertTrue(!closed.message().isBlank());
	}

	@Test
	void aStorageThatReportsNoHealthSaysSoRatherThanClaimingToBeUp ( ) {
		EventStorage storage = new SilentStorage();

		StorageHealth health = storage.health();

		assertEquals("silent", health.storage());
		assertEquals(HealthStatus.UNKNOWN, health.status());
		assertThrows(IllegalArgumentException.class, () -> storage.health(Duration.ZERO));
	}

	/** a storage written before {@link EventStorage#health(Duration)} existed: it implements only what was abstract */
	private static final class SilentStorage implements EventStorage {
		@Override public String name ( ) { return "silent"; }
		@Override public List<StoredEvent> query ( EventFilter filter, EventStreamId stream, EventReference after, Limit limit, Direction direction ) { return List.of(); }
		@Override public List<StoredEvent> append ( AppendCriteria appendCriteria, EventStreamId stream, List<EventToStore> events ) { return List.of(); }
		@Override public Optional<StoredEvent> getEventById ( EventId eventId ) { return Optional.empty(); }
		@Override public void subscribe ( EventStoreListener listener ) { }
		@Override public Optional<EventReference> getBookmark ( String reader ) { return Optional.empty(); }
		@Override public void bookmark ( String reader, EventReference eventReference, Tags tags ) { }
		@Override public void removeBookmark ( String reader ) { }
		@Override public List<Bookmark> getBookmarks ( ) { return List.of(); }
	}

}
