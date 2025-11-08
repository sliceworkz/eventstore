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

import java.io.IOException;
import java.util.List;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class SubscribeToAppendsExample {

	public static void main ( String[] args ) throws IOException {

		// database backed eventstore
		EventStore eventstore = PostgresEventStorage.newBuilder().buildStore();
		
		// we open a (readonly) eventstream that sees all events
		EventStream<Object> stream = eventstore.getEventStream(EventStreamId.anyContext());
		
		// get a reference to the last Event in the stream as a starting point ...
		Handle<EventReference> lastSeen = Handle.of(stream.queryBackwards(EventQuery.matchAll(), Limit.to(1)).findFirst().map(Event::reference).orElse(null));
		
		System.out.println("following all events as from " + lastSeen.get());
		
		stream.subscribe(new EventStreamEventuallyConsistentAppendListener() {
			
			@Override
			public synchronized void eventsAppended(EventReference atLeastUntil) {
				
				// each time we are notified, we query any events after the last we've seen ...
				List<Event<Object>> events = stream.query(EventQuery.matchAll(), lastSeen.get()).toList();
				events.forEach(System.out::println);
				
				// and change our reference point
				if ( ! events.isEmpty() ) {
					lastSeen.set(events.getLast().reference());
				}
			}
			
		});

		System.out.println("press any key to exit ...");
		System.in.read();
		System.out.println("exiting.");

	}
	
	public static class Handle<T> {
		private T value;
		public Handle ( T value ) {
			this.value = value;
		}
		T get ( ) {
			return value;
		}
		
		void set ( T value ) {
			this.value = value;
		}
		static <T> Handle<T> of ( T value ) {
			return new Handle<> ( value );
		}
	}
}
