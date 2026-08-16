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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.sliceworkz.eventstore.shredding.KeyAuditQuery;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingAudit;
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
 * Resolved keys are cached in memory, keyed on key id, so replaying a stream does not issue a query per
 * protected value. The cache is <b>invalidated on erasure within this instance</b>, so a shred is
 * immediate here. Across a cluster it is not: another instance that has already cached the key keeps
 * decrypting with it until its entry lapses. That is the usual crypto-shredding trade — instant in
 * storage, eventually consistent in memory — and it is why entries expire rather than living forever:
 * {@link #DEFAULT_CACHE_TTL} is the outer bound on how long an erasure performed elsewhere can go
 * unnoticed here, and it is a number worth stating in a data protection notice rather than discovering.
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

	/**
	 * How long a resolved key stays usable before this store looks it up again — one hour.
	 * <p>
	 * The cache is what keeps replaying a stream from issuing a query per protected value, and an
	 * erasure performed <em>by this instance</em> drops the entry immediately, so the ttl is not what
	 * bounds that. What it bounds is an erasure performed somewhere else: another instance that has
	 * already cached the key keeps decrypting with it until its entry lapses. An hour is the outer edge
	 * of "erased" for a deployment of several instances, and worth stating in a data protection notice
	 * rather than discovering.
	 * <p>
	 * {@link Duration#ZERO} disables the cache and resolves every value from the database, which makes
	 * an erasure effective everywhere at once at the cost of a query per protected value.
	 */
	public static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(1);

	private static final String KEY_ALGORITHM = "AES";
	private static final int KEY_BITS = 256;

	private final DataSource dataSource;
	private final String tableName;

	/**
	 * Resolved key material, by key id. Bounded in practice by the number of distinct subjects a process
	 * actually reads for, not by the number of keys that exist.
	 */
	private final Map<KeyId, CachedKey> cache = new ConcurrentHashMap<>();
	private final Duration cacheTtl;

	/**
	 * A resolved key and the moment it stops being trusted.
	 * <p>
	 * The expiry is what bounds an erasure performed by <em>another</em> instance: this one keeps
	 * decrypting with a key it cached until the entry lapses and the next read finds the row shredded.
	 */
	private record CachedKey ( SecretKey key, Instant expiresAt ) {
		private boolean isLive ( Instant now ) {
			return now.isBefore(expiresAt);
		}
	}

	/**
	 * @param dataSource the storage's data source; never closed by this key store
	 * @param prefix     the table prefix the storage was built with, so the key table sits beside the
	 *                   events of the same store; may be empty but not null
	 * @throws IllegalArgumentException if either argument is null
	 */
	public PostgresShreddingKeyStore ( DataSource dataSource, String prefix ) {
		this(dataSource, prefix, DEFAULT_CACHE_TTL);
	}

	/**
	 * @param dataSource the storage's data source; never closed by this key store
	 * @param prefix     the table prefix the storage was built with
	 * @param cacheTtl   how long a resolved key stays usable before it is looked up again; see
	 *                   {@link #DEFAULT_CACHE_TTL} for what this bounds
	 * @throws IllegalArgumentException if any argument is null, or the ttl is negative
	 */
	public PostgresShreddingKeyStore ( DataSource dataSource, String prefix, Duration cacheTtl ) {
		if ( dataSource == null ) {
			throw new IllegalArgumentException("dataSource cannot be null");
		}
		if ( prefix == null ) {
			throw new IllegalArgumentException("prefix cannot be null; use an empty string for no prefix");
		}
		if ( cacheTtl == null || cacheTtl.isNegative() ) {
			throw new IllegalArgumentException("cacheTtl cannot be null or negative; use Duration.ZERO to resolve every key from the database");
		}
		this.dataSource = dataSource;
		this.tableName = prefix + "shredding_keys";
		this.cacheTtl = cacheTtl;
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
				cacheKey(existing.get().id(), existing.get().key());
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
				cacheKey(winner.id(), winner.key());
				return winner;
			}

			cacheKey(keyId, material);
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

		CachedKey cached = cache.get(key);
		if ( cached != null ) {
			if ( cached.isLive(Instant.now()) ) {
				return Optional.of(cached.key());
			}
			// lapsed rather than wrong: drop it and ask the database, which is where an erasure by
			// another instance will have been recorded
			cache.remove(key, cached);
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
				cacheKey(key, secretKey);
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

	@Override
	public Optional<ShreddingAudit> audit ( ) {
		return Optional.of(new PostgresAudit());
	}

	/**
	 * Reports on the key table without ever selecting the key material.
	 * <p>
	 * The {@code key_material} column is absent from every statement here, not merely unread: a report
	 * that never puts key bytes on the wire cannot leak them through a log, a heap dump or a stray
	 * {@code toString}. It reads through the same {@code DataSource}, so a credential granted only
	 * {@code SELECT} on the table serves the whole of this interface.
	 */
	private final class PostgresAudit implements ShreddingAudit {

		@Override
		public List<KeyRecord> keys ( KeyAuditQuery query ) {
			if ( query == null ) {
				throw new IllegalArgumentException("query cannot be null");
			}

			StringBuilder sql = new StringBuilder("""
					SELECT key_id, subject_type, subject_id, subject_category, created_at, shredded_at, shredded_reason
					  FROM %s
					 WHERE 1 = 1
					""".formatted(tableName));

			List<String> parameters = new ArrayList<>();
			if ( query.shreddedOnly() ) {
				sql.append(" AND key_material IS NULL");
			}
			if ( query.subjectType() != null ) {
				sql.append(" AND subject_type = ?");
				parameters.add(query.subjectType());
			}
			if ( query.subjectId() != null ) {
				sql.append(" AND subject_id = ?");
				parameters.add(query.subjectId());
			}
			if ( query.category() != null ) {
				sql.append(" AND subject_category = ?");
				parameters.add(query.category());
			}
			// key_id breaks the tie so that paging is stable when several keys share a creation instant,
			// which two subjects minted inside one append genuinely do.
			sql.append(" ORDER BY created_at DESC, key_id DESC LIMIT ?");

			try ( Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(sql.toString()) ) {

				int index = 1;
				for ( String parameter : parameters ) {
					statement.setString(index++, parameter);
				}
				statement.setInt(index, query.limit());

				List<KeyRecord> records = new ArrayList<>();
				try ( ResultSet resultSet = statement.executeQuery() ) {
					while ( resultSet.next() ) {
						records.add(recordOf(resultSet));
					}
				}
				return List.copyOf(records);

			} catch (SQLException e) {
				throw new ShreddingException("failed to read shredding keys from %s".formatted(tableName), e);
			}
		}

		@Override
		public ShreddingTotals totals ( ) {
			// One pass over the table rather than three: the counts are always reported together, and the
			// live-subject count has to see the same snapshot as the key counts or the summary contradicts
			// itself while an erasure is running.
			String sql = """
					SELECT count(*) FILTER (WHERE key_material IS NOT NULL) AS live_keys,
					       count(*) FILTER (WHERE key_material IS NULL) AS shredded_keys,
					       count(DISTINCT (subject_type, subject_id, subject_category))
					           FILTER (WHERE key_material IS NOT NULL) AS live_subjects
					  FROM %s
					""".formatted(tableName);

			try ( Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(sql);
					ResultSet resultSet = statement.executeQuery() ) {

				if ( !resultSet.next() ) {
					return new ShreddingTotals(0, 0, 0);
				}
				return new ShreddingTotals(
						resultSet.getLong("live_subjects"),
						resultSet.getLong("live_keys"),
						resultSet.getLong("shredded_keys"));

			} catch (SQLException e) {
				throw new ShreddingException("failed to summarise the shredding keys in %s".formatted(tableName), e);
			}
		}

		private KeyRecord recordOf ( ResultSet resultSet ) throws SQLException {
			DataSubject subject = DataSubject.of(resultSet.getString("subject_type"), resultSet.getString("subject_id"))
					.withCategory(resultSet.getString("subject_category"));

			Timestamp shreddedAt = resultSet.getTimestamp("shredded_at");
			String reason = resultSet.getString("shredded_reason");

			return new KeyRecord(
					KeyId.of(resultSet.getString("key_id")),
					subject,
					resultSet.getTimestamp("created_at").toInstant(),
					Optional.ofNullable(shreddedAt).map(Timestamp::toInstant),
					Optional.ofNullable(reason).map(ErasureReason::of));
		}

	}

	private void cacheKey ( KeyId keyId, SecretKey material ) {
		if ( cacheTtl.isZero() ) {
			return;
		}
		cache.put(keyId, new CachedKey(material, Instant.now().plus(cacheTtl)));
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
