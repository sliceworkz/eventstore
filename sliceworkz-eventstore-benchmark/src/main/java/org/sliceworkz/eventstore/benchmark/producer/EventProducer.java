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

import java.util.Collections;
import java.util.UUID;

import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public abstract class EventProducer<EventType> implements Runnable {
	
	private EventStream<EventType> eventStream;
	private int eventsToGenerate;
	private int msWaitBetweenEvents;
	
	public EventProducer ( EventStream<EventType> eventStream, int eventsToGenerate, int msWaitBetweenEvents ) {
		this.eventStream = eventStream;
		this.eventsToGenerate = eventsToGenerate;
		this.msWaitBetweenEvents = msWaitBetweenEvents;
	}
	
	@Override
	public void run() {
		for ( int i = 0; i < eventsToGenerate; i++ ) {
			appendEvents();
			if ( msWaitBetweenEvents > 0 ) {
				try {
					Thread.sleep(msWaitBetweenEvents);
				} catch (InterruptedException e) {
				}
			}
		}
	}
	
	void appendEvents ( ) {
		String transaction = UUID.randomUUID().toString();
		Tags tags = Tags.of("transaction", transaction);
		
		EphemeralEvent<EventType> event = createEvent(tags);
		
		try {
			eventStream.append(AppendCriteria.none(), Collections.singletonList(event), getEventStreamId(event)); //.forEach(System.err::println);
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}
	
	public abstract EphemeralEvent<EventType> createEvent ( Tags tags );

	public abstract EventStreamId getEventStreamId ( EphemeralEvent<EventType> event );

}
