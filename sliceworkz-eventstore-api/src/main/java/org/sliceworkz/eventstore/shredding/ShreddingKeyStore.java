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

import java.util.List;
import java.util.Optional;

import javax.crypto.SecretKey;

/**
 * Where data encryption keys live, and what erasure destroys.
 * <p>
 * This is the narrow of the two shredding seams. Implement it to keep the library's AES-256-GCM
 * encryption but hold the keys somewhere of your own — Vault, a cloud KMS, an HSM, a table in another
 * database. Implement {@link ShreddingCodec} instead to take over the cryptography as well.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>{@link #keyFor} creates on first sight and returns the same key afterwards.</b> It is called
 *       once per distinct {@link DataSubject} per append, so it must be cheap on the hot path and safe
 *       under concurrency: two threads appending for the same subject at the same moment must end up
 *       with one key, not two. A subject whose key was shredded gets a <em>new</em> key, so data
 *       appended after an erasure is readable again — the old ciphertext stays unreadable.</li>
 *   <li><b>{@link #resolve} answers empty only for a destroyed key.</b> See below; this is the one
 *       contract that must not be got wrong.</li>
 *   <li><b>{@link #shred} is idempotent</b> and returns what it actually destroyed, so a second erasure
 *       for the same subject reports an empty list rather than failing.</li>
 *   <li><b>Key material is never resurrected.</b> Destroying a key means the bytes are gone; keep the
 *       row, with the material nulled and the reason and timestamp stamped, so the erasure remains
 *       auditable and the key id keeps resolving to "shredded" rather than to "unknown".</li>
 * </ul>
 *
 * <h2>Empty means erased; unavailable means throw</h2>
 * {@link #resolve} returns an empty {@link Optional} <em>only</em> when the key genuinely no longer
 * exists. Every other failure — an unreachable Vault, an expired token, a timeout, a permissions
 * problem — must throw {@link ShreddingException}.
 * <p>
 * Collapsing the two is the most damaging mistake an implementation can make. Reported as empty, a
 * transient outage renders every protected value as erased; projections are at-least-once and advance
 * a bookmark past what they have handled, so they write those gaps into read models and never revisit
 * them. A five-minute outage becomes permanent, silent data loss in every downstream copy. Reported as
 * an exception, the read fails loudly, the bookmark does not move, and the projection recovers by
 * itself once the key store is back.
 *
 * <h2>Ordering, when the key store is not transactional with the events</h2>
 * The default key stores that ship with a SQL backend write keys on the same {@code DataSource} as the
 * events, so a key mint and the append that needs it commit together. An external key store cannot do
 * that, and then the order is the whole guarantee: <b>mint the key first, append second</b>. A crash
 * between the two leaves an orphan key, which decrypts nothing and costs nothing. The other order
 * leaves an event whose key was never persisted — a value that can never be read, which is
 * indistinguishable from an erasure nobody asked for.
 *
 * @see ShreddingCodec
 * @see DataSubject
 */
public interface ShreddingKeyStore extends AutoCloseable {

	/**
	 * The key currently in use for a subject, created if the subject has none.
	 * <p>
	 * Called on the append path, once per distinct subject appearing in the events being appended.
	 *
	 * @param subject whose data is about to be sealed
	 * @return the active key and its id, never null
	 * @throws ShreddingException if the key store cannot be reached or the key cannot be created
	 */
	ActiveKey keyFor ( DataSubject subject );

	/**
	 * The key material for a key id, or empty if that key has been destroyed.
	 * <p>
	 * Called on the read path, once per distinct key id in the events being read. Implementations are
	 * expected to cache; see {@link ShreddingCodec} for what that costs in erasure latency.
	 *
	 * @param key the key id taken from a sealed envelope
	 * @return the key material, or empty if the key was shredded or never existed
	 * @throws ShreddingException if the key store cannot be reached — never for a destroyed key
	 */
	Optional<SecretKey> resolve ( KeyId key );

	/**
	 * Destroys every key held for a subject, recording why.
	 *
	 * @param subject whose keys to destroy
	 * @param reason  the authority for the erasure, persisted alongside the shredded key
	 * @return the keys destroyed by this call; empty if the subject held none
	 * @throws ShreddingException if the key store cannot be reached
	 */
	List<KeyId> shred ( DataSubject subject, ErasureReason reason );

	/**
	 * Releases whatever this key store holds — connections, caches, a background refresher.
	 * <p>
	 * Idempotent, and never throws. A key store handed to a storage builder is closed with the storage;
	 * one you construct and keep is yours to close.
	 */
	@Override
	default void close ( ) {
		// nothing to release by default
	}

	/**
	 * A key together with the id that names it in a sealed envelope.
	 * <p>
	 * Returned as a pair so that appending does not have to mint and then look up the same key again.
	 *
	 * @param id  what to write into the envelope, and into the event's {@code dek:} tag
	 * @param key the material to encrypt with
	 */
	record ActiveKey ( KeyId id, SecretKey key ) {

		/**
		 * @throws IllegalArgumentException if either component is null
		 */
		public ActiveKey {
			if ( id == null ) {
				throw new IllegalArgumentException("ActiveKey id must not be null");
			}
			if ( key == null ) {
				throw new IllegalArgumentException("ActiveKey key must not be null");
			}
		}

	}

}
