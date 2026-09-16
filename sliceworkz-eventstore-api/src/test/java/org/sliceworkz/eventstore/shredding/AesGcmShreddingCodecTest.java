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
package org.sliceworkz.eventstore.shredding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.shredding.ShreddingCodec.Sealed;
import org.sliceworkz.eventstore.shredding.ShreddingCodec.Unsealed;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore.KeyResolution;

/**
 * What the shipped codec refuses to seal, and what it still opens: a key that is not 256-bit AES
 * material, and a label whose fields hold the separator of the authenticated metadata. The read path
 * through a store, erasure and entitlement are pinned elsewhere ({@code ShreddableEventDataTest} per
 * backend, {@link ReaderEntitlementTest} at the seams).
 */
public class AesGcmShreddingCodecTest {

	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");
	private static final KeyId KEY = KeyId.of("k-1");

	@Test
	void aValueSealsAndOpensUnderA256BitAesKey ( ) {
		FixedKeyStore keyStore = new FixedKeyStore(KEY, aesKey(256));
		AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(keyStore);

		Sealed sealed = codec.seal("\"Alice\"", ALICE);

		assertEquals(AesGcmShreddingCodec.ALGORITHM, sealed.alg());
		assertEquals(new Unsealed.Plaintext("\"Alice\""), codec.open(sealed));
	}

	@Test
	void aKeyOfAnotherLengthIsRefusedAtSeal ( ) {
		for ( int bits : new int[] { 128, 192 } ) {
			FixedKeyStore keyStore = new FixedKeyStore(KEY, aesKey(bits));
			AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(keyStore);

			ShreddingException thrown = assertThrows(ShreddingException.class, () -> codec.seal("\"Alice\"", ALICE));

			assertTrue(thrown.getMessage().contains(bits + "-bit"), thrown.getMessage());
			assertTrue(thrown.getMessage().contains(AesGcmShreddingCodec.KEY_BITS + "-bit"), thrown.getMessage());
			assertTrue(thrown.getMessage().contains(KEY.value()), thrown.getMessage());
		}
	}

	@Test
	void aKeyOfAnotherAlgorithmIsRefusedAtSealWhateverItsLength ( ) {
		SecretKey hmacKey = new SecretKeySpec(new byte[32], "HmacSHA256");
		AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(new FixedKeyStore(KEY, hmacKey));

		ShreddingException thrown = assertThrows(ShreddingException.class, () -> codec.seal("\"Alice\"", ALICE));

		assertTrue(thrown.getMessage().contains("HmacSHA256"), thrown.getMessage());
	}

	@Test
	void aKeyWhoseMaterialIsNotExtractableIsRefusedAtSeal ( ) {
		SecretKey opaque = new SecretKey() {
			private static final long serialVersionUID = 1L;

			@Override
			public String getAlgorithm ( ) {
				return "AES";
			}

			@Override
			public String getFormat ( ) {
				return null;
			}

			@Override
			public byte[] getEncoded ( ) {
				return null;
			}
		};
		AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(new FixedKeyStore(KEY, opaque));

		ShreddingException thrown = assertThrows(ShreddingException.class, () -> codec.seal("\"Alice\"", ALICE));

		assertTrue(thrown.getMessage().contains("getEncoded()"), thrown.getMessage());
		assertTrue(thrown.getMessage().contains(KEY.value()), thrown.getMessage());
	}

	@Test
	void openDoesNotMeasureTheKeyAValueWasSealedUnder ( ) throws GeneralSecurityException {
		// What is sealed is sealed: an envelope written under a 128-bit key by a key store that has since
		// been fixed must stay readable, since refusing it would strand the data and protect nothing.
		SecretKey shortKey = aesKey(128);
		Sealed sealed = sealByHand("\"Alice\"", KEY, ALICE, shortKey);
		AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(new FixedKeyStore(KEY, shortKey));

		assertEquals(new Unsealed.Plaintext("\"Alice\""), codec.open(sealed));
	}

	@Test
	void aSeparatorInTheSubjectIsRefusedAtSealBeforeAKeyIsMinted ( ) {
		char separator = AesGcmShreddingCodec.AAD_SEPARATOR;
		List<DataSubject> ambiguous = List.of(
				new DataSubject("cust" + separator + "omer", "alice-42", "default"),
				new DataSubject("customer", "tenant" + separator + "alice-42", "default"),
				new DataSubject("customer", "alice-42", "mark" + separator + "eting"));

		for ( DataSubject subject : ambiguous ) {
			FixedKeyStore keyStore = new FixedKeyStore(KEY, aesKey(256));
			AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(keyStore);

			ShreddingException thrown = assertThrows(ShreddingException.class, () -> codec.seal("\"Alice\"", subject));

			assertTrue(thrown.getMessage().contains("'" + separator + "'"), thrown.getMessage());
			assertTrue(thrown.getMessage().contains(subject.toString()), thrown.getMessage());
			assertEquals(List.of(), keyStore.mintedFor, "a subject that can never be sealed for must not be given a key");
		}
	}

	@Test
	void aSeparatorInTheKeyIdIsRefusedAtSeal ( ) {
		KeyId ambiguous = KeyId.of("k" + AesGcmShreddingCodec.AAD_SEPARATOR + "1");
		AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(new FixedKeyStore(ambiguous, aesKey(256)));

		ShreddingException thrown = assertThrows(ShreddingException.class, () -> codec.seal("\"Alice\"", ALICE));

		assertTrue(thrown.getMessage().contains(ambiguous.value()), thrown.getMessage());
	}

	@Test
	void aValueAlreadySealedUnderALabelHoldingTheSeparatorStillOpens ( ) throws GeneralSecurityException {
		// The authenticated metadata is wire format: an envelope written before the separator was refused
		// authenticates against the plain join, and must keep opening -- ambiguous as it was written.
		DataSubject legacy = new DataSubject("customer", "tenant" + AesGcmShreddingCodec.AAD_SEPARATOR + "alice-42", "default");
		SecretKey key = aesKey(256);
		Sealed sealed = sealByHand("\"Alice\"", KEY, legacy, key);
		AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(new FixedKeyStore(KEY, key));

		assertEquals(new Unsealed.Plaintext("\"Alice\""), codec.open(sealed));
	}

	@Test
	void theAuthenticatedMetadataIsTheFiveFieldsJoinedBySeparator ( ) throws GeneralSecurityException {
		// Pins the layout every existing envelope authenticates against: a codec that joined the fields
		// any other way would fail to open everything already sealed.
		SecretKey key = aesKey(256);
		AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(new FixedKeyStore(KEY, key));

		assertEquals(new Unsealed.Plaintext("\"Alice\""), codec.open(sealByHand("\"Alice\"", KEY, ALICE, key)));

		Sealed sealed = codec.seal("\"Alice\"", ALICE);
		Sealed relabelled = new Sealed(sealed.alg(), sealed.key(), DataSubject.of("customer", "bob-7"), sealed.iv(), sealed.ciphertext());
		ShreddingException thrown = assertThrows(ShreddingException.class, () -> codec.open(relabelled));
		assertInstanceOf(GeneralSecurityException.class, thrown.getCause());
	}

	/**
	 * Seals exactly as the codec does, with the authenticated metadata spelled out here rather than taken
	 * from it, so the tests above hold the codec to the layout rather than to itself.
	 */
	private static Sealed sealByHand ( String plaintext, KeyId key, DataSubject subject, SecretKey secretKey ) throws GeneralSecurityException {
		byte[] iv = new byte[12];
		new SecureRandom().nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
		String aad = String.join("|", AesGcmShreddingCodec.ALGORITHM, key.value(), subject.type(), subject.id(), subject.category());
		cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
		byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
		return new Sealed(AesGcmShreddingCodec.ALGORITHM, key, subject,
				Base64.getEncoder().encodeToString(iv), Base64.getEncoder().encodeToString(ciphertext));
	}

	private static SecretKey aesKey ( int bits ) {
		try {
			KeyGenerator generator = KeyGenerator.getInstance("AES");
			generator.init(bits);
			return generator.generateKey();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Hands out one key under one id, whatever the subject, and remembers who asked. */
	private static final class FixedKeyStore implements ShreddingKeyStore {

		private final KeyId id;
		private final SecretKey key;
		private final List<DataSubject> mintedFor = new ArrayList<>();

		FixedKeyStore ( KeyId id, SecretKey key ) {
			this.id = id;
			this.key = key;
		}

		@Override
		public ActiveKey keyFor ( DataSubject subject ) {
			mintedFor.add(subject);
			return new ActiveKey(id, key);
		}

		@Override
		public KeyResolution resolveKey ( KeyId requested ) {
			if ( !id.equals(requested) ) {
				throw new ShreddingException("this store never held " + requested);
			}
			return new KeyResolution.Resolved(key);
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
