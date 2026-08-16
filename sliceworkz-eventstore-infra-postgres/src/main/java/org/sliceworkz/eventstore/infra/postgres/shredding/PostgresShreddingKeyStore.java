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
package org.sliceworkz.eventstore.infra.postgres.shredding;

import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingException;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;

/**
 * A {@link ShreddingKeyStore} keeping data encryption keys in a SQL table beside the events.
 * <p>
 * This is the production key store for the PostgreSQL backend. Keys live in
 * {@code <prefix>shredding_keys}, created and validated by the same schema machinery as the events,
 * bookmarks and lease tables — so {@code ENSURE} creates it, {@code VALIDATE} checks it, and a
 * deployment whose DBA applies DDL by hand is told when it is missing rather than discovering it at the
 * first erasure request.
 *
 * <h2>Keys and events commit together</h2>
 * Both use the storage's own {@code DataSource}, so a minted key and the append that seals under it can
 * land in the same transaction. That removes the ordering hazard an external key store has, where the
 * key must be made durable before the event or a crash between the two leaves an event nothing can
 * decrypt.
 *
 * <h2>Erasure keeps the row</h2>
 * Shredding sets {@code key_material} to NULL and stamps {@code shredded_at} and
 * {@code shredded_reason}. The events are not touched at all: their ciphertext stays byte-identical in
 * the table, in the write-ahead log, on every replica and in every backup, and all of it becomes
 * unreadable at the moment the material goes. Keeping the row is what gives the erasure an audit trail —
 * nothing else records that it happened — and what lets a key id keep resolving to "erased" instead of
 * to "unknown".
 * <p>
 * Every key a subject has ever held is destroyed, not just the active one: a subject appended for after
 * an earlier erasure holds a second key, and missing it would leave that data readable while the
 * erasure reported success.
 *
 * <h2>Caching, and what it costs</h2>
 * Resolved keys are cached in memory, unbounded but keyed on key id, so replaying a stream does not
 * issue a query per protected value. The cache is <b>invalidated on erasure within this instance</b>,
 * so a shred is immediate here. Across a cluster it is not: another instance that has already cached
 * the key keeps decrypting with it until it is restarted or its cache entry is evicted. That is the
 * usual crypto-shredding trade — instant in storage, eventually consistent in memory — and it is worth
 * knowing before promising an exact erasure time.
 * <p>
 * A key that was never seen is not cached as absent, so a shredded key still costs one query per read.
 * That is deliberate: caching absence would make an erasure that happened elsewhere invisible for the
 * cache's lifetime, in the direction that matters least — a few queries against reporting stale data as
 * readable.
 *
 * <h2>Privileges</h2>
 * Needs {@code SELECT}, {@code INSERT} and {@code UPDATE} on the table. It does not need {@code DELETE}:
 * erasure updates a row rather than removing it.
 *
 * <h2>Ownership</h2>
 * The {@code DataSource} is never closed by this key store — it belongs to the storage, which closes
 * it. {@link #close()} only drops the cache.
 *
 * @see ShreddingKeyStore
 * @see org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec
 */
public class PostgresShreddingKeyStore implements ShreddingKeyStore {

	private static final String KEY_ALGORITHM = "AES";
	private static final int KEY_BITS = 256;

	private final DataSource dataSource;
	private final String tableName;

	/**
	 * Resolved key material, by key id. Bounded in practice by the number of distinct subjects a process
	 * actually reads for, not by the number of keys that exist.
	 */
	private final Map<KeyId, SecretKey> cache = new ConcurrentHashMap<>();

	/**
	 * @param dataSource the storage's data source; never closed by this key store
	 * @param prefix     the table prefix the storage was built with, so the key table sits beside the
	 *                   events of the same store; may be empty but not null
	 * @throws IllegalArgumentException if either argument is null
	 */
	public PostgresShreddingKeyStore ( DataSource dataSource, String prefix ) {
		if ( dataSource == null ) {
			throw new IllegalArgumentException("dataSource cannot be null");
		}
		if ( prefix == null ) {
			throw new IllegalArgumentException("prefix cannot be null; use an empty string for no prefix");
		}
		this.dataSource = dataSource;
		this.tableName = prefix + "shredding_keys";
	}

	/**
	 * A key store on a data source, with no table prefix.
	 *
	 * @param dataSource the storage's data source
	 * @return the key store
	 */
	public static PostgresShreddingKeyStore on ( DataSource dataSource ) {
		return new PostgresShreddingKeyStore(dataSource, "");
	}

	/**
	 * A key store on a data source, using the same table prefix as the storage it serves.
	 *
	 * @param dataSource the storage's data source
	 * @param prefix     the storage's table prefix
	 * @return the key store
	 */
	public static PostgresShreddingKeyStore on ( DataSource dataSource, String prefix ) {
		return new PostgresShreddingKeyStore(dataSource, prefix);
	}

	@Override
	public ActiveKey keyFor ( DataSubject subject ) {
		if ( subject == null ) {
			throw new IllegalArgumentException("subject cannot be null");
		}

		try ( Connection connection = dataSource.getConnection() ) {

			Optional<ActiveKey> existing = selectActiveKey(connection, subject);
			if ( existing.isPresent() ) {
				cache.put(existing.get().id(), existing.get().key());
				return existing.get();
			}

			KeyId keyId = KeyId.of("k-" + UUID.randomUUID());
			SecretKey material = generateKey();

			// ON CONFLICT DO NOTHING against the partial unique index on the un-shredded rows: two
			// threads or two instances appending for the same subject at the same moment must end up
			// sharing one key. The loser inserts nothing and re-reads the winner's key below.
			String sql = """
					INSERT INTO %s (key_id, subject_type, subject_id, subject_category, key_material)
					VALUES (?, ?, ?, ?, ?)
					ON CONFLICT DO NOTHING
					""".formatted(tableName);

			int inserted;
			try ( PreparedStatement statement = connection.prepareStatement(sql) ) {
				statement.setString(1, keyId.value());
				statement.setString(2, subject.type());
				statement.setString(3, subject.id());
				statement.setString(4, subject.category());
				statement.setBytes(5, material.getEncoded());
				inserted = statement.executeUpdate();
			}

			if ( inserted == 0 ) {
				ActiveKey winner = selectActiveKey(connection, subject).orElseThrow(() -> new ShreddingException(
						"could not obtain a key for subject %s: the insert conflicted but no active key is present".formatted(subject)));
				cache.put(winner.id(), winner.key());
				return winner;
			}

			cache.put(keyId, material);
			return new ActiveKey(keyId, material);

		} catch (SQLException e) {
			throw new ShreddingException("failed to obtain a shredding key for subject %s from %s".formatted(subject, tableName), e);
		}
	}

	@Override
	public Optional<SecretKey> resolve ( KeyId key ) {
		if ( key == null ) {
			throw new IllegalArgumentException("key cannot be null");
		}

		SecretKey cached = cache.get(key);
		if ( cached != null ) {
			return Optional.of(cached);
		}

		String sql = "SELECT key_material FROM %s WHERE key_id = ?".formatted(tableName);

		try ( Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql) ) {

			statement.setString(1, key.value());
			try ( ResultSet resultSet = statement.executeQuery() ) {
				if ( !resultSet.next() ) {
					// No such key. Reported as "erased" rather than thrown: a key id this store has never
					// held cannot be produced by a retry either, and the only readings that reach here are
					// an erasure whose row was pruned, or an envelope from another store.
					return Optional.empty();
				}
				byte[] material = resultSet.getBytes("key_material");
				if ( material == null ) {
					// Shredded. This is the mechanism working, and must never be confused with the
					// database being unreachable -- which throws, from the catch below.
					return Optional.empty();
				}
				SecretKey secretKey = new SecretKeySpec(material, KEY_ALGORITHM);
				cache.put(key, secretKey);
				return Optional.of(secretKey);
			}

		} catch (SQLException e) {
			// Loudly, and never as an empty Optional: reported as erased, a database blip would make
			// every protected value read as destroyed, and bookmarked projections would write those gaps
			// into read models and never revisit them.
			throw new ShreddingException("failed to resolve shredding key %s from %s".formatted(key, tableName), e);
		}
	}

	@Override
	public List<KeyId> shred ( DataSubject subject, ErasureReason reason ) {
		if ( subject == null ) {
			throw new IllegalArgumentException("subject cannot be null");
		}
		if ( reason == null ) {
			throw new IllegalArgumentException("reason cannot be null");
		}

		// Every key the subject has ever held that still has material, not just the active one.
		String sql = """
				UPDATE %s
				   SET key_material = NULL,
				       shredded_at = CURRENT_TIMESTAMP,
				       shredded_reason = ?
				 WHERE subject_type = ? AND subject_id = ? AND subject_category = ?
				   AND key_material IS NOT NULL
				RETURNING key_id
				""".formatted(tableName);

		List<KeyId> shredded = new ArrayList<>();

		try ( Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql) ) {

			statement.setString(1, reason.value());
			statement.setString(2, subject.type());
			statement.setString(3, subject.id());
			statement.setString(4, subject.category());

			try ( ResultSet resultSet = statement.executeQuery() ) {
				while ( resultSet.next() ) {
					shredded.add(KeyId.of(resultSet.getString("key_id")));
				}
			}

		} catch (SQLException e) {
			throw new ShreddingException("failed to shred the keys of subject %s in %s".formatted(subject, tableName), e);
		}

		// Only after the database has committed the erasure: dropping the cache first would leave a
		// window in which a failed UPDATE had already cost this instance its keys.
		shredded.forEach(cache::remove);

		return List.copyOf(shredded);
	}

	private Optional<ActiveKey> selectActiveKey ( Connection connection, DataSubject subject ) throws SQLException {
		String sql = """
				SELECT key_id, key_material
				  FROM %s
				 WHERE subject_type = ? AND subject_id = ? AND subject_category = ?
				   AND key_material IS NOT NULL
				""".formatted(tableName);

		try ( PreparedStatement statement = connection.prepareStatement(sql) ) {
			statement.setString(1, subject.type());
			statement.setString(2, subject.id());
			statement.setString(3, subject.category());

			try ( ResultSet resultSet = statement.executeQuery() ) {
				if ( !resultSet.next() ) {
					return Optional.empty();
				}
				return Optional.of(new ActiveKey(
						KeyId.of(resultSet.getString("key_id")),
						new SecretKeySpec(resultSet.getBytes("key_material"), KEY_ALGORITHM)));
			}
		}
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

	/**
	 * Drops the key cache. The data source belongs to the storage and is left alone.
	 */
	@Override
	public void close ( ) {
		cache.clear();
	}

	@Override
	public String toString ( ) {
		return "PostgresShreddingKeyStore[%s]".formatted(tableName);
	}

}
