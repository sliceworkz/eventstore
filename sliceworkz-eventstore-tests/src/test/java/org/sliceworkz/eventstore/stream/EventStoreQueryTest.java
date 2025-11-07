package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorageImpl;
import org.sliceworkz.eventstore.mock.MockDomainEvent;
import org.sliceworkz.eventstore.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.EventStoreQueryTest.BankDomainEvent.AccountClosed;
import org.sliceworkz.eventstore.stream.EventStoreQueryTest.BankDomainEvent.AccountOpened;
import org.sliceworkz.eventstore.stream.EventStoreQueryTest.BankDomainEvent.MoneyDeposited;
import org.sliceworkz.eventstore.stream.EventStoreQueryTest.BankDomainEvent.MoneyTransfered;
import org.sliceworkz.eventstore.stream.EventStoreQueryTest.BankDomainEvent.MoneyWithdrawn;

public class EventStoreQueryTest {
	
	private EventStorage eventStorage;
	private EventStore eventStore;
	
	EventStreamId eventStreamId;
	EventStream<BankDomainEvent> eventStream;
	
	EventStreamId otherPurposeEventStreamId;
	EventStream<Object> otherPurposeEventStream; 

	EventStreamId otherEventStreamId;
	EventStream<MockDomainEvent> otherEventStream; 

	EventStream<Object> allEventsStream; 

	
	@BeforeEach
	public void setUp ( ) {
		this.eventStorage = createEventStorage();
		this.eventStore = EventStoreFactory.get().eventStore(eventStorage);

		this.eventStreamId = EventStreamId.forContext("app").withPurpose("domain");
		this.eventStream = eventStore.getEventStream(eventStreamId, BankDomainEvent.class);
		
		this.otherPurposeEventStreamId = EventStreamId.forContext("app").withPurpose("performance-logging");
		this.otherPurposeEventStream = eventStore.getEventStream(otherPurposeEventStreamId, PerformanceReportEvent.class);

		this.otherEventStreamId = EventStreamId.forContext("otherApp").withPurpose("domain");
		this.otherEventStream = eventStore.getEventStream(otherEventStreamId, MockDomainEvent.class);
		
		Set<Class<?>> rootEventClasses = new HashSet<>();
		rootEventClasses.add(BankDomainEvent.class);
		rootEventClasses.add(PerformanceReportEvent.class);
		rootEventClasses.add(MockDomainEvent.class);
		
		this.allEventsStream = eventStore.getEventStream(EventStreamId.anyContext().anyPurpose(), rootEventClasses);

		storeTestEvents();
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
	
	private void storeTestEvents ( ) {
		AppendCriteria ac = AppendCriteria.none();
		eventStream.append(ac, Collections.singletonList(accountOpened("1")));
		eventStream.append(ac, Collections.singletonList(accountOpened("2")));
		eventStream.append(ac, Collections.singletonList(accountOpened("3")));
		eventStream.append(ac, Collections.singletonList(moneyDeposited("1", 800)));
		eventStream.append(ac, Collections.singletonList(moneyDeposited("2", 200)));
		otherEventStream.append(ac, Collections.singletonList(someRandomOtherApplicationsEvent()));
		eventStream.append(ac, Collections.singletonList(moneyWithdrawn("1", 100)));
		eventStream.append(ac, Collections.singletonList(moneyTransfered("1", "2", 200)));
		otherPurposeEventStream.append(ac, Collections.singletonList(Event.of(new PerformanceReportEvent("test"), Tags.parse("account:1") )));
		eventStream.append(ac, Collections.singletonList(moneyTransfered("1", "3", 50)));
		eventStream.append(ac, Collections.singletonList(accountClosed("2")));
	}
	
	List<Event<Object>> allEvents ( ) {
		return allEventsStream.query(EventQuery.matchAll()).toList();
	}
	
	@Test
	void testCountAllInStorage ( ) {
		assertEquals(11, allEvents().size());
	}

	@Test
	void testCountMatchAll ( ) {
		query(EventQuery.matchAll()).map(Object::toString).forEach(System.out::println);
		assertEquals(9, query(EventQuery.matchAll()).count());
	}

	@Test
	void testCountMatchAllUntil ( ) {
		query(EventQuery.matchAll()).map(Object::toString).forEach(System.out::println);
		
		EventReference until = allEvents().get(0).reference();
		assertEquals(1, query(EventQuery.matchAll().until(until)).count()); // index 0, is in our stream

		until = allEvents().get(1).reference();
		assertEquals(2, query(EventQuery.matchAll().until(until)).count()); // index 0 and index 1, both in our stream

		until = allEvents().get(5).reference();
		assertEquals(5, query(EventQuery.matchAll().until(until)).count()); // without index 5, which is not in our stream

		until = allEvents().get(6).reference();
		assertEquals(6, query(EventQuery.matchAll().until(until)).count()); // without index 5, which is not in our stream

		until = allEvents().get(7).reference();
		assertEquals(7, query(EventQuery.matchAll().until(until)).count()); // without index 5, which is not in our stream

		until = allEvents().get(9).reference();
		assertEquals(8, query(EventQuery.matchAll().until(until)).count()); // without index 5 and 8, which are not in our stream

	}

	@Test
	void testCountMatchNone ( ) {
		assertEquals(0, query(EventQuery.matchNone()).count());
	}

	@Test
	void testCountMatchByType ( ) {
		assertEquals(3, query(EventQuery.forEvents(EventTypesFilter.of(AccountOpened.class), Tags.none())).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.of(MoneyDeposited.class), Tags.none())).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.of(MoneyWithdrawn.class), Tags.none())).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.of(MoneyTransfered.class), Tags.none())).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.of(AccountClosed.class), Tags.none())).count());

		assertEquals(4, query(EventQuery.forEvents(EventTypesFilter.of(AccountOpened.class, AccountClosed.class), Tags.none())).count());
		assertEquals(3, query(EventQuery.forEvents(EventTypesFilter.of(MoneyDeposited.class, MoneyWithdrawn.class), Tags.none())).count());
		assertEquals(9, query(EventQuery.forEvents(EventTypesFilter.of(AccountOpened.class, AccountClosed.class, MoneyDeposited.class, MoneyWithdrawn.class, MoneyTransfered.class), Tags.none())).count());

		// should find another app's events!
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())).count());
		assertEquals(1, queryOther(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none())));
	}

	@Test
	void testCountMatchCombinedQueryWithoutOverlap ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(AccountOpened.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(MoneyDeposited.class), Tags.none());
		
		EventQuery q = q1.combineWith(q2);
		
		assertEquals(3, query(q1).count());
		assertEquals(2, query(q2).count());
		assertEquals(5, query(q).count());
	}

	@Test
	void testCountMatchCombinedQueryWithOverlap ( ) {
		EventQuery q1 = EventQuery.forEvents(EventTypesFilter.of(AccountOpened.class, AccountClosed.class), Tags.none());
		EventQuery q2 = EventQuery.forEvents(EventTypesFilter.of(MoneyDeposited.class, AccountClosed.class), Tags.none());
		
		EventQuery q = q1.combineWith(q2);
		
		assertEquals(4, query(q1).count());
		assertEquals(3, query(q2).count());
		assertEquals(6, query(q).count());
	}

	@Test
	void testCountMatchByTag ( ) {
		assertEquals(5, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).count());
		assertEquals(allEvents().get(0).reference(), query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(0).reference());
		assertEquals(allEvents().get(3).reference(), query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(1).reference());
		assertEquals(allEvents().get(6).reference(), query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(2).reference());
		assertEquals(allEvents().get(7).reference(), query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(3).reference());
		assertEquals(allEvents().get(9).reference(), query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(4).reference());

		// first one on account:1 has position 1
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).findFirst().get().reference().position());

		// first one on account:2 has position 2
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:2"))).findFirst().get().reference().position());

		// first one on account:3 has position 3
		assertEquals(3, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:3"))).findFirst().get().reference().position());


		
		assertEquals(5, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).count());
		assertEquals(3, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(3)).count());
		assertEquals(allEvents().get(9).reference(), queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(0).reference());
		assertEquals(allEvents().get(7).reference(), queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(1).reference());
		assertEquals(allEvents().get(6).reference(), queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(2).reference());
		assertEquals(allEvents().get(3).reference(), queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(3).reference());
		assertEquals(allEvents().get(0).reference(), queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).toList().get(4).reference());
		
		// last one on account:1
		assertEquals(10, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1"))).findFirst().get().reference().position());

		// last one on account:2
		assertEquals(11, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:2"))).findFirst().get().reference().position());

		// last one on account:3
		assertEquals(10, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:3"))).findFirst().get().reference().position());

		
		assertEquals(3, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(3)).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(1)).count());
		
		// after certain event
		assertEquals(4, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(0)).count());
		assertEquals(4, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(1)).count());
		assertEquals(4, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(2)).count());
		assertEquals(3, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(3)).count());
		assertEquals(3, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(4)).count());
		assertEquals(3, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(5)).count()); // TODO is this OK, as this event is not in the stream.  should "after" work with an event reference on an event that is not in the stream??
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(6)).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(7)).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(8)).count()); // TODO same question
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(9)).count());
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(10)).count());

		// before certain event (backwards)
		assertEquals(0, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(0)).count());
		assertEquals(1, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(1)).count());
		assertEquals(1, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(2)).count());
		assertEquals(1, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(3)).count());
		assertEquals(2, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(4)).count());
		assertEquals(2, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(5)).count()); // TODO is this OK, as this event is not in the stream.  should "after" work with an event reference on an event that is not in the stream??
		assertEquals(2, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(6)).count());
		assertEquals(3, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(7)).count());
		assertEquals(4, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(8)).count()); // TODO same question
		assertEquals(4, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(9)).count());
		assertEquals(5, queryReversed(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.none(), allEvents().get(10)).count());

		
		// after certain event and limit count
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(0)).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(1)).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(2)).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(3)).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(4)).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(5)).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(6)).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(7)).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(8)).count());
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(9)).count());
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1")), Limit.to(2), allEvents().get(10)).count());

		
		assertEquals(4, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:2"))).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:2")), Limit.to(2)).count());
		
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:3"))).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1", "account:2"))).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:1", "account:3"))).count());
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("account:2", "account:3"))).count());
		assertEquals(2, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("from-account:1"))).count());
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("from-account:2"))).count());
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("from-account:3"))).count());
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("to-account:1"))).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("to-account:2"))).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("to-account:3"))).count());

		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("to-account:3", "account:3", "from-account:1", "account:1"))).count());
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("to-account:3", "to-account:3", "to-account:3", "to-account:3", "account:3", "from-account:1", "account:1"))).count());

		// should find another app's events!
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("otherapp:tag"))).count());
		assertEquals(1, queryOther(EventQuery.forEvents(EventTypesFilter.any(), Tags.parse("otherapp:tag"))));
	}
	
	@Test
	void testCountMatchByTypeAndTags ( ) {
		assertEquals(1, query(EventQuery.forEvents(EventTypesFilter.of(MoneyTransfered.class), Tags.parse("to-account:3", "account:3", "from-account:1", "account:1"))).count());
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.of(MoneyWithdrawn.class), Tags.parse("to-account:3", "account:3", "from-account:1", "account:1"))).count());

		// should find another app's events!
		assertEquals(0, query(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.parse("otherapp:tag"))).count());
		assertEquals(1, queryOther(EventQuery.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.parse("otherapp:tag"))));
	}

	private Stream<Event<Object>> query ( EventQuery eventQuery ) {
		return query(eventQuery, null, null);
	}
	private Stream<Event<Object>> query ( EventQuery eventQuery, Limit limit ) {
		return query(eventQuery, limit, null);
	}
	private Stream<Event<Object>> query ( EventQuery eventQuery, Limit limit, Event<?> after ) {
		return eventStore.getEventStream(EventStreamId.forContext("app").withPurpose("domain"), BankDomainEvent.class).query(eventQuery, after==null?null:after.reference(), limit);
	}
	
	private Stream<Event<Object>> queryReversed ( EventQuery eventQuery ) {
		return queryReversed(eventQuery, null, null);
	}
	private Stream<Event<Object>> queryReversed ( EventQuery eventQuery, Limit limit ) {
		return queryReversed(eventQuery, limit, null);
	}
	private Stream<Event<Object>> queryReversed ( EventQuery eventQuery, Limit limit, Event<?> after ) {
		return eventStore.getEventStream(EventStreamId.forContext("app").withPurpose("domain"), BankDomainEvent.class).queryBackwards(eventQuery, after==null?null:after.reference(), limit);
	}

	private long queryOther ( EventQuery eventQuery ) {
		return eventStore.getEventStream(EventStreamId.forContext("otherApp").withPurpose("domain")).query(eventQuery).count();
	}

	public sealed interface BankDomainEvent {
		public record AccountOpened ( AccountId account ) implements BankDomainEvent { }
		public record AccountClosed ( AccountId account ) implements BankDomainEvent { }
		public record MoneyDeposited ( AccountId account, BigDecimal amount ) implements BankDomainEvent { }
		public record MoneyWithdrawn ( AccountId account, BigDecimal amount ) implements BankDomainEvent { }
		public record MoneyTransfered ( AccountId fromAccount, AccountId toAccount, BigDecimal amount ) implements BankDomainEvent { }
	}
	
	EphemeralEvent<BankDomainEvent> accountOpened ( String accountId ) {
		AccountOpened d = new AccountOpened(AccountId.of(accountId));
		return Event.of(d, Tags.parse("account:" + d.account.value()));
	}

	EphemeralEvent<BankDomainEvent> accountClosed ( String accountId ) {
		AccountClosed d = new AccountClosed(AccountId.of(accountId));
		return Event.of(d, Tags.parse("account:" + d.account.value()));
	}

	EphemeralEvent<BankDomainEvent> moneyDeposited ( String accountId, int amount ) {
		MoneyDeposited d = new MoneyDeposited(AccountId.of(accountId), BigDecimal.valueOf(amount));
		return Event.of(d, Tags.parse("account:" + d.account.value()));
	}

	EphemeralEvent<BankDomainEvent> moneyWithdrawn ( String accountId, int amount ) {
		MoneyWithdrawn d = new MoneyWithdrawn(AccountId.of(accountId),  BigDecimal.valueOf(amount));
		return Event.of(d, Tags.parse("account:" + d.account.value()));
	}

	EphemeralEvent<BankDomainEvent> moneyTransfered ( String fromAccountId, String toAccountId, int amount ) {
		MoneyTransfered d = new MoneyTransfered(AccountId.of(fromAccountId), AccountId.of(toAccountId), BigDecimal.valueOf(amount));
		return Event.of(d, Tags.parse("account:" + d.fromAccount.value(), "from-account:" + d.fromAccount.value(), "account:" + d.toAccount.value(), "to-account:" + d.toAccount().value()));
	}

	EphemeralEvent<MockDomainEvent> someRandomOtherApplicationsEvent ( ) {
		FirstDomainEvent d = new MockDomainEvent.FirstDomainEvent("some random event");
	    // voluntarily using tag that overlaps with one in our application
		return Event.of(d, Tags.parse("account:3", "otherapp:tag"));
	}
	
	record PerformanceReportEvent ( String value ) { }
	
	record AccountId ( String value ) {
		public static AccountId of ( String value ) {
			return new AccountId(value);
		}
	}

}
