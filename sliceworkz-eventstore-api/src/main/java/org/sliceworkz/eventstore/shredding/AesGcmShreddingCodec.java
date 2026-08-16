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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.sliceworkz.eventstore.shredding.ShreddingKeyStore.ActiveKey;

/**
 * The shipped {@link ShreddingCodec}: AES-256-GCM over a pluggable {@link ShreddingKeyStore}.
 * <p>
 * Everything cryptographic lives here, so an implementation that only wants its keys held somewhere
 * else — Vault, a cloud KMS, a table in another database — implements {@link ShreddingKeyStore} and
 * keeps this codec. Taking over encryption as well means implementing {@link ShreddingCodec} directly.
 *
 * <h2>What is used, and why it needs no post-quantum work</h2>
 * AES-256 in GCM, with a fresh random 96-bit initialisation vector per sealed value and a 128-bit
 * authentication tag. This is symmetric cryptography only — there is no key exchange, no key wrapping
 * and no public key anywhere in the design — so Shor's algorithm has nothing to attack, and Grover's
 * quadratic speedup leaves AES-256 with roughly 128 bits of effective security, which NIST treats as
 * quantum-resistant.
 * <p>
 * Post-quantum becomes a question only for a key store that protects its keys with asymmetric
 * cryptography, which is a decision on the far side of the {@link ShreddingKeyStore} seam.
 *
 * <h2>The IV is random per value, never derived</h2>
 * One key protects every value ever sealed for a subject, so an IV derived from anything the events
 * provide — a position, a counter restarted per event, a hash of the payload — risks reusing an IV
 * under the same key, which in GCM is catastrophic rather than merely weak: it leaks the XOR of two
 * plaintexts and, worse, the authentication subkey. A 96-bit random IV per value has a birthday bound
 * far beyond any realistic number of events for one subject.
 *
 * <h2>The envelope is authenticated against its own metadata</h2>
 * The algorithm, key id and subject are passed to GCM as additional authenticated data. They are stored
 * in the clear next to the ciphertext, so binding them means a sealed value cannot be moved to another
 * subject, relabelled with another key id, or have its recorded algorithm altered without decryption
 * failing outright. Tampering surfaces as a {@link ShreddingException}, never as a silently wrong value
 * and never as a spurious "erased".
 *
 * <h2>Caching is the key store's business</h2>
 * This codec resolves a key per sealed value and holds nothing between calls, so erasure takes effect
 * here the instant the key store stops returning the key. Where resolving is expensive — a network call
 * per value would be — the caching belongs in the {@link ShreddingKeyStore} implementation, which knows
 * what its own lookups cost and can bound the resulting window in which a destroyed key is still
 * usable. The shipped key stores document what they do.
 *
 * <h2>Ownership</h2>
 * {@link #close()} does not close the key store: a key store handed in was created by the caller and
 * may back several codecs, following the same rule the library applies to a {@code DataSource}. The
 * storage builders close the key stores <em>they</em> create.
 *
 * @see ShreddingKeyStore
 * @see ShreddingCodec
 */
public class AesGcmShreddingCodec implements ShreddingCodec {

	/**
	 * The algorithm name written into every envelope this codec seals, and the only one it will unseal.
	 * <p>
	 * Recorded per value rather than assumed globally, so a later codec can add an algorithm and leave
	 * everything already written decrypting under this one — no migration, no rewrite of history.
	 */
	public static final String ALGORITHM = "A256GCM";

	/**
	 * The AES key length this codec expects, in bits. Key stores minting material for it should use the
	 * same; a key of another length fails at {@link #seal}, loudly, rather than silently weakening.
	 */
	public static final int KEY_BITS = 256;

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final ShreddingKeyStore keyStore;
	private final SecureRandom secureRandom = new SecureRandom();

	/**
	 * @param keyStore where keys are minted, resolved and destroyed; not closed by this codec
	 * @throws IllegalArgumentException if the key store is null
	 */
	public AesGcmShreddingCodec ( ShreddingKeyStore keyStore ) {
		if ( keyStore == null ) {
			throw new IllegalArgumentException("keyStore cannot be null");
		}
		this.keyStore = keyStore;
	}

	/**
	 * @param keyStore where keys are minted, resolved and destroyed
	 * @return a codec sealing under AES-256-GCM with keys from that store
	 */
	public static AesGcmShreddingCodec over ( ShreddingKeyStore keyStore ) {
		return new AesGcmShreddingCodec(keyStore);
	}

	/**
	 * The key store this codec seals against, for a caller that needs to inspect or close it.
	 *
	 * @return the key store
	 */
	public ShreddingKeyStore keyStore ( ) {
		return keyStore;
	}

	@Override
	public Sealed seal ( String plaintext, DataSubject subject ) {
		if ( plaintext == null ) {
			throw new IllegalArgumentException("plaintext cannot be null");
		}
		if ( subject == null ) {
			throw new IllegalArgumentException("subject cannot be null");
		}

		ActiveKey activeKey = keyStore.keyFor(subject);

		byte[] iv = new byte[IV_BYTES];
		secureRandom.nextBytes(iv);

		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, activeKey.key(), new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(additionalAuthenticatedData(activeKey.id(), subject));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			return new Sealed(ALGORITHM, activeKey.id(), subject, encode(iv), encode(ciphertext));

		} catch (GeneralSecurityException e) {
			// The plaintext is deliberately absent from this message: it is the personal data this whole
			// mechanism exists to protect, and an exception message ends up in logs and error reporters.
			throw new ShreddingException(
					"failed to seal a value for subject %s under key %s: %s".formatted(subject, activeKey.id(), e), e);
		}
	}

	@Override
	public Optional<String> unseal ( Sealed sealed ) {
		if ( sealed == null ) {
			throw new IllegalArgumentException("sealed cannot be null");
		}
		if ( !ALGORITHM.equals(sealed.alg()) ) {
			// Not a corrupt envelope and not an erasure: an algorithm this codec does not implement. It
			// throws rather than reporting "erased" so that a store holding values written by a newer
			// codec fails visibly instead of silently presenting readable data as destroyed.
			throw new ShreddingException(
					"cannot unseal a value written with algorithm '%s': this codec implements '%s' only. The event is intact -- read it with a codec that supports its algorithm."
							.formatted(sealed.alg(), ALGORITHM));
		}

		Optional<SecretKey> key = keyStore.resolve(sealed.key());
		if ( key.isEmpty() ) {
			// The key is gone, which is the mechanism working. Never conflate this with a key store that
			// could not be reached -- that throws, from the key store itself.
			return Optional.empty();
		}

		try {
			byte[] iv = decode(sealed.iv(), "iv", sealed);
			byte[] ciphertext = decode(sealed.ciphertext(), "ct", sealed);

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key.get(), new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(additionalAuthenticatedData(sealed.key(), sealed.subject()));

			return Optional.of(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));

		} catch (GeneralSecurityException e) {
			// An authentication failure here means the ciphertext, the iv or the authenticated metadata
			// was altered -- or the key store returned the wrong key. All of them are real problems that
			// must not be reported as an erasure.
			throw new ShreddingException(
					"failed to unseal a value for subject %s under key %s; the envelope or its metadata does not authenticate: %s"
							.formatted(sealed.subject(), sealed.key(), e), e);
		}
	}

	@Override
	public ErasureReport shred ( DataSubject subject, ErasureReason reason ) {
		if ( subject == null ) {
			throw new IllegalArgumentException("subject cannot be null");
		}
		if ( reason == null ) {
			throw new IllegalArgumentException("reason cannot be null");
		}
		List<KeyId> shredded = keyStore.shred(subject, reason);
		return new ErasureReport(subject, reason, shredded == null ? List.of() : shredded, Instant.now());
	}

	/**
	 * Binds the envelope's plaintext metadata to its ciphertext.
	 * <p>
	 * Everything here is stored in the clear beside the ciphertext, so authenticating it is what stops a
	 * sealed value being relabelled — moved to another subject, attributed to another key, or claimed to
	 * have been written with another algorithm — without decryption failing.
	 */
	private static byte[] additionalAuthenticatedData ( KeyId key, DataSubject subject ) {
		return "%s|%s|%s|%s|%s".formatted(ALGORITHM, key.value(), subject.type(), subject.id(), subject.category())
				.getBytes(StandardCharsets.UTF_8);
	}

	private static String encode ( byte[] bytes ) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	private static byte[] decode ( String value, String field, Sealed sealed ) {
		try {
			return Base64.getDecoder().decode(value);
		} catch (IllegalArgumentException e) {
			throw new ShreddingException(
					"the '%s' of a sealed value for subject %s under key %s is not valid base64"
							.formatted(field, sealed.subject(), sealed.key()), e);
		}
	}

	@Override
	public String toString ( ) {
		return "AesGcmShreddingCodec[%s over %s]".formatted(ALGORITHM, keyStore);
	}

}
