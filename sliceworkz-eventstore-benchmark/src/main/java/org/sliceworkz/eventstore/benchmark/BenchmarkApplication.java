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
package org.sliceworkz.eventstore.benchmark;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.CustomerEvent;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent;
import org.sliceworkz.eventstore.benchmark.producer.CustomerEventProducer;
import org.sliceworkz.eventstore.benchmark.producer.SupplierEventProducer;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class BenchmarkApplication {

	private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkApplication.class);
	
	public static void main ( String[] args ) throws InterruptedException {
		LOGGER.info("starting...");
		
		EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
		
		// stream-design: one single stream "customer", tags to differentiate
		EventStream<CustomerEvent> customerStream = eventStore.getEventStream(EventStreamId.forContext("customer").defaultPurpose(), CustomerEvent.class);
		
		// stream-design: stream per supplier "supplier/<id>"
		EventStream<SupplierEvent> supplierStream = eventStore.getEventStream(EventStreamId.forContext("supplier").anyPurpose(), SupplierEvent.class);

		EventStream<SupplierEvent> supplier42Stream = eventStore.getEventStream(EventStreamId.forContext("supplier").withPurpose("42"), SupplierEvent.class);
		supplier42Stream.subscribe(new EventStreamEventuallyConsistentAppendListener() {
			@Override
			public EventReference eventsAppended(EventReference atLeastUntil) {
				System.err.println("-------> " + atLeastUntil);
				System.err.println(supplier42Stream.getEventById(atLeastUntil.id()).get());
				return atLeastUntil;
			}
		});

		CustomerEventProducer cep = new CustomerEventProducer(customerStream);
		SupplierEventProducer sep = new SupplierEventProducer(supplierStream);
		
		Instant start = Instant.now();
		
		new Thread(cep).start();
		new Thread(cep).start();
		new Thread(cep).start();
		new Thread(cep).start();
		new Thread(sep).start();
		new Thread(sep).start();
		new Thread(sep).start();
		new Thread(sep).start();
		
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run ( ) {
				Instant stop = Instant.now();

				long durationMs = stop.toEpochMilli() - start.toEpochMilli();

				
				EventStream<Object> allStream = eventStore.getEventStream(EventStreamId.anyContext().anyPurpose());
				allStream.queryBackwards(EventQuery.matchAll(),Limit.to(10)).forEach(System.out::println);
				
				long position = allStream.queryBackwards(EventQuery.matchAll(),Limit.to(1)).findFirst().get().reference().position();
				
				System.err.println("duration: %d".formatted(durationMs));
				System.err.println("last pos: %d".formatted(position));

				double eventsPerSecond = ((1000*position)/(double)durationMs);
				
				System.err.println("events/sec: %f".formatted(eventsPerSecond));

				LOGGER.info("done.");
			}
		});
		
	}
	
}
