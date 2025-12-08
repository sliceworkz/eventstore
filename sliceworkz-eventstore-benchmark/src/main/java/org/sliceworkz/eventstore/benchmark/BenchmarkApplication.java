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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.CustomerEvent;
import org.sliceworkz.eventstore.benchmark.BenchmarkEvent.SupplierEvent;
import org.sliceworkz.eventstore.benchmark.consumer.CustomerConsumer;
import org.sliceworkz.eventstore.benchmark.consumer.SupplierConsumer;
import org.sliceworkz.eventstore.benchmark.producer.CustomerEventProducer;
import org.sliceworkz.eventstore.benchmark.producer.SupplierEventProducer;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class BenchmarkApplication {

	public static final int EVENTS_PER_PRODUCER_INSTANCE = 1000;
	public static final int PARALLEL_WORKERS = 2;
	public static final int TOTAL_CONSUMER_EVENTS = PARALLEL_WORKERS * EVENTS_PER_PRODUCER_INSTANCE; 
	public static final int TOTAL_SUPPLIER_EVENTS = PARALLEL_WORKERS * EVENTS_PER_PRODUCER_INSTANCE; 

	private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkApplication.class);
	
	public static void main ( String[] args ) throws InterruptedException {
		LOGGER.info("starting...");
		
		//EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore();
		EventStore eventStore = PostgresEventStorage.newBuilder().prefix("benchmark_").initializeDatabase().buildStore();
		
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

		CustomerConsumer cc = new CustomerConsumer(customerStream);
		SupplierConsumer sc = new SupplierConsumer(supplierStream);

		CustomerEventProducer cep = new CustomerEventProducer(customerStream, EVENTS_PER_PRODUCER_INSTANCE);
		SupplierEventProducer sep = new SupplierEventProducer(supplierStream, EVENTS_PER_PRODUCER_INSTANCE);
		
		
		Instant start = Instant.now();
		
		ExecutorService executor = Executors.newFixedThreadPool(20);

		for ( int i = 0; i < PARALLEL_WORKERS; i++ ) {
		    executor.submit(cep);
		    executor.submit(sep);
		}

		executor.shutdown();
		executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
		
		for ( int i = 0; i < 10; i++ ) {
			System.err.println("====================================================================================================");
		}
		
		System.out.println("CUSTOMER EVENTS PROCESSED #1: %d (%d batches)".formatted(cc.getProjection().eventsProcessed(), cc.getProjection().batchesProcessed()));
		System.out.println("SUPPLIER EVENTS PROCESSED #1: %d (%d batches)".formatted(sc.getProjection().eventsProcessed(), sc.getProjection().batchesProcessed()));

		Instant stopProduce = Instant.now();

		long produceDurationMs = stopProduce.toEpochMilli() - start.toEpochMilli();

		
		EventStream<Object> allStream = eventStore.getEventStream(EventStreamId.anyContext().anyPurpose());
		allStream.queryBackwards(EventQuery.matchAll(),Limit.to(10)).forEach(System.out::println);
		
		long position = allStream.queryBackwards(EventQuery.matchAll(),Limit.to(1)).findFirst().get().reference().position();
		
		System.err.println("duration: %d".formatted(produceDurationMs));
		System.err.println("last pos: %d".formatted(position));

		double producedEventsPerSec = ((1000*position)/(double)produceDurationMs);
		
		System.err.println("events/sec produced: %f".formatted(producedEventsPerSec));

		System.out.println("CUSTOMER EVENTS PROCESSED #2: %d (%d batches)".formatted(cc.getProjection().eventsProcessed(), cc.getProjection().batchesProcessed()));
		System.out.println("SUPPLIER EVENTS PROCESSED #2 %d (%d batches)".formatted(sc.getProjection().eventsProcessed(), sc.getProjection().batchesProcessed()));
		
		// while read side hasn't kept up
		if ( cc.getProjection().eventsProcessed() < TOTAL_CONSUMER_EVENTS ) {
			cc.runProjector(); // TODO how to better do this?
			System.out.println("CUSTOMER EVENTS PROCESSED #3: %d (%d batches)".formatted(cc.getProjection().eventsProcessed(), cc.getProjection().batchesProcessed()));
		}
		if ( sc.getProjection().eventsProcessed() < TOTAL_SUPPLIER_EVENTS ) {
			sc.runProjector(); // TODO how to better do this?
			System.out.println("SUPPLIER EVENTS PROCESSED #3 %d (%d batches)".formatted(sc.getProjection().eventsProcessed(), sc.getProjection().batchesProcessed()));
		}

		if ( cc.getProjection().eventsProcessed() < TOTAL_CONSUMER_EVENTS ) {
			System.err.println("== Customer Event COUNT NOT OK ! ==================================================================================");
		}
		if ( sc.getProjection().eventsProcessed() < TOTAL_SUPPLIER_EVENTS ) {
			System.err.println("== Supplier Event COUNT NOT OK ! ==================================================================================");
		}
		
		Instant stopConsume = Instant.now();

		long consumeDurationMs = stopConsume.toEpochMilli() - start.toEpochMilli();

		double consumedEventsPerSec = ((1000*position)/(double)consumeDurationMs);
		
		System.err.println("events/sec consumed: %f".formatted(consumedEventsPerSec));

		System.out.println("received notifications: %d".formatted(cc.recievedAppendNotifications()));

		LOGGER.info("done.");
	}
	
}
