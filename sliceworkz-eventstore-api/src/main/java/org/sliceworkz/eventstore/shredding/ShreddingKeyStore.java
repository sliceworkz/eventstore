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
 *   <li><b>{@link #resolveKey} answers {@link KeyResolution.Erased} only for a destroyed key.</b> See
 *       below; this is the one contract that must not be got wrong. A key id this store has never held
 *       is not a destroyed key: that throws too, see further below.</li>
 *   <li><b>{@link KeyResolution.Denied} is its third answer</b>: the key exists and this caller may not
 *       have it. A store with no notion of entitlement never answers it; one that can tell a refusal
 *       from an outage does, see below.</li>
 *   <li><b>{@link #shred} is idempotent</b> and returns what it actually destroyed, so a second erasure
 *       for the same subject reports an empty list rather than failing.</li>
 *   <li><b>Key material is never resurrected.</b> Destroying a key means the bytes are gone; keep the
 *       row, with the material nulled and the reason and timestamp stamped, so the erasure remains
 *       auditable and the key id keeps resolving to "shredded" rather than to "unknown" — which, since
 *       an unknown key throws, is also what keeps the erased subject's events readable at all.</li>
 * </ul>
 *
 * <h2>Erased means erased; unavailable means throw</h2>
 * {@link #resolveKey} answers {@link KeyResolution.Erased} <em>only</em> when the key genuinely no
 * longer exists. Every other failure — an unreachable Vault, an expired token, a timeout — must throw
 * {@link ShreddingException}; a permissions problem is the third answer, below.
 * <p>
 * Collapsing the two is the most damaging mistake an implementation can make. Reported as erased, a
 * transient outage renders every protected value as erased; projections are at-least-once and advance
 * a bookmark past what they have handled, so they write those gaps into read models and never revisit
 * them. A five-minute outage becomes permanent, silent data loss in every downstream copy. Reported as
 * an exception, the read fails loudly, the bookmark does not move, and the projection recovers by
 * itself once the key store is back.
 *
 * <h2>Denied is a third answer, and it is not an outage either</h2>
 * A key store fronting a KMS or a database with per-role privileges will meet a caller that is not
 * entitled to a key: a 403 from Vault, {@code insufficient_privilege} from PostgreSQL. That is neither an
 * erasure nor a failure. Reported as erased, the value reads as {@link Shreddable.Shredded} and a
 * projection renders "erased" for data that is not. Reported as a {@link ShreddingException}, it means
 * "retry later", and a projector that is simply not entitled fails its batch and never advances. So
 * {@link #resolveKey} has {@link KeyResolution.Denied} for it, which the read path turns into
 * {@link Shreddable.Withheld}: the reader sees whose data it is not shown and carries on.
 * <p>
 * The three answers are a sealed type rather than an exception hierarchy so that an implementation has
 * to name which one it means, and so that a {@code catch (ShreddingException)} retry loop cannot swallow
 * a refusal by accident. There is deliberately no two-answer lookup beside it — an
 * {@code Optional<SecretKey>} that a default derives the three answers from. Such a method cannot say
 * denied, so the derived default never would, and a key store written against it would implement what
 * nothing calls and inherit an answer it never chose. One method, three answers, is the whole seam.
 * <p>
 * This is where the <em>hard</em> boundary lives. A key store's refusal is enforced by whatever holds
 * the keys — a KMS policy per service role, column privileges on the key table — and cannot be argued
 * with from inside the JVM. The in-process alternative, {@link ShreddingCodec#restrictedTo(java.util.Set)},
 * is a data-minimisation boundary a deployment declares for itself; the two compose.
 *
 * <h2>A key this store never held is not erased either</h2>
 * A store keeps the row of every key it destroys, so it can tell a key it shredded from a key id it has
 * never seen — and the two mean different things. "Erased" is the mechanism working: the value is gone
 * for everyone, and reporting it lets a projection move on. An id the store has never held means the
 * store is not the one the events were sealed against: a file-backed store pointed at the wrong
 * directory, a SQL one at the wrong prefix or database, a KMS in the wrong account, events imported
 * without their keys. Every protected value then looks erased at once, and reported that way it is the
 * same permanent, silent gap in every read model as a mis-reported outage, on the whole store rather
 * than a few values. So an unknown key id must throw {@link ShreddingException}, like an outage: the
 * read fails, the bookmark stays, and the projection recovers when the store is pointed at its keys.
 * <p>
 * The alternative — a fourth {@link KeyResolution} answer — was turned down because there is nothing a
 * reader could do with it but throw: the value is not erased, so it cannot be rendered as such, and
 * it is not withheld from this reader in particular. What the rule costs is that a shredded row must
 * stay, which the audit already requires: pruning one turns that subject's events from "erased" into
 * unreadable, with an error naming the key. To move events without their keys deliberately, carry
 * the key rows across shredded — material gone, reason stamped — so the values read as erased and
 * the audit says why.
 * <h2>Ordering: the key is durable before the event is, and no key store is transactional with it</h2>
 * No key store commits together with the append it serves, the shipped ones included: {@link #keyFor}
 * runs while the payload is being sealed, before the storage is handed anything to append, and the
 * Postgres key store takes a connection of its own for it even though it writes to the same
 * {@code DataSource} as the events. So the order is the whole guarantee, for every implementation:
 * <b>mint the key first, and make it durable before returning it; the append comes second</b>. A crash
 * or a rolled-back append between the two leaves an orphan key, which decrypts nothing and costs one
 * row, and is what the subject's next append seals under. The other order — a key returned before it is
 * durable — leaves an event whose key was never persisted: a value that can never be read, which is
 * indistinguishable from an erasure nobody asked for.
 *
 * <h2>Rotation only ever applies forward</h2>
 * There is deliberately no way to rotate a live key and re-seal what it protects. Re-sealing means
 * rewriting stored events, which is the one thing this design exists to avoid: the events stay
 * byte-identical so that destroying a key reaches every copy of them — write-ahead logs, replicas,
 * backups — with nothing to chase. So a subject whose keys are shredded gets a fresh key for data
 * appended afterwards, and everything sealed under the old one stays sealed under it for as long as
 * that ciphertext exists.
 * <p>
 * What <em>can</em> change without rewriting anything is the algorithm, which is recorded per sealed
 * value: new appends can use a new one while old events keep decrypting under the one they were written
 * with. That agility, rather than rotation, is what a long-lived log actually needs.
 *
 * @see ShreddingCodec
 * @see ShreddingAudit
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
	 * The key material for a key id, or why this caller does not get it.
	 * <p>
	 * Called on the read path, once per distinct key id in the events being read. Implementations are
	 * expected to cache; see {@link ShreddingCodec} for what that costs in erasure latency. A store
	 * that has no notion of entitlement answers {@link KeyResolution.Resolved} or
	 * {@link KeyResolution.Erased} and nothing else. One that has — a KMS, a database role without the
	 * privilege — answers {@link KeyResolution.Denied} for a refusal, and keeps throwing
	 * {@link ShreddingException} for everything that a retry might fix.
	 *
	 * @param key the key id taken from a sealed envelope
	 * @return the key, or why it is not available: destroyed, or not for this caller
	 * @throws ShreddingException if the key store cannot be reached, or holds no key of that id and never
	 *                            has — never for a destroyed or denied key
	 */
	KeyResolution resolveKey ( KeyId key );

	/**
	 * Destroys every key held for a subject under the subject's {@link DataSubject#category() category},
	 * recording why.
	 * <p>
	 * A {@link DataSubject} always names one category — {@link DataSubject#DEFAULT_CATEGORY} unless set —
	 * and this destroys the keys of that category only, which is what "erase marketing, retain financial"
	 * needs. Keys the same person holds under other categories are untouched. Erasing a person outright
	 * is {@link #shredAllCategories}, which takes the subject without a category precisely so that it
	 * cannot be narrowed by accident.
	 *
	 * @param subject whose keys to destroy, under which category
	 * @param reason  the authority for the erasure, persisted alongside the shredded key
	 * @return the keys destroyed by this call; empty if the subject held none under that category
	 * @throws ShreddingException if the key store cannot be reached
	 */
	List<KeyId> shred ( DataSubject subject, ErasureReason reason );

	/**
	 * Destroys every key held for a subject under <em>every</em> category, recording why.
	 * <p>
	 * The whole-person erasure: an art.17 request names a person, not a retention category, and a
	 * caller answering it must not have to know which categories the person's data was ever written
	 * under. Nothing about a subject is left readable when this returns, whichever categories it held
	 * keys under and however many keys each of them had accumulated across earlier erasures.
	 * <p>
	 * Answered per category, so the caller can see which slices of the data went. A category the
	 * subject never held a key under, or whose keys were shredded already, gets no report.
	 * <p>
	 * The default throws {@link UnsupportedOperationException}, like the optional methods on
	 * {@code EventStorage}: a key store written before this method existed is told, rather than made
	 * to answer "erased" for one category while the others stay readable. Every shipped key store
	 * implements it; a store that cannot enumerate a subject's categories cannot support it.
	 *
	 * @param subjectType what kind of subject, e.g. {@code "customer"}
	 * @param subjectId   the pseudonymous identifier of the subject within that type
	 * @param reason      the authority for the erasure, persisted alongside every shredded key
	 * @return one report per category that held live keys; empty if the subject held none
	 * @throws ShreddingException            if the key store cannot be reached
	 * @throws UnsupportedOperationException if this key store cannot erase across categories
	 * @throws IllegalArgumentException      if any argument is null or blank
	 */
	default List<ErasureReport> shredAllCategories ( String subjectType, String subjectId, ErasureReason reason ) {
		throw new UnsupportedOperationException(
				"%s cannot erase subject %s/%s across categories: it does not implement ShreddingKeyStore.shredAllCategories; erase each category through shred(DataSubject, ErasureReason)"
						.formatted(getClass().getName(), subjectType, subjectId));
	}

	/**
	 * Reading what this store holds, without the means to decrypt any of it.
	 * <p>
	 * Empty when the store cannot enumerate — one fronting a KMS that does not list, for instance. The
	 * shipped key stores all implement it.
	 *
	 * @return the audit view, or empty if this store cannot provide one
	 * @see ShreddingAudit
	 */
	default Optional<ShreddingAudit> audit ( ) {
		return Optional.empty();
	}

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
	 * The three answers a key lookup can have.
	 * <p>
	 * A sealed type rather than an {@code Optional} plus an exception, so that an implementation names
	 * which one it means and a caller has to handle all three. The difference between them is the most
	 * important contract in this subsystem: {@link Erased} is the mechanism working, {@link Denied} is a
	 * reader that is not entitled, and an outage is neither — that one throws.
	 *
	 * @see ShreddingKeyStore#resolveKey(KeyId)
	 */
	sealed interface KeyResolution permits KeyResolution.Resolved, KeyResolution.Erased, KeyResolution.Denied {

		/**
		 * The key exists and this caller may use it.
		 *
		 * @param key the key material
		 */
		record Resolved ( SecretKey key ) implements KeyResolution {

			/**
			 * @throws IllegalArgumentException if the key is null
			 */
			public Resolved {
				if ( key == null ) {
					throw new IllegalArgumentException("Resolved key must not be null; use Erased for a destroyed key");
				}
			}

		}

		/**
		 * The key has been destroyed. The value sealed under it is gone for everyone.
		 * <p>
		 * Only for a key the store once held: an id it has never seen is a miswiring, and throws.
		 */
		record Erased ( ) implements KeyResolution {

			/**
			 * The one instance; a record with no components has nothing to distinguish two.
			 */
			public static final Erased INSTANCE = new Erased();

		}

		/**
		 * The key exists, and this caller is not entitled to it. The value reads as
		 * {@link Shreddable.Withheld}.
		 *
		 * @param reason what refused it, for a log line; never key material, and never personal data
		 */
		record Denied ( String reason ) implements KeyResolution {

			/**
			 * Normalises a null reason to an empty string.
			 */
			public Denied {
				reason = reason == null ? "" : reason;
			}

		}

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
