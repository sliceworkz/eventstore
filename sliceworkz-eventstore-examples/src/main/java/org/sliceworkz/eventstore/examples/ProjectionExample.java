package org.sliceworkz.eventstore.examples;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.ProjectionExample.CustomerEvent.CustomerChurned;
import org.sliceworkz.eventstore.examples.ProjectionExample.CustomerEvent.CustomerNameChanged;
import org.sliceworkz.eventstore.examples.ProjectionExample.CustomerEvent.CustomerRegistered;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class ProjectionExample {
	
	public static void main ( String[] args ) {
		
		EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();

		// create a single event stream for all customers
		EventStreamId streamId = EventStreamId.forContext("customers");
		EventStream<CustomerEvent> stream = eventstore.getEventStream(streamId, CustomerEvent.class);
		
		// append some events to the stream
		stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("John Doe"), Tags.of("customer","123")));
		stream.append(AppendCriteria.none(), Event.of(new CustomerNameChanged("John"), Tags.of("customer","123")));
		stream.append(AppendCriteria.none(), Event.of(new CustomerChurned(), Tags.of("customer","123")));
		
		stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("Jane"), Tags.of("customer","234")));
		stream.append(AppendCriteria.none(), Event.of(new CustomerNameChanged("Jane Doe"), Tags.of("customer","234")));
		
		CustomerProjection john = new CustomerProjection("123");
		new Projector<>(stream, john).run();
		System.out.println("john    : " + john.getSummary());    // john    : CustomerSummary[name=John, churned=true]
		
		CustomerProjection jane = new CustomerProjection("234");
		new Projector<>(stream, jane).run();
		System.out.println("jane    : " + jane.getSummary());    // jane    : CustomerSummary[name=Jane Doe, churned=false]

		CustomerProjection unknown = new CustomerProjection("345");
		new Projector<>(stream, unknown).run();
		System.out.println("unknown : " + unknown.getSummary()); // unknown : null

	}
	
	static class CustomerProjection implements Projection<CustomerEvent> {

		private String customerId;
		private CustomerSummary customerSummary;
		
		public CustomerProjection ( String customerId ) {
			this.customerId = customerId;
		}
		
		public CustomerSummary getSummary ( ) {
			return customerSummary;
		}
		
		@Override
		public void when(CustomerEvent event ) {
			switch(event) {
				case CustomerRegistered r -> customerSummary = new CustomerSummary(r.name(), false);
				case CustomerNameChanged n -> customerSummary = customerSummary.name(n.name());
				case CustomerChurned c -> customerSummary = customerSummary.churned(true);
			}
		}

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", customerId));
		}
		
		public record CustomerSummary ( String name, boolean churned ) {
			public CustomerSummary name ( String name ) {
				return new CustomerSummary(name, churned);
			}
			public CustomerSummary churned ( boolean churned ) {
				return new CustomerSummary(name, churned);
			}
			public static CustomerSummary create ( ) {
				return new CustomerSummary(null, false);
			}
		}
		
	}
	
	sealed interface CustomerEvent {
		
		public record CustomerRegistered ( String name ) implements CustomerEvent { }
		
		public record CustomerNameChanged (String name ) implements CustomerEvent { }
		
		public record CustomerChurned ( ) implements CustomerEvent { }
		
	}
}
