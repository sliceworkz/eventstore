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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventHandler;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Example on how one could implement an eventsourced Aggregate, using DCB to realise optimistic locking 
 * Very simplistic implementation to show the crucial aspects to it; left as an exercise to the reader to elaborate. 
 */
public class AggregateExample {
	
	private final EventStream<CustomerEvent> stream;

	
	public AggregateExample ( EventStream<CustomerEvent> stream ) {
		this.stream = stream;
	}

	public void runScenario ( ) {
		CustomerAggregate john = loadCustomer("123");
		System.out.println("john (before any command) : " + john);

		appendEvents(john.register("John"), "123", john.lastEventReference());
		john = loadCustomer("123");
		System.out.println("john (after registration) : " + john);

		CustomerAggregate outdatedJohn = loadCustomer("123");

		appendEvents(john.changeName("Jane"), "123", john.lastEventReference());
		john = loadCustomer("123");
		System.out.println("john (after 1st rename  ) : " + john);

		// if we try to base a decision on outdated information ...
		try {
			// ... an event is produced by the Aggregate, but it will never be appended to our EventStream, as ... 
			appendEvents(outdatedJohn.changeName("OtherName"), "123", outdatedJohn.lastEventReference());
			System.err.println("if you see this message, optimistic locking failed ...");
		} catch (OptimisticLockingException e) {
			// ... we got ourselves an OptimisticLockingException.
			System.out.println("optimistic locking saved us : %s".formatted(e.getMessage()));
		}

		john = loadCustomer("123");
		System.out.println("john (after optlock     ) : " + john);

		appendEvents(john.changeName("Johnny"), "123", john.lastEventReference());

		john = loadCustomer("123");
		System.out.println("john (after 2nd rename  ) : " + john);

		final CustomerAggregate finalJohn = john;
		// we can even "update" the aggregate with the new Events if we want, so we don't need to do a "loadCustomer" call
		appendEvents(john.changeName("John"), "123", john.lastEventReference()).forEach(e->finalJohn.when(e.data(), e.reference()));

		john = loadCustomer("123");
		
		appendEvents(john.churn(), "123", john.lastEventReference());

		john = loadCustomer("123");
		System.out.println("john (after churn       ) : " + john);
		
		
	}
	
	/**
	 *  Loads an Aggregate from the Events found in the EventStream.  All Events tagged with the specified CustomerId are retrieved.
	 */
	CustomerAggregate loadCustomer ( String customerId ) {
		CustomerAggregateProjection projection = new CustomerAggregateProjection(customerId);
		Projector.from(stream).towards(projection).build().run();
		return projection.customerAggregate();
	}
	
	/**
	 * Appends all Events produced by the Aggregate to the stream, unless the last Event seen by the Aggregate 
	 * is not the last in the EventStream anymore.  In the latter case, an OptimisticLockingException will be thrown.
	 */
	List<Event<CustomerEvent>> appendEvents ( List<CustomerEvent> events, String customerId, EventReference lastEventReference ) {
		return stream.append(
				
				// DCB-style optimistic locking : only append the events ...
				AppendCriteria.of(
						// ... if no other event of any type exists by now ...
						EventQuery.forEvents(EventTypesFilter.any(), 
						// ... for this customer ...
						Tags.of("customer", customerId)),
						// ... since we last read this customer
						Optional.ofNullable(lastEventReference)),
				
				// and of course don't forget to tag the new Events with this customerId ...
				events.stream().<EphemeralEvent<? extends CustomerEvent>>map(
						e->Event.of(e, Tags.of("customer", customerId))).toList()
				);
	}
	
	/**
	 * A simple eventsourced Aggregate example.
	 */
	class CustomerAggregate implements EventHandler<CustomerEvent> {

		private String name;
		private boolean registered;
		
		private EventReference lastEventReference;
		
		public List<CustomerEvent> register ( String initialName ) {
			List<CustomerEvent> result = new ArrayList<>();
			
			if ( registered ) {
				throw new IllegalArgumentException("customer already registered - cannot register again");
			}
			checkName(initialName);
			
			if ( this.name == null || !this.name.equals(initialName) ) {
				result.add(new CustomerEvent.CustomerRegistered(initialName));
			}
			
			return result;
		}

		public List<CustomerEvent> changeName ( String newName ) {
			List<CustomerEvent> result = new ArrayList<>();
			
			if ( ! registered ) {
				throw new IllegalArgumentException("customer not registered - cannot change name");
			}
			
			// if name is not changed, no event to raise ...
			if ( !this.name.equals(newName) ) {
				result.add(new CustomerEvent.CustomerNameChanged(newName));
			}
			
			return result;
		}
		
		public List<CustomerEvent> churn (  ) {
			List<CustomerEvent> result = new ArrayList<>();
			
			if ( ! registered ) {
				throw new IllegalArgumentException("customer already churned - cannot churn again");
			}
			
			result.add(new CustomerEvent.CustomerChurned());
			
			return result;
		}
		
		private void checkName ( String newName ) {
			if ( newName == null || "".equals(newName) ) {
				throw new IllegalArgumentException("name cannot be empty");
			}	
		}
		
		@Override
		public void when(CustomerEvent event, EventReference reference ) {
			when(event);
			lastEventReference = reference;
		}
		
		public void when ( CustomerEvent event ) {
			switch(event) {
				case CustomerEvent.CustomerRegistered r -> { this.name = r.name(); this.registered = true; }
				case CustomerEvent.CustomerNameChanged n -> this.name = n.name();
				case CustomerEvent.CustomerChurned c -> this.registered = false;
				default -> throw new RuntimeException("PANIC! Unhandled event type %s".formatted(event.getClass()));
			}
		}
		
		public EventReference lastEventReference ( ) {
			return lastEventReference;
		}
		
		public String toString ( ) {
			return "%-10s - %15s (%s)".formatted(name, registered?"REGISTERED":"NOT_REGISTERED", lastEventReference);
		}
		
	}
	
	
	/**
	 *  While not strictly necessary (one could query the eventstream directly), 
	 *  this Projection provides a clean way to get the project the Events in a CustomerAggregate 
	 */
	class CustomerAggregateProjection implements Projection<CustomerEvent> {
		
		private String customerId;
		private CustomerAggregate customerAggregate;
		
		public CustomerAggregateProjection ( String customerId ) {
			this.customerId = customerId;
			this.customerAggregate = new CustomerAggregate();
		}

		@Override
		public void when(CustomerEvent event, EventReference reference) {
			customerAggregate.when(event, reference);
		}

		@Override
		public void when(CustomerEvent event) {
			// not needed
		}

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", customerId));
		}

		public CustomerAggregate customerAggregate ( ) {
			return customerAggregate;
		}
		
	}
	
	sealed interface CustomerEvent {
		
		public record CustomerRegistered ( String name ) implements CustomerEvent { }
		
		public record CustomerNameChanged (String name ) implements CustomerEvent { }
		
		public record CustomerChurned ( ) implements CustomerEvent { }
		
	}

	
	public static void main ( String[] args ) {
		
		// create a memory-backed eventstore
		EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();

		// create an EventStream for a particular customer 
		EventStreamId streamId = EventStreamId.forContext("customers");
		EventStream<CustomerEvent> stream = eventstore.getEventStream(streamId, CustomerEvent.class);
		
		new AggregateExample ( stream ).runScenario ( );
		
	}
}