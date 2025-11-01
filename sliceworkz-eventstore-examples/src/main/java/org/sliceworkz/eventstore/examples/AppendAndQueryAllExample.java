package org.sliceworkz.eventstore.examples;

import java.util.stream.Stream;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
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
		allEvents.forEach(System.out::println);
		
	}
	
	sealed interface CustomerEvent {
		
		public record CustomerRegistered ( String name ) implements CustomerEvent { }
		
		public record CustomerNameChanged (String name ) implements CustomerEvent { }
		
		public record CustomerChurned ( ) implements CustomerEvent { }
		
	}

}
