/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.mock.MockDomainEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainDuplicatedEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class EventStoreLimitTest {
	
	private EventStorage eventStorage;
	private EventStore eventStore;
	private EventStreamId stream = EventStreamId.forContext("app").withPurpose("stream1");
	
	@BeforeEach
	public void setUp ( ) {
		this.eventStorage = createEventStorage();
		this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
	}
	
	@AfterEach
	public void tearDown ( ) {
		destroyEventStorage(eventStorage);
	}
	
	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().resultLimit(2).build(); // deliberately limiting strictly to max 2 results
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}
	
	private void storeEvent ( EventStreamId eventStreamId, MockDomainEvent event, Tags tags ) {
		EventStream<MockDomainEvent> eventStream = eventStore.getEventStream(eventStreamId, MockDomainEvent.class);
		EphemeralEvent<MockDomainEvent> e = Event.of(event, tags);
		eventStream.append(AppendCriteria.none(), Collections.singletonList((e)));
	}
	
	@Test
	void testHardLimit ( ) {
		storeEvent(stream, new MockDomainEvent.FirstDomainEvent("one"), Tags.of(Tag.of("mod2", "0"), Tag.of("mod3", "0")));
		storeEvent(stream, new MockDomainEvent.FirstDomainEvent("one"), Tags.of(Tag.of("mod2", "1"), Tag.of("mod3", "1")));
		storeEvent(stream, new MockDomainEvent.FirstDomainEvent("one"), Tags.of(Tag.of("mod2", "0"), Tag.of("mod3", "2")));
		storeEvent(stream, new MockDomainEvent.SecondDomainEvent("one"), Tags.of(Tag.of("mod2", "1"), Tag.of("mod3", "0")));
		storeEvent(stream, new MockDomainEvent.SecondDomainEvent("one"), Tags.of(Tag.of("mod2", "0"), Tag.of("mod3", "1")));
		storeEvent(stream, new MockDomainEvent.SecondDomainEvent("one"), Tags.of(Tag.of("mod2", "1"), Tag.of("mod3", "2")));
		storeEvent(stream, new MockDomainEvent.ThirdDomainEvent("one"), Tags.of(Tag.of("mod2", "0"), Tag.of("mod3", "0")));
		storeEvent(stream, new MockDomainEvent.ThirdDomainEvent("one"), Tags.of(Tag.of("mod2", "1"), Tag.of("mod3", "1")));
		
		EventStorageException e = assertThrows(EventStorageException.class, ()->eventStore.getEventStream(stream).query(EventQuery.matchAll()));
		assertEquals("query returned more results than the configured absolute limit of 2", e.getMessage());

		e = assertThrows(EventStorageException.class, ()->eventStore.getEventStream(stream).query(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())));
		assertEquals("query returned more results than the configured absolute limit of 2", e.getMessage());

		e = assertThrows(EventStorageException.class, ()->eventStore.getEventStream(stream).query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("mod2", "0"))));
		assertEquals("query returned more results than the configured absolute limit of 2", e.getMessage());

		// these should all be ok
		eventStore.getEventStream(stream).query(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.of("mod2", "1")));
		eventStore.getEventStream(stream).query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("mod2", "2")));
	}

}
