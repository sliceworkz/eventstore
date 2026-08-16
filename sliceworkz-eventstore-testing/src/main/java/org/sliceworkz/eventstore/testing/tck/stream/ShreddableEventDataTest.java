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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.ErasureReport;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingException;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * The shredding contract every backend must satisfy: personal data is protected on append, readable
 * while its key exists, and permanently unreadable once the key is destroyed — without any event ever
 * being rewritten.
 * <p>
 * Each backend runs these against its own key store — the SQL table on PostgreSQL, the file-backed one
 * on inmem-fs, in-memory otherwise — so the key storage itself is under test, not just the codec.
 * <p>
 * Several scenarios here pin down behaviour that the previous {@code @Erasable} design got wrong, and
 * they are the reason it was replaced. They are marked as such.
 */
public class ShreddableEventDataTest extends AbstractEventStoreTest {

	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");
	private static final DataSubject BOB = DataSubject.of("customer", "bob-77");

	private static final EventStreamId STREAM = EventStreamId.forContext("payments");

	@ForEachBackend
	void aProtectedValueRoundTripsWhileItsKeyExists ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);

		TransferMade transfer = transfer();
		payments.append(AppendCriteria.none(), Event.of(transfer, Tags.of("transfer", "t-9001")));

		Event<PaymentEvent> read = payments.query(EventQuery.matchAll()).findFirst().orElseThrow();
		assertEquals(transfer, read.data());
	}

	@ForEachBackend
	void theStoredPayloadDoesNotContainThePersonalDataInTheClear ( ) {
		EventStore store = eventStoreWithShredding();
		store.getEventStream(STREAM, PaymentEvent.class)
				.append(AppendCriteria.none(), Event.of(transfer(), Tags.none()));

		// read back with no domain classes and no codec: raw mode hands back the sealed envelope as it
		// is stored, which is exactly what an import or an export sees
		String stored = eventStorage()
				.query(EventQuery.matchAll(), Optional.of(STREAM), null, org.sliceworkz.eventstore.query.Limit.none())
				.findFirst().orElseThrow().immutableData();

		assertFalse(stored.contains("Alice Martin"), "the payload still holds personal data in the clear: " + stored);
		assertFalse(stored.contains("BE68 5390 0754 7034"), "the payload still holds personal data in the clear: " + stored);
		assertTrue(stored.contains("A256GCM"), "the payload carries no sealed envelope: " + stored);
		// the transfer itself is not personal data and must stay queryable
		assertTrue(stored.contains("t-9001"), "the non-personal payload was protected too: " + stored);
	}

	@ForEachBackend
	void erasingOneSubjectLeavesTheOtherReadable ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);
		payments.append(AppendCriteria.none(), Event.of(transfer(), Tags.none()));

		ErasureReport report = store.erase(ALICE, ErasureReason.of("GDPR art.17 request #4711"));
		assertEquals(1, report.keysShredded());
		assertFalse(report.isNoop());

		TransferMade read = (TransferMade) payments.query(EventQuery.matchAll()).findFirst().orElseThrow().data();

		// This is the case no per-field annotation and no per-event key can express: one event, two data
		// subjects, one erased.
		Shreddable.Shredded<PartyDetails> from = assertInstanceOf(Shreddable.Shredded.class, read.from());
		assertEquals(ALICE, from.subject());
		assertEquals("[erased]", read.from().map(PartyDetails::name).orElse("[erased]"));

		assertEquals("Bob Jansen", read.to().map(PartyDetails::name).orElse("[erased]"));

		// and everything that is not personal data is untouched, so the ledger still reconciles
		assertEquals("t-9001", read.transferId());
		assertEquals(25000, read.cents());
		assertEquals("alice-42", read.fromCustomerId());
	}

	/**
	 * The defect that made the previous design untenable: with the payload split across two documents
	 * and reconciled by a deep merge, a collection whose elements held both personal and non-personal
	 * fields came back with the non-personal ones gone — on every ordinary read, with no erasure
	 * involved at all.
	 */
	@ForEachBackend
	void aCollectionOfProtectedValuesKeepsEveryElementAndErasesOnlyTheRightOne ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);

		DocumentSigned signed = new DocumentSigned("doc-1",
				List.of(Shreddable.of("Alice Martin", ALICE), Shreddable.of("Bob Jansen", BOB)));
		payments.append(AppendCriteria.none(), Event.of(signed, Tags.none()));

		assertEquals(signed, payments.query(EventQuery.matchAll()).findFirst().orElseThrow().data());

		store.erase(ALICE, ErasureReason.of("art.17"));

		DocumentSigned read = (DocumentSigned) payments.query(EventQuery.matchAll()).findFirst().orElseThrow().data();
		assertEquals("doc-1", read.documentId());
		assertEquals(2, read.signatories().size(), "erasure changed the size of the collection");
		assertTrue(read.signatories().get(0).isShredded());
		assertEquals("Bob Jansen", read.signatories().get(1).orElse(null));
	}

	/**
	 * The other defect: erasure used to null the field, so a record whose compact constructor rejected a
	 * null became permanently unreadable — a poison event failing every query and every projection over
	 * its stream. A shredded value is never null, so the record still builds.
	 */
	@ForEachBackend
	void anEventWhoseRecordRejectsNullsIsStillReadableAfterErasure ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);
		payments.append(AppendCriteria.none(), Event.of(
				new StrictlyValidated("v-1", Shreddable.of("alice@example.org", ALICE)), Tags.none()));

		store.erase(ALICE, ErasureReason.of("art.17"));

		StrictlyValidated read = (StrictlyValidated) payments.query(EventQuery.matchAll()).findFirst().orElseThrow().data();
		assertEquals("v-1", read.id());
		assertTrue(read.email().isShredded());
	}

	@ForEachBackend
	void anEventIsTaggedWithTheKeysItWasSealedUnder ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);
		payments.append(AppendCriteria.none(), Event.of(transfer(), Tags.of("transfer", "t-9001")));

		Event<PaymentEvent> read = payments.query(EventQuery.matchAll()).findFirst().orElseThrow();

		List<Tag> keyTags = read.tags().tags().stream().filter(t -> KeyId.TAG_KEY.equals(t.key())).toList();
		assertEquals(2, keyTags.size(), "expected one dek: tag per data subject, got " + read.tags());
		assertTrue(read.tags().tags().contains(Tag.of("transfer", "t-9001")), "the caller's own tags were lost");

		// which makes "every event holding data under this key" an ordinary tag query
		Tag anyKeyTag = keyTags.getFirst();
		assertEquals(1, payments.query(EventQuery.forEvents(
				org.sliceworkz.eventstore.query.EventTypesFilter.any(),
				Tags.of(anyKeyTag.key(), anyKeyTag.value()))).count());
	}

	@ForEachBackend
	void erasureIsIdempotentAndDataAppendedAfterwardsIsReadable ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);
		payments.append(AppendCriteria.none(), Event.of(transfer(), Tags.none()));

		assertEquals(1, store.erase(ALICE, ErasureReason.of("art.17")).keysShredded());

		ErasureReport second = store.erase(ALICE, ErasureReason.of("art.17 again"));
		assertTrue(second.isNoop(), "a second erasure destroyed something that should already have been gone");

		// a subject appended for after an erasure gets a fresh key
		payments.append(AppendCriteria.none(), Event.of(
				new StrictlyValidated("v-2", Shreddable.of("alice-new@example.org", ALICE)), Tags.none()));

		StrictlyValidated read = (StrictlyValidated) payments.query(EventQuery.matchAll()).toList().getLast().data();
		assertEquals("alice-new@example.org", read.email().orElse(null));

		// ...and erasing again takes the new key too, rather than reporting nothing to do
		assertEquals(1, store.erase(ALICE, ErasureReason.of("art.17, once more")).keysShredded());
		StrictlyValidated afterwards = (StrictlyValidated) payments.query(EventQuery.matchAll()).toList().getLast().data();
		assertTrue(afterwards.email().isShredded());
	}

	@ForEachBackend
	void categoriesAreErasedIndependently ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);

		DataSubject marketing = ALICE.withCategory("marketing");
		DataSubject financial = ALICE.withCategory("financial");

		payments.append(AppendCriteria.none(), List.of(
				Event.of(new StrictlyValidated("m-1", Shreddable.of("alice@marketing", marketing)), Tags.none()),
				Event.of(new StrictlyValidated("f-1", Shreddable.of("alice@financial", financial)), Tags.none())));

		store.erase(marketing, ErasureReason.of("erase marketing data only"));

		List<Event<PaymentEvent>> read = payments.query(EventQuery.matchAll()).toList();
		assertTrue(((StrictlyValidated) read.get(0).data()).email().isShredded());
		assertEquals("alice@financial", ((StrictlyValidated) read.get(1).data()).email().orElse(null),
				"erasing one category took another category's data with it");
	}

	/**
	 * The single most damaging mistake a key store can make. Reported as an erasure, an outage would have
	 * bookmarked projections write those gaps into read models permanently.
	 */
	@ForEachBackend
	void anUnreachableKeyStoreThrowsRatherThanReportingTheDataAsErased ( ) {
		ShreddingKeyStore working = backend().shreddingKeyStore(eventStorage());
		FailingKeyStore failing = new FailingKeyStore(working);

		EventStream<PaymentEvent> writing = eventStoreWithShredding(failing).getEventStream(STREAM, PaymentEvent.class);
		writing.append(AppendCriteria.none(), Event.of(transfer(), Tags.none()));

		failing.failing = true;

		EventStream<PaymentEvent> reading = eventStoreWithShredding(failing).getEventStream(STREAM, PaymentEvent.class);
		ShreddingException thrown = assertThrows(ShreddingException.class,
				() -> reading.query(EventQuery.matchAll()).toList());
		assertNotNull(thrown.getMessage());
	}

	@ForEachBackend
	void registeringAProtectedEventTypeWithoutACodecFails ( ) {
		// no shredding configured: personal data would be written in the clear, with no key to destroy
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(STREAM, PaymentEvent.class));
		assertTrue(thrown.getMessage().contains("Shreddable"), thrown.getMessage());
	}

	@ForEachBackend
	void anAlreadyErasedValueCannotBeAppendedAgain ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);
		payments.append(AppendCriteria.none(), Event.of(
				new StrictlyValidated("v-1", Shreddable.of("alice@example.org", ALICE)), Tags.none()));

		store.erase(ALICE, ErasureReason.of("art.17"));
		StrictlyValidated erased = (StrictlyValidated) payments.query(EventQuery.matchAll()).findFirst().orElseThrow().data();

		// re-appending would store a placeholder no later read could tell from real data
		assertThrows(RuntimeException.class,
				() -> payments.append(AppendCriteria.none(), Event.of(erased, Tags.none())));
	}

	private TransferMade transfer ( ) {
		return new TransferMade("t-9001", 25000, "alice-42", "bob-77",
				Shreddable.of(new PartyDetails("Alice Martin", "BE68 5390 0754 7034"), ALICE),
				Shreddable.of(new PartyDetails("Bob Jansen", "NL91 ABNA 0417 1643 00"), BOB));
	}

	/**
	 * Stands in for a key store that cannot be reached, so that the difference between "the key is gone"
	 * and "the key store is down" is actually exercised.
	 */
	private static final class FailingKeyStore implements ShreddingKeyStore {

		private final ShreddingKeyStore delegate;
		private boolean failing;

		private FailingKeyStore ( ShreddingKeyStore delegate ) {
			this.delegate = delegate;
		}

		@Override
		public ActiveKey keyFor ( DataSubject subject ) {
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

	/** A party to a transfer. Personal data, protected as a whole. */
	public record PartyDetails ( String name, String iban ) { }

	public sealed interface PaymentEvent { }

	/** Two data subjects in one event, each under their own key. */
	public record TransferMade (
			String transferId,
			long cents,
			String fromCustomerId,
			String toCustomerId,
			Shreddable<PartyDetails> from,
			Shreddable<PartyDetails> to ) implements PaymentEvent { }

	/** Protected values inside a collection. */
	public record DocumentSigned ( String documentId, List<Shreddable<String>> signatories ) implements PaymentEvent { }

	/** A record that refuses nulls, which erasure used to make permanently unreadable. */
	public record StrictlyValidated ( String id, Shreddable<String> email ) implements PaymentEvent {
		public StrictlyValidated {
			if ( id == null || id.isBlank() ) {
				throw new IllegalArgumentException("id required");
			}
			if ( email == null ) {
				throw new IllegalArgumentException("email required");
			}
		}
	}

}
