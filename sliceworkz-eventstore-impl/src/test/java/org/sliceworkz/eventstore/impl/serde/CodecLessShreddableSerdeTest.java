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
package org.sliceworkz.eventstore.impl.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventSerializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndSerializedPayload;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.ErasureReport;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;

/**
 * A store with no {@code ShreddingCodec} refuses to write a {@link Shreddable} wherever one turns up in
 * a payload, not only where the registration check can see one coming.
 * <p>
 * The registration check walks record components, type arguments and array elements, and stops at a
 * component declared as an interface or a non-record class: what such a component holds at runtime is
 * not knowable from the declaration. A {@code Shreddable} reached only through one of those is
 * therefore registered without complaint, and it is the mapper that has to refuse it at the append —
 * on the typed serde and on the raw one alike, since both write through the same mapper.
 */
class CodecLessShreddableSerdeTest {

	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");
	private static final String SECRET = "Alice Martin";

	// a component typed as an interface: the registration walk cannot see the Shreddable behind it
	interface Party { }
	record NamedParty ( Shreddable<String> name ) implements Party { }

	sealed interface PaymentEvent {
		record Paid ( String id, Party party ) implements PaymentEvent { }
		record Noted ( String id, Object note ) implements PaymentEvent { }
	}

	@Test
	void aShreddableBehindAnInterfaceTypedComponentIsRefusedWithoutACodec ( ) {
		// the declaration shows no Shreddable, so registration passes
		EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed()
				.registerEventTypes(PaymentEvent.class).validate();

		EventSerializationException thrown = assertThrows(EventSerializationException.class,
				() -> serde.serialize(new PaymentEvent.Paid("p-1", new NamedParty(Shreddable.of(SECRET, ALICE)))));

		assertTrue(thrown.getMessage().contains("ShreddingCodec"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains(ALICE.toString()), thrown.getMessage());
		assertFalse(thrown.getMessage().contains(SECRET), "the refusal must not carry the personal data itself: " + thrown.getMessage());
		assertEquals(EventType.ofType("Paid"), thrown.getEventType());
	}

	@Test
	void aShreddableBehindAnObjectTypedComponentIsRefusedWithoutACodec ( ) {
		EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed()
				.registerEventTypes(PaymentEvent.class).validate();

		EventSerializationException thrown = assertThrows(EventSerializationException.class,
				() -> serde.serialize(new PaymentEvent.Noted("p-1", Shreddable.of(SECRET, ALICE))));

		assertTrue(thrown.getMessage().contains("ShreddingCodec"), thrown.getMessage());
		assertFalse(thrown.getMessage().contains(SECRET), thrown.getMessage());
	}

	@Test
	void anErasedOrWithheldValueIsRefusedWithoutACodecToo ( ) {
		// neither holds plaintext, but a codec-less mapper would write the record as a placeholder
		EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed()
				.registerEventTypes(PaymentEvent.class).validate();
		KeyId key = KeyId.of("k-1");

		assertThrows(EventSerializationException.class,
				() -> serde.serialize(new PaymentEvent.Noted("p-1", new Shreddable.Shredded<>(ALICE, key))));
		assertThrows(EventSerializationException.class,
				() -> serde.serialize(new PaymentEvent.Noted("p-1", new Shreddable.Withheld<>(ALICE, key))));
	}

	@Test
	void theRawSerdeRefusesItAsWell ( ) {
		// a raw stream appends whatever object it is handed, through the same codec-less mapper
		EventPayloadSerializerDeserializer raw = EventPayloadSerializerDeserializer.raw();

		EventSerializationException thrown = assertThrows(EventSerializationException.class,
				() -> raw.serialize(new PaymentEvent.Paid("p-1", new NamedParty(Shreddable.of(SECRET, ALICE)))));

		assertTrue(thrown.getMessage().contains("ShreddingCodec"), thrown.getMessage());
		assertFalse(thrown.getMessage().contains(SECRET), thrown.getMessage());
	}

	@Test
	void withACodecTheSameValueIsSealed ( ) {
		// the refusal is the codec-less mapper's only; with a codec the sealing serializer applies, and
		// applies behind an interface-typed component exactly as it does to a declared one
		FixedKeyStore keyStore = new FixedKeyStore(KeyId.of("k-1"), aesKey());
		EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed(AesGcmShreddingCodec.over(keyStore))
				.registerEventTypes(PaymentEvent.class).validate();

		TypeAndSerializedPayload stored = serde.serialize(new PaymentEvent.Paid("p-1", new NamedParty(Shreddable.of(SECRET, ALICE))));

		assertFalse(stored.immutablePayload().contains(SECRET), "personal data in the clear: " + stored.immutablePayload());
		assertTrue(stored.immutablePayload().contains("\"ct\""), stored.immutablePayload());
		assertEquals(java.util.Set.of(KeyId.of("k-1")), stored.shreddingKeys());
	}

	private static SecretKey aesKey ( ) {
		try {
			KeyGenerator generator = KeyGenerator.getInstance("AES");
			generator.init(256);
			return generator.generateKey();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Hands out one key under one id, whatever the subject. */
	private static final class FixedKeyStore implements ShreddingKeyStore {

		private final KeyId id;
		private final SecretKey key;

		FixedKeyStore ( KeyId id, SecretKey key ) {
			this.id = id;
			this.key = key;
		}

		@Override
		public ActiveKey keyFor ( DataSubject subject ) {
			return new ActiveKey(id, key);
		}

		@Override
		public Optional<SecretKey> resolve ( KeyId requested ) {
			return id.equals(requested) ? Optional.of(key) : Optional.empty();
		}

		@Override
		public List<KeyId> shred ( DataSubject subject, ErasureReason reason ) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<ErasureReport> shredAllCategories ( String subjectType, String subjectId, ErasureReason reason ) {
			throw new UnsupportedOperationException();
		}

	}

}
