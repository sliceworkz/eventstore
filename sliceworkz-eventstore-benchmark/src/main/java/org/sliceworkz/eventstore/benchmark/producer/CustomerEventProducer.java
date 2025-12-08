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

import java.util.concurrent.atomic.AtomicInteger;

import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.CustomerEvent;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.CustomerEvent.CustomerRegistered;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class CustomerEventProducer extends EventProducer<CustomerEvent> {

	private EventStream<CustomerEvent> stream;
	
	private AtomicInteger counter = new AtomicInteger();

	public CustomerEventProducer(EventStream<CustomerEvent> stream) {
		this.stream = stream;
	}
	
	@Override
	public EphemeralEvent<CustomerEvent> createEvent( Tags tags ) {
		return Event.of(new CustomerRegistered("Customer %d".formatted(counter.incrementAndGet())), tags);
	}

	@Override
	public EventStream<CustomerEvent> getEventStream() {
		return stream;
	}

	@Override
	public EventStreamId getEventStreamId() {
		return stream.id();
	}
	
}
