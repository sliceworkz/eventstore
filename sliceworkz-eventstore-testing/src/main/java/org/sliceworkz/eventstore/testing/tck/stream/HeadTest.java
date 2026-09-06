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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingException;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.FirstDomainEvent;
import org.sliceworkz.eventstore.testing.tck.mock.MockDomainEvent.SecondDomainEvent;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastMultiTest.CurrentEvent;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastMultiTest.LegacyEvents;
import org.sliceworkz.eventstore.testing.tck.stream.UpcastMultiTest.OriginalEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * {@link org.sliceworkz.eventstore.stream.EventSource#head()}: the reference of the newest stored
 * event of a stream, answered without reading the event.
 * <p>
 * The head exists for one job — pinning a consistency boundary <em>before</em> a decision is read,
 * so that every read can be bounded to it and the same reference handed to {@link AppendCriteria}.
 * That makes three properties load-bearing, and this scenario holds every backend to them:
 * <ul>
 *   <li><b>It is what a query would see</b>, not what has been committed. Everything a later query
 *       sees that a query taken with the head could not is strictly after the head in the total
 *       order — which is what makes the head a sound {@code until} for reads and a sound expected
 *       reference for the optimistic-locking check, whatever the type of the event at the head.</li>
 *   <li><b>It never deserializes, upcasts or decrypts.</b> A head this stream cannot map, one that
 *       upcasts into nothing, or one holding a {@code Shreddable} under a key store that is down
 *       cannot make it fail or lie. The typed {@code backwards().limit(1)} idiom fails on all three,
 *       which is why the head is a method and not a convenience wrapper around a query.</li>
 *   <li><b>It names a stored event, whole.</b> Its {@code index} is 0, and an {@code until} at the
 *       head includes every event the stored event upcasts into — {@code until} bounds stored events,
 *       never a fragment of one.</li>
 * </ul>
 */
public class HeadTest extends AbstractEventStoreTest {

	private final EventStreamId streamId = EventStreamId.forContext("app").withPurpose("head");

	private static final EventFilter FIRSTS = EventFilter.forEvents(EventTypesFilter.of(FirstDomainEvent.class), Tags.none());

	private EventStream<MockDomainEvent> stream ( ) {
		return eventStore().getEventStream(streamId, MockDomainEvent.class);
	}

	private Event<MockDomainEvent> append ( EventStream<MockDomainEvent> stream, MockDomainEvent event ) {
		return stream.append(AppendCriteria.none(), Event.of(event, Tags.none())).getFirst();
	}

	private static List<EventReference> references ( Stream<? extends Event<?>> events ) {
		return events.map(e -> e.reference()).toList();
	}

	// --- what the head is -----------------------------------------------------------------------

	@ForEachBackend
	void anEmptyStreamHasNoHead ( ) {
		assertTrue(stream().head().isEmpty(), "an empty stream has no head");
		assertTrue(eventStorage().head(Optional.of(streamId)).isEmpty(), "an empty stream has no head at the SPI either");
		assertTrue(eventStorage().head(Optional.empty()).isEmpty(), "an empty storage has no head");
	}

	@ForEachBackend
	void theHeadIsTheNewestStoredEvent ( ) {
		EventStream<MockDomainEvent> stream = stream();

		Event<MockDomainEvent> a = append(stream, new FirstDomainEvent("a"));
		assertEquals(Optional.of(a.reference()), stream.head());

		append(stream, new SecondDomainEvent("b"));
		Event<MockDomainEvent> c = append(stream, new FirstDomainEvent("c"));

		assertEquals(Optional.of(c.reference()), stream.head(), "the head must move with every append");
		assertEquals(stream.query(EventQuery.matchAll()).toList().getLast().reference(), stream.head().orElseThrow(),
				"the head is the last event a full read returns");
		assertEquals(stream.head(), eventStorage().head(Optional.of(streamId)),
				"the stream and the SPI must agree on the head");
	}

	@ForEachBackend
	void theHeadIsScopedLikeAQuery ( ) {
		EventStream<MockDomainEvent> mine = stream();
		EventStream<MockDomainEvent> sibling = eventStore().getEventStream(streamId.withPurpose("other"), MockDomainEvent.class);
		EventStream<MockDomainEvent> elsewhere = eventStore().getEventStream(EventStreamId.forContext("elsewhere"), MockDomainEvent.class);

		Event<MockDomainEvent> a = append(mine, new FirstDomainEvent("a"));
		Event<MockDomainEvent> b = append(sibling, new FirstDomainEvent("b"));
		Event<MockDomainEvent> c = append(elsewhere, new FirstDomainEvent("c"));

		assertEquals(Optional.of(a.reference()), mine.head(), "another purpose's append must not move this stream's head");
		assertEquals(Optional.of(b.reference()), sibling.head());
		assertEquals(Optional.of(b.reference()),
				eventStore().<MockDomainEvent>getEventStream(streamId.anyPurpose(), MockDomainEvent.class).head(),
				"a wildcard purpose answers the newest event across the context's purposes");
		assertEquals(Optional.of(c.reference()),
				eventStore().<MockDomainEvent>getEventStream(EventStreamId.anyContext().anyPurpose(), MockDomainEvent.class).head(),
				"the wildcard stream answers the storage-wide head");
		assertEquals(Optional.of(c.reference()), eventStorage().head(Optional.empty()),
				"no stream at the SPI means the storage-wide head");
	}

	// --- the property a consistency boundary is built on ------------------------------------------

	@ForEachBackend
	void aHeadTakenBeforeAnAppendIsBeforeIt ( ) {
		EventStream<MockDomainEvent> stream = stream();
		Event<MockDomainEvent> a = append(stream, new FirstDomainEvent("a"));

		EventReference head = stream.head().orElseThrow();

		Event<MockDomainEvent> b = append(stream, new FirstDomainEvent("b"));

		assertTrue(b.reference().happenedAfter(head), "an event appended after the head was taken must sort after it");
		assertEquals(List.of(a.reference()), references(stream.query(EventQuery.matchAll().until(head))),
				"a read bounded at the head must see exactly what was there when it was taken");
		assertEquals(List.of(a.reference()), references(stream.query(EventQuery.matchAll().until(head).backwards())),
				"in either direction");
	}

	/**
	 * The intended use: pin the head, decide, append with the head as the expected reference. The
	 * event at the head need not match the boundary's filter — the reference is a cursor for the
	 * check, and only matching events after it count.
	 */
	@ForEachBackend
	void theHeadIsASoundExpectedReferenceWhateverItsType ( ) {
		EventStream<MockDomainEvent> stream = stream();
		append(stream, new FirstDomainEvent("f0"));
		Event<MockDomainEvent> unrelated = append(stream, new SecondDomainEvent("s0"));

		// the head is an event outside the boundary; nothing inside it happened after: admitted
		EventReference head = stream.head().orElseThrow();
		assertEquals(unrelated.reference(), head, "fixture: the head is an event the filter does not match");
		assertEquals(1, stream.append(AppendCriteria.of(FIRSTS, head), Event.of(new FirstDomainEvent("mine"), Tags.none())).size());

		// a matching fact landed after the head: rejected
		EventReference stale = stream.head().orElseThrow();
		append(stream, new FirstDomainEvent("concurrent"));
		assertThrows(OptimisticLockingException.class,
				() -> stream.append(AppendCriteria.of(FIRSTS, stale), Event.of(new FirstDomainEvent("mine"), Tags.none())),
				"a matching event after the head must conflict");

		// an unrelated fact landed after the head: admitted
		EventReference current = stream.head().orElseThrow();
		append(stream, new SecondDomainEvent("noise"));
		assertEquals(1, stream.append(AppendCriteria.of(FIRSTS, current), Event.of(new FirstDomainEvent("mine"), Tags.none())).size(),
				"an event the filter does not match must not conflict, wherever it sits relative to the head");
	}

	/**
	 * An absent head is an empty stream, and handed to {@link AppendCriteria} as an absent reference
	 * it means "I decided on an empty boundary" — still a boundary, so a matching event that lands in
	 * between is a conflict. A caller pinning at the head must keep an empty head empty rather than
	 * substituting some other reference.
	 */
	@ForEachBackend
	void anAbsentHeadIsAnEmptyBoundary ( ) {
		EventStream<MockDomainEvent> stream = stream();
		EventReference head = stream.head().orElse(null);

		append(stream, new FirstDomainEvent("concurrent"));

		assertThrows(OptimisticLockingException.class,
				() -> stream.append(AppendCriteria.of(FIRSTS, head), Event.of(new FirstDomainEvent("mine"), Tags.none())));
	}

	// --- a stored event, whole --------------------------------------------------------------------

	@ForEachBackend
	void theHeadNamesTheWholeStoredEventWhenItUpcastsIntoSeveral ( ) {
		EventStream<OriginalEvent> original = eventStore().getEventStream(streamId, OriginalEvent.class);
		original.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerNameChanged("Jane"), Tags.none()));
		original.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerRegisteredWithAddress("John", "123 Main St", "Springfield"), Tags.none()));

		EventStream<CurrentEvent> current = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);
		EventReference head = current.head().orElseThrow();

		// what the typed read makes of the stream: Renamed, then the split registration as two events
		List<Event<CurrentEvent>> all = current.query(EventQuery.matchAll()).toList();
		assertEquals(3, all.size(), "fixture");
		assertEquals(0, head.index(), "the head is the stored event's own reference");
		assertEquals(all.get(1).reference(), head, "the stored reference is the first event it upcasts into");
		assertEquals(head.withIndex(1), all.get(2).reference(), "fixture: the second event it upcasts into");
		assertEquals(head, eventStorage().head(Optional.of(streamId)).orElseThrow());

		// until bounds stored events: every event the head upcasts into is at or before it
		assertEquals(references(all.stream()), references(current.query(EventQuery.matchAll().until(head))),
				"an until at the head must include every event the stored event at the head upcasts into");
		assertEquals(references(all.stream()).reversed(), references(current.query(EventQuery.matchAll().until(head).backwards())));

		// and so does a projector bounded at the head
		CountingProjection projection = new CountingProjection();
		ProjectorMetrics metrics = Projector.from(current).towards(projection).build().runUntil(head);
		assertEquals(3, projection.handled, "a projector bounded at the head must handle every event the head upcasts into");
		assertEquals(3, metrics.eventsHandled());
	}

	@ForEachBackend
	void theHeadIsAnsweredWhenTheNewestStoredEventUpcastsIntoNothing ( ) {
		EventStream<OriginalEvent> original = eventStore().getEventStream(streamId, OriginalEvent.class);
		original.append(AppendCriteria.none(), Event.of(new OriginalEvent.CustomerNameChanged("Jane"), Tags.none()));
		Event<OriginalEvent> audit = original.append(AppendCriteria.none(),
				Event.of(new OriginalEvent.CustomerLegacyAuditLog("dropped on read"), Tags.none())).getFirst();

		EventStream<CurrentEvent> current = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

		// the idiom the head replaces cannot answer: reading one stored event yields no event at all
		assertEquals(0, current.query(EventQuery.matchAll().backwards().limit(1)).count(),
				"fixture: the newest stored event upcasts into nothing");

		assertEquals(Optional.of(audit.reference()), current.head(),
				"the head is the newest stored event, whatever it upcasts into");
	}

	// --- never deserializes, never decrypts -------------------------------------------------------

	sealed interface OrderEvent {
		record OrderPlaced ( String orderId ) implements OrderEvent { }
	}

	sealed interface ShippingEvent {
		record ParcelShipped ( String parcelId ) implements ShippingEvent { }
	}

	@ForEachBackend
	void theHeadNeedsNoMappingForTheTypeAtTheHead ( ) {
		EventStream<ShippingEvent> shipping = eventStore().getEventStream(streamId, ShippingEvent.class);
		Event<ShippingEvent> shipped = shipping.append(AppendCriteria.none(),
				Event.of(new ShippingEvent.ParcelShipped("p-1"), Tags.none())).getFirst();

		EventStream<OrderEvent> orders = eventStore().getEventStream(streamId, OrderEvent.class);

		assertThrows(EventDeserializationException.class, () -> orders.query(EventQuery.matchAll()).toList(),
				"fixture: this stream cannot read the event at the head");

		assertEquals(Optional.of(shipped.reference()), orders.head(),
				"the head is answered without reading the event, so its type is irrelevant");
	}

	sealed interface ProfileEvent {
		record EmailRecorded ( String customerId, Shreddable<String> email ) implements ProfileEvent { }
	}

	@ForEachBackend
	void theHeadDoesNotTouchTheKeyStore ( ) {
		FailingKeyStore keyStore = new FailingKeyStore(backend().shreddingKeyStore(eventStorage()));
		DataSubject alice = DataSubject.of("customer", "alice-42");

		EventStream<ProfileEvent> writing = eventStoreWithShredding(keyStore).getEventStream(streamId, ProfileEvent.class);
		Event<ProfileEvent> recorded = writing.append(AppendCriteria.none(),
				Event.of(new ProfileEvent.EmailRecorded("alice-42", Shreddable.of("alice@example.com", alice)), Tags.none())).getFirst();

		keyStore.failing = true;

		EventStream<ProfileEvent> reading = eventStoreWithShredding(keyStore).getEventStream(streamId, ProfileEvent.class);
		assertThrows(ShreddingException.class, () -> reading.query(EventQuery.matchAll()).toList(),
				"fixture: reading the event at the head needs the key store");

		assertEquals(Optional.of(recorded.reference()), reading.head(),
				"the head is answered without decrypting anything, so the key store is not consulted");
	}

	// --- observability ----------------------------------------------------------------------------

	@ForEachBackend
	void headLookupsAreCountedOnTheirOwnMeter ( ) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		try ( EventStore meteredStore = EventStoreFactory.get().eventStore(eventStorage(), registry) ) {
			EventStream<MockDomainEvent> meteredStream = meteredStore.getEventStream(streamId, MockDomainEvent.class);

			Counter head = registry.find("sliceworkz.eventstore.head").counter();
			assertNotNull(head, "no sliceworkz.eventstore.head counter was registered");
			assertEquals(0.0, head.count(), "the counter exists from the moment the stream does, reading 0");

			meteredStream.head();
			assertEquals(1.0, head.count());

			meteredStream.query(EventQuery.matchAll().backwards().limit(1)).count();
			assertEquals(1.0, head.count(), "a query is not a head lookup");
			assertEquals(1.0, registry.find("sliceworkz.eventstore.query").counter().count(),
					"and a head lookup is not a query");
		}
	}

	// --- fixtures ---------------------------------------------------------------------------------

	private static final class CountingProjection implements Projection<CurrentEvent> {

		private int handled;

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		public void when ( Event<CurrentEvent> event ) {
			handled++;
		}
	}

	/** A key store that can be switched off, so that "the head needs no key" is actually exercised. */
	private static final class FailingKeyStore implements ShreddingKeyStore {

		private final ShreddingKeyStore delegate;
		private volatile boolean failing;

		private FailingKeyStore ( ShreddingKeyStore delegate ) {
			this.delegate = delegate;
		}

		@Override
		public ActiveKey keyFor ( DataSubject subject ) {
			if ( failing ) {
				throw new ShreddingException("simulated key store outage");
			}
			return delegate.keyFor(subject);
		}

		@Override
		public Optional<SecretKey> resolve ( KeyId key ) {
			if ( failing ) {
				throw new ShreddingException("simulated key store outage");
			}
			return delegate.resolve(key);
		}

		@Override
		public List<KeyId> shred ( DataSubject subject, ErasureReason reason ) {
			return delegate.shred(subject, reason);
		}
	}

}
