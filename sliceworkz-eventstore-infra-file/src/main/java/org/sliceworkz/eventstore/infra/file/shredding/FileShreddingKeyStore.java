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
package org.sliceworkz.eventstore.infra.file.shredding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.sliceworkz.eventstore.infra.file.log.BinaryFormat;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.KeyAuditQuery;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingAudit;
import org.sliceworkz.eventstore.shredding.ShreddingException;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;

/**
 * A key store that survives a restart, for pairing with {@code FileEventStorage}.
 * <p>
 * Everything is held in memory while the store runs, so a read never touches the disk; the file is the
 * record that lets the keys outlive the process. Pair a durable event log with a durable key store, or
 * every protected value in it reads as erased the next time the application starts.
 *
 * <h2>Erasure rewrites the file; it never appends a tombstone</h2>
 * This is the one place in this module where the append-only instinct is wrong, and wrong in a way that
 * defeats the entire point of crypto-shredding. A log that records "key destroyed" while the record
 * carrying the key material still sits above it has destroyed nothing: anyone holding the file holds the
 * key, and the personal data is still readable. So minting a key appends — that is the common case and it
 * stays cheap — and an erasure rewrites the whole file through a temporary file and an atomic move, so
 * the material is gone from it by the time the call returns.
 * <p>
 * What is <em>not</em> promised is that the bytes become unrecoverable from the device. A rewrite leaves
 * the old file's blocks in place until they are reused, and on a copy-on-write or log-structured
 * filesystem, an SSD doing wear levelling, or a snapshotted volume, they can survive indefinitely. Where
 * an erasure has to hold up against someone holding the disk, keep keys somewhere that can actually
 * destroy them — a KMS or an HSM behind this same interface.
 *
 * <h2>Empty means erased, and nothing else may say so</h2>
 * {@link #resolve} returns empty only for a key that was destroyed or never existed. Anything a retry
 * would fix throws {@link ShreddingException} instead, because reporting a transient failure as an
 * erasure is permanent damage: projections are at-least-once and bookmarked, so they would write the
 * resulting gaps into read models and never look again.
 * <p>
 * That is also why a key record that does not decode makes the constructor throw rather than skipping it.
 * Skipping one would make every value sealed under it read as erased — silently, and for good.
 *
 * <h2>Frames, magic {@code "SWKY"}</h2>
 * <pre>
 *  str  keyId
 *  str  subjectType
 *  str  subjectId
 *  str  subjectCategory
 *  i64  createdAt, seconds from the epoch
 *  i32  createdAt, nanosecond of second
 *  i32  materialLength     -1 once the key has been shredded
 *  u8[] material           absent when shredded
 *  u8   flags              bit0: a shredding time and reason follow
 *  i64  shreddedAt, seconds from the epoch
 *  i32  shreddedAt, nanosecond of second
 *  str  reason
 * </pre>
 */
public class FileShreddingKeyStore implements ShreddingKeyStore {

	private static final String KEY_FILE = "keys.bin";
	private static final String KEY_ALGORITHM = "AES";
	private static final int KEY_BITS = 256;

	private static final byte FLAG_SHREDDED = 0x01;

	private final Path path;

	/** Insertion-ordered, so the audit can report newest first by walking it backwards. */
	private final Map<KeyId, StoredKey> keys = new LinkedHashMap<>();
	private final Map<DataSubject, KeyId> activeKeys = new LinkedHashMap<>();

	/**
	 * Opens a key store in a directory, loading whatever it already holds.
	 * <p>
	 * Pointing this at the event storage's own directory is convenient and puts the keys beside the
	 * ciphertext they protect, so anyone with the directory has both. That is fine for development and
	 * for tests; it is not a deployment posture.
	 *
	 * @param directory where {@code keys.bin} lives; created if absent
	 * @throws ShreddingException if the directory or the key file cannot be read, or holds a record that
	 *         does not decode
	 */
	public FileShreddingKeyStore ( Path directory ) {
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			throw new ShreddingException("could not create the key store directory " + directory, e);
		}
		this.path = directory.resolve(KEY_FILE);
		load();
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
		StoredKey stored = new StoredKey(keyId, subject, material, Instant.now(), null, null);

		// written before the call returns, and so before the append that uses this key can commit: a
		// crash between the two leaves an orphan key that decrypts nothing, never an event that nothing
		// can decrypt
		append(stored);

		keys.put(keyId, stored);
		activeKeys.put(subject, keyId);
		return new ActiveKey(keyId, material);
	}

	@Override
	public synchronized Optional<SecretKey> resolve ( KeyId key ) {
		if ( key == null ) {
			throw new IllegalArgumentException("key cannot be null");
		}
		StoredKey stored = keys.get(key);
		// a key that was shredded and one this store never knew are both "no key": there is nothing to
		// decrypt with either way, and neither is something a retry would fix. Everything here is in
		// memory by the time this is called, so there is no transient failure to confuse with an erasure
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

		// every key ever minted for the subject, not only the active one: a subject appended for after an
		// earlier erasure holds a second key, and leaving it would make the second erasure a no-op while
		// the data it protects stayed readable
		for ( StoredKey stored : new ArrayList<>(keys.values()) ) {
			if ( stored.subject().equals(subject) && stored.material() != null ) {
				keys.put(stored.id(), stored.shredded(shreddedAt, reason));
				shredded.add(stored.id());
			}
		}

		activeKeys.remove(subject);

		if ( !shredded.isEmpty() ) {
			rewrite();
		}
		return List.copyOf(shredded);
	}

	@Override
	public synchronized Optional<ShreddingAudit> audit ( ) {
		return Optional.of(new FileAudit());
	}

	@Override
	public String toString ( ) {
		return "FileShreddingKeyStore[%s, %d key(s)]".formatted(path, keys.size());
	}

	// ---------------------------------------------------------------------------------------------
	// audit
	// ---------------------------------------------------------------------------------------------

	/**
	 * Reports on this store's keys without handing out any of them.
	 * <p>
	 * {@link KeyRecord} has nowhere to put key material, which is the point of the audit being a second
	 * interface rather than another method here: a credential granted it can see that data is protected
	 * and when it was erased, and never what it was.
	 */
	private final class FileAudit implements ShreddingAudit {

		@Override
		public List<KeyRecord> keys ( KeyAuditQuery query ) {
			if ( query == null ) {
				throw new IllegalArgumentException("query cannot be null");
			}
			synchronized ( FileShreddingKeyStore.this ) {
				List<StoredKey> newestFirst = new ArrayList<>(FileShreddingKeyStore.this.keys.values());
				Collections.reverse(newestFirst);

				List<KeyRecord> matching = new ArrayList<>();
				for ( StoredKey stored : newestFirst ) {
					if ( matches(stored, query) ) {
						matching.add(new KeyRecord(stored.id(), stored.subject(), stored.createdAt(),
								Optional.ofNullable(stored.shreddedAt()), Optional.ofNullable(stored.reason())));
						if ( matching.size() >= query.limit() ) {
							break;
						}
					}
				}
				return List.copyOf(matching);
			}
		}

		@Override
		public ShreddingTotals totals ( ) {
			synchronized ( FileShreddingKeyStore.this ) {
				long live = 0;
				long shredded = 0;
				Set<DataSubject> subjects = new HashSet<>();
				for ( StoredKey stored : FileShreddingKeyStore.this.keys.values() ) {
					if ( stored.material() == null ) {
						shredded++;
					} else {
						live++;
						subjects.add(stored.subject());
					}
				}
				return new ShreddingTotals(subjects.size(), live, shredded);
			}
		}

		private boolean matches ( StoredKey stored, KeyAuditQuery query ) {
			if ( query.shreddedOnly() && stored.material() != null ) {
				return false;
			}
			DataSubject subject = stored.subject();
			return ( query.subjectType() == null || query.subjectType().equals(subject.type()) )
					&& ( query.subjectId() == null || query.subjectId().equals(subject.id()) )
					&& ( query.category() == null || query.category().equals(subject.category()) );
		}
	}

	// ---------------------------------------------------------------------------------------------
	// persistence
	// ---------------------------------------------------------------------------------------------

	private void append ( StoredKey stored ) {
		byte[] frame = frameOf(stored);
		try {
			Files.write(path, frame, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.APPEND, StandardOpenOption.SYNC);
		} catch (IOException e) {
			throw new ShreddingException("could not write a key to " + path, e);
		}
	}

	private void rewrite ( ) {
		ByteBuffer rewritten = BinaryFormat.buffer(keys.values().stream().mapToInt(k -> frameOf(k).length).sum());
		for ( StoredKey stored : keys.values() ) {
			rewritten.put(frameOf(stored));
		}

		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.write(temporary, rewritten.array(), StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new ShreddingException("could not rewrite the key store " + path
					+ "; the key material it holds has NOT been destroyed", e);
		}
	}

	private void load ( ) {
		if ( !Files.exists(path) ) {
			return;
		}

		byte[] bytes;
		try {
			bytes = Files.readAllBytes(path);
		} catch (IOException e) {
			throw new ShreddingException("could not read the key store " + path, e);
		}

		ByteBuffer buffer = BinaryFormat.wrap(bytes);
		int offset = 0;
		while ( offset < bytes.length ) {
			if ( offset + BinaryFormat.FRAME_HEADER_BYTES > bytes.length ) {
				throw new ShreddingException(truncationMessage(offset, bytes.length));
			}
			buffer.position(offset);
			int magic = buffer.getInt();
			int bodyLength = buffer.getInt();
			int storedCrc = buffer.getInt();

			if ( magic != BinaryFormat.MAGIC_KEY || bodyLength <= 0
					|| bodyLength > BinaryFormat.MAX_FRAME_BODY_BYTES
					|| offset + BinaryFormat.FRAME_HEADER_BYTES + bodyLength > bytes.length
					|| BinaryFormat.crc32c(bytes, offset + BinaryFormat.FRAME_HEADER_BYTES, bodyLength) != storedCrc ) {
				throw new ShreddingException(truncationMessage(offset, bytes.length));
			}

			StoredKey stored;
			try {
				stored = decode(buffer);
			} catch (RuntimeException e) {
				throw new ShreddingException(truncationMessage(offset, bytes.length), e);
			}

			// a later record for a key supersedes an earlier one, which is what makes an interrupted
			// rewrite recoverable in the only direction that is safe
			keys.put(stored.id(), stored);
			if ( stored.material() != null ) {
				activeKeys.put(stored.subject(), stored.id());
			} else {
				activeKeys.remove(stored.subject(), stored.id());
			}

			offset += BinaryFormat.FRAME_HEADER_BYTES + bodyLength;
		}
	}

	/**
	 * A key store is not allowed to shrug off a record it cannot read.
	 * <p>
	 * The event log truncates a ragged tail because an append that did not finish never happened. A key
	 * that cannot be read is the opposite: the events sealed under it are still there, and dropping it
	 * would report every one of their protected values as erased — quietly, permanently, and with a
	 * perfectly healthy-looking store.
	 */
	private String truncationMessage ( int offset, int length ) {
		return ("the key store %s does not decode at byte %d of %d. Refusing to open rather than skipping the record: "
				+ "a key that is merely unreadable would make every value sealed under it look erased, which is not "
				+ "something that can be undone. Restore the file from a backup.").formatted(path, offset, length);
	}

	private static byte[] frameOf ( StoredKey stored ) {
		byte[] material = stored.material() == null ? null : stored.material().getEncoded();
		boolean hasShredding = stored.shreddedAt() != null;

		int size = BinaryFormat.stringSize(stored.id().value())
				+ BinaryFormat.stringSize(stored.subject().type())
				+ BinaryFormat.stringSize(stored.subject().id())
				+ BinaryFormat.stringSize(stored.subject().category())
				+ Long.BYTES + Integer.BYTES
				+ Integer.BYTES + ( material == null ? 0 : material.length )
				+ Byte.BYTES;
		if ( hasShredding ) {
			size += Long.BYTES + Integer.BYTES
					+ BinaryFormat.stringSize(stored.reason() == null ? null : stored.reason().value());
		}

		ByteBuffer body = BinaryFormat.buffer(size);
		BinaryFormat.putString(body, stored.id().value());
		BinaryFormat.putString(body, stored.subject().type());
		BinaryFormat.putString(body, stored.subject().id());
		BinaryFormat.putString(body, stored.subject().category());
		body.putLong(stored.createdAt().getEpochSecond());
		body.putInt(stored.createdAt().getNano());
		if ( material == null ) {
			body.putInt(BinaryFormat.NULL_LENGTH);
		} else {
			body.putInt(material.length);
			body.put(material);
		}
		body.put(hasShredding ? FLAG_SHREDDED : 0);
		if ( hasShredding ) {
			body.putLong(stored.shreddedAt().getEpochSecond());
			body.putInt(stored.shreddedAt().getNano());
			BinaryFormat.putString(body, stored.reason() == null ? null : stored.reason().value());
		}

		ByteBuffer frame = BinaryFormat.buffer(BinaryFormat.FRAME_HEADER_BYTES + size);
		frame.putInt(BinaryFormat.MAGIC_KEY);
		frame.putInt(size);
		frame.putInt(BinaryFormat.crc32c(body.array(), 0, size));
		frame.put(body.array());
		return frame.array();
	}

	private static StoredKey decode ( ByteBuffer body ) {
		KeyId id = KeyId.of(BinaryFormat.getString(body));
		String type = BinaryFormat.getString(body);
		String subjectId = BinaryFormat.getString(body);
		String category = BinaryFormat.getString(body);
		Instant createdAt = Instant.ofEpochSecond(body.getLong(), body.getInt());

		int materialLength = body.getInt();
		SecretKey material = null;
		if ( materialLength != BinaryFormat.NULL_LENGTH ) {
			byte[] bytes = new byte[materialLength];
			body.get(bytes);
			material = new SecretKeySpec(bytes, KEY_ALGORITHM);
		}

		Instant shreddedAt = null;
		ErasureReason reason = null;
		if ( ( body.get() & FLAG_SHREDDED ) != 0 ) {
			shreddedAt = Instant.ofEpochSecond(body.getLong(), body.getInt());
			String value = BinaryFormat.getString(body);
			reason = value == null ? null : ErasureReason.of(value);
		}

		return new StoredKey(id, new DataSubject(type, subjectId, category), material, createdAt, shreddedAt, reason);
	}

	private static SecretKey generateKey ( ) {
		try {
			KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
			keyGenerator.init(KEY_BITS);
			return keyGenerator.generateKey();
		} catch (NoSuchAlgorithmException e) {
			throw new ShreddingException(
					"this JVM has no %s key generator, so personal data cannot be protected".formatted(KEY_ALGORITHM), e);
		}
	}

	/**
	 * One key, with everything needed to audit it after its material is gone.
	 *
	 * @param id names the key in a sealed envelope and in the event's {@code dek:} tag
	 * @param subject whose data it protects
	 * @param material the key, or null once it has been shredded
	 * @param createdAt when it was minted
	 * @param shreddedAt when it was destroyed, or null
	 * @param reason on whose authority it was destroyed, or null
	 */
	private record StoredKey ( KeyId id, DataSubject subject, SecretKey material, Instant createdAt,
			Instant shreddedAt, ErasureReason reason ) {

		StoredKey shredded ( Instant at, ErasureReason why ) {
			return new StoredKey(id, subject, null, createdAt, at, why);
		}
	}

}
