/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025-2026 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventstore.testing.tck.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.sliceworkz.eventstore.events.EphemeralEvent;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventSerializationException;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.TagRequirement;
import org.sliceworkz.eventstore.events.Tagged;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * An event that states its own tags ({@link Tagged}) is stored with them merged into the tags the caller
 * passed, and an append is refused, whole, when the merged tags do not meet the requirements the event
 * declares.
 * <p>
 * These scenarios pin, per backend, what the stored event carries and that a refused batch stores
 * nothing — the two things a consistency boundary over a stated tag relies on.
 */
public class TaggedEventTest extends AbstractEventStoreTest {

	public sealed interface BankEvent {

		/** About two accounts, each also under the key of its role; the channel is not in the payload. */
		record MoneyTransferred ( String transferId, String from, String to, long amount ) implements BankEvent, Tagged {
			@Override
			public Tags eventTags ( ) {
				return Tags.of(Tag.of("transfer", transferId), Tag.of("account", from), Tag.of("account", to),
						Tag.of("from-account", from), Tag.of("to-account", to));
			}

			@Override
			public Set<TagRequirement> tagRequirements ( ) {
				return Set.of(TagRequirement.of("transfer").exactlyOne(),
						TagRequirement.of("account").between(1, 2),
						TagRequirement.of("from-account").exactlyOne(),
						TagRequirement.of("to-account").exactlyOne(),
						TagRequirement.of("channel").exactlyOne());
			}
		}

		/** A transfer that insists on two distinct accounts. */
		record StrictTransfer ( String from, String to ) implements BankEvent, Tagged {
			@Override
			public Tags eventTags ( ) {
				return Tags.of(Tag.of("account", from), Tag.of("account", to));
			}

			@Override
			public Set<TagRequirement> tagRequirements ( ) {
				return Set.of(TagRequirement.of("account").exactly(2));
			}
		}

		record MoneyDeposited ( String account, long amount ) implements BankEvent, Tagged {
			@Override
			public Tags eventTags ( ) {
				return Tags.of(Tag.of("account", account));
			}
		}

		record AccountNamed ( String account, String name ) implements BankEvent, Tagged {
			@Override
			public Tags eventTags ( ) {
				// Tag.of(key, null) is a legal flag tag, so a null id has to be refused explicitly
				return Tags.of(Tag.of("account", Objects.requireNonNull(account, "account")));
			}
		}

		record Untaggable ( String id ) implements BankEvent, Tagged {
			@Override
			public Tags eventTags ( ) {
				return null;
			}
		}

		record TwiceRequired ( String id ) implements BankEvent, Tagged {
			@Override
			public Tags eventTags ( ) {
				return Tags.of(Tag.of("account", id));
			}

			@Override
			public Set<TagRequirement> tagRequirements ( ) {
				return Set.of(TagRequirement.of("account"), TagRequirement.of("account").exactlyOne());
			}
		}

		record Plain ( String id ) implements BankEvent { }
	}

	private final EventStreamId bank = EventStreamId.forContext("bank").withPurpose("tagged");

	private EventStream<BankEvent> stream ( ) {
		return eventStore().getEventStream(bank, BankEvent.class);
	}

	private List<Event<BankEvent>> all ( ) {
		return stream().query(EventFilter.matchAll());
	}

	@ForEachBackend
	void theStatedTagsAreMergedWithThePassedOnes ( ) {
		List<Event<BankEvent>> appended = stream().append(Event.of(new BankEvent.MoneyTransferred("t1", "A", "B", 100),
				Tags.of(Tag.of("channel", "atm"), Tag.of("account", "A"))));

		Tags expected = Tags.of(Tag.of("transfer", "t1"), Tag.of("account", "A"), Tag.of("account", "B"),
				Tag.of("from-account", "A"), Tag.of("to-account", "B"), Tag.of("channel", "atm"));
		// what append returns is what was stored
		assertEquals(expected, appended.getFirst().tags());
		assertEquals(expected, all().getFirst().tags());
	}

	@ForEachBackend
	void anEventThatIsNotTaggedKeepsTheTagsItWasGiven ( ) {
		stream().append(Event.of(new BankEvent.Plain("p1"), Tags.of("plain", "p1")));

		assertEquals(Tags.of("plain", "p1"), all().getFirst().tags());
	}

	@ForEachBackend
	void aStatedTagIsFoundByATagQuery ( ) {
		stream().append(Event.of(new BankEvent.MoneyTransferred("t1", "A", "B", 100), Tags.of("channel", "atm")));
		stream().append(Event.of(new BankEvent.MoneyDeposited("B", 5), Tags.none()));

		assertEquals(2, stream().query(EventFilter.forTags("account", "B")).size());
		assertEquals(0, stream().query(EventFilter.forTags("from-account", "B")).size());
		assertEquals(1, stream().query(EventFilter.forTags("to-account", "B")).size());
	}

	@ForEachBackend
	void aRequiredTagTheCallerDidNotPassRefusesTheAppend ( ) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> stream().append(Event.of(new BankEvent.MoneyTransferred("t1", "A", "B", 100), Tags.none())));

		assertTrue(refused.getMessage().contains("MoneyTransferred"), refused.getMessage());
		assertTrue(refused.getMessage().contains("'channel' tags required: exactly 1, found 0"), refused.getMessage());
		assertTrue(all().isEmpty());
	}

	@ForEachBackend
	void aTagTooManyRefusesTheAppend ( ) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> stream().append(Event.of(new BankEvent.MoneyTransferred("t1", "A", "B", 100),
						Tags.of(Tag.of("channel", "atm"), Tag.of("account", "C")))));

		assertTrue(refused.getMessage().contains("'account' tags required: between 1 and 2, found 3 [account:A, account:B, account:C]"),
				refused.getMessage());
		assertTrue(all().isEmpty());
	}

	@ForEachBackend
	void everyUnmetRequirementIsNamed ( ) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> stream().append(Event.of(new BankEvent.MoneyTransferred("t1", "A", "B", 100),
						Tags.of(Tag.of("account", "C"), Tag.of("channel", "atm"), Tag.of("channel", "web")))));

		assertTrue(refused.getMessage().contains("'account' tags required"), refused.getMessage());
		assertTrue(refused.getMessage().contains("'channel' tags required"), refused.getMessage());
	}

	@ForEachBackend
	void aCountIsOfDistinctTags ( ) {
		// a transfer from an account to itself carries one account tag, but both role tags
		List<Event<BankEvent>> appended = stream().append(Event.of(new BankEvent.MoneyTransferred("t1", "A", "A", 100), Tags.of("channel", "atm")));
		assertEquals(Set.of(Tag.of("account", "A")), appended.getFirst().tags().tags("account"));
		assertEquals(Set.of(Tag.of("from-account", "A")), appended.getFirst().tags().tags("from-account"));
		assertEquals(Set.of(Tag.of("to-account", "A")), appended.getFirst().tags().tags("to-account"));

		// and one that requires exactly two accounts refuses it
		assertThrows(IllegalArgumentException.class, () -> stream().append(Event.of(new BankEvent.StrictTransfer("A", "A"), Tags.none())));
		stream().append(Event.of(new BankEvent.StrictTransfer("A", "B"), Tags.none()));
		assertEquals(2, all().size());
	}

	@ForEachBackend
	void oneIncompleteEventStoresNothingOfItsBatch ( ) {
		List<EphemeralEvent<BankEvent>> batch = List.of(
				Event.of(new BankEvent.MoneyDeposited("A", 10), Tags.none()),
				Event.of(new BankEvent.MoneyTransferred("t1", "A", "B", 5), Tags.none()));

		assertThrows(IllegalArgumentException.class, () -> stream().append(batch));
		assertTrue(all().isEmpty());
	}

	@ForEachBackend
	void eventTagsThatThrowFailTheAppendAsTheEventsSerialization ( ) {
		EventSerializationException failed = assertThrows(EventSerializationException.class,
				() -> stream().append(Event.of(new BankEvent.AccountNamed(null, "Alice"), Tags.none())));

		assertEquals("AccountNamed", failed.getEventType().name());
		assertInstanceOf(NullPointerException.class, failed.getCause());
		assertTrue(all().isEmpty());
	}

	@ForEachBackend
	void eventTagsReturningNullFailTheAppend ( ) {
		EventSerializationException failed = assertThrows(EventSerializationException.class,
				() -> stream().append(Event.of(new BankEvent.Untaggable("u1"), Tags.none())));

		assertTrue(failed.getMessage().contains("Tags.none()"), failed.getMessage());
		assertTrue(all().isEmpty());
	}

	@ForEachBackend
	void twoRequirementsForOneKeyAreRefused ( ) {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> stream().append(Event.of(new BankEvent.TwiceRequired("A"), Tags.none())));

		assertTrue(refused.getMessage().contains("more than one requirement for tag 'account'"), refused.getMessage());
		assertTrue(all().isEmpty());
	}

	@ForEachBackend
	void aBoundaryOnAStatedTagSeesTheEventsThatOnlyStatedIt ( ) {
		stream().append(Event.of(new BankEvent.MoneyDeposited("A", 10), Tags.none()));

		// decide on account A, pinned at the head
		EventFilter accountA = EventFilter.forTags("account", "A");
		EventReference head = stream().head().orElse(null);
		stream().query(EventQuery.of(accountA).notAfter(head));

		// a transfer from A lands meanwhile, tagged only through its payload
		stream().append(Event.of(new BankEvent.MoneyTransferred("t1", "A", "B", 5), Tags.of("channel", "atm")));

		assertThrows(OptimisticLockingException.class,
				() -> stream().append(AppendCriteria.of(accountA, head), Event.of(new BankEvent.MoneyDeposited("A", 1), Tags.none())));
	}

	@ForEachBackend
	void aBoundaryOnARoleTagIgnoresTheOtherRole ( ) {
		stream().append(Event.of(new BankEvent.MoneyTransferred("t1", "A", "B", 5), Tags.of("channel", "atm")));

		// decide on what A sent
		EventFilter sentByA = EventFilter.forTags("from-account", "A");
		EventReference head = stream().head().orElse(null);

		// money arriving in A is no new fact for that decision
		stream().append(Event.of(new BankEvent.MoneyTransferred("t2", "C", "A", 7), Tags.of("channel", "web")));

		stream().append(AppendCriteria.of(sentByA, head), Event.of(new BankEvent.MoneyTransferred("t3", "A", "D", 1), Tags.of("channel", "atm")));
		assertEquals(3, all().size());
	}

	@ForEachBackend
	void theStatedTagsAreNotPartOfThePayload ( ) {
		stream().append(Event.of(new BankEvent.MoneyTransferred("t1", "A", "B", 100), Tags.of("channel", "atm")));

		EventSource<String> raw = eventStore().getRawEventStream(bank);
		String payload = raw.query(EventFilter.matchAll()).getFirst().data();
		assertFalse(payload.contains("eventTags"), payload);
		assertFalse(payload.contains("tagRequirements"), payload);
	}

}
