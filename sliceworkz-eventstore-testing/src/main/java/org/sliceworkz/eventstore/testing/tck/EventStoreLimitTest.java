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
package org.sliceworkz.eventstore.testing.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mockdomain.MockDomainDuplicatedEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.StorageOptions;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;

public class EventStoreLimitTest extends AbstractEventStoreTest {

	private EventStreamId stream = EventStreamId.forContext("app").withPurpose("stream1");

	@Override
	protected StorageOptions storageOptions ( ) {
		return StorageOptions.defaults().withResultLimit(2);
	}

	private void storeEvent ( EventStreamId eventStreamId, MockDomainEvent event, Tags tags ) {
		EventStream<MockDomainEvent> eventStream = eventStore().getEventStream(eventStreamId, MockDomainEvent.class);
		EphemeralEvent<MockDomainEvent> e = Event.of(event, tags);
		eventStream.append(AppendCriteria.none(), Collections.singletonList((e)));
	}

	@ForEachBackend(requires = Capability.RESULT_LIMIT)
	void testHardLimit ( ) {
		storeEvent(stream, new MockDomainEvent.FirstDomainEvent("one"), Tags.of(Tag.of("mod2", "0"), Tag.of("mod3", "0")));
		storeEvent(stream, new MockDomainEvent.FirstDomainEvent("one"), Tags.of(Tag.of("mod2", "1"), Tag.of("mod3", "1")));
		storeEvent(stream, new MockDomainEvent.FirstDomainEvent("one"), Tags.of(Tag.of("mod2", "0"), Tag.of("mod3", "2")));
		storeEvent(stream, new MockDomainEvent.SecondDomainEvent("one"), Tags.of(Tag.of("mod2", "1"), Tag.of("mod3", "0")));
		storeEvent(stream, new MockDomainEvent.SecondDomainEvent("one"), Tags.of(Tag.of("mod2", "0"), Tag.of("mod3", "1")));
		storeEvent(stream, new MockDomainEvent.SecondDomainEvent("one"), Tags.of(Tag.of("mod2", "1"), Tag.of("mod3", "2")));
		storeEvent(stream, new MockDomainEvent.ThirdDomainEvent("one"), Tags.of(Tag.of("mod2", "0"), Tag.of("mod3", "0")));
		storeEvent(stream, new MockDomainEvent.ThirdDomainEvent("one"), Tags.of(Tag.of("mod2", "1"), Tag.of("mod3", "1")));

		EventStorageException e = assertThrows(EventStorageException.class, ()->eventStore().getEventStream(stream).query(EventQuery.matchAll()));
		assertEquals("query returned more results than the configured absolute limit of 2", e.getMessage());

		e = assertThrows(EventStorageException.class, ()->eventStore().getEventStream(stream).query(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())));
		assertEquals("query returned more results than the configured absolute limit of 2", e.getMessage());

		e = assertThrows(EventStorageException.class, ()->eventStore().getEventStream(stream).query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("mod2", "0"))));
		assertEquals("query returned more results than the configured absolute limit of 2", e.getMessage());

		// these should all be ok
		eventStore().getEventStream(stream).query(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.of("mod2", "1")));
		eventStore().getEventStream(stream).query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("mod2", "2")));
	}

	/**
	 * A limit the caller asked for has to reach the storage query, not be applied to its result.
	 * <p>
	 * The configured hard limit is what makes this observable without reaching past the SPI. The store
	 * below holds eight events and refuses to return more than two, so a query carrying
	 * {@code limit(2)} can only succeed if those two are the only two ever fetched. A backend
	 * satisfying the limit by reading everything and trimming afterwards trips its own guard, and one
	 * that quietly drops the limit somewhere between the caller and the storage does too — which is
	 * the failure this pins, since it is otherwise invisible: the caller still receives the two events
	 * it asked for, having paid to fetch and materialise every event in the store.
	 * <p>
	 * Every overload is exercised, because they do not all carry the limit the same way: one takes it
	 * from the query, one takes it as an argument, and the cursor overload used to substitute
	 * {@link Limit#none()} for it — turning the natural way to page through a stream into a full read
	 * of everything past the cursor.
	 */
	@ForEachBackend(requires = Capability.RESULT_LIMIT)
	void testAnAskedForLimitReachesTheStorageQueryOnEveryOverload ( ) {
		for ( int i = 0 ; i < 8 ; i++ ) {
			storeEvent(stream, new MockDomainEvent.FirstDomainEvent("event-" + i), Tags.none());
		}

		EventStream<MockDomainEvent> eventStream = eventStore().getEventStream(stream, MockDomainEvent.class);

		EventReference cursor = eventStream.query(EventQuery.matchAll().limit(1))
				.findFirst().orElseThrow().reference();

		assertEquals(2, eventStream.query(EventQuery.matchAll().limit(2)).count(),
				"limit carried by the query itself");

		assertEquals(2, eventStream.query(EventQuery.matchAll().limit(2), cursor).count(),
				"limit carried by the query, with a cursor -- the paging idiom");

		assertEquals(2, eventStream.query(EventQuery.matchAll(), cursor, Limit.to(2)).count(),
				"limit passed explicitly, overriding the query's own");

		assertEquals(2, eventStream.query(EventQuery.matchAll().limit(5), cursor, Limit.to(2)).count(),
				"explicit limit wins over the query's own");
	}

}
