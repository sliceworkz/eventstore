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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Collections;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;

public class EventStoreBasicTest extends AbstractEventStoreTest {

	private void storeEvent ( EventStreamId eventStreamId, MockDomainEvent event, Tags tags ) {
		EventStream<MockDomainEvent> eventStream = eventStore().getEventStream(eventStreamId, MockDomainEvent.class);
		EphemeralEvent<MockDomainEvent> e = Event.of(event, tags);
		eventStream.append(AppendCriteria.none(), Collections.singletonList((e)));
	}

	private void storeTestEvent ( EventStreamId eventStreamId ) {
		storeEvent(eventStreamId, new FirstDomainEvent("test"), Tags.parse("a:1", "b:2", "c:3"));
	}

	@ForEachBackend
	void testAppendToStorageWithNonSpecifiedPurpose ( ) {
		try {
			storeTestEvent(EventStreamId.forContext("a").anyPurpose());
			fail("exception expected");
		} catch (RuntimeException e) {
			// OK
		}
	}

	@ForEachBackend
	void testAppendToStorageWithNonSpecifiedContext ( ) {
		try {
			storeTestEvent(EventStreamId.anyContext().withPurpose("p"));
			fail("exception expected");
		} catch (RuntimeException e) {
			// OK
		}
	}

	@ForEachBackend
	void testQueryEmptyStorageAll ( ) {
		assertEquals(0, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQueryEmptyStorageDomain ( ) {
		assertEquals(0, eventStore().getEventStream(EventStreamId.forContext("a").withPurpose("domain")).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQueryOneEvent ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.forContext("a").withPurpose("domain")).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQueryAnyStreamAnyPurpose ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQueryEmptyStream ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(0, eventStore().getEventStream(EventStreamId.forContext("b").anyPurpose()).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQuerySpecificStreamSpecificPurposeNoMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(0, eventStore().getEventStream(EventStreamId.forContext("a").withPurpose("p")).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQuerySpecificStreamSpecificPurposeMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("p"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.forContext("a").withPurpose("p")).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQuerySpecificStreamAnyPurpose ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("p"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.forContext("a").anyPurpose()).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQuerySpecificStreamAnyPurposeOnApplication ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.forContext("a").anyPurpose()).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQueryAnyStreamSpecificPurpose ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("p"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.anyContext().withPurpose("p")).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQuerySpecificStreamApplicationPurpose ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.forContext("a").withPurpose("domain")).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQueryAnyStreamApplicationPurpose ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.anyContext().withPurpose("domain")).query(EventQuery.matchAll()).count());
	}

	@ForEachBackend
	void testQueryByEventTypeMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(
				EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())
		).count());
	}

	@ForEachBackend
	void testQueryByEventTypeNoMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(0, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(
				EventQuery.forEvents(EventTypesFilter.of(SecondDomainEvent.class), Tags.none())
		).count());
	}

	@ForEachBackend
	void testQueryBySingleTagNoMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(0, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(
				EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("a:2"))
		).count());
	}

	@ForEachBackend
	void testQueryBySingleTagMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(
				EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("b:2"))
		).count());
	}

	@ForEachBackend
	void testQueryByMultipleTagsNoMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(0, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(
				EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("a:1", "b:2", "c:4"))
		).count());
	}

	@ForEachBackend
	void testQueryByMultipleTagsMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(
				EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("a:1", "c:3"))
		).count());
	}

	@ForEachBackend
	void testQueryByAllTagsMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(
				EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("a:1", "b:2", "c:3"))
		).count());
	}

	@ForEachBackend
	void testQueryByEventTypeAndAllTagsMatch ( ) {
		storeTestEvent(EventStreamId.forContext("a").withPurpose("domain"));
		assertEquals(1, eventStore().getEventStream(EventStreamId.anyContext().anyPurpose()).query(
				EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.parse("a:1", "b:2", "c:3"))
		).count());
	}

}
