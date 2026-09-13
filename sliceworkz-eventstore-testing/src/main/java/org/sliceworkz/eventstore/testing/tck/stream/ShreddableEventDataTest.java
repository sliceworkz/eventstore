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
import java.util.Set;

import javax.crypto.SecretKey;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventSerializationException;
import org.sliceworkz.eventstore.events.Tag;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.ErasureReport;
import org.sliceworkz.eventstore.shredding.KeyAuditQuery;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingAudit;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingException;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore.KeyResolution;
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
 * Several scenarios here pin down what the design buys over the alternative of splitting personal data
 * into a second, erasable document — see {@code AbstractEventPayloadSerializerDeserializer} — and say so.
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
	 * What a split payload cannot do: with personal data in a second document reconciled by a deep
	 * merge, a collection whose elements hold both personal and non-personal fields comes back with the
	 * non-personal ones gone — on every ordinary read, with no erasure involved at all.
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

	@ForEachBackend
	void theAuditReportsWhoHoldsProtectedDataAndWhatWasErased ( ) {
		EventStore store = eventStoreWithShredding();
		store.getEventStream(STREAM, PaymentEvent.class)
				.append(AppendCriteria.none(), Event.of(transfer(), Tags.none()));

		ShreddingAudit audit = store.shreddingAudit().orElseThrow(
				() -> new AssertionError("the shipped key stores must all be able to report on themselves"));

		assertEquals(new ShreddingAudit.ShreddingTotals(2, 2, 0), audit.totals());

		List<ShreddingAudit.KeyRecord> alicesKeys = audit.keys(KeyAuditQuery.forSubject(ALICE));
		assertEquals(1, alicesKeys.size());
		ShreddingAudit.KeyRecord aliceKey = alicesKeys.getFirst();
		assertEquals(ALICE, aliceKey.subject());
		assertFalse(aliceKey.isShredded());
		assertNotNull(aliceKey.createdAt());
		// the record must be enough to find the events under this key, which is a dek: tag query
		assertEquals(1, store.getEventStream(STREAM, PaymentEvent.class)
				.query(EventQuery.forEvents(org.sliceworkz.eventstore.query.EventTypesFilter.any(),
						Tags.of(KeyId.TAG_KEY, aliceKey.id().value())))
				.count());

		store.erase(ALICE, ErasureReason.of("GDPR art.17 request #4711"));

		// the erasure log: what was destroyed, when, and on whose authority. Nothing else records it --
		// the events are byte-identical to what they were before.
		List<ShreddingAudit.KeyRecord> erasures = audit.keys(KeyAuditQuery.all().onlyShredded());
		assertEquals(1, erasures.size());
		ShreddingAudit.KeyRecord erased = erasures.getFirst();
		assertEquals(aliceKey.id(), erased.id());
		assertTrue(erased.isShredded());
		assertEquals(Optional.of(ErasureReason.of("GDPR art.17 request #4711")), erased.reason());
		assertTrue(erased.shreddedAt().isPresent());

		// Bob is untouched, and Alice no longer holds a live key
		assertEquals(new ShreddingAudit.ShreddingTotals(1, 1, 1), audit.totals());
		assertEquals(List.of(), audit.keys(KeyAuditQuery.forSubject(ALICE)).stream().filter(k -> !k.isShredded()).toList());
	}

	@ForEachBackend
	void theAuditNeverHandsOutKeyMaterial ( ) {
		EventStore store = eventStoreWithShredding();
		store.getEventStream(STREAM, PaymentEvent.class)
				.append(AppendCriteria.none(), Event.of(transfer(), Tags.none()));

		ShreddingAudit audit = store.shreddingAudit().orElseThrow();
		ShreddingAudit.KeyRecord record = audit.keys(KeyAuditQuery.all()).getFirst();

		// The whole reason this is a separate interface rather than another method on the key store: a
		// dashboard credential granted it can see *that* data is protected and never *what* it is. If a
		// component is ever added to KeyRecord that could carry key bytes, this fails.
		assertFalse(record.toString().contains("SecretKey"), record.toString());
		for ( java.lang.reflect.RecordComponent component : ShreddingAudit.KeyRecord.class.getRecordComponents() ) {
			assertFalse(SecretKey.class.isAssignableFrom(component.getType()),
					"KeyRecord.%s hands out key material".formatted(component.getName()));
		}
	}

	@ForEachBackend
	void theAuditNarrowsByCategoryAndIsBounded ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);

		DataSubject marketing = ALICE.withCategory("marketing");
		payments.append(AppendCriteria.none(), Event.of(
				new StrictlyValidated("v-1", Shreddable.of("alice@example.org", marketing)), Tags.none()));
		payments.append(AppendCriteria.none(), Event.of(transfer(), Tags.none()));

		// three keys now: Alice/default, Alice/marketing, Bob/default
		assertEquals(3, audit(store).keys(KeyAuditQuery.all()).size());
		assertEquals(1, audit(store).keys(KeyAuditQuery.all().withCategory("marketing")).size());
		assertEquals(2, audit(store).keys(KeyAuditQuery.forSubject("customer", "alice-42")).size());

		// "erase marketing, retain financial" is a category away, and the audit has to show that
		store.erase(marketing, ErasureReason.of("marketing consent withdrawn"));
		assertEquals(new ShreddingAudit.ShreddingTotals(2, 2, 1), audit(store).totals());

		// and the limit is honoured, because a store running for years holds more keys than any caller
		// meant to page through by accident
		assertEquals(2, audit(store).keys(KeyAuditQuery.all().withLimit(2)).size());
	}

	/**
	 * The category inventory: which categories of personal data the store holds, and how much under
	 * each. A category is what an erasure takes and what a reader is granted, and only the key store
	 * knows which ones exist -- the events carry them inside sealed envelopes -- so this is what an
	 * operator reads before deciding which categories a service may open.
	 */
	@ForEachBackend
	void theAuditBreaksItsTotalsDownByCategory ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);

		DataSubject marketing = ALICE.withCategory("marketing");
		payments.append(AppendCriteria.none(), Event.of(
				new StrictlyValidated("v-1", Shreddable.of("alice@example.org", marketing)), Tags.none()));
		payments.append(AppendCriteria.none(), Event.of(transfer(), Tags.none()));

		// default: Alice and Bob; marketing: Alice only. Most live subjects first.
		assertEquals(List.of(
				new ShreddingAudit.CategoryTotals("default", 2, 2, 0),
				new ShreddingAudit.CategoryTotals("marketing", 1, 1, 0)),
				audit(store).categories());

		store.erase(marketing, ErasureReason.of("marketing consent withdrawn"));

		// an erased category stays in the inventory with its erasure counted, rather than vanishing --
		// "we held marketing data and destroyed it" is exactly what the inventory is for
		assertEquals(List.of(
				new ShreddingAudit.CategoryTotals("default", 2, 2, 0),
				new ShreddingAudit.CategoryTotals("marketing", 0, 0, 1)),
				audit(store).categories());

		// and the breakdown sums to the totals
		ShreddingAudit.ShreddingTotals totals = audit(store).totals();
		assertEquals(totals.liveKeys(), audit(store).categories().stream().mapToLong(ShreddingAudit.CategoryTotals::liveKeys).sum());
		assertEquals(totals.shreddedKeys(), audit(store).categories().stream().mapToLong(ShreddingAudit.CategoryTotals::shreddedKeys).sum());
	}

	/**
	 * The join back from an event to the key store. An event says which keys it was sealed under and
	 * nothing else; a reader holding one -- a dashboard rendering it, a support tool -- asks the audit
	 * for exactly those keys to tell "protected" from "erased", without a key of its own.
	 */
	@ForEachBackend
	void theKeysAnEventCarriesCanBeLookedUpToTellProtectedFromErased ( ) {
		EventStore store = eventStoreWithShredding();
		EventStream<PaymentEvent> payments = store.getEventStream(STREAM, PaymentEvent.class);

		Event<PaymentEvent> stored = payments.append(AppendCriteria.none(), Event.of(transfer(), Tags.none())).getFirst();
		Set<KeyId> keysOnTheEvent = stored.tags().tags().stream()
				.filter(tag -> KeyId.TAG_KEY.equals(tag.key()))
				.map(tag -> KeyId.of(tag.value()))
				.collect(java.util.stream.Collectors.toSet());
		assertEquals(2, keysOnTheEvent.size(), "two subjects, two keys");

		List<ShreddingAudit.KeyRecord> records = audit(store).keys(KeyAuditQuery.forKeys(keysOnTheEvent));
		assertEquals(keysOnTheEvent, records.stream().map(ShreddingAudit.KeyRecord::id).collect(java.util.stream.Collectors.toSet()));
		assertTrue(records.stream().noneMatch(ShreddingAudit.KeyRecord::isShredded));

		store.erase(ALICE, ErasureReason.of("GDPR art.17 request #4711"));

		// the same lookup now says which of the two is gone, and whose it was
		records = audit(store).keys(KeyAuditQuery.forKeys(keysOnTheEvent));
		assertEquals(2, records.size());
		List<ShreddingAudit.KeyRecord> erased = records.stream().filter(ShreddingAudit.KeyRecord::isShredded).toList();
		assertEquals(1, erased.size());
		assertEquals(ALICE, erased.getFirst().subject());
		assertEquals(Optional.of(ErasureReason.of("GDPR art.17 request #4711")), erased.getFirst().reason());

		// the key filter composes with the others rather than replacing them
		assertEquals(1, audit(store).keys(KeyAuditQuery.forKeys(keysOnTheEvent).onlyShredded()).size());
		assertEquals(1, audit(store).keys(KeyAuditQuery.forSubject(BOB).withKeys(keysOnTheEvent)).size());
		assertEquals(0, audit(store).keys(KeyAuditQuery.forSubject(BOB).withKeys(keysOnTheEvent).onlyShredded()).size());

		// a key this store never held answers nothing, rather than failing: an envelope from another
		// store is a miswiring the caller can see from an empty answer
		assertEquals(List.of(), audit(store).keys(KeyAuditQuery.forKey(KeyId.of("k-never-minted-here"))));
	}

	// ---- who may read what: withheld is a third state, decided on the key seams -------------------------

	/**
	 * A reader with no keys at all still gets the typed events. Without a codec it could not open the
	 * stream, since registering a type that declares a Shreddable fails; with the withholding codec it
	 * reads everything that is not personal data and is told, per value, that the rest is withheld.
	 */
	@ForEachBackend
	void aReaderWithNoKeysReadsTheTypedEventsAndEveryProtectedValueIsWithheld ( ) {
		eventStoreWithShredding().getEventStream(STREAM, PaymentEvent.class)
				.append(AppendCriteria.none(), Event.of(transfer(), Tags.of("transfer", "t-9001")));

		EventStore reader = eventStoreWithShredding(ShreddingCodec.withholdingAll());
		EventStream<PaymentEvent> payments = reader.getEventStream(STREAM, PaymentEvent.class);

		Event<PaymentEvent> read = payments.query(EventQuery.matchAll()).findFirst().orElseThrow();
		TransferMade transfer = (TransferMade) read.data();

		// the non-personal payload is all there
		assertEquals("t-9001", transfer.transferId());
		assertEquals(25000, transfer.cents());
		assertEquals("alice-42", transfer.fromCustomerId());
		assertTrue(read.tags().tags().contains(Tag.of("transfer", "t-9001")));

		// and the personal data is withheld: not erased, not an error, and still says whose it is
		Shreddable.Withheld<PartyDetails> from = assertInstanceOf(Shreddable.Withheld.class, transfer.from());
		assertEquals(ALICE, from.subject());
		assertNotNull(from.key());
		assertTrue(transfer.from().isWithheld());
		assertFalse(transfer.from().isShredded(), "withheld must never read as erased");
		assertFalse(transfer.from().isPresent());
		assertEquals(Optional.empty(), transfer.from().toOptional());
		assertEquals("[withheld]", transfer.from().map(PartyDetails::name).orElse("[withheld]"));
		assertTrue(transfer.to().isWithheld());

		// it holds no keys, so it can neither seal nor erase, and has nothing to audit
		assertThrows(EventSerializationException.class,
				() -> payments.append(AppendCriteria.none(), Event.of(transfer(), Tags.none())));
		assertThrows(UnsupportedOperationException.class, () -> reader.erase(ALICE, ErasureReason.of("art.17")));
		assertEquals(Optional.empty(), reader.shreddingAudit());
	}

	/**
	 * "Names but not addresses": two categories, and a reader restricted to one of them. The unit of
	 * access is the Shreddable value, partitioned by the category chosen when the event was written.
	 */
	@ForEachBackend
	void aReaderRestrictedToACategoryReadsThatCategoryAndIsWithheldTheRest ( ) {
		DataSubject identity = ALICE.withCategory("identity");
		DataSubject address = ALICE.withCategory("address");

		// one key store, shared by the writer and the restricted reader, as in a deployment
		CountingKeyStore keyStore = new CountingKeyStore(backend().shreddingKeyStore(eventStorage()));
		eventStoreWithShredding(keyStore).getEventStream(STREAM, PaymentEvent.class).append(AppendCriteria.none(),
				Event.of(new ContactRecorded("c-1", Shreddable.of("Alice Martin", identity), Shreddable.of("Rue Haute 1", address)), Tags.none()));
		keyStore.resolutions.clear();

		EventStream<PaymentEvent> namesOnly = eventStoreWithShredding(AesGcmShreddingCodec.over(keyStore).restrictedTo(Set.of("identity")))
				.getEventStream(STREAM, PaymentEvent.class);

		ContactRecorded read = (ContactRecorded) namesOnly.query(EventQuery.matchAll()).findFirst().orElseThrow().data();
		assertEquals("Alice Martin", read.name().orElse(null));
		Shreddable.Withheld<String> withheld = assertInstanceOf(Shreddable.Withheld.class, read.address());
		assertEquals(address, withheld.subject());

		// the denied category is decided on the envelope, before any key is looked up
		assertEquals(Set.of(withheld.key()), keyStore.notAskedFor(), "a withheld category must not cost a key lookup");
		assertEquals(1, keyStore.resolutions.size(), "exactly the permitted value's key was resolved");
	}

	/**
	 * Withheld says nothing about erasure. A reader that may not decrypt a value cannot tell whether
	 * it has been erased and is not told; a reader that may sees the erasure as before.
	 */
	@ForEachBackend
	void aWithheldValueSaysNothingAboutErasureAndAPermittedOneStillReportsIt ( ) {
		DataSubject identity = ALICE.withCategory("identity");
		DataSubject address = ALICE.withCategory("address");

		ShreddingKeyStore keyStore = backend().shreddingKeyStore(eventStorage());
		EventStore full = eventStoreWithShredding(keyStore);
		full.getEventStream(STREAM, PaymentEvent.class).append(AppendCriteria.none(),
				Event.of(new ContactRecorded("c-1", Shreddable.of("Alice Martin", identity), Shreddable.of("Rue Haute 1", address)), Tags.none()));

		full.erase(address, ErasureReason.of("address no longer needed"));
		full.erase(identity, ErasureReason.of("art.17"));

		ShreddingCodec restricted = AesGcmShreddingCodec.over(keyStore).restrictedTo(Set.of("identity"));
		ContactRecorded read = (ContactRecorded) eventStoreWithShredding(restricted).getEventStream(STREAM, PaymentEvent.class)
				.query(EventQuery.matchAll()).findFirst().orElseThrow().data();

		assertTrue(read.address().isWithheld(), "an erased value outside the reader's categories is withheld, not reported erased");
		assertTrue(read.name().isShredded(), "an erased value inside the reader's categories is reported erased");

		ContactRecorded seenByFull = (ContactRecorded) full.getEventStream(STREAM, PaymentEvent.class)
				.query(EventQuery.matchAll()).findFirst().orElseThrow().data();
		assertTrue(seenByFull.address().isShredded());
	}

	/**
	 * The restriction is symmetric: a process configured not to handle a category cannot seal it either,
	 * and the append fails before anything is stored.
	 */
	@ForEachBackend
	void aRestrictedCodecDoesNotSealOutsideItsCategoriesAndStoresNothing ( ) {
		DataSubject identity = ALICE.withCategory("identity");
		DataSubject address = ALICE.withCategory("address");

		ShreddingCodec restricted = AesGcmShreddingCodec.over(backend().shreddingKeyStore(eventStorage())).restrictedTo(Set.of("identity"));
		EventStream<PaymentEvent> namesOnly = eventStoreWithShredding(restricted).getEventStream(STREAM, PaymentEvent.class);

		namesOnly.append(AppendCriteria.none(), Event.of(new StrictlyValidated("ok", Shreddable.of("Alice Martin", identity)), Tags.none()));

		EventSerializationException thrown = assertThrows(EventSerializationException.class, () -> namesOnly.append(AppendCriteria.none(),
				Event.of(new ContactRecorded("c-1", Shreddable.of("Alice Martin", identity), Shreddable.of("Rue Haute 1", address)), Tags.none())));
		assertTrue(thrown.getMessage().contains("address"), thrown.getMessage());

		assertEquals(1, namesOnly.query(EventQuery.matchAll()).count(), "a refused append must store nothing");
	}

	/**
	 * Erasure is not a read. A restricted codec passes it through whole, because an erasure that
	 * silently left another category readable while reporting success is the worst outcome an erasure
	 * can have.
	 */
	@ForEachBackend
	void erasingThroughARestrictedCodecErasesEveryCategory ( ) {
		DataSubject address = ALICE.withCategory("address");
		ShreddingKeyStore keyStore = backend().shreddingKeyStore(eventStorage());
		eventStoreWithShredding(keyStore).getEventStream(STREAM, PaymentEvent.class).append(AppendCriteria.none(),
				Event.of(new StrictlyValidated("a-1", Shreddable.of("Rue Haute 1", address)), Tags.none()));

		EventStore namesOnly = eventStoreWithShredding(AesGcmShreddingCodec.over(keyStore).restrictedTo(Set.of("identity")));
		assertEquals(1, namesOnly.erase(address, ErasureReason.of("art.17")).keysShredded());

		StrictlyValidated read = (StrictlyValidated) eventStoreWithShredding(keyStore).getEventStream(STREAM, PaymentEvent.class)
				.query(EventQuery.matchAll()).findFirst().orElseThrow().data();
		assertTrue(read.email().isShredded());
		assertEquals(1, namesOnly.shreddingAudit().orElseThrow().keys(KeyAuditQuery.all().onlyShredded()).size(), "the audit passes through too");
	}

	/**
	 * The hard boundary: a key store that refuses a key this caller is not granted. The refusal is
	 * neither an erasure nor an outage, and a projector that is merely not entitled keeps advancing.
	 */
	@ForEachBackend
	void aKeyStoreThatDeniesAKeyReadsAsWithheldAndAProjectorAdvancesOverIt ( ) {
		ShreddingKeyStore working = backend().shreddingKeyStore(eventStorage());
		DenyingKeyStore denying = new DenyingKeyStore(working);

		EventStream<PaymentEvent> writing = eventStoreWithShredding(denying).getEventStream(STREAM, PaymentEvent.class);
		writing.append(AppendCriteria.none(), List.of(
				Event.of(transfer(), Tags.none()),
				Event.of(new StrictlyValidated("v-1", Shreddable.of("alice@example.org", ALICE)), Tags.none())));

		denying.denying = true;

		EventStream<PaymentEvent> reading = eventStoreWithShredding(denying).getEventStream(STREAM, PaymentEvent.class);
		TransferMade transfer = (TransferMade) reading.query(EventQuery.matchAll()).findFirst().orElseThrow().data();
		Shreddable.Withheld<PartyDetails> from = assertInstanceOf(Shreddable.Withheld.class, transfer.from());
		assertEquals(ALICE, from.subject());
		assertFalse(transfer.from().isShredded());

		CountingProjection projection = new CountingProjection();
		ProjectorMetrics metrics = Projector.from(reading).towards(projection).build().run();
		assertEquals(2, projection.handled, "a reader that is not entitled must still project what it is entitled to");
		assertEquals(2, metrics.eventsHandled());
		assertEquals(3, projection.withheld, "every protected value came through as withheld");
	}

	@ForEachBackend
	void aWithheldValueCannotBeAppendedAgain ( ) {
		EventStore full = eventStoreWithShredding();
		full.getEventStream(STREAM, PaymentEvent.class).append(AppendCriteria.none(),
				Event.of(new StrictlyValidated("v-1", Shreddable.of("alice@example.org", ALICE)), Tags.none()));

		StrictlyValidated withheld = (StrictlyValidated) eventStoreWithShredding(ShreddingCodec.withholdingAll())
				.getEventStream(STREAM, PaymentEvent.class).query(EventQuery.matchAll()).findFirst().orElseThrow().data();
		assertTrue(withheld.email().isWithheld(), "fixture");

		// this reader never had the plaintext; a placeholder would read as real data to one that is entitled
		EventStream<PaymentEvent> entitled = full.getEventStream(STREAM, PaymentEvent.class);
		assertThrows(EventSerializationException.class, () -> entitled.append(AppendCriteria.none(), Event.of(withheld, Tags.none())));
		assertEquals(1, entitled.query(EventQuery.matchAll()).count());
	}

	private ShreddingAudit audit ( EventStore store ) {
		return store.shreddingAudit().orElseThrow();
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

	/**
	 * Records which keys were asked for, so a scenario can assert that a withheld category never reached
	 * the key store.
	 */
	private static final class CountingKeyStore implements ShreddingKeyStore {

		private final ShreddingKeyStore delegate;
		private final java.util.Set<KeyId> resolutions = new java.util.LinkedHashSet<>();
		private final java.util.Set<KeyId> minted = new java.util.LinkedHashSet<>();

		private CountingKeyStore ( ShreddingKeyStore delegate ) {
			this.delegate = delegate;
		}

		@Override
		public ActiveKey keyFor ( DataSubject subject ) {
			ActiveKey key = delegate.keyFor(subject);
			minted.add(key.id());
			return key;
		}

		@Override
		public Optional<SecretKey> resolve ( KeyId key ) {
			resolutions.add(key);
			return delegate.resolve(key);
		}

		@Override
		public KeyResolution resolveKey ( KeyId key ) {
			resolutions.add(key);
			return delegate.resolveKey(key);
		}

		@Override
		public List<KeyId> shred ( DataSubject subject, ErasureReason reason ) {
			return delegate.shred(subject, reason);
		}

		/**
		 * The keys this store knows about that were never resolved through it.
		 */
		private java.util.Set<KeyId> notAskedFor ( ) {
			return delegate.audit().orElseThrow().keys(KeyAuditQuery.all()).stream()
					.map(ShreddingAudit.KeyRecord::id)
					.filter(id -> !resolutions.contains(id))
					.collect(java.util.stream.Collectors.toSet());
		}

	}

	/**
	 * Stands in for a key store fronting a KMS or a database role that refuses this caller every key.
	 */
	private static final class DenyingKeyStore implements ShreddingKeyStore {

		private final ShreddingKeyStore delegate;
		private boolean denying;

		private DenyingKeyStore ( ShreddingKeyStore delegate ) {
			this.delegate = delegate;
		}

		@Override
		public ActiveKey keyFor ( DataSubject subject ) {
			return delegate.keyFor(subject);
		}

		@Override
		public Optional<SecretKey> resolve ( KeyId key ) {
			if ( denying ) {
				throw new ShreddingException("a caller of the two-answer method cannot be told about a denial");
			}
			return delegate.resolve(key);
		}

		@Override
		public KeyResolution resolveKey ( KeyId key ) {
			if ( denying ) {
				return new KeyResolution.Denied("simulated: this role is not granted key " + key);
			}
			return delegate.resolveKey(key);
		}

		@Override
		public List<KeyId> shred ( DataSubject subject, ErasureReason reason ) {
			return delegate.shred(subject, reason);
		}

	}

	private static final class CountingProjection implements Projection<PaymentEvent> {

		private int handled;
		private int withheld;

		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}

		@Override
		public void when ( Event<PaymentEvent> event ) {
			handled++;
			switch ( event.data() ) {
				case TransferMade t -> withheld += (t.from().isWithheld() ? 1 : 0) + (t.to().isWithheld() ? 1 : 0);
				case StrictlyValidated v -> withheld += v.email().isWithheld() ? 1 : 0;
				default -> { }
			}
		}

	}

	/** A name and an address under two categories, so a reader can be entitled to one and not the other. */
	public record ContactRecorded ( String contactId, Shreddable<String> name, Shreddable<String> address ) implements PaymentEvent { }

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
