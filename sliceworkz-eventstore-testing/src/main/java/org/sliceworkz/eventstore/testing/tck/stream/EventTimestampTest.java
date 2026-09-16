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
package org.sliceworkz.eventstore.testing.tck.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;

/**
 * An event's timestamp is the {@link Instant} at which the storage persisted it, on the storage's clock.
 * <p>
 * An instant has no zone, so the check is on the moment itself: the stamp must fall between two readings
 * of the JVM clock taken around the append, with a tolerance for a storage keeping its own clock (the
 * Postgres server's). That bound is what catches a backend stamping a wall-clock reading as if it were
 * UTC — on a JVM whose default zone is not UTC, such a stamp is a whole offset away, well outside the
 * tolerance — and there is nothing more a test can ask of a zone-less value.
 */
public class EventTimestampTest extends AbstractEventStoreTest {

	@ForEachBackend
	void timestampIsTheInstantTheEventWasStored ( ) {
		EventStreamId streamId = EventStreamId.forContext("test").withPurpose("instant");
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		Instant beforeAppend = Instant.now();
		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("instant-test"), Tags.none()));
		Instant afterAppend = Instant.now();

		Event<MockDomainEvent> event = stream.query(EventQuery.matchAll()).stream().findFirst().orElseThrow();

		assertNotNull(event.timestamp(), "Event timestamp should not be null");

		// one second of tolerance for a storage that stamps from its own clock rather than the JVM's
		Instant lowerBound = beforeAppend.minusSeconds(1);
		Instant upperBound = afterAppend.plusSeconds(1);
		assertTrue(
			!event.timestamp().isBefore(lowerBound) && !event.timestamp().isAfter(upperBound),
			"Event timestamp " + event.timestamp() + " should be between " + lowerBound + " and " + upperBound
		);
	}

	@ForEachBackend
	void typedReadCarriesTheStoredInstantUnchanged ( ) {
		EventStreamId streamId = EventStreamId.forContext("test").withPurpose("stored");
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		Event<MockDomainEvent> appended = stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("stored-test"), Tags.none())).get(0);
		Event<MockDomainEvent> queried = stream.query(EventQuery.matchAll()).stream().findFirst().orElseThrow();
		StoredEvent stored = eventStorage().getEventById(appended.reference().id()).orElseThrow();

		assertEquals(stored.timestamp(), appended.timestamp(), "the events append returns carry the stored instant");
		assertEquals(stored.timestamp(), queried.timestamp(), "a query carries the stored instant");
	}

	@ForEachBackend
	void timestampRendersInWhateverZoneTheReaderMeans ( ) {
		EventStreamId streamId = EventStreamId.forContext("test").withPurpose("render");
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("render-test"), Tags.none()));

		Event<MockDomainEvent> event = stream.query(EventQuery.matchAll()).stream().findFirst().orElseThrow();
		Instant timestamp = event.timestamp();

		// the instant is the same whichever zone it is rendered in; only the wall-clock reading differs
		assertEquals(timestamp, timestamp.atZone(ZoneOffset.UTC).toInstant());
		assertEquals(timestamp, timestamp.atZone(ZoneId.of("Asia/Tokyo")).toInstant());
		assertEquals(9, timestamp.atZone(ZoneId.of("Asia/Tokyo")).getOffset().getTotalSeconds() / 3600,
			"Tokyo renders the instant nine hours ahead of UTC");
	}

}
