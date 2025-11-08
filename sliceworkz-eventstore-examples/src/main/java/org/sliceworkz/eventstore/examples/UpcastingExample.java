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
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.examples.AppendAndQueryAllExample.CustomerEvent.CustomerChurned;
import org.sliceworkz.eventstore.examples.UpcastingExample.CustomerEvent.CustomerRegisteredV2;
import org.sliceworkz.eventstore.examples.UpcastingExample.CustomerEvent.CustomerRenamed;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class UpcastingExample {
	
	public static void main ( String[] args ) {
		
		// create a memory-backed eventstore
		EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();

		// create an EventStream for a particular customer 
		EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
		EventStream<OriginalEvent> stream = eventstore.getEventStream(streamId, OriginalEvent.class);
		
		// append 3 individual events to the stream
		stream.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerRegistered("John"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerNameChanged("Jane"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerChurned(), Tags.none()));

		System.out.println("querying all events in the store, without upcasts:");
			stream.query(EventQuery.matchAll()).map(e->(OriginalEvent)e.data())
			.forEach(System.out::println);

		// query and print all events that are now in the stream, using the new definitions and upcasting

		EventStream<CustomerEvent> streamNew = eventstore.getEventStream(streamId, CustomerEvent.class, CustomerHistoricalEvent.class);
		
		// now when we read the new stream, the upcasters are run to provide us with the latest versions of the events...

		System.out.println("querying all events in the store, the result will include (upcasted) legacy Event types:");

		// explicit cast to verify that no HistoricalEvents are returned
		streamNew.query(EventQuery.matchAll()).map(e->(CustomerEvent)e.data())
			.forEach(System.out::println);
		
		System.out.println("querying filtered on a specific new Event type, the result will include (upcasted) legacy Event types:");
		
		// we can even query old Event types with their upcasted counterparts ...
		streamNew.query(EventQuery.forEvents(EventTypesFilter.of(CustomerRenamed.class), Tags.none())).map(e->(CustomerEvent)e.data())
			.forEach(System.out::println);

		System.out.println("querying filtered on multiple new Event types, the result will include (upcasted) legacy Event types:");

		// ... or multiple new event types, and have old events upcasted on the fly! ...
		streamNew.query(EventQuery.forEvents(EventTypesFilter.of(CustomerRegisteredV2.class, CustomerRenamed.class, CustomerChurned.class), Tags.none())).map(e->(CustomerEvent)e.data())
			.forEach(System.out::println);

		// ... while appending a historical event type is not possible thanks to string typing!
		// COMPILER CHECK: streamNew.append(AppendCriteria.none(), new OriginalEvent.CustomerNameChanged("test"));
	}
	
	/*
	 * The original events appended to the store
	 */
	sealed interface OriginalEvent {
		
		public record CustomerRegistered ( String name ) implements OriginalEvent { }
		
		public record CustomerNameChanged (String name ) implements OriginalEvent { }
		
		public record CustomerChurned ( ) implements OriginalEvent { }
		
	}

	
	/*
	 * Our latest and brightest event definitions
	 */
	sealed interface CustomerEvent {
		
		// this one changes to much that we consider it a new version
		public record CustomerRegisteredV2 ( Name name ) implements CustomerEvent { }
		
		// this one is renamed from "CustomerNameChanged"		
		public record CustomerRenamed ( Name name ) implements CustomerEvent { }
		
		public record CustomerChurned ( ) implements CustomerEvent { }
		
		
		public record Name ( String value ) {
			
			public Name ( String value ) {
				if ( value == null || value.strip().length() == 0 ) {
					throw new IllegalArgumentException();
				}
				this.value = value;
			}
		
			public static Name of ( String value ) {
				if ( value != null ) {
					if ( value.length() < 3 || value.length() > 20 ) {
						throw new IllegalArgumentException("name length must be between 3 and 20");
					}
				}
				return new Name(value);
			}
		}
		
	}

	/*
	 * Deprecated historical event definitions, needed to deserialization, but will be upcasted
	 */
	sealed interface CustomerHistoricalEvent {
		
		@LegacyEvent(upcast=CustomerRegisteredUpcaster.class)
		public record CustomerRegistered ( String name ) implements CustomerHistoricalEvent { }
		
		@LegacyEvent(upcast=CustomerNameChangedUpcaster.class)
		public record CustomerNameChanged (String name ) implements CustomerHistoricalEvent { }
		
	}
	
	/*
	 * Our upcasters that transform the legacy events to current event definitions
	 */
	
	public static class CustomerRegisteredUpcaster implements Upcast<CustomerHistoricalEvent.CustomerRegistered, CustomerEvent.CustomerRegisteredV2> {

		@Override
		public CustomerEvent.CustomerRegisteredV2 upcast(CustomerHistoricalEvent.CustomerRegistered historicalEvent) {
			// using the constructor, not the "of" utility method to allow historical values that don't adhere to the new length business rules
			return new CustomerEvent.CustomerRegisteredV2(new CustomerEvent.Name(historicalEvent.name()));
		}

		@Override
		public Class<CustomerRegisteredV2> targetType() {
			return CustomerRegisteredV2.class;
		}
		
	}
	
	public static class CustomerNameChangedUpcaster implements Upcast<CustomerHistoricalEvent.CustomerNameChanged, CustomerEvent.CustomerRenamed> {

		@Override
		public CustomerEvent.CustomerRenamed upcast(CustomerHistoricalEvent.CustomerNameChanged historicalEvent) {
			// using the constructor, not the "of" utility method to allow historical values that don't adhere to the new length business rules
			return new CustomerEvent.CustomerRenamed(new CustomerEvent.Name(historicalEvent.name()));
		}
		
		@Override
		public Class<CustomerRenamed> targetType() {
			return CustomerRenamed.class;
		}
		

	}

}
