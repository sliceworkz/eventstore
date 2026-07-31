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
package org.sliceworkz.eventstore.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.migration.EventStoreImporter;
import org.sliceworkz.eventstore.migration.ImportReport;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.backend.PostgresContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the actual migration path: an in-memory store moved into PostgreSQL and back out again,
 * with every event arriving as the same event.
 * <p>
 * Also pins down the one lossy edge. PostgreSQL stores {@code timestamptz} at microsecond resolution
 * and rounds anything finer, so a nanosecond-precision timestamp comes back up to half a microsecond
 * away from where it started. Everything else round-trips exactly.
 */
class EventImportRoundTripTest {

	private static final JsonMapper JSONMAPPER = JsonMapper.builder().build();

	private static final Duration ONE_MICROSECOND = Duration.ofNanos(1000);

	private final EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");

	@Test
	void testInMemoryToPostgresAndBack ( ) {
		EventStorage origin = InMemoryEventStorage.newBuilder().name("origin").build();
		EventStorage postgres = PostgresEventStorage.newBuilder()
				.name("roundtrip")
				.prefix("roundtrip_")
				.dataSource(PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18))
				.initializeDatabase()
				.build();
		EventStorage destination = InMemoryEventStorage.newBuilder().name("destination").build();

		try {
			origin.append(AppendCriteria.none(), Optional.of(stream), List.of(
					new EventToStore(stream, EventType.ofType("Plain"), "{\"a\":1}", null, Tags.of("kind", "plain"), null),
					new EventToStore(stream, EventType.ofType("Keyed"), "{\"b\":2}", null, Tags.none(), "the-key"),
					new EventToStore(stream, EventType.ofType("Erasable"), "{\"keep\":true}", "{\"secret\":\"pii\"}", Tags.of("kind", "erasable"), null)));

			List<StoredEvent> originals = allEventsIn(origin);
			assertEquals(3, originals.size());

			ImportReport out = EventStoreImporter.from(origin).to(postgres).run();
			assertEquals(3, out.imported());

			ImportReport back = EventStoreImporter.from(postgres).to(destination).run();
			assertEquals(3, back.imported());

			List<StoredEvent> returned = allEventsIn(destination);
			assertEquals(3, returned.size());

			for ( int i = 0; i < originals.size(); i++ ) {
				StoredEvent original = originals.get(i);
				StoredEvent copy = returned.get(i);

				assertEquals(original.reference().id(), copy.reference().id(), "identity survives a full round trip");
				assertEquals(original.stream(), copy.stream());
				assertEquals(original.type(), copy.type());
				assertEquals(original.tags(), copy.tags());
				assertEquals(original.idempotencyKey(), copy.idempotencyKey());

				// PostgreSQL normalises JSONB (key order, whitespace), so compare semantically
				assertEquals(JSONMAPPER.readTree(original.immutableData()), JSONMAPPER.readTree(copy.immutableData()));
				if ( original.erasableData() == null ) {
					assertEquals(null, copy.erasableData());
				} else {
					assertEquals(JSONMAPPER.readTree(original.erasableData()), JSONMAPPER.readTree(copy.erasableData()));
				}

				// timestamptz keeps microseconds and rounds anything finer, so a nanosecond-precision
				// source timestamp can come back up to half a microsecond away from where it started
				assertTrue(Duration.between(original.timestamp(), copy.timestamp()).abs().compareTo(ONE_MICROSECOND) < 0,
						"timestamp must survive to microsecond resolution, was %s and came back %s".formatted(original.timestamp(), copy.timestamp()));
			}

			// the erasable payload really made the trip rather than being merged away
			assertNotNull(returned.get(2).erasableData());
		} finally {
			postgres.close();
			PostgresContainer.close(PostgresContainer.IMAGE_PG18);
		}
	}

	private List<StoredEvent> allEventsIn ( EventStorage storage ) {
		return storage.query(EventQuery.matchAll(), Optional.empty(), null, Limit.none(), QueryDirection.FORWARD).toList();
	}

}
