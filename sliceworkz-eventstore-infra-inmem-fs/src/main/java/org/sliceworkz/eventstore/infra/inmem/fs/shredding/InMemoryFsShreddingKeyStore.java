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
package org.sliceworkz.eventstore.infra.inmem.fs.shredding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.sliceworkz.eventstore.infra.inmem.shredding.InMemoryShreddingKeyStore;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A file-backed {@link org.sliceworkz.eventstore.shredding.ShreddingKeyStore}: the in-memory store with
 * its contents read from disk at startup and written back on every change.
 * <p>
 * Keys live in one JSON-lines file, {@code keys.jsonl}, under the given directory. Everything is held in
 * memory while the store runs — reads never touch the disk — so this behaves exactly like
 * {@link InMemoryShreddingKeyStore} apart from surviving a restart, which is what makes it the right
 * companion to the file-backed event storage.
 *
 * <h2>Shredding rewrites the file; it does not append a tombstone</h2>
 * This is the one place where the obvious append-only design would be wrong. A log that records "key
 * destroyed" while the line that carries the key material stays above it has destroyed nothing: anyone
 * with the file still holds the key, and the personal data is still readable. Erasure therefore rewrites
 * the whole file, through a temporary file and an atomic move, so the material is gone from it when the
 * call returns. Minting a key only appends, which is the common case and stays cheap.
 * <p>
 * What is <em>not</em> promised is that the bytes are unrecoverable from the underlying device. A
 * rewrite leaves the old file's blocks on disk until they are reused, and on a copy-on-write or
 * log-structured filesystem, an SSD with wear levelling, or a snapshotted volume, they may survive
 * indefinitely. This key store is meant for development and for tests; where an erasure has to hold up
 * against someone with the disk, keep keys in a store that can actually destroy them.
 *
 * <h2>Durability</h2>
 * Each write is flushed and the file is atomically replaced, so a crash leaves either the previous state
 * or the new one. Keys are written before the append that uses them can commit — see
 * {@link InMemoryShreddingKeyStore#keyCreated}, which is called under the store's monitor — so a crash
 * between the two leaves an orphan key that decrypts nothing, never an event nothing can decrypt.
 *
 * @see InMemoryShreddingKeyStore
 */
public class InMemoryFsShreddingKeyStore extends InMemoryShreddingKeyStore {

	/**
	 * The file, under the configured directory, that holds this store's keys.
	 */
	public static final String KEYS_FILE_NAME = "keys.jsonl";

	private static final String KEY_ALGORITHM = "AES";

	private final Path keysFile;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	/**
	 * Opens the key store in a directory, creating it if needed and loading whatever is already there.
	 *
	 * @param directory where {@value #KEYS_FILE_NAME} lives
	 * @throws IllegalArgumentException if the directory is null
	 * @throws ShreddingException if the directory or the existing file cannot be read
	 */
	public InMemoryFsShreddingKeyStore ( Path directory ) {
		if ( directory == null ) {
			throw new IllegalArgumentException("directory cannot be null");
		}
		this.keysFile = directory.resolve(KEYS_FILE_NAME);
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			throw new ShreddingException("cannot create the shredding key directory %s".formatted(directory), e);
		}
		load();
	}

	/**
	 * Opens the key store in a directory named by a string.
	 *
	 * @param directory where {@value #KEYS_FILE_NAME} lives
	 */
	public InMemoryFsShreddingKeyStore ( String directory ) {
		this(Path.of(directory));
	}

	/**
	 * Reads every key back into memory. Called once, from the constructor.
	 */
	private void load ( ) {
		if ( !Files.exists(keysFile) ) {
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(keysFile, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new ShreddingException("cannot read the shredding keys from %s".formatted(keysFile), e);
		}

		for ( String line : lines ) {
			if ( line.isBlank() ) {
				continue;
			}
			try {
				restore(fromJson(jsonMapper.readTree(line)));
			} catch (RuntimeException e) {
				// A key that cannot be parsed is not a key that can be ignored: every event sealed under
				// it would silently read as erased, which is exactly the confusion this design works to
				// avoid everywhere else.
				throw new ShreddingException(
						"cannot parse a shredding key in %s; refusing to start with keys missing, because every value sealed under them would read as erased".formatted(keysFile), e);
			}
		}
	}

	@Override
	protected void keyCreated ( KeyId keyId, DataSubject subject, SecretKey material ) {
		// Appending is enough for a new key, and keeps the common path cheap.
		ObjectNode node = jsonMapper.createObjectNode();
		node.put("id", keyId.value());
		node.set("subject", subjectNode(subject));
		node.put("material", Base64.getEncoder().encodeToString(material.getEncoded()));
		node.put("createdAt", Instant.now().toString());

		try {
			Files.writeString(keysFile, node.toString() + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new ShreddingException("cannot persist a new shredding key to %s".formatted(keysFile), e);
		}
	}

	@Override
	protected void keysShredded ( List<KeyId> shreddedKeys, DataSubject subject, ErasureReason reason, Instant shreddedAt ) {
		// A full rewrite, not an appended tombstone: the point of an erasure is that the key material
		// stops existing, and appending "destroyed" beneath the material destroys nothing.
		rewrite();
	}

	private void rewrite ( ) {
		StringBuilder contents = new StringBuilder();
		for ( StoredKey key : storedKeys() ) {
			contents.append(toJson(key)).append(System.lineSeparator());
		}

		Path temporary = keysFile.resolveSibling(KEYS_FILE_NAME + ".rewriting");
		try {
			Files.writeString(temporary, contents.toString(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
			try {
				Files.move(temporary, keysFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				// Some filesystems cannot promise it; a plain replace still leaves a consistent file, and
				// failing an erasure over this would be the worse outcome.
				Files.move(temporary, keysFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new ShreddingException(
					"cannot rewrite %s after an erasure; the key material may still be on disk and the erasure must be treated as incomplete".formatted(keysFile), e);
		}
	}

	private ObjectNode subjectNode ( DataSubject subject ) {
		ObjectNode node = jsonMapper.createObjectNode();
		node.put("type", subject.type());
		node.put("id", subject.id());
		node.put("category", subject.category());
		return node;
	}

	private String toJson ( StoredKey key ) {
		ObjectNode node = jsonMapper.createObjectNode();
		node.put("id", key.id().value());
		node.set("subject", subjectNode(key.subject()));
		if ( key.material() != null ) {
			node.put("material", Base64.getEncoder().encodeToString(key.material().getEncoded()));
		} else {
			node.putNull("material");
		}
		node.put("createdAt", key.createdAt().toString());
		if ( key.shreddedAt() != null ) {
			node.put("shreddedAt", key.shreddedAt().toString());
			node.put("reason", key.reason() == null ? null : key.reason().value());
		}
		return node.toString();
	}

	private StoredKey fromJson ( JsonNode node ) {
		JsonNode subject = node.get("subject");
		DataSubject dataSubject = new DataSubject(
				subject.get("type").asString(),
				subject.get("id").asString(),
				subject.get("category").asString());

		JsonNode material = node.get("material");
		SecretKey secretKey = material == null || material.isNull()
				? null
				: new SecretKeySpec(Base64.getDecoder().decode(material.asString()), KEY_ALGORITHM);

		JsonNode shreddedAt = node.get("shreddedAt");
		JsonNode reason = node.get("reason");

		return new StoredKey(
				KeyId.of(node.get("id").asString()),
				dataSubject,
				secretKey,
				Instant.parse(node.get("createdAt").asString()),
				shreddedAt == null || shreddedAt.isNull() ? null : Instant.parse(shreddedAt.asString()),
				reason == null || reason.isNull() ? null : ErasureReason.of(reason.asString()));
	}

	/**
	 * Where this key store keeps its keys.
	 *
	 * @return the path of {@value #KEYS_FILE_NAME}
	 */
	public Path keysFile ( ) {
		return keysFile;
	}

	@Override
	public String toString ( ) {
		return "InMemoryFsShreddingKeyStore[%s]".formatted(keysFile);
	}

}
