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

import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent.SupplierRegistered;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class SupplierEventProducer extends EventProducer<SupplierEvent> {

	private EventStream<SupplierEvent> stream;
	private AtomicInteger counter = new AtomicInteger();

	public SupplierEventProducer(EventStream<SupplierEvent> stream) {
		this.stream = stream;
	}
	
	@Override
	public EphemeralEvent<SupplierEvent> createEvent( Tags tags ) {
		return Event.of(new SupplierRegistered("Supplier %d".formatted(counter.incrementAndGet())), tags);
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