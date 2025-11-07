package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorageImpl;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.UpcastTest.CustomerEvent.CustomerRegisteredV2;
import org.sliceworkz.eventstore.stream.UpcastTest.CustomerEvent.CustomerRenamed;
import org.sliceworkz.eventstore.stream.UpcastTest.CustomerEvent.Name;
import org.sliceworkz.eventstore.stream.UpcastTest.CustomerHistoricalEvent;

public class UpcastTest {
	
	EventStorage eventStorage;
	EventStore eventStore;
	EventStreamId streamId = EventStreamId.forContext("unittest");
	
	@BeforeEach
	public void setUp ( ) {
		this.eventStorage = createEventStorage();
		this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
	}
	
	@AfterEach
	public void tearDown ( ) {
		destroyEventStorage(eventStorage);
	}
	
	public EventStorage createEventStorage ( ) {
		return new InMemoryEventStorageImpl();
	}
	
	public void destroyEventStorage ( EventStorage storage ) {
		
	}
	
	@Test
	void testUpcasting ( ) {
		EventStream<OriginalEvent> originalStream = eventStore.getEventStream(streamId, OriginalEvent.class);
		originalStream.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerRegistered("John"), Tags.of("customer", "123")));
		originalStream.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerNameChanged("Jane"), Tags.of("customer", "123")));
		originalStream.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerChurned(), Tags.of("customer", "123")));
		
		List<Event<OriginalEvent>> originalEvents = originalStream.query(EventQuery.matchAll()).toList(); 
		
		assertEquals(3, originalEvents.size());
		
		EventStream<CustomerEvent> streamWithUpcasts = eventStore.getEventStream(streamId, CustomerEvent.class, CustomerHistoricalEvent.class);
		streamWithUpcasts.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerRegisteredV2(Name.of("Superman")), Tags.of("customer", "234")));
		streamWithUpcasts.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerRenamed(Name.of("Batman")), Tags.of("customer", "234")));
		streamWithUpcasts.append(AppendCriteria.none(), Event.of(new CustomerEvent.CustomerChurned(), Tags.of("customer", "234")));


		List<Event<CustomerEvent>> newEvents = streamWithUpcasts.query(EventQuery.matchAll()).toList();
		assertEquals(6, newEvents.size());

		// make sure we can query both old and new events on the new (potentially upcasted) type
		assertEquals(2, streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerRegisteredV2.class), Tags.none())).toList().size());
		assertEquals(2, streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerRenamed.class), Tags.none())).toList().size());
		assertEquals(2, streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerChurned.class), Tags.none())).toList().size());

		// verify data on the Register events
		List<Event<CustomerEvent>> registers = streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerRegisteredV2.class), Tags.none())).toList();
		assertEquals(EventType.ofType("CustomerRegisteredV2"), registers.get(0).type());
		assertEquals(EventType.ofType("CustomerRegistered"), registers.get(0).storedType());
		assertEquals("John", ((CustomerRegisteredV2)(registers.get(0).data())).name().value());
		assertEquals(1, registers.get(0).reference().position());
		assertEquals(CustomerRegisteredV2.class, registers.get(0).data().getClass());
		assertEquals(EventType.ofType("CustomerRegisteredV2"), registers.get(1).type());
		assertEquals(EventType.ofType("CustomerRegisteredV2"), registers.get(1).storedType());
		assertEquals(CustomerRegisteredV2.class, registers.get(1).data().getClass());
		assertEquals("Superman", ((CustomerRegisteredV2)(registers.get(1).data())).name().value());
		assertEquals(4, registers.get(1).reference().position());

		// verify data on the Rename events
		List<Event<CustomerEvent>> renames = streamWithUpcasts.query(EventQuery.forEvents(EventTypesFilter.of(CustomerEvent.CustomerRenamed.class), Tags.none())).toList();
		assertEquals(EventType.ofType("CustomerRenamed"), renames.get(0).type());
		assertEquals(EventType.ofType("CustomerNameChanged"), renames.get(0).storedType());
		assertEquals("Jane", ((CustomerRenamed)(renames.get(0).data())).name().value());
		assertEquals(2, renames.get(0).reference().position());
		assertEquals(CustomerRenamed.class, renames.get(0).data().getClass());
		assertEquals(EventType.ofType("CustomerRenamed"), renames.get(1).type());
		assertEquals(EventType.ofType("CustomerRenamed"), renames.get(1).storedType());
		assertEquals(CustomerRenamed.class, renames.get(1).data().getClass());
		assertEquals("Batman", ((CustomerRenamed)(renames.get(1).data())).name().value());
		assertEquals(5, renames.get(1).reference().position());

		// check that references are not changed during upcasting
		assertEquals(originalEvents.get(0).reference(), newEvents.get(0).reference());
		assertEquals(originalEvents.get(1).reference(), newEvents.get(1).reference());
		assertEquals(originalEvents.get(2).reference(), newEvents.get(2).reference());
		
		
		
		// verify reading the raw stream still shows all historical details 
		EventStream<?> rawStream = eventStore.getEventStream(streamId);
		List<? extends Event<?>> rawEvents = rawStream.query(EventQuery.matchAll()).toList();
		
		assertEquals(6, rawEvents.size());

		assertEquals(EventType.ofType("CustomerRegistered"), rawEvents.get(0).type());
		assertEquals(EventType.ofType("CustomerRegistered"), rawEvents.get(0).storedType());
		assertTrue(rawEvents.get(0).data().toString().contains("John"));
		assertEquals(1, rawEvents.get(0).reference().position());
		
		assertEquals(EventType.ofType("CustomerNameChanged"), rawEvents.get(1).type());
		assertEquals(EventType.ofType("CustomerNameChanged"), rawEvents.get(1).storedType());
		assertTrue(rawEvents.get(1).data().toString().contains("Jane"));
		assertEquals(2, rawEvents.get(1).reference().position());

		assertEquals(EventType.ofType("CustomerChurned"), rawEvents.get(2).type());
		assertEquals(EventType.ofType("CustomerChurned"), rawEvents.get(2).storedType());
		assertEquals(3, rawEvents.get(2).reference().position());

		assertEquals(EventType.ofType("CustomerRegisteredV2"), rawEvents.get(3).type());
		assertEquals(EventType.ofType("CustomerRegisteredV2"), rawEvents.get(3).storedType());
		assertTrue(rawEvents.get(3).data().toString().contains("Superman"));
		assertEquals(4, rawEvents.get(3).reference().position());
		
		assertEquals(EventType.ofType("CustomerRenamed"), rawEvents.get(4).type());
		assertEquals(EventType.ofType("CustomerRenamed"), rawEvents.get(4).storedType());
		assertTrue(rawEvents.get(4).data().toString().contains("Batman"));
		assertEquals(5, rawEvents.get(4).reference().position());

		assertEquals(EventType.ofType("CustomerChurned"), rawEvents.get(5).type());
		assertEquals(EventType.ofType("CustomerChurned"), rawEvents.get(5).storedType());
		assertEquals(6, rawEvents.get(5).reference().position());
	}
	
	@Test
	void testUpcastAnnotationNotAllowedOnCurrentEventVersions ( ) {
		RuntimeException e = assertThrows(RuntimeException.class,()->eventStore.getEventStream(streamId, CustomerHistoricalEvent.CustomerNameChanged.class));
		assertEquals("Event type class org.sliceworkz.eventstore.stream.UpcastTest$CustomerHistoricalEvent$CustomerNameChanged should not be annotated as a @LegcayEvent, or moved to the legacy Event types", e.getMessage());
	}
	
	@Test
	void testUpcastRequiredOnHistoricalEventVersions ( ) {
		RuntimeException e = assertThrows(RuntimeException.class,()->eventStore.getEventStream(streamId, OriginalEvent.CustomerRegistered.class, CustomerEvent.CustomerRenamed.class));
		assertEquals("legacy Event type class org.sliceworkz.eventstore.stream.UpcastTest$CustomerEvent$CustomerRenamed should be annotated as a @LegcayEvent and configured with an Upcaster", e.getMessage());
	}
	
	
	/*
	 * The original events appended to the store
	 */
	sealed interface OriginalEvent {
		
		public record CustomerRegistered ( String name ) implements OriginalEvent { }
		
		public record CustomerNameChanged (String name ) implements OriginalEvent { }
		
		public record CustomerChurned ( ) implements OriginalEvent { }
		
	}

	
	/*
	 * Our latest and brightest event definitions
	 */
	sealed interface CustomerEvent {
		
		// this one changes to much that we consider it a new version
		public record CustomerRegisteredV2 ( Name name ) implements CustomerEvent { }
		
		// this one is renamed from "CustomerNameChanged"		
		public record CustomerRenamed ( Name name ) implements CustomerEvent { }
		
		public record CustomerChurned ( ) implements CustomerEvent { }
		
		
		public record Name ( String value ) {
			
			public Name ( String value ) {
				if ( value == null || value.strip().length() == 0 ) {
					throw new IllegalArgumentException();
				}
				this.value = value;
			}
		
			public static Name of ( String value ) {
				if ( value != null ) {
					if ( value.length() < 3 || value.length() > 20 ) {
						throw new IllegalArgumentException("name length must be between 3 and 20");
					}
				}
				return new Name(value);
			}
		}
		
	}

	/*
	 * Deprecated historical event definitions, needed to deserialization, but will be upcasted
	 */
	sealed interface CustomerHistoricalEvent {
		
		@LegacyEvent(upcast=CustomerRegisteredUpcaster.class)
		public record CustomerRegistered ( String name ) implements CustomerHistoricalEvent { }
		
		@LegacyEvent(upcast=CustomerNameChangedUpcaster.class)
		public record CustomerNameChanged (String name ) implements CustomerHistoricalEvent { }
		
	}
	
	/*
	 * Our upcasters that transform the legacy events to current event definitions
	 */
	
	public static class CustomerRegisteredUpcaster implements Upcast<CustomerHistoricalEvent.CustomerRegistered, CustomerEvent.CustomerRegisteredV2> {

		@Override
		public CustomerEvent.CustomerRegisteredV2 upcast(CustomerHistoricalEvent.CustomerRegistered historicalEvent) {
			// using the constructor, not the "of" utility method to allow historical values that don't adhere to the new length business rules
			return new CustomerEvent.CustomerRegisteredV2(new CustomerEvent.Name(historicalEvent.name()));
		}

		@Override
		public Class<CustomerRegisteredV2> targetType() {
			return CustomerRegisteredV2.class;
		}
		
	}
	
	public static class CustomerNameChangedUpcaster implements Upcast<CustomerHistoricalEvent.CustomerNameChanged, CustomerEvent.CustomerRenamed> {

		@Override
		public CustomerEvent.CustomerRenamed upcast(CustomerHistoricalEvent.CustomerNameChanged historicalEvent) {
			// using the constructor, not the "of" utility method to allow historical values that don't adhere to the new length business rules
			return new CustomerEvent.CustomerRenamed(new CustomerEvent.Name(historicalEvent.name()));
		}
		
		@Override
		public Class<CustomerRenamed> targetType() {
			return CustomerRenamed.class;
		}
		

	}

}
