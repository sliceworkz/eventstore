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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.StorageOptions;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.FirstDomainEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * What an idempotency key does, and — just as important — what it must not do.
 * <p>
 * A duplicate key on the same stream is swallowed: the append writes nothing and reports nothing,
 * which is the whole point of the mechanism. That makes the branch that recognises the duplicate the
 * one place in the write path where a wrong answer is invisible. Recognise too little and a caller
 * that expected de-duplication gets an exception; recognise too much and a write that genuinely
 * failed is reported as a successful de-duplication, with the events silently absent.
 * <p>
 * <b>Why this store is built with a table prefix containing the word "idempotency".</b> That is not
 * decoration. A backend deriving object names from the prefix ends up with names like
 * {@code idempotency_events_event_id_key} for the <em>other</em> unique keys on its table, so a
 * backend recognising the duplicate by looking for that word somewhere in the driver's error message
 * — rather than by identifying the constraint that actually rejected the row — silently swallows
 * every unique violation the table can raise. The prefix is caller-supplied and validated only as
 * {@code [a-zA-Z0-9_]+_}, so nothing stops one being chosen. Backends that do not support prefixes
 * ignore it and the scenarios still hold.
 */
public class EventStreamIdempotencyTest extends AbstractEventStoreTest {

	/** Also the table prefix on SQL backends — see the class javadoc for why this word. */
	private static final String PREFIX = "idempotency_";

	private EventStream<MockDomainEvent> stream;

	@Override
	protected StorageOptions storageOptions ( ) {
		return StorageOptions.defaults().withPrefix(PREFIX);
	}

	@BeforeEach
	void openStream ( ) {
		stream = eventStore().getEventStream(EventStreamId.forContext("app").withPurpose("default"), MockDomainEvent.class);
	}

	@ForEachBackend
	void duplicateIdempotencyKeyOnTheSameStreamIsDeduplicated ( ) {

		List<Event<MockDomainEvent>> first = stream.append(AppendCriteria.none(),
				Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("order-4711"));
		assertEquals(1, first.size());

		// same key, same stream: silently ignored, reported as an empty result
		List<Event<MockDomainEvent>> repeat = stream.append(AppendCriteria.none(),
				Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("order-4711"));
		assertEquals(0, repeat.size());

		// and the second event really is absent, rather than written and merely not returned
		List<Event<MockDomainEvent>> stored = stream.query(EventQuery.matchAll()).toList();
		assertEquals(1, stored.size());
		assertEquals(new FirstDomainEvent("1"), stored.getFirst().data());
	}

	@ForEachBackend
	void theSameIdempotencyKeyOnAnotherStreamIsAppended ( ) {

		EventStream<MockDomainEvent> otherStream = eventStore()
				.getEventStream(EventStreamId.forContext("app-other").withPurpose("default"), MockDomainEvent.class);

		List<Event<MockDomainEvent>> first = stream.append(AppendCriteria.none(),
				Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("order-4711"));
		assertEquals(1, first.size());

		// dedup is scoped to the logical stream, so the same key on another one is a different event
		List<Event<MockDomainEvent>> onOtherStream = otherStream.append(AppendCriteria.none(),
				Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("order-4711"));
		assertEquals(1, onOtherStream.size());
		assertNotEquals(first.getFirst().reference(), onOtherStream.getFirst().reference());

		assertEquals(1, stream.query(EventQuery.matchAll()).count());
		assertEquals(1, otherStream.query(EventQuery.matchAll()).count());
	}

	/**
	 * A swallowed duplicate is visible on {@code sliceworkz.eventstore.append.deduplicated}, and
	 * nowhere else.
	 * <p>
	 * The de-duplication is otherwise silent by design: the call succeeds and returns an empty list.
	 * The surrounding meters cannot recover it — {@code sliceworkz.eventstore.append} counts calls and
	 * {@code append.event} counts submitted events, and one call can carry several events, so their
	 * difference means nothing in general. A caller wanting to tell "n events ingested" from "n calls,
	 * some de-duplicated" reads this counter; a clean run reads 0.
	 */
	@ForEachBackend
	void aSwallowedDuplicateIsCountedOnTheDeduplicatedMeter ( ) {

		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStore meteredStore = EventStoreFactory.get().eventStore(eventStorage(), registry) ) {
			EventStream<MockDomainEvent> meteredStream = meteredStore
					.getEventStream(EventStreamId.forContext("app").withPurpose("default"), MockDomainEvent.class);

			// the counter exists from the moment the stream does, reading 0 -- a series that only
			// appears once something is de-duplicated cannot be told apart from a broken scrape
			Counter deduplicated = registry.find("sliceworkz.eventstore.append.deduplicated").counter();
			assertNotNull(deduplicated, "no sliceworkz.eventstore.append.deduplicated counter was registered");
			assertEquals(0.0, deduplicated.count());

			meteredStream.append(AppendCriteria.none(),
					Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("order-4711"));
			assertEquals(0.0, deduplicated.count(), "a clean append must not count as de-duplicated");

			meteredStream.append(AppendCriteria.none(),
					Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("order-4711"));
			assertEquals(1.0, deduplicated.count(), "a swallowed duplicate did not reach the deduplicated meter");

			// an ordinary un-keyed append leaves the counter where it was
			meteredStream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("3"), Tags.none()));
			assertEquals(1.0, deduplicated.count());

			// and the meter only reports, it does not change what the caller sees: one event was
			// de-duplicated, two were stored
			assertEquals(2, meteredStream.query(EventQuery.matchAll()).count());
		}
	}

	/**
	 * An event id colliding with one already stored is a different failure, and must surface as one.
	 * <p>
	 * Reaching it needs the id an append generates to be forced to a known value, which no API
	 * offers — an event id is the store's to mint — so this goes in behind the store's back and is
	 * skipped on backends that cannot offer that. A store that mistakes this for a duplicate
	 * idempotency key returns an empty list, telling the caller its event was de-duplicated when in
	 * fact nothing was written and the reason was unrelated.
	 */
	@ForEachBackend(requires = Capability.RAW_STORAGE_ACCESS)
	void anEventIdCollisionIsNotMistakenForADuplicateIdempotencyKey ( ) throws Exception {

		pinGeneratedEventIdTo("00000000-0000-7000-8000-000000000001");

		// no idempotency key anywhere in this scenario: the only uniqueness in play is the event id
		List<Event<MockDomainEvent>> first = stream.append(AppendCriteria.none(),
				Event.of(new FirstDomainEvent("1"), Tags.none()));
		assertEquals(1, first.size());

		// the point of the assertion is that it throws at all: a store misreading this as a duplicate
		// idempotency key returns an empty list instead, which is indistinguishable from a successful
		// de-duplication
		assertThrows(EventStorageException.class, () ->
				stream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("2"), Tags.none())));

		assertEquals(1, stream.query(EventQuery.matchAll()).count());
	}

	/**
	 * Overrides the id the store generates, so that the next append collides with the previous one.
	 * <p>
	 * A {@code BEFORE INSERT} trigger rather than a prepared row, because the store mints the id
	 * inside the INSERT — server-side on PostgreSQL 18 and up — so there is nothing to predict and
	 * nothing to bind.
	 *
	 * @param eventId the id every appended row will be given
	 */
	private void pinGeneratedEventIdTo ( String eventId ) throws Exception {
		DataSource dataSource = dataSource().orElseThrow();
		try ( Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement() ) {
			// CREATE OR REPLACE, because the store's per-test reset drops and recreates its tables --
			// taking the trigger with them -- but leaves a function of its own naming behind
			statement.execute("""
				CREATE OR REPLACE FUNCTION %spin_event_id ( ) RETURNS TRIGGER AS $$
				BEGIN
					NEW.event_id := '%s'::uuid;
					RETURN NEW;
				END;
				$$ LANGUAGE plpgsql
				""".formatted(PREFIX, eventId));
			statement.execute("""
				CREATE TRIGGER %spin_event_id_trigger
				BEFORE INSERT ON %sevents
				FOR EACH ROW EXECUTE FUNCTION %spin_event_id()
				""".formatted(PREFIX, PREFIX, PREFIX));
		}
	}

}
