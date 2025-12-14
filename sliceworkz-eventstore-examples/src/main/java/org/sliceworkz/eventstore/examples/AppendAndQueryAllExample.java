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
package org.sliceworkz.eventstore.examples;

import java.util.stream.Stream;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.events.EventWithMetaDataHandler;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.AppendAndQueryAllExample.CustomerEvent.CustomerChurned;
import org.sliceworkz.eventstore.examples.AppendAndQueryAllExample.CustomerEvent.CustomerNameChanged;
import org.sliceworkz.eventstore.examples.AppendAndQueryAllExample.CustomerEvent.CustomerRegistered;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class AppendAndQueryAllExample {
	
	public static void main ( String[] args ) {
		
		// create a memory-backed eventstore
		EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();

		// create an EventStream for a particular customer 
		EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
		EventStream<CustomerEvent> stream = eventstore.getEventStream(streamId, CustomerEvent.class);
		
		// append 3 individual events to the stream
		stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("John"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new CustomerNameChanged("Jane"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new CustomerChurned(), Tags.none()));
		
		// query and print all events that are now in the stream
		Stream<Event<CustomerEvent>> allEvents = stream.query(EventQuery.matchAll());
		
		new EventWithMetaDataHandler<CustomerEvent>() {

			@Override
			public void when(Stream<Event<CustomerEvent>> eventsWithMeta) {
				System.out.println("printing events with metadata...");
				eventsWithMeta.forEach(this::when);
				System.out.println("done printing events.");
			}
			
			@Override
			public void when(Event<CustomerEvent> eventWithMeta) {
				System.out.println(eventWithMeta);
			}

		}.when(allEvents);

		// query and print all events that are now in the stream
		allEvents = stream.query(EventQuery.matchAll());

		new EventHandler<CustomerEvent>() {

			@Override
			public void when(Stream<Event<CustomerEvent>> events) {
				System.out.println("printing events ...");
				events.forEach(this::when);
				System.out.println("done printing events.");
			}
			
			@Override
			public void when(CustomerEvent event) {
				System.out.println(event);
			}

		}.when(allEvents);

	}
	
	sealed interface CustomerEvent {
		
		public record CustomerRegistered ( String name ) implements CustomerEvent { }
		
		public record CustomerNameChanged (String name ) implements CustomerEvent { }
		
		public record CustomerChurned ( ) implements CustomerEvent { }
		
	}

}
