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
package org.sliceworkz.eventstore.infra.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;

import com.zaxxer.hikari.HikariDataSource;

/**
 * What the {@code btree_gin} line in {@code ensure-schema.sql} does to a role that is not allowed to
 * create an extension — and to two stores that try to create it at the same moment.
 * <p>
 * The extension is needed for {@code idx_events_stream_tags}, the combined stream+tags GIN index that
 * schema validation requires. It is <em>trusted</em>, so no superuser is involved, but installing it
 * requires {@code CREATE} on the <em>database</em> — a different privilege from {@code CREATE} on the
 * schema. The ordinary locked-down deployment grants the latter and not the former, which used to make
 * the store fail to start on a bare {@code permission denied to create extension}: the whole script is
 * one transaction, so the entire schema rolled back with it, and the remedy had to be deduced from a
 * message that named none.
 * <p>
 * The scenarios here pin down the deployment shapes that must keep working, and the two that were
 * broken:
 * <ul>
 *   <li>a DBA installs the extension once and the application role, which may not create one, starts
 *       against it forever after — the pre-check means the extension statement never runs at all;</li>
 *   <li>when it genuinely cannot be installed, the failure names both remedies;</li>
 *   <li>two stores with different table prefixes starting together no longer race each other on
 *       {@code pg_extension_name_index} — the schema advisory lock is keyed on the prefix, and an
 *       extension is database-scoped, so that lock does not serialize them here;</li>
 *   <li>an extension installed into a schema of its own — the convention on several managed
 *       offerings — still serves the index, even without {@code USAGE} on that schema.</li>
 * </ul>
 * <p>
 * Each scenario works in a database of its own. The shared {@code integration-tests-db} carries the
 * schemas of every other test in the JVM, and an extension is database-scoped: dropping
 * {@code btree_gin} there would take their combined index with it.
 */
public class PostgresBtreeGinPrivilegeTest {

	private static final String PASSWORD = "pwd";

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		/**
		 * The deployment the documentation recommends: a DBA installs the extension once, the
		 * application role may not create one, and every start from then on works.
		 * <p>
		 * This works because PostgreSQL's {@code IF NOT EXISTS} short-circuit precedes its privilege
		 * check — and because the script now pre-checks {@code pg_extension} and does not issue the
		 * statement at all. The second start asserts the property that matters operationally: this is
		 * not a one-off blessing of a fresh database, it is every restart of every instance.
		 */
		@Test
		public void testUnprivilegedRoleStartsWhenTheExtensionIsAlreadyInstalled ( ) throws Exception {
			String database = "btreegin_preinstalled";
			String role = "btreegin_preinstalled_role";
			String prefix = "bgpre_";

			createUnprivilegedRoleAndDatabase(database, role);
			PostgresContainer.asSuperuserIn(image, database, statement ->
				statement.execute("CREATE EXTENSION btree_gin"));

			try ( HikariDataSource dataSource = appDataSource(database, role) ) {
				ensure(prefix, dataSource, "first-start").close();

				assertTrue(indexExists(dataSource, prefix + "idx_events_stream_tags"),
					"the unprivileged role created the combined stream+tags index against the pre-installed extension");

				// the ordinary restart: still no privilege, still fine
				ensure(prefix, dataSource, "second-start").close();
			}
		}

		/**
		 * When the extension is genuinely absent and the role genuinely cannot create it, the failure
		 * names the two ways out instead of leaving them to be deduced.
		 * <p>
		 * The second assertion is the one that explains why this matters more than an unhelpful message:
		 * the script is a single transaction, so this does not degrade to "the store starts without one
		 * index" — nothing at all is created and the store does not come up.
		 */
		@Test
		public void testUncreatableExtensionFailsWithAnActionableError ( ) throws Exception {
			String database = "btreegin_denied";
			String role = "btreegin_denied_role";
			String prefix = "bgdenied_";

			createUnprivilegedRoleAndDatabase(database, role);

			try ( HikariDataSource dataSource = appDataSource(database, role) ) {
				EventStorageException thrown = assertThrows(EventStorageException.class,
					() -> ensure(prefix, dataSource, "denied"));

				String reported = report(thrown);
				assertTrue(reported.contains("btree_gin"),
					"the failure names the extension, got: " + reported);
				assertTrue(reported.contains("CREATE EXTENSION btree_gin"),
					"the failure offers the DBA-installs-it-once remedy, got: " + reported);
				assertTrue(reported.contains("GRANT CREATE ON DATABASE"),
					"the failure offers the grant-the-privilege remedy, got: " + reported);

				assertEquals(0, relationsWithPrefix(dataSource, prefix),
					"the script is one transaction: a schema half-created here would be worse than none");
			}
		}

		/**
		 * Two stores with different table prefixes, starting together against a database that does not
		 * have the extension yet, both come up.
		 * <p>
		 * They are <em>not</em> serialized against each other: the schema advisory lock is keyed on the
		 * table prefix, while an extension is database-scoped, so this is the same catalog race the
		 * tables used to lose on {@code pg_type_typname_nsp_index} before that lock existed — 64 of 80
		 * instances failed to start, then. The loser's whole transaction rolls back, so it is not a
		 * missing index, it is an instance that does not boot.
		 * <p>
		 * Whether the race is actually joined on a given run is a matter of timing, so this is a
		 * regression guard rather than a proof: it fails most of the time against a bare
		 * {@code CREATE EXTENSION IF NOT EXISTS}, and never against the guarded block.
		 */
		@Test
		public void testConcurrentStartsUnderDifferentPrefixesDoNotRaceOnTheExtension ( ) throws Exception {
			String database = "btreegin_concurrent";
			String role = "btreegin_concurrent_role";
			int instances = 6;

			createUnprivilegedRoleAndDatabase(database, role);
			// this role may create the extension -- the race is between equals, not a privilege problem
			PostgresContainer.asSuperuser(image, statement ->
				statement.execute("GRANT CREATE ON DATABASE " + database + " TO " + role));

			List<HikariDataSource> pools = new ArrayList<>();
			List<EventStorage> started = new ArrayList<>();
			AtomicReference<Exception> failure = new AtomicReference<>();
			CountDownLatch startGate = new CountDownLatch(1);
			CountDownLatch finished = new CountDownLatch(instances);
			ExecutorService executor = Executors.newFixedThreadPool(instances);

			try {
				for ( int i = 0; i < instances; i++ ) {
					// a pool per instance, as separate application instances would have -- one shared pool
					// would have to hold two monitor connections per store on top of the schema work
					HikariDataSource dataSource = appDataSource(database, role);
					pools.add(dataSource);
					String prefix = "bgconc" + i + "_";
					executor.submit(() -> {
						try {
							startGate.await();
							EventStorage storage = ensure(prefix, dataSource, "concurrent-" + prefix);
							synchronized ( started ) {
								started.add(storage);
							}
						} catch (Exception e) {
							failure.compareAndSet(null, e);
						} finally {
							finished.countDown();
						}
					});
				}

				startGate.countDown();
				assertTrue(finished.await(120, TimeUnit.SECONDS), "all instances finished starting");

				if ( failure.get() != null ) {
					throw new AssertionError("an instance failed to start: " + report(failure.get()), failure.get());
				}
				assertEquals(instances, started.size(), "every instance started");

				try ( HikariDataSource dataSource = appDataSource(database, role) ) {
					for ( int i = 0; i < instances; i++ ) {
						assertTrue(indexExists(dataSource, "bgconc" + i + "_idx_events_stream_tags"),
							"instance " + i + " created its combined stream+tags index, so it saw the extension "
								+ "whether it installed it or lost the race for it");
					}
				}
			} finally {
				executor.shutdownNow();
				started.forEach(EventStorage::close);
				pools.forEach(HikariDataSource::close);
			}
		}

		/**
		 * An extension installed into a schema of its own — the convention on several managed offerings
		 * — still serves the index.
		 * <p>
		 * Worth pinning rather than assuming: the index below names no operator class, so it depends on
		 * PostgreSQL resolving the <em>default</em> GIN opclass for {@code text}, and that resolution is
		 * not filtered by {@code search_path}. The application role here has neither the schema on its
		 * {@code search_path} nor {@code USAGE} on it, and the index is still created.
		 */
		@Test
		public void testExtensionInItsOwnSchemaStillServesTheIndex ( ) throws Exception {
			String database = "btreegin_ownschema";
			String role = "btreegin_ownschema_role";
			String prefix = "bgschema_";

			createUnprivilegedRoleAndDatabase(database, role);
			PostgresContainer.asSuperuserIn(image, database, statement -> {
				statement.execute("CREATE SCHEMA extensions");
				statement.execute("CREATE EXTENSION btree_gin SCHEMA extensions");
			});

			try ( HikariDataSource dataSource = appDataSource(database, role) ) {
				ensure(prefix, dataSource, "own-schema").close();

				assertTrue(indexExists(dataSource, prefix + "idx_events_stream_tags"),
					"the combined index resolves btree_gin's opclasses from another schema");
			}
		}

		// ---------------------------------------------------------------- helpers

		/**
		 * A database of this test's own, and a role that may create everything in its schema and no
		 * extension: {@code CREATE} on the schema, nothing on the database.
		 */
		private void createUnprivilegedRoleAndDatabase ( String database, String role ) throws SQLException {
			// database first: a role cannot be dropped while it still owns objects anywhere in the cluster
			PostgresContainer.createDatabase(image, database);
			PostgresContainer.createRole(image, role, PASSWORD);
			PostgresContainer.asSuperuserIn(image, database, statement ->
				statement.execute("GRANT CREATE, USAGE ON SCHEMA public TO " + role));
		}

		private HikariDataSource appDataSource ( String database, String role ) {
			return PostgresContainer.dataSource(image, database, role, PASSWORD);
		}

		private EventStorage ensure ( String prefix, DataSource dataSource, String name ) {
			return PostgresEventStorage.newBuilder()
				.name(name).prefix(prefix).dataSource(dataSource)
				.ensureDatabase().build();
		}

		/**
		 * Everything the operator gets to read: the exception chain's messages, plus the DETAIL and HINT
		 * the server attached, which is where the remedies live.
		 */
		private String report ( Throwable thrown ) {
			StringBuilder reported = new StringBuilder();
			for ( Throwable t = thrown; t != null; t = t.getCause() ) {
				reported.append(t.getMessage()).append('\n');
				if ( t instanceof PSQLException psql && psql.getServerErrorMessage() != null ) {
					reported.append(psql.getServerErrorMessage().getDetail()).append('\n')
							.append(psql.getServerErrorMessage().getHint()).append('\n');
				}
			}
			return reported.toString();
		}

		private boolean indexExists ( DataSource dataSource, String indexName ) throws SQLException {
			return queryString(dataSource,
				"SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND indexname = '"
					+ indexName + "'") != null;
		}

		private int relationsWithPrefix ( DataSource dataSource, String prefix ) throws SQLException {
			return Integer.parseInt(queryString(dataSource,
				"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
					+ "WHERE n.nspname = current_schema() AND c.relname LIKE '" + prefix + "%'"));
		}

		private String queryString ( DataSource dataSource, String sql ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
				  Statement statement = connection.createStatement();
				  ResultSet rs = statement.executeQuery(sql) ) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	/**
	 * The oldest supported major version. Trusted extensions arrived in PostgreSQL 13, so the floor is
	 * the version where this behaviour is least safe to assume.
	 */
	@Nested
	class OnPostgres16 extends Tests {

		OnPostgres16 ( ) { super(PostgresContainer.IMAGE_PG16); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG16);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			PostgresContainer.stop(PostgresContainer.IMAGE_PG16);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG16);
		}
	}

	@Nested
	class OnPostgres17 extends Tests {

		OnPostgres17 ( ) { super(PostgresContainer.IMAGE_PG17); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG17);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			PostgresContainer.stop(PostgresContainer.IMAGE_PG17);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17);
		}
	}

	@Nested
	class OnPostgres18 extends Tests {

		OnPostgres18 ( ) { super(PostgresContainer.IMAGE_PG18); }

		@BeforeAll
		public static void setUpBeforeAll ( ) {
			PostgresContainer.start(PostgresContainer.IMAGE_PG18);
		}

		@AfterAll
		public static void tearDownAfterAll ( ) {
			PostgresContainer.stop(PostgresContainer.IMAGE_PG18);
			PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18);
		}
	}

}
