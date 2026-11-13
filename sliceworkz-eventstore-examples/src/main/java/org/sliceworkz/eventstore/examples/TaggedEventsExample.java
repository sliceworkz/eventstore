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
package org.sliceworkz.eventstore.examples;

import java.util.Objects;
import java.util.Set;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.TagRequirement;
import org.sliceworkz.eventstore.events.Tagged;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.examples.TaggedEventsExample.BankEvent.MoneyDeposited;
import org.sliceworkz.eventstore.examples.TaggedEventsExample.BankEvent.MoneyTransferred;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Events that state their own tags: the store merges them with the tags passed on append, and refuses
 * an append that does not carry the tags an event requires.
 */
public class TaggedEventsExample {

	public static void main ( String[] args ) {

		EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();
		EventStream<BankEvent> stream = eventstore.getEventStream(EventStreamId.forContext("bank"), BankEvent.class);

		// the transfer states its transfer and both accounts, each also under the key of its role;
		// the channel is not in the payload, so the caller passes it
		stream.append(Event.of(new MoneyTransferred("t1", "A", "B", 100), Tags.of("channel", "atm")));
		stream.append(Event.of(new MoneyDeposited("A", 50), Tags.none()));

		// everything that happened to account A, whatever its role
		stream.query(EventFilter.forTags("account", "A")).forEach(System.out::println);

		// only what account A sent: a decision about A's outgoing limit reads this, so money arriving in A
		// does not conflict with it
		EventFilter sentByA = EventFilter.forTags("from-account", "A");
		EventReference head = stream.head().orElse(null);
		stream.append(Event.of(new MoneyDeposited("A", 10), Tags.none()));
		stream.append(AppendCriteria.of(sentByA, head), Event.of(new MoneyTransferred("t2", "A", "C", 20), Tags.of("channel", "web")));

		// forgetting the channel is refused, and nothing is stored
		try {
			stream.append(Event.of(new MoneyTransferred("t3", "A", "C", 5), Tags.none()));
		} catch ( IllegalArgumentException e ) {
			System.out.println(e.getMessage());
		}
	}

	sealed interface BankEvent {

		record MoneyTransferred ( String transferId, String from, String to, long amount ) implements BankEvent, Tagged {

			@Override
			public Tags eventTags ( ) {
				return Tags.of(Tag.of("transfer", Objects.requireNonNull(transferId)),
						Tag.of("account", Objects.requireNonNull(from)), Tag.of("account", Objects.requireNonNull(to)),
						Tag.of("from-account", from), Tag.of("to-account", to));
			}

			@Override
			public Set<TagRequirement> tagRequirements ( ) {
				return Set.of(TagRequirement.of("account").between(1, 2),   // one for a transfer to the same account
						TagRequirement.of("channel").exactlyOne());
			}
		}

		record MoneyDeposited ( String account, long amount ) implements BankEvent, Tagged {

			@Override
			public Tags eventTags ( ) {
				return Tags.of(Tag.of("account", Objects.requireNonNull(account)));
			}
		}

	}

}
