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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.IdempotencyKeyConflictException;
import org.sliceworkz.eventstore.observability.Observation;
import org.sliceworkz.eventstore.observability.Outcome;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.RecordingObserver;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.StorageOptions;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainEvent.FirstDomainEvent;


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
		List<Event<MockDomainEvent>> stored = stream.query(EventQuery.matchAll());
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

		assertEquals(1, stream.query(EventQuery.matchAll()).size());
		assertEquals(1, otherStream.query(EventQuery.matchAll()).size());
	}

	/**
	 * A swallowed duplicate is reported as what it is: an append that completed as
	 * {@link Outcome.Duplicated}, not as one that stored nothing for no reason, and not as a failure.
	 * <p>
	 * The de-duplication is otherwise silent by design: the call succeeds and returns an empty list. The
	 * observation is where a caller wanting to tell "n events ingested" from "n calls, some retried" finds
	 * the difference.
	 */
	@ForEachBackend
	void aSwallowedDuplicateIsReportedAsDuplicated ( ) {

		RecordingObserver observer = new RecordingObserver();
		try ( EventStore observedStore = EventStore.on(eventStorage()).observer(observer).build() ) {
			EventStream<MockDomainEvent> observedStream = observedStore
					.getEventStream(EventStreamId.forContext("app").withPurpose("default"), MockDomainEvent.class);

			observedStream.append(AppendCriteria.none(),
					Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("order-4711"));
			Outcome.Appended appended = observer.last(Observation.Append.class).outcome(Outcome.Appended.class);
			assertEquals(1, appended.stored(), "a clean append is reported as stored");
			assertEquals(1, observer.last(Observation.Append.class).observation(Observation.Append.class).idempotencyKeys());

			observedStream.append(AppendCriteria.none(),
					Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("order-4711"));
			Outcome.Duplicated duplicated = observer.last(Observation.Append.class).outcome(Outcome.Duplicated.class);
			assertEquals(1, duplicated.events(), "the swallowed event is reported");
			assertTrue(observer.last(Observation.Append.class).failure().isEmpty(), "a retry is an answer, not a failure");

			// an ordinary un-keyed append is an ordinary append
			observedStream.append(AppendCriteria.none(), Event.of(new FirstDomainEvent("3"), Tags.none()));
			observer.last(Observation.Append.class).outcome(Outcome.Appended.class);

			// and the observation only reports, it does not change what the caller sees
			assertEquals(2, observedStream.query(EventQuery.matchAll()).size());
			assertEquals(List.of(), observer.violations());
		}
	}

	/**
	 * A command producing several events is made idempotent by a key per event, and its retry is
	 * swallowed whole.
	 * <p>
	 * The batch is stored atomically, so a retry finds either every key or none; nothing in between
	 * can exist for the second append to store a fragment of.
	 */
	@ForEachBackend
	void aBatchWithDistinctKeysIsAppendedAndItsRetryIsSwallowedWhole ( ) {

		List<Event<MockDomainEvent>> first = stream.append(AppendCriteria.none(), List.of(
				Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("cmd-4711/1"),
				Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("cmd-4711/2")));
		assertEquals(2, first.size());

		List<Event<MockDomainEvent>> retry = stream.append(AppendCriteria.none(), List.of(
				Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("cmd-4711/1"),
				Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("cmd-4711/2")));
		assertEquals(0, retry.size());

		List<Event<MockDomainEvent>> stored = stream.query(EventQuery.matchAll());
		assertEquals(2, stored.size());
		assertEquals(new FirstDomainEvent("1"), stored.get(0).data());
		assertEquals(new FirstDomainEvent("2"), stored.get(1).data());
	}

	/**
	 * A batch mixing keys already stored with keys not stored is refused, and nothing of it is stored.
	 * <p>
	 * Such a batch cannot be a retry: a batch is stored atomically, so a retry finds every key or
	 * none. One of its events collides with a different event holding its key, and the rest are
	 * unknown to the store. The two silent answers both lie — storing the unknown events leaves the
	 * caller believing the colliding fact landed too, and swallowing the batch loses the unknown
	 * events with nothing to say so — and the second is the one an all-or-nothing rule reaches for by
	 * default, which is why this scenario exists. Partial storage is also not an answer every backend
	 * can give: Postgres writes the batch as a single multi-row insert paired with the input by
	 * position, so it inserts all of them or none.
	 */
	@ForEachBackend
	void aBatchMixingStoredAndNewKeysIsRefusedAndStoresNothing ( ) {

		stream.append(AppendCriteria.none(),
				Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("order-4711"));

		IdempotencyKeyConflictException conflict = assertThrows(IdempotencyKeyConflictException.class, () ->
				stream.append(AppendCriteria.none(), List.of(
						Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("order-4712"),
						Event.of(new FirstDomainEvent("3"), Tags.none()).withIdempotencyKey("order-4711"),
						Event.of(new FirstDomainEvent("4"), Tags.none()).withIdempotencyKey("order-4713"))));
		assertEquals(Set.of("order-4711"), conflict.storedKeys());
		assertEquals(Set.of("order-4712", "order-4713"), conflict.newKeys());

		List<Event<MockDomainEvent>> stored = stream.query(EventQuery.matchAll());
		assertEquals(1, stored.size(), "a refused batch must store none of its events");
		assertEquals(new FirstDomainEvent("1"), stored.getFirst().data());

		// the keys that were new in the refused batch were not consumed by it: they are still free to use
		List<Event<MockDomainEvent>> later = stream.append(AppendCriteria.none(), List.of(
				Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("order-4712"),
				Event.of(new FirstDomainEvent("4"), Tags.none()).withIdempotencyKey("order-4713")));
		assertEquals(2, later.size());
	}

	/**
	 * An event carrying no key rides along with the keyed events of its batch: on a retry it is
	 * swallowed with them, and nothing can tell that retry from a batch reusing the key with
	 * different unkeyed events. That is the one blind spot the mixed-batch refusal leaves, and the
	 * reason a command should key every event it emits.
	 */
	@ForEachBackend
	void anUnkeyedEventIsSwallowedWithTheKeyedEventsOfItsBatchOnARetry ( ) {

		List<Event<MockDomainEvent>> first = stream.append(AppendCriteria.none(), List.of(
				Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("cmd-4711"),
				Event.of(new FirstDomainEvent("2"), Tags.none())));
		assertEquals(2, first.size());

		List<Event<MockDomainEvent>> retry = stream.append(AppendCriteria.none(), List.of(
				Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("cmd-4711"),
				Event.of(new FirstDomainEvent("2"), Tags.none())));
		assertEquals(0, retry.size());
		assertEquals(2, stream.query(EventQuery.matchAll()).size());
	}

	/**
	 * A batch repeating a key is a caller error, refused before anything is stored.
	 * <p>
	 * Left to storage, the stream-scoped unique index rejects the second row of the batch, and the
	 * append path reads that violation as "this key was appended before": the first ever attempt at
	 * such a batch would store nothing and report a successful de-duplication. So it is refused as
	 * an {@link IllegalArgumentException}, at the store and at the SPI (see the spi scenarios).
	 */
	@ForEachBackend
	void aBatchRepeatingAKeyIsRejectedAndStoresNothing ( ) {

		assertThrows(IllegalArgumentException.class, () -> stream.append(AppendCriteria.none(), List.of(
				Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("cmd-4711"),
				Event.of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("cmd-4711"))));

		assertEquals(0, stream.query(EventQuery.matchAll()).size());

		// and the key is not spent by the rejected batch
		List<Event<MockDomainEvent>> afterwards = stream.append(AppendCriteria.none(),
				Event.of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("cmd-4711"));
		assertEquals(1, afterwards.size());
	}

	/**
	 * A swallowed batch is reported as duplicated whole: every event it carried, the unkeyed one riding
	 * along included, since a batch is stored whole or not at all. A batch mixing stored and new keys is
	 * no retry, and is reported as the failure the caller receives.
	 */
	@ForEachBackend
	void aSwallowedBatchIsReportedAsDuplicatedWhole ( ) {

		RecordingObserver observer = new RecordingObserver();
		try ( EventStore observedStore = EventStore.on(eventStorage()).observer(observer).build() ) {
			EventStream<MockDomainEvent> observedStream = observedStore
					.getEventStream(EventStreamId.forContext("app").withPurpose("default"), MockDomainEvent.class);

			List<EphemeralEvent<? extends MockDomainEvent>> batch = List.of(
					Event.<MockDomainEvent>of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("cmd-4711/1"),
					Event.<MockDomainEvent>of(new FirstDomainEvent("2"), Tags.none()).withIdempotencyKey("cmd-4711/2"),
					Event.<MockDomainEvent>of(new FirstDomainEvent("3"), Tags.none()));

			observedStream.append(AppendCriteria.none(), batch);
			assertEquals(3, observer.last(Observation.Append.class).outcome(Outcome.Appended.class).stored());

			observedStream.append(AppendCriteria.none(), batch);
			assertEquals(3, observer.last(Observation.Append.class).outcome(Outcome.Duplicated.class).events(),
					"every event of a swallowed batch is de-duplicated");
			assertEquals(3, observedStream.query(EventQuery.matchAll()).size());

			List<EphemeralEvent<? extends MockDomainEvent>> mixed = List.of(
					Event.<MockDomainEvent>of(new FirstDomainEvent("1"), Tags.none()).withIdempotencyKey("cmd-4711/1"),
					Event.<MockDomainEvent>of(new FirstDomainEvent("4"), Tags.none()).withIdempotencyKey("cmd-4712/1"));
			IdempotencyKeyConflictException conflict = assertThrows(IdempotencyKeyConflictException.class,
					() -> observedStream.append(AppendCriteria.none(), mixed));
			assertSame(conflict, observer.last(Observation.Append.class).failure().orElseThrow(),
					"a mixed batch is reported as the failure the caller receives");
			assertEquals(List.of(), observer.violations());
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

		assertEquals(1, stream.query(EventQuery.matchAll()).size());
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
