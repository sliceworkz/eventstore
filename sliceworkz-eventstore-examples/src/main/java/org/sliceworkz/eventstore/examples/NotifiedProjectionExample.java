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

import java.util.Optional;
import java.util.UUID;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.NotifiedProjectionExample.CustomerDomainEvent.CustomerRegistered;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.projection.BatchAwareProjection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class NotifiedProjectionExample implements EventStreamEventuallyConsistentAppendListener {

	public static int CUSTOMER_COUNT = 25;
	
	private EventStream<CustomerDomainEvent> eventStream;
	private Projector<CustomerDomainEvent> projector;
	private SomeCustomerProjection customerProjection;

	public static void main ( String[] args ) throws InterruptedException {
		new NotifiedProjectionExample(InMemoryEventStorage.newBuilder().buildStore()).scenario ( );
	}

	public NotifiedProjectionExample ( EventStore eventStore ) {
		this.eventStream = eventStore.getEventStream(EventStreamId.forContext("customer").withPurpose("domain"), CustomerDomainEvent.class);
		eventStream.subscribe(this);
		this.customerProjection = new SomeCustomerProjection();
		this.projector = Projector.<CustomerDomainEvent>newBuilder()
				.from(eventStream)
				.towards(customerProjection)
				.inBatchesOf(10)
				.bookmarkProgress()
					.withReader("demo-reader")
					.readOnManualTriggerOnly()
					.done()
				.build();
	}
	
	public void scenario ( ) throws InterruptedException {
		int index = 0;
		for ( int i = 0; i < CUSTOMER_COUNT; i++ ) {
			eventStream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("Customer %d".formatted(++index)), Tags.of("customer", UUID.randomUUID().toString())));
		}
		
		while ( customerProjection.counter() < CUSTOMER_COUNT ) {
			System.err.println("all events appended, projection is at  : %d/%d".formatted(customerProjection.counter(), CUSTOMER_COUNT));
			System.err.println("bookmark is at                           : " + eventStream.getBookmark("demo-reader"));
			Thread.sleep(100);
		}
		System.err.println("process at and, projection is at       : %d/%d".formatted(customerProjection.counter(), CUSTOMER_COUNT));
		System.err.println("bookmark is at                         : " + eventStream.getBookmark("demo-reader"));
	}

	/**
	 * Listener callback - triggers the projector and returns the new point reached
	 */
	@Override
	public EventReference eventsAppended(EventReference atLeastUntil) {
		try {
			return projector.run().lastEventReference();
		} catch (ProjectorException e) {
			System.err.println("must retry after projector enountered a problem: %s".formatted(e.getMessage()));
			System.err.println("projector metrics reports last updated : " + projector.accumulatedMetrics().lastEventReference());
			System.err.println("exception reports problem event        : " + e.getEventReference());
			System.err.println("bookmark is at                         : " + eventStream.getBookmark("demo-reader"));
			return null; // report no new events processed correctly (as batch is completely rollbacked) 
		}
	}
	
	/**
	 * Example projection (this could be stored in a SQL or NoSQL database, projected into ElasticSearch, file system, ... 
	 */
	class SomeCustomerProjection implements BatchAwareProjection<CustomerDomainEvent> {
		
		private boolean failedOnce = false;
		private int counter;
		private int counterInBatch;
		
		@Override
		public EventQuery eventQuery() {
			return EventQuery.matchAll();
		}
	
	
		@Override
		public void when(Event<CustomerDomainEvent> eventWithMeta) {
			// insert anything in your database or storage here
			System.out.println("[%d] event: %s".formatted(Thread.currentThread().threadId(), eventWithMeta));
			if ( !failedOnce && eventWithMeta.reference().position()==15) {
				failedOnce = true;
				throw new RuntimeException("deliberately failing customer 15 to demonstrate batch recovery");
			}
			counterInBatch++;
		}
		
		@Override
		public void beforeBatch() {
			// ... you could get a database connection from a datasource and start a transaction
			System.out.println("batch started ...");
			counterInBatch = 0;
		}
	
		@Override
		public void cancelBatch() {
			// ... time to rollback the transaction here!
			System.out.println("batch failed !!!");
		}
	
		@Override
		public void afterBatch(Optional<EventReference> lastEventReference) {
			// ... time to commit the transaction here, and close the database connection / return to pool
			System.out.println("batch done.");
			counter += counterInBatch;
			if ( lastEventReference.isPresent() ) {
				System.out.println("last reference is %d".formatted(lastEventReference.get().position()));
			}
		}
		
		public int counter ( ) {
			return counter;
		}
	}

	public sealed interface CustomerDomainEvent {
	    record CustomerRegistered(String name) implements CustomerDomainEvent {}
	}

}
