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
package org.sliceworkz.eventstore.benchmark.corpus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * Where corpus manifests are kept: one table beside the corpora themselves.
 *
 * <p>Deliberately <b>one shared table</b> rather than one per corpus. Listing what a database holds,
 * and finding the corpora nobody uses any more, are the two operational questions that actually come
 * up, and both are a single query against one table. Per-corpus manifests would make them a catalogue
 * crawl.
 *
 * <p>Note that this table is <em>not</em> prefixed with a corpus fingerprint -- it spans them. Its
 * name still starts with the suite's namespace so a stray one is recognisable in a database that also
 * holds real data.
 */
public final class ManifestStore {

	/** The one table, shared across every corpus in a database. */
	public static final String TABLE = CorpusFingerprint.PREFIX_NAMESPACE + "corpus_manifest";

	private static final String CREATE = """
			CREATE TABLE IF NOT EXISTS %s (
			    fingerprint       TEXT PRIMARY KEY,
			    spec_json         TEXT        NOT NULL,
			    generator_version INTEGER     NOT NULL,
			    provisioned_at    TIMESTAMPTZ NOT NULL,
			    event_count       BIGINT      NOT NULL,
			    facts_json        TEXT        NOT NULL
			)""".formatted(TABLE);

	private static final String UPSERT = """
			INSERT INTO %s (fingerprint, spec_json, generator_version, provisioned_at, event_count, facts_json)
			VALUES (?, ?, ?, ?, ?, ?)
			ON CONFLICT (fingerprint) DO UPDATE SET
			    spec_json = EXCLUDED.spec_json,
			    generator_version = EXCLUDED.generator_version,
			    provisioned_at = EXCLUDED.provisioned_at,
			    event_count = EXCLUDED.event_count,
			    facts_json = EXCLUDED.facts_json""".formatted(TABLE);

	private static final String SELECT_ONE = """
			SELECT fingerprint, spec_json, generator_version, provisioned_at, event_count, facts_json
			FROM %s WHERE fingerprint = ?""".formatted(TABLE);

	private static final String SELECT_ALL = """
			SELECT fingerprint, spec_json, generator_version, provisioned_at, event_count, facts_json
			FROM %s ORDER BY provisioned_at DESC""".formatted(TABLE);

	private static final String DELETE = "DELETE FROM %s WHERE fingerprint = ?".formatted(TABLE);

	private final DataSource dataSource;

	public ManifestStore ( DataSource dataSource ) {
		this.dataSource = dataSource;
	}

	/** Creates the manifest table if it is not there. Cheap, and idempotent. */
	public void ensureTable ( ) {
		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement() ) {
			statement.execute(CREATE);
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not create the corpus manifest table " + TABLE, e);
		}
	}

	public Optional<CorpusManifest> find ( String fingerprint ) {
		try ( Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(SELECT_ONE) ) {
			statement.setString(1, fingerprint);
			try ( ResultSet rows = statement.executeQuery() ) {
				return rows.next() ? Optional.of(read(rows)) : Optional.empty();
			}
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not read the corpus manifest for " + fingerprint, e);
		}
	}

	public List<CorpusManifest> findAll ( ) {
		List<CorpusManifest> manifests = new ArrayList<>();
		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(SELECT_ALL) ) {
			while ( rows.next() ) {
				manifests.add(read(rows));
			}
			return manifests;
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not list corpus manifests", e);
		}
	}

	public void save ( CorpusManifest manifest ) {
		try ( Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(UPSERT) ) {
			statement.setString(1, manifest.fingerprint());
			statement.setString(2, manifest.specJson());
			statement.setInt(3, manifest.generatorVersion());
			statement.setTimestamp(4, Timestamp.from(manifest.provisionedAt()));
			statement.setLong(5, manifest.eventCount());
			statement.setString(6, manifest.factsJson());
			statement.executeUpdate();
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not save the corpus manifest for " + manifest.fingerprint(), e);
		}
	}

	/**
	 * Forgets a corpus.
	 *
	 * <p>Called <b>before</b> the data is dropped and rebuilt, never after. A manifest present beside
	 * a half-built store is the one state that would let a later run reuse an incomplete corpus, so
	 * the window where that is possible is kept as short as it can be: no manifest means "rebuild",
	 * which is always safe.
	 */
	public void forget ( String fingerprint ) {
		try ( Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(DELETE) ) {
			statement.setString(1, fingerprint);
			statement.executeUpdate();
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not delete the corpus manifest for " + fingerprint, e);
		}
	}

	/** How many events a corpus's events table actually holds, or empty if the table is not there. */
	public Optional<Long> countEvents ( String prefix ) {
		String table = prefix + "events";
		try ( Connection connection = dataSource.getConnection() ) {
			if ( !tableExists(connection, table) ) {
				return Optional.empty();
			}
			try ( Statement statement = connection.createStatement();
					ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table) ) {
				return rows.next() ? Optional.of(rows.getLong(1)) : Optional.empty();
			}
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not count events in " + table, e);
		}
	}

	private static boolean tableExists ( Connection connection, String table ) throws SQLException {
		// to_regclass answers without throwing for a missing relation, which a plain SELECT would not
		try ( PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL") ) {
			statement.setString(1, table);
			try ( ResultSet rows = statement.executeQuery() ) {
				return rows.next() && rows.getBoolean(1);
			}
		}
	}

	private static CorpusManifest read ( ResultSet rows ) throws SQLException {
		return new CorpusManifest(
				rows.getString("fingerprint"),
				rows.getString("spec_json"),
				rows.getInt("generator_version"),
				rows.getTimestamp("provisioned_at").toInstant(),
				rows.getLong("event_count"),
				rows.getString("facts_json"));
	}

	/** A manifest for a corpus just written. */
	public static CorpusManifest manifestFor ( String fingerprint, String specJson, long eventCount,
			String factsJson ) {
		return new CorpusManifest(fingerprint, specJson, CorpusFingerprint.GENERATOR_VERSION,
				Instant.now(), eventCount, factsJson);
	}
}
