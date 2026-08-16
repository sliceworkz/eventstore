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
package org.sliceworkz.eventstore.infra.inmem.shredding;

import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingException;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;

/**
 * A {@link ShreddingKeyStore} holding keys in memory, for development and tests.
 * <p>
 * Keys live for as long as the JVM does. Nothing is persisted, so restarting the process makes every
 * event sealed by the previous run permanently unreadable — which is exactly right for a development
 * store whose events do not survive either, and exactly wrong for anything else. Use the file-backed
 * or SQL key store where the events outlive the process.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>AES-256 keys, minted lazily on the first append for a {@link DataSubject}.</li>
 *   <li>Random {@link KeyId}s, unrelated to the subject — a key id is stored on the event as a
 *       {@code dek:} tag, so deriving it from the subject would leave a re-identifiable trace behind
 *       after erasure.</li>
 *   <li>Shredding destroys the key material and keeps the row, stamped with when and why, so the audit
 *       trail survives and the key id keeps resolving to "erased" rather than to "unknown".</li>
 *   <li>A subject that is appended for after an erasure gets a <em>new</em> key. New data is readable;
 *       what was sealed under the destroyed key never is.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Every operation is synchronized on this instance. That is what makes two threads appending for the
 * same subject at the same moment end up with one key rather than two — with two, half the values would
 * be sealed under a key that a later erasure by subject still finds, but only because this store tracks
 * every key it ever minted for a subject.
 *
 * <p>
 * Pair it with the shipped AES-256-GCM codec, which is what turns keys into protected values.
 */
public class InMemoryShreddingKeyStore implements ShreddingKeyStore {

	private static final String KEY_ALGORITHM = "AES";
	private static final int KEY_BITS = 256;

	private final Map<DataSubject, KeyId> activeKeys = new LinkedHashMap<>();
	private final Map<KeyId, StoredKey> keys = new LinkedHashMap<>();

	/**
	 * Creates an empty key store.
	 */
	public InMemoryShreddingKeyStore ( ) {
		// nothing to set up
	}

	@Override
	public synchronized ActiveKey keyFor ( DataSubject subject ) {
		if ( subject == null ) {
			throw new IllegalArgumentException("subject cannot be null");
		}

		KeyId activeKeyId = activeKeys.get(subject);
		if ( activeKeyId != null ) {
			StoredKey stored = keys.get(activeKeyId);
			if ( stored != null && stored.material() != null ) {
				return new ActiveKey(activeKeyId, stored.material());
			}
		}

		KeyId keyId = KeyId.of("k-" + UUID.randomUUID());
		SecretKey material = generateKey();
		keys.put(keyId, new StoredKey(keyId, subject, material, Instant.now(), null, null));
		activeKeys.put(subject, keyId);
		keyCreated(keyId, subject, material);

		return new ActiveKey(keyId, material);
	}

	@Override
	public synchronized Optional<SecretKey> resolve ( KeyId key ) {
		if ( key == null ) {
			throw new IllegalArgumentException("key cannot be null");
		}
		StoredKey stored = keys.get(key);
		// A key that was shredded and one this store never knew are both "no key": there is nothing to
		// decrypt with either way, and an unknown key id in an envelope this store cannot serve is not
		// something a retry would fix. Anything that a retry *would* fix throws instead -- there is
		// nothing here that can fail transiently, which is the whole reason this store is trivial.
		return stored == null ? Optional.empty() : Optional.ofNullable(stored.material());
	}

	@Override
	public synchronized List<KeyId> shred ( DataSubject subject, ErasureReason reason ) {
		if ( subject == null ) {
			throw new IllegalArgumentException("subject cannot be null");
		}
		if ( reason == null ) {
			throw new IllegalArgumentException("reason cannot be null");
		}

		Instant shreddedAt = Instant.now();
		List<KeyId> shredded = new ArrayList<>();

		// Every key ever minted for the subject, not just the active one: a subject appended for after
		// an earlier erasure holds a second key, and leaving it would make the second erasure a no-op
		// while its data stayed readable.
		for ( StoredKey stored : new ArrayList<>(keys.values()) ) {
			if ( stored.subject().equals(subject) && stored.material() != null ) {
				keys.put(stored.id(), stored.shredded(shreddedAt, reason));
				shredded.add(stored.id());
			}
		}

		activeKeys.remove(subject);

		if ( !shredded.isEmpty() ) {
			keysShredded(shredded, subject, reason, shreddedAt);
		}
		return List.copyOf(shredded);
	}

	/**
	 * Every key this store holds, shredded ones included, oldest first.
	 * <p>
	 * The shredded entries are the audit trail: they carry the reason and the moment the material was
	 * destroyed, which is the only record that an erasure happened at all — the events themselves are
	 * unchanged by it.
	 *
	 * @return an immutable snapshot
	 */
	public synchronized List<StoredKey> storedKeys ( ) {
		return List.copyOf(keys.values());
	}

	/**
	 * Restores a key that was persisted previously, without minting anything.
	 * <p>
	 * The hook a persistent key store built on this one uses to load its contents at startup. A key
	 * whose material is null is restored as already shredded.
	 *
	 * @param storedKey the key to put back
	 */
	protected synchronized void restore ( StoredKey storedKey ) {
		keys.put(storedKey.id(), storedKey);
		if ( storedKey.material() != null ) {
			activeKeys.put(storedKey.subject(), storedKey.id());
		}
	}

	/**
	 * Called after a new key has been minted, while the monitor is still held.
	 * <p>
	 * A persistent subclass writes the key out here. Doing it under the lock is deliberate: the key must
	 * be durable before the append that uses it can be, or a crash leaves an event nothing can ever
	 * decrypt.
	 *
	 * @param keyId    the new key's id
	 * @param subject  whose data it protects
	 * @param material the key itself
	 */
	protected void keyCreated ( KeyId keyId, DataSubject subject, SecretKey material ) {
		// nothing to persist in memory
	}

	/**
	 * Called after keys have been destroyed, while the monitor is still held.
	 *
	 * @param shreddedKeys the keys whose material was destroyed
	 * @param subject      whose data they protected
	 * @param reason       why
	 * @param shreddedAt   when
	 */
	protected void keysShredded ( List<KeyId> shreddedKeys, DataSubject subject, ErasureReason reason, Instant shreddedAt ) {
		// nothing to persist in memory
	}

	private static SecretKey generateKey ( ) {
		try {
			KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
			keyGenerator.init(KEY_BITS);
			return keyGenerator.generateKey();
		} catch (NoSuchAlgorithmException e) {
			throw new ShreddingException("this JVM has no %s key generator, so personal data cannot be protected".formatted(KEY_ALGORITHM), e);
		}
	}

	@Override
	public String toString ( ) {
		return "InMemoryShreddingKeyStore[%d key(s)]".formatted(keys.size());
	}

	/**
	 * One key, with everything needed to audit it after its material is gone.
	 *
	 * @param id          names the key in a sealed envelope and in the event's {@code dek:} tag
	 * @param subject     whose data it protects
	 * @param material    the key, or null once it has been shredded
	 * @param createdAt   when it was minted
	 * @param shreddedAt  when it was destroyed, or null if it still exists
	 * @param reason      why it was destroyed, or null if it still exists
	 */
	public record StoredKey ( KeyId id, DataSubject subject, SecretKey material, Instant createdAt, Instant shreddedAt, ErasureReason reason ) {

		/**
		 * Records the erasure and drops the key material.
		 *
		 * @param at     when the material was destroyed
		 * @param reason why
		 * @return the same key with its material gone and the erasure recorded
		 */
		public StoredKey shredded ( Instant at, ErasureReason reason ) {
			return new StoredKey(id, subject, null, createdAt, at, reason);
		}

		/**
		 * Whether this key has been destroyed.
		 *
		 * @return true if the material has been destroyed
		 */
		public boolean isShredded ( ) {
			return material == null;
		}

	}

}
