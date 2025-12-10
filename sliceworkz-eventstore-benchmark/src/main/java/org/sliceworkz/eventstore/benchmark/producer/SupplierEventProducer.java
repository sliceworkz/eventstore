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

import java.util.concurrent.atomic.AtomicLong;

import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent.SupplierRegistered;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class SupplierEventProducer extends EventProducer<SupplierEvent> {

	private EventStream<SupplierEvent> stream;
	private AtomicLong counter = new AtomicLong();

	public SupplierEventProducer(EventStream<SupplierEvent> stream, int eventsToGenerate, int msWaitBetweenEvents ) {
		super(eventsToGenerate, msWaitBetweenEvents);
		this.stream = stream;
	}
	
	@Override
	public EphemeralEvent<SupplierEvent> createEvent( Tags tags ) {
		long id = counter.incrementAndGet();
		return Event.of(new SupplierRegistered("Supplier %d".formatted(id)), tags.merge(Tags.of("supplier",id)));
	}

	@Override
	public EventStream<SupplierEvent> getEventStream() {
		return stream.withPurpose("" + counter.get());
	}

	@Override
	public EventStreamId getEventStreamId() {
		return stream.id().withPurpose("" + counter.get());
	}
}