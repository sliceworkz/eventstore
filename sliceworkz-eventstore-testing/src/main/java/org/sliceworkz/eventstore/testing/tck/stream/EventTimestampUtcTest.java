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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class EventTimestampUtcTest extends AbstractEventStoreTest {

	@ForEachBackend
	void timestampShouldBeInUtc() {
		EventStreamId streamId = EventStreamId.forContext("test").withPurpose("utc");
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		LocalDateTime beforeAppend = LocalDateTime.now(ZoneOffset.UTC);
		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("utc-test"), Tags.none()));
		LocalDateTime afterAppend = LocalDateTime.now(ZoneOffset.UTC);

		Event<MockDomainEvent> event = stream.query(EventQuery.matchAll()).findFirst().orElseThrow();

		assertNotNull(event.timestamp(), "Event timestamp should not be null");

		// The event timestamp (UTC) should be between our UTC before/after markers
		// Allow 1 second tolerance for clock differences (especially relevant for Postgres)
		LocalDateTime lowerBound = beforeAppend.minus(1, ChronoUnit.SECONDS);
		LocalDateTime upperBound = afterAppend.plus(1, ChronoUnit.SECONDS);
		assertTrue(
			!event.timestamp().isBefore(lowerBound) && !event.timestamp().isAfter(upperBound),
			"Event timestamp " + event.timestamp() + " should be between " + lowerBound + " and " + upperBound + " (UTC)"
		);
	}

	@ForEachBackend
	void timestampShouldNotMatchNonUtcTimezone() {
		// Only meaningful when the JVM is not running in UTC
		ZoneId localZone = ZoneId.systemDefault();
		if (localZone.equals(ZoneOffset.UTC) || localZone.equals(ZoneId.of("UTC"))) {
			// Can't distinguish UTC from local if JVM is already in UTC; skip gracefully
			return;
		}

		EventStreamId streamId = EventStreamId.forContext("test").withPurpose("nonlocal");
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("tz-test"), Tags.none()));

		Event<MockDomainEvent> event = stream.query(EventQuery.matchAll()).findFirst().orElseThrow();

		// The stored UTC timestamp should be close to now in UTC, not to now in local time
		LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
		long diffSeconds = Math.abs(ChronoUnit.SECONDS.between(event.timestamp(), nowUtc));
		assertTrue(diffSeconds < 5, "Event timestamp should be within 5 seconds of UTC now, but diff was " + diffSeconds + "s");
	}

	@ForEachBackend
	void timestampCanBeConvertedToOtherTimezones() {
		EventStreamId streamId = EventStreamId.forContext("test").withPurpose("convert");
		EventStream<MockDomainEvent> stream = eventStore().getEventStream(streamId, MockDomainEvent.class);

		stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("convert-test"), Tags.none()));

		Event<MockDomainEvent> event = stream.query(EventQuery.matchAll()).findFirst().orElseThrow();
		LocalDateTime utcTimestamp = event.timestamp();

		// Convert from UTC to specific timezones
		ZonedDateTime utcZoned = utcTimestamp.atZone(ZoneOffset.UTC);

		ZonedDateTime newYork = utcZoned.withZoneSameInstant(ZoneId.of("America/New_York"));
		ZonedDateTime tokyo = utcZoned.withZoneSameInstant(ZoneId.of("Asia/Tokyo"));
		ZonedDateTime brussels = utcZoned.withZoneSameInstant(ZoneId.of("Europe/Brussels"));

		// All conversions should represent the same instant
		assertTrue(utcZoned.toInstant().equals(newYork.toInstant()),
			"New York conversion should represent the same instant");
		assertTrue(utcZoned.toInstant().equals(tokyo.toInstant()),
			"Tokyo conversion should represent the same instant");
		assertTrue(utcZoned.toInstant().equals(brussels.toInstant()),
			"Brussels conversion should represent the same instant");

		// Tokyo is always UTC+9, so hour should differ by 9
		long hourDiff = utcZoned.getHour() - tokyo.getHour();
		// Normalize for day boundary crossings
		if (hourDiff > 12) hourDiff -= 24;
		if (hourDiff < -12) hourDiff += 24;
		assertTrue(Math.abs(hourDiff + 9) <= 0,
			"Tokyo should be 9 hours ahead of UTC, but hour diff was " + hourDiff);

		// Extract local time in a target timezone for display/business logic
		LocalDateTime brusselsLocal = brussels.toLocalDateTime();
		assertNotNull(brusselsLocal, "Should be able to extract LocalDateTime in Brussels timezone");
	}

}
