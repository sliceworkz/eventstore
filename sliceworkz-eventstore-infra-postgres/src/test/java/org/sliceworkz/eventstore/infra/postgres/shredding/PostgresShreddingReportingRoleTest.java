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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.ErasureReason;
import org.sliceworkz.eventstore.shredding.KeyAuditQuery;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.ShreddingAudit;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingCodec.Sealed;
import org.sliceworkz.eventstore.shredding.ShreddingCodec.Unsealed;
import org.sliceworkz.eventstore.shredding.ShreddingException;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore.KeyResolution;
import org.sliceworkz.eventstore.spi.EventStorage;

import com.zaxxer.hikari.HikariDataSource;

/**
 * The reporting role the module README recommends — granted every column of the key table except
 * {@code key_material} — really does get what the README promises: a denial for every key, and a
 * working audit.
 * <p>
 * The audit half is the one worth a test of its own, because it is easy to break without noticing.
 * PostgreSQL checks {@code SELECT} privilege on <em>every</em> column a statement references, a
 * {@code WHERE} or {@code FILTER} clause included, not only the columns it returns. So an audit
 * statement that judged "shredded" by {@code key_material IS NULL} would fail for exactly this role
 * with the same {@code insufficient_privilege} the key lookup gets — and since the audit reports that
 * as a {@link ShreddingException}, an operations screen would render "the key store cannot be
 * reached" for a store that is perfectly healthy. The audit therefore judges on {@code shredded_at},
 * which {@code shred()} stamps in the same statement, and this pins it for every statement it issues.
 * <p>
 * Runs in the shared database under its own table prefix; the role is cluster-wide and named for
 * this test.
 */
public class PostgresShreddingReportingRoleTest {

	private static final String PASSWORD = "pwd";
	private static final String PREFIX = "reprole_";
	private static final String ROLE = "shredding_reporting_role";

	private static final DataSubject ALICE = DataSubject.of("customer", "alice-42");
	private static final DataSubject BOB_MARKETING = DataSubject.of("customer", "bob-77").withCategory("marketing");

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testTheReportingRoleIsDeniedEveryKeyAndCanStillAudit ( ) throws Exception {
			DataSource application = PostgresContainer.dataSource(image);

			// the application role: creates the schema, mints two keys, erases one
			KeyId alicesKey;
			KeyId bobsKey;
			Sealed alicesValue;
			try ( EventStorage schema = PostgresEventStorage.newBuilder()
					.name("reporting-schema").prefix(PREFIX).dataSource(application).initializeDatabase().build();
				  ShreddingKeyStore applicationKeys = PostgresShreddingKeyStore.on(application, PREFIX);
				  ShreddingCodec applicationCodec = AesGcmShreddingCodec.over(applicationKeys) ) {
				alicesValue = applicationCodec.seal("\"Alice Martin\"", ALICE);
				alicesKey = alicesValue.key();
				bobsKey = applicationCodec.seal("\"Bob Jansen\"", BOB_MARKETING).key();
				applicationCodec.shred(BOB_MARKETING, ErasureReason.of("marketing consent withdrawn"));
			}

			// the reporting role, granted as the README says
			PostgresContainer.createRole(image, ROLE, PASSWORD);
			PostgresContainer.asSuperuser(image, statement -> statement.execute(
					"GRANT SELECT (key_id, subject_type, subject_id, subject_category, created_at, shredded_at, shredded_reason) ON "
							+ PREFIX + "shredding_keys TO " + ROLE));

			try ( HikariDataSource reporting = PostgresContainer.dataSource(image, "integration-tests-db", ROLE, PASSWORD);
				  ShreddingKeyStore reportingKeys = new PostgresShreddingKeyStore(reporting, PREFIX, Duration.ZERO);
				  ShreddingCodec reportingCodec = AesGcmShreddingCodec.over(reportingKeys) ) {

				// the denial: not an outage, and not an erasure -- a live key and a destroyed one read the
				// same to a role that may not see the column that tells them apart
				assertInstanceOf(KeyResolution.Denied.class, reportingKeys.resolveKey(alicesKey));
				assertInstanceOf(KeyResolution.Denied.class, reportingKeys.resolveKey(bobsKey));
				assertInstanceOf(Unsealed.Withheld.class, reportingCodec.open(alicesValue));
				// the two-answer method cannot say "denied" and must not say "erased"
				assertThrows(ShreddingException.class, () -> reportingKeys.resolve(alicesKey));

				// the audit, every statement of it
				ShreddingAudit audit = reportingKeys.audit().orElseThrow();
				assertEquals(new ShreddingAudit.ShreddingTotals(1, 1, 1), audit.totals());
				assertEquals(List.of(
						new ShreddingAudit.CategoryTotals("default", 1, 1, 0),
						new ShreddingAudit.CategoryTotals("marketing", 0, 0, 1)),
						audit.categories());

				assertEquals(2, audit.keys(KeyAuditQuery.all()).size());

				List<ShreddingAudit.KeyRecord> erasures = audit.keys(KeyAuditQuery.all().onlyShredded());
				assertEquals(1, erasures.size());
				assertEquals(bobsKey, erasures.getFirst().id());
				assertEquals(Optional.of(ErasureReason.of("marketing consent withdrawn")), erasures.getFirst().reason());

				List<ShreddingAudit.KeyRecord> byKey = audit.keys(KeyAuditQuery.forKeys(Set.of(alicesKey, bobsKey)));
				assertEquals(2, byKey.size());
				assertEquals(List.of(bobsKey),
						byKey.stream().filter(ShreddingAudit.KeyRecord::isShredded).map(ShreddingAudit.KeyRecord::id).toList());

				assertEquals(1, audit.keys(KeyAuditQuery.forSubject(ALICE)).size());
				assertEquals(1, audit.keys(KeyAuditQuery.all().withCategory("marketing")).size());
			}
		}
	}

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
