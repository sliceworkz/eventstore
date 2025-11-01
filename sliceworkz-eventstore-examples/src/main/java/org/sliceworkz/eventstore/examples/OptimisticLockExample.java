package org.sliceworkz.eventstore.examples;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.OptimisticLockExample.CustomerEvent.CustomerChurned;
import org.sliceworkz.eventstore.examples.OptimisticLockExample.CustomerEvent.CustomerNameChanged;
import org.sliceworkz.eventstore.examples.OptimisticLockExample.CustomerEvent.CustomerRegistered;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

public class OptimisticLockExample {
	
	public static void main ( String[] args ) {
		
		EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();
		
		// one stream for all customers, Tags are used to ID them
		EventStreamId streamId = EventStreamId.forContext("customers");  
		EventStream<CustomerEvent> stream = eventstore.getEventStream(streamId, CustomerEvent.class);
		
		stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("123", "John"), Tags.of(Tag.of("customer", "123"))));
		stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("124", "Jane"), Tags.of(Tag.of("customer", "124"))));
		stream.append(AppendCriteria.none(), Event.of(new CustomerChurned("124"), Tags.of(Tag.of("customer", "124"))));

		// Two registration events of different customers, queried by Event Type
		Stream<Event<CustomerEvent>> registrations = 
				stream.query(EventQuery.forEvents(EventTypesFilter.of(CustomerRegistered.class), Tags.none()));
		registrations.forEach(System.out::println);
		
		
		// One churn event, queried by Event Type
		List<Event<CustomerEvent>> churns = 
				stream.query(EventQuery.forEvents(EventTypesFilter.of(CustomerChurned.class), Tags.none())).toList();
		churns.forEach(System.out::println);
		
		
		// Single event on the first customer, queried by Tag
		List<Event<CustomerEvent>> singleCustomer = 
				stream.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123"))).toList();
	
		// Reference to the last known event
		EventReference lastEventReference = singleCustomer.getLast().reference();

		// An extra (conditional) append is done, notice we still hold the same lastEventReference without changing it
		stream.append(AppendCriteria.of(
				EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123")), 
				Optional.of(lastEventReference)),
				Event.of(new CustomerNameChanged("123", "Marc"), Tags.of(Tag.of("customer", "123"))));

		// Another conditional append is not possible using the (outdated) lastEventReference ...
		try {
			stream.append(AppendCriteria.of(
								EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123")), 
								Optional.of(lastEventReference)), Event.of(new CustomerNameChanged("123", "John"), Tags.of("customer", "123")));
		} catch (OptimisticLockingException e) {
			// ... as a new fact about this customer exists, that is found by the optimistic-lock query linked to the append AppendCriteria
		}
		
	}
	
	/**
	 * Customer Domain Events 
	 */
	sealed interface CustomerEvent {
		
		record CustomerRegistered ( String id, String name ) implements CustomerEvent { }

		record CustomerNameChanged ( String id, String name ) implements CustomerEvent { }

		record CustomerChurned ( String id ) implements CustomerEvent { }

	}

}
