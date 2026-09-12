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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.shredding.ShreddingCodec.Sealed;
import org.sliceworkz.eventstore.shredding.ShreddingCodec.Unsealed;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore.KeyResolution;

/**
 * The two codec seams' third answer, below the event store: a restricted codec, the withholding codec,
 * a key store's denial, and the {@link Shreddable.Withheld} state itself. The read path through a store
 * is pinned per backend by the TCK's {@code ShreddableEventDataTest}.
 */
public class ReaderEntitlementTest {

	private static final DataSubject IDENTITY = DataSubject.of("customer", "alice-42").withCategory("identity");
	private static final DataSubject ADDRESS = DataSubject.of("customer", "alice-42").withCategory("address");

	@Test
	void aRestrictedCodecUnsealsItsCategoriesAndWithholdsTheRestWithoutAskingTheKeyStore ( ) {
		MapKeyStore keyStore = new MapKeyStore();
		AesGcmShreddingCodec full = AesGcmShreddingCodec.over(keyStore);
		Sealed name = full.seal("\"Alice\"", IDENTITY);
		Sealed street = full.seal("\"Rue Haute 1\"", ADDRESS);

		ShreddingCodec namesOnly = full.restrictedTo(Set.of("identity"));
		keyStore.resolved.clear();

		assertEquals(new Unsealed.Plaintext("\"Alice\""), namesOnly.open(name));
		Unsealed.Withheld withheld = assertInstanceOf(Unsealed.Withheld.class, namesOnly.open(street));
		assertTrue(withheld.reason().contains("address"), withheld.reason());
		assertEquals(List.of(name.key()), keyStore.resolved, "the withheld category must not reach the key store");

		// the two-answer method cannot say withheld, and must not say erased
		assertEquals(Optional.of("\"Alice\""), namesOnly.unseal(name));
		assertThrows(ShreddingException.class, () -> namesOnly.unseal(street));
	}

	@Test
	void aRestrictedCodecSealsOnlyItsCategories ( ) {
		ShreddingCodec namesOnly = AesGcmShreddingCodec.over(new MapKeyStore()).restrictedTo(Set.of("identity"));
		assertEquals(IDENTITY, namesOnly.seal("\"Alice\"", IDENTITY).subject());
		ShreddingException thrown = assertThrows(ShreddingException.class, () -> namesOnly.seal("\"Rue Haute 1\"", ADDRESS));
		assertTrue(thrown.getMessage().contains("address"), thrown.getMessage());
	}

	@Test
	void aRestrictedCodecErasesAndAuditsThroughTheWholeDelegate ( ) {
		MapKeyStore keyStore = new MapKeyStore();
		AesGcmShreddingCodec full = AesGcmShreddingCodec.over(keyStore);
		Sealed street = full.seal("\"Rue Haute 1\"", ADDRESS);

		ShreddingCodec namesOnly = full.restrictedTo(Set.of("identity"));
		assertEquals(List.of(street.key()), namesOnly.shred(ADDRESS, ErasureReason.of("art.17")).shreddedKeys(),
				"erasure is not a read and must not be narrowed to the reader's categories");
		assertEquals(Unsealed.Erased.INSTANCE, full.open(street));
		assertSame(full.audit().isPresent(), namesOnly.audit().isPresent());
	}

	@Test
	void aRestrictedCodecRejectsAnEmptyOrBlankCategorySet ( ) {
		ShreddingCodec codec = AesGcmShreddingCodec.over(new MapKeyStore());
		assertThrows(IllegalArgumentException.class, () -> codec.restrictedTo(Set.of()));
		assertThrows(IllegalArgumentException.class, () -> codec.restrictedTo(null));
		assertThrows(IllegalArgumentException.class, () -> codec.restrictedTo(Set.of(" ")));
		assertThrows(IllegalArgumentException.class, () -> new CategoryRestrictedShreddingCodec(null, Set.of("identity")));
	}

	@Test
	void theWithholdingCodecHoldsNoKeysAndWithholdsEverything ( ) {
		Sealed name = AesGcmShreddingCodec.over(new MapKeyStore()).seal("\"Alice\"", IDENTITY);
		ShreddingCodec none = ShreddingCodec.withholdingAll();

		assertInstanceOf(Unsealed.Withheld.class, none.open(name));
		assertThrows(ShreddingException.class, () -> none.unseal(name), "must not report withheld as erased");
		assertThrows(ShreddingException.class, () -> none.seal("\"Alice\"", IDENTITY));
		assertThrows(UnsupportedOperationException.class, () -> none.shred(IDENTITY, ErasureReason.of("art.17")));
		assertEquals(Optional.empty(), none.audit());
		assertSame(none, ShreddingCodec.withholdingAll(), "holds nothing, so one instance serves everyone");
	}

	@Test
	void aKeyStoreDenialIsPassedOnAsWithheldByTheShippedCodec ( ) {
		MapKeyStore keyStore = new MapKeyStore();
		AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(keyStore);
		Sealed name = codec.seal("\"Alice\"", IDENTITY);

		keyStore.denying = true;
		Unsealed.Withheld withheld = assertInstanceOf(Unsealed.Withheld.class, codec.open(name));
		assertEquals("simulated 403", withheld.reason());
		assertThrows(ShreddingException.class, () -> codec.unseal(name), "the two-answer method cannot say withheld");

		keyStore.denying = false;
		assertEquals(new Unsealed.Plaintext("\"Alice\""), codec.open(name));
	}

	@Test
	void theDefaultResolveKeyDerivesResolvedAndErasedFromResolveAndNeverDenied ( ) {
		MapKeyStore keyStore = new MapKeyStore();
		KeyId key = keyStore.keyFor(IDENTITY).id();

		KeyResolution.Resolved resolved = assertInstanceOf(KeyResolution.Resolved.class, new TwoAnswerKeyStore(keyStore).resolveKey(key));
		assertEquals(keyStore.resolve(key).orElseThrow(), resolved.key());

		keyStore.shred(IDENTITY, ErasureReason.of("art.17"));
		assertEquals(KeyResolution.Erased.INSTANCE, new TwoAnswerKeyStore(keyStore).resolveKey(key));
	}

	@Test
	void theDefaultOpenDerivesPlaintextAndErasedFromUnsealAndNeverWithheld ( ) {
		MapKeyStore keyStore = new MapKeyStore();
		AesGcmShreddingCodec codec = AesGcmShreddingCodec.over(keyStore);
		Sealed name = codec.seal("\"Alice\"", IDENTITY);

		ShreddingCodec twoAnswers = new TwoAnswerCodec(codec);
		assertEquals(new Unsealed.Plaintext("\"Alice\""), twoAnswers.open(name));
		keyStore.shred(IDENTITY, ErasureReason.of("art.17"));
		assertEquals(Unsealed.Erased.INSTANCE, twoAnswers.open(name));
	}

	@Test
	void withheldIsNeitherPresentNorShreddedAndMapsToItself ( ) {
		KeyId key = KeyId.of("k-1");
		Shreddable<String> withheld = new Shreddable.Withheld<>(IDENTITY, key);

		assertTrue(withheld.isWithheld());
		assertFalse(withheld.isShredded());
		assertFalse(withheld.isPresent());
		assertEquals(Optional.empty(), withheld.toOptional());
		assertEquals("fallback", withheld.orElse("fallback"));
		assertEquals("supplied", withheld.orElseGet(() -> "supplied"));
		assertSame(withheld, withheld.map(String::length));
		assertEquals(IDENTITY, withheld.subject());

		assertThrows(IllegalArgumentException.class, () -> new Shreddable.Withheld<>(null, key));
		assertThrows(IllegalArgumentException.class, () -> new Shreddable.Withheld<>(IDENTITY, null));

		// the other two keep their meaning
		assertFalse(Shreddable.of("Alice", IDENTITY).isWithheld());
		assertTrue(Shreddable.of("Alice", IDENTITY).isPresent());
		assertFalse(new Shreddable.Shredded<>(IDENTITY, key).isWithheld());
		assertFalse(new Shreddable.Shredded<>(IDENTITY, key).isPresent());
	}

	@Test
	void theResultTypesRejectWhatTheyCannotMean ( ) {
		assertThrows(IllegalArgumentException.class, () -> new KeyResolution.Resolved(null));
		assertThrows(IllegalArgumentException.class, () -> new Unsealed.Plaintext(null));
		assertEquals("", new KeyResolution.Denied(null).reason());
		assertEquals("", new Unsealed.Withheld(null).reason());
	}

	/**
	 * A key store written against the two-answer contract only, to check the defaults derive the
	 * three-answer one from it.
	 */
	private static final class TwoAnswerKeyStore implements ShreddingKeyStore {

		private final ShreddingKeyStore delegate;

		private TwoAnswerKeyStore ( ShreddingKeyStore delegate ) {
			this.delegate = delegate;
		}

		@Override
		public ActiveKey keyFor ( DataSubject subject ) {
			return delegate.keyFor(subject);
		}

		@Override
		public Optional<SecretKey> resolve ( KeyId key ) {
			return delegate.resolve(key);
		}

		@Override
		public List<KeyId> shred ( DataSubject subject, ErasureReason reason ) {
			return delegate.shred(subject, reason);
		}

	}

	/**
	 * A codec written against the two-answer contract only.
	 */
	private static final class TwoAnswerCodec implements ShreddingCodec {

		private final ShreddingCodec delegate;

		private TwoAnswerCodec ( ShreddingCodec delegate ) {
			this.delegate = delegate;
		}

		@Override
		public Sealed seal ( String plaintext, DataSubject subject ) {
			return delegate.seal(plaintext, subject);
		}

		@Override
		public Optional<String> unseal ( Sealed sealed ) {
			return delegate.unseal(sealed);
		}

		@Override
		public ErasureReport shred ( DataSubject subject, ErasureReason reason ) {
			return delegate.shred(subject, reason);
		}

	}

	/**
	 * The smallest key store that can mint, resolve, shred, deny and be watched.
	 */
	private static final class MapKeyStore implements ShreddingKeyStore {

		private final Map<DataSubject, KeyId> active = new HashMap<>();
		private final Map<KeyId, SecretKey> material = new HashMap<>();
		private final Map<KeyId, DataSubject> subjects = new HashMap<>();
		private final List<KeyId> resolved = new ArrayList<>();
		private boolean denying;

		@Override
		public ActiveKey keyFor ( DataSubject subject ) {
			KeyId id = active.computeIfAbsent(subject, s -> {
				KeyId fresh = KeyId.of("k-" + UUID.randomUUID());
				material.put(fresh, generate());
				subjects.put(fresh, s);
				return fresh;
			});
			return new ActiveKey(id, material.get(id));
		}

		@Override
		public Optional<SecretKey> resolve ( KeyId key ) {
			resolved.add(key);
			return Optional.ofNullable(material.get(key));
		}

		@Override
		public KeyResolution resolveKey ( KeyId key ) {
			if ( denying ) {
				return new KeyResolution.Denied("simulated 403");
			}
			return ShreddingKeyStore.super.resolveKey(key);
		}

		@Override
		public List<KeyId> shred ( DataSubject subject, ErasureReason reason ) {
			List<KeyId> shredded = new ArrayList<>();
			subjects.forEach((id, s) -> {
				if ( s.equals(subject) && material.get(id) != null ) {
					material.put(id, null);
					shredded.add(id);
				}
			});
			active.remove(subject);
			return shredded;
		}

		@Override
		public Optional<ShreddingAudit> audit ( ) {
			return Optional.of(new ShreddingAudit() {
				@Override
				public List<KeyRecord> keys ( KeyAuditQuery query ) {
					return List.of();
				}

				@Override
				public ShreddingTotals totals ( ) {
					return new ShreddingTotals(0, 0, 0);
				}
			});
		}

		private static SecretKey generate ( ) {
			try {
				KeyGenerator generator = KeyGenerator.getInstance("AES");
				generator.init(256);
				return generator.generateKey();
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}

	}

}
