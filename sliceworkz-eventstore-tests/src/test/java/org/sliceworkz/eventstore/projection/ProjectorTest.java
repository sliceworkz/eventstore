package org.sliceworkz.eventstore.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.AbstractEventStoreTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorageImpl;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.mockdomain.MockDomainEvent.ThirdDomainEvent;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class ProjectorTest extends AbstractEventStoreTest {

	private EventStream<MockDomainEvent> es; 
	
	@BeforeEach
	public void setUp ( ) {
		super.setUp();
		
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");
		es = eventStore().getEventStream(stream, MockDomainEvent.class);
		
		append(es, new FirstDomainEvent("1"), Tags.of("nr", "one"));
		append(es, new SecondDomainEvent("2"), Tags.of("nr", "two"));
		append(es, new ThirdDomainEvent("3"), Tags.of("nr", "three"));
		append(es, new FirstDomainEvent("4"), Tags.of("nr", "four"));
		append(es, new SecondDomainEvent("5"), Tags.of("nr", "five"));
		append(es, new ThirdDomainEvent("6"), Tags.of("nr", "six"));
	}

	@Test
	void testProjector ( ) {
		TestProjection projection = new TestProjection();	
		
		var projector = new Projector<>(es, projection);

		ProjectorMetrics projectorMetrics = projector.run();
		assertEquals(4, projection.counter()); // SecondDomainEvent type is left out by the query
		assertEquals(1, projectorMetrics.queriesDone());
		assertEquals(4,  projectorMetrics.eventsStreamed());
		assertEquals(4,  projectorMetrics.eventsHandled());

		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone());
		assertEquals(4,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(4,  accumulatedMetrics.eventsHandled());
	}
	
	@Test
	void testProjectorQueryUntilCertainEvent ( ) {
		TestProjection projection = new TestProjection();
		
		EventReference ref = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).findFirst().get().reference();
		
		var projector = new Projector<>(es, projection);
		
		ProjectorMetrics projectorMetrics = projector.runUntil(ref);
		assertEquals(3, projection.counter()); // SecondDomainEvent type is left out by the query, until removes everything after four
		assertEquals(1, projectorMetrics.queriesDone());
		assertEquals(3,  projectorMetrics.eventsStreamed()); // since we pass in until, we don't stream the extra events
		assertEquals(3,  projectorMetrics.eventsHandled());
	
		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone());
		assertEquals(3,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(3,  accumulatedMetrics.eventsHandled());
	}

	@Test
	void testProjectorMultipleRuns ( ) {
		TestProjection projection = new TestProjection();
		
		EventStreamId stream = EventStreamId.forContext("app").withPurpose("alternative");
		EventStream<MockDomainEvent> alternativeStream = eventStore().getEventStream(stream, MockDomainEvent.class);
		
		append(alternativeStream, new FirstDomainEvent("1"), Tags.of("nr", "one"));

		Projector<MockDomainEvent> projector = new Projector<>(alternativeStream, projection);
		
		ProjectorMetrics projectorMetrics = projector.run();
		assertEquals(1, projection.counter()); 
		assertEquals(1, projectorMetrics.queriesDone());
		assertEquals(1,  projectorMetrics.eventsStreamed()); 
		assertEquals(1,  projectorMetrics.eventsHandled());

		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone());
		assertEquals(1,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(1,  accumulatedMetrics.eventsHandled());

		append(alternativeStream, new FirstDomainEvent("2"), Tags.of("nr", "two"));

		projectorMetrics = projector.run();
		assertEquals(2, projection.counter()); // second event now also processed by projection 
		assertEquals(1, projectorMetrics.queriesDone());
		assertEquals(1,  projectorMetrics.eventsStreamed()); 
		assertEquals(1,  projectorMetrics.eventsHandled());

		accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(2, accumulatedMetrics.queriesDone());
		assertEquals(2,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(2,  accumulatedMetrics.eventsHandled());
	}
	
	@Test
	void testProjectorStartInStreamQueryUntilCertainEvent ( ) {
		TestProjection projection = new TestProjection();
		
		EventReference refAfter = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "one"))).findFirst().get().reference();
		EventReference refUntil = es.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of("nr", "four"))).findFirst().get().reference();
		
		var projector = new Projector<>(es, projection, refAfter);
		
		ProjectorMetrics projectorMetrics = projector.runUntil(refUntil);
		assertEquals(2, projection.counter()); // SecondDomainEvent type is left out by the query, until removes everything after four
		assertEquals(1, projectorMetrics.queriesDone());
		assertEquals(2,  projectorMetrics.eventsStreamed()); // since we pass "until", we don't stream the extra events 
		assertEquals(2,  projectorMetrics.eventsHandled());
	
		ProjectorMetrics accumulatedMetrics = projector.accumulatedMetrics();
		assertEquals(1, accumulatedMetrics.queriesDone());
		assertEquals(2,  accumulatedMetrics.eventsStreamed()); 
		assertEquals(2,  accumulatedMetrics.eventsHandled());
	}


	private List<Event<MockDomainEvent>> append ( EventStream<MockDomainEvent> es, MockDomainEvent event, Tags tags ) {
		return es.append(AppendCriteria.none(), Collections.singletonList(Event.of(event, tags)));
	}
	
	class TestProjection implements Projection<MockDomainEvent> {

		private int counter;
		
		@Override
		public void when(MockDomainEvent event) {
			counter++;
		}

		@Override
		public EventQuery eventQuery() {
			return EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class, ThirdDomainEvent.class), Tags.none());
		}
		
		public int counter ( ) {
			return counter;
		}
			
	}

	@Override
	public EventStorage createEventStorage() {
		return new InMemoryEventStorageImpl();
	}

	
}
