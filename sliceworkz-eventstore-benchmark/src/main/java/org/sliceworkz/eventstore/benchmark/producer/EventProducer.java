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
package org.sliceworkz.eventstore.benchmark.producer;

import java.util.ArrayList;
import java.util.List;

import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public abstract class EventProducer<EventType> implements Runnable {
	
	private EventStream<EventType> stream;
	
	public EventProducer ( EventStream<EventType> stream ) {
		this.stream = stream;
	}
	
	@Override
	public void run() {
		for ( int i = 0; i < 10000; i++ ) {
			appendEvents();
		}
	}
	
	void appendEvents ( ) {
		int numberOfEventsInTransaction = 1;
		List<EphemeralEvent<? extends EventType>> events = new ArrayList<>(numberOfEventsInTransaction);

		String transaction = String.valueOf(System.currentTimeMillis());
		Tags tags = Tags.of("transaction", transaction);
		
		for ( int i = 0; i < numberOfEventsInTransaction; i++ ) {
			events.add(createEvent(tags));
		}
		
		stream.append(AppendCriteria.none(), events, getEventStreamId()); //.forEach(System.out::println);
	}
	
	public abstract EphemeralEvent<EventType> createEvent ( Tags tags );

	public abstract EventStreamId getEventStreamId ( );
	
}
