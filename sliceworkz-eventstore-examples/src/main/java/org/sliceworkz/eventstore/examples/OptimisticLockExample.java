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
package org.sliceworkz.eventstore.examples;

import java.util.List;
import java.util.stream.Stream;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
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
		
		stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("123", "John"), Tags.of("customer", "123")));
		stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("124", "Jane"), Tags.of("customer", "124", "region", "EU")));
		stream.append(AppendCriteria.none(), Event.of(new CustomerChurned("124"), Tags.of("customer", "124")));

		// Two registration events of different customers, queried by Event Type
		Stream<Event<CustomerEvent>> registrations = 
				stream.query(EventQuery.forEvents(EventTypesFilter.of(CustomerRegistered.class), Tags.none()));
		registrations.forEach(System.out::println);
		
		
		// One churn event, queried by Event Type
		List<Event<CustomerEvent>> churns = 
				stream.query(EventQuery.forEvents(EventTypesFilter.of(CustomerChurned.class), Tags.none())).toList();
		churns.forEach(System.out::println);
		
		
		// The DCB loop: every fact about the first customer, whatever its type, is the consistency boundary
		EventQuery customer123 = EventQuery.forTags(Tags.of("customer", "123"));
		
		// Pin the boundary BEFORE reading: the head is the newest stored event of the stream. It is absent
		// for an empty stream, and an absent reference is a valid boundary ("I decided on an empty stream"),
		// so nothing here needs to guard against a customer with no history yet
		EventReference head = stream.head().orElse(null);
		
		// Read the facts up to the boundary and decide; nothing appended after the head is seen here
		List<Event<CustomerEvent>> singleCustomer = stream.query(customer123.until(head)).toList();
		singleCustomer.forEach(System.out::println);

		// A conditional append: only if no fact about this customer landed after the head
		stream.append(AppendCriteria.of(customer123, head),
				Event.of(new CustomerNameChanged("123", "Marc"), Tags.of("customer", "123")));

		// Another conditional append is not possible using the (outdated) head ...
		try {
			stream.append(AppendCriteria.of(customer123, head),
					Event.of(new CustomerNameChanged("123", "John"), Tags.of("customer", "123")));
		} catch (OptimisticLockingException e) {
			// ... as a new fact about this customer exists, that is found by the optimistic-lock query linked to the append AppendCriteria
		}
		
		// A uniqueness check is the same loop over a customer that does not exist yet: the head bounds the
		// read and feeds the check, and the read comes back empty
		EventQuery customer125 = EventQuery.forTags(Tags.of("customer", "125"));
		EventReference headBeforeRegistering = stream.head().orElse(null);
		if ( stream.query(customer125.until(headBeforeRegistering)).findAny().isEmpty() ) {
			stream.append(AppendCriteria.of(customer125, headBeforeRegistering),
					Event.of(new CustomerRegistered("125", "Anne"), Tags.of("customer", "125")));
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
