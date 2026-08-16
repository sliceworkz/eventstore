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

import java.util.Optional;

/**
 * Turns a {@link Shreddable} value into a sealed envelope and back, and destroys the keys that make
 * that possible.
 * <p>
 * This is the outer of the two shredding seams. Implement it to take over encryption and key handling
 * together — the library then never sees key material at all, which is what lets keys stay inside an
 * HSM and never enter this JVM's heap. To keep the shipped AES-256-GCM implementation and only move
 * where keys are stored, implement {@link ShreddingKeyStore} instead and let the default codec use it.
 *
 * <h2>Empty means erased; unavailable means throw</h2>
 * {@link #unseal} returns an empty {@link Optional} <em>only</em> when the key has been destroyed. A
 * key store that cannot be reached, an expired credential, a timeout, a corrupt envelope or an
 * unsupported algorithm must throw {@link ShreddingException}. See {@link ShreddingKeyStore} for why
 * conflating the two turns a transient outage into permanent, silent loss in every read model.
 *
 * <h2>Algorithm agility is a requirement, not a nicety</h2>
 * The algorithm is recorded on every envelope rather than assumed globally, so one store can hold
 * values sealed under several algorithms at once. That is what makes it possible to change algorithm
 * later without rewriting history: new appends use the new algorithm, old events keep decrypting under
 * the one they were written with, and the log stays byte-identical. A codec must therefore dispatch on
 * {@link Sealed#alg()} rather than assume its own current choice, and must throw rather than guess when
 * it meets an algorithm it does not implement.
 * <p>
 * Post-quantum is worth being precise about here, because it mostly does not apply. Shor's algorithm
 * breaks asymmetric cryptography, and the shipped codec uses none; Grover's algorithm is only a
 * quadratic speedup against symmetric ciphers, leaving AES-256 with roughly 128 bits of effective
 * security, which NIST treats as quantum-resistant. Shredding is in fact a stronger position than
 * encryption at rest generally is: the threat model is an attacker holding ciphertext recovered from a
 * backup and <em>not</em> holding the key, because it was destroyed, and no amount of computation
 * recovers a key that does not exist. Post-quantum becomes a real question only one layer out, in an
 * implementation that wraps data keys under a key-encrypting key with RSA-OAEP or ECIES — which is what
 * several KMS products do, and which is exactly the decision this seam leaves to the implementer.
 *
 * <h2>Caching, and what it costs</h2>
 * A codec that resolves a key per value will want to cache. That is expected, and it means shredding is
 * immediate in storage but eventually consistent in memory: a running projector can hold a key that has
 * just been destroyed. Bound the cache with a TTL, or invalidate on erasure, and document which. A codec
 * that keeps keys inside an HSM has no such window, and pays for it in latency per value instead.
 *
 * @see ShreddingKeyStore
 * @see Shreddable
 * @see org.sliceworkz.eventstore.EventStore#erase(DataSubject, ErasureReason)
 */
public interface ShreddingCodec extends AutoCloseable {

	/**
	 * Encrypts one value's serialized form under the subject's current key.
	 * <p>
	 * Called on the append path, once per {@link Shreddable} in the payload. Two values for the same
	 * subject in one event resolve to the same key, and each gets its own initialisation vector.
	 *
	 * @param plaintext the value's JSON form
	 * @param subject   whose data it is; determines which key is used, and is carried on the envelope
	 * @return the sealed envelope, to be written into the event
	 * @throws ShreddingException if the key cannot be obtained or the value cannot be encrypted
	 */
	Sealed seal ( String plaintext, DataSubject subject );

	/**
	 * Decrypts a sealed envelope, or reports that its key is gone.
	 * <p>
	 * Called on the read path, once per sealed value.
	 *
	 * @param sealed the envelope read from the event
	 * @return the value's JSON form, or empty if the key has been destroyed
	 * @throws ShreddingException if the key store cannot be reached, the algorithm is not supported, or
	 *                            the envelope is malformed — never for a destroyed key
	 */
	Optional<String> unseal ( Sealed sealed );

	/**
	 * Destroys every key held for a subject, making all values sealed under them permanently unreadable.
	 * <p>
	 * Idempotent: erasing a subject twice reports an empty second run rather than failing.
	 *
	 * @param subject whose keys to destroy
	 * @param reason  the authority for the erasure, recorded for audit
	 * @return what was destroyed
	 * @throws ShreddingException if the key store cannot be reached
	 */
	ErasureReport shred ( DataSubject subject, ErasureReason reason );

	/**
	 * Reading which subjects hold protected data and which erasures have happened, without the means to
	 * decrypt any of it.
	 *
	 * @return the audit view, or empty if the underlying key store cannot provide one
	 * @see ShreddingAudit
	 */
	default Optional<ShreddingAudit> audit ( ) {
		return Optional.empty();
	}

	/**
	 * Releases whatever this codec holds — a key cache, connections, a key store it owns.
	 * <p>
	 * Idempotent, and never throws. A codec handed to a storage builder is closed with the storage.
	 */
	@Override
	default void close ( ) {
		// nothing to release by default
	}

	/**
	 * One encrypted value, as it is stored inside the event's JSON payload.
	 * <p>
	 * The envelope carries everything needed to decrypt it later except the key itself, and everything
	 * needed to describe it honestly once the key is gone. It is written as a JSON object in place of
	 * the protected value:
	 * <pre>{@code
	 * "from": { "alg": "A256GCM",
	 *           "dek": "k-7f2a91c4",
	 *           "sub": { "type": "customer", "id": "alice-42", "category": "default" },
	 *           "iv":  "yQ3mR1…",
	 *           "ct":  "8Kd2vRhT…" }
	 * }</pre>
	 * The subject is stored in the clear alongside the ciphertext, which is safe only because a subject
	 * id is required to be pseudonymous — see {@link DataSubject}. It is what lets a shredded value
	 * still say whose data it was without a key-store lookup.
	 *
	 * @param alg        names the algorithm, so a store can hold several at once and change without a rewrite
	 * @param key        which key sealed this value
	 * @param subject    whose data it is
	 * @param iv         the initialisation vector, base64; must be unique per value under a given key,
	 *                   and must never be derived from the event's position, since one key protects many events
	 * @param ciphertext the encrypted JSON, base64
	 */
	record Sealed ( String alg, KeyId key, DataSubject subject, String iv, String ciphertext ) {

		/**
		 * @throws IllegalArgumentException if any component is null or blank
		 */
		public Sealed {
			if ( alg == null || alg.isBlank() ) {
				throw new IllegalArgumentException("Sealed alg must not be null or blank: without it the envelope cannot be decrypted after an algorithm change");
			}
			if ( key == null ) {
				throw new IllegalArgumentException("Sealed key must not be null");
			}
			if ( subject == null ) {
				throw new IllegalArgumentException("Sealed subject must not be null");
			}
			if ( iv == null || iv.isBlank() ) {
				throw new IllegalArgumentException("Sealed iv must not be null or blank");
			}
			if ( ciphertext == null ) {
				throw new IllegalArgumentException("Sealed ciphertext must not be null");
			}
		}

	}

}
