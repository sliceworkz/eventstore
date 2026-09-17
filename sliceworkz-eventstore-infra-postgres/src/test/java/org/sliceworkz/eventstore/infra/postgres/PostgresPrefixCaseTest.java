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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * A table prefix is an unquoted identifier wherever it names a database object, and PostgreSQL folds
 * those to lowercase; it is a plain string wherever the store compares a name it reads back — the
 * bound {@code table_name} of schema validation, the {@code relname} of the trigger-shape guard, the
 * channel literal inside the trigger's {@code pg_notify}. Unless the prefix is folded the same way,
 * a prefix with an uppercase letter names two different things: the objects the DDL creates, and
 * the names the store then looks for. Under {@code ENSURE} that is a validation failure for a table
 * that was just created; under {@code NONE} it is a store that starts, reports its notifications up,
 * and never receives one, because the monitors {@code LISTEN} on the folded channel while the
 * trigger notifies the literal one.
 * <p>
 * These scenarios pin down that a mixed-case prefix is the same store as its lowercase spelling,
 * end to end: it validates, it notifies, and both spellings read the same events.
 */
public class PostgresPrefixCaseTest {

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		/**
		 * The DDL creates {@code mixedcase_events}; validation must look for that name and not for
		 * {@code MixedCase_events}, which no catalog holds.
		 */
		@Test
		public void testAMixedCasePrefixValidatesUnderEnsure ( ) throws Exception {
			DataSource dataSource = PostgresContainer.dataSource(image);
			try ( EventStorage storage = build("MixedCase_", dataSource, true) ) {
				assertTrue(tableExists(dataSource, "mixedcase_events"), "the objects exist under the folded name");
			} finally {
				PostgresContainer.closeDataSource(image);
			}
		}

		/**
		 * The trigger notifies the channel named in the function body, and the monitors listen on the
		 * channel named in their {@code LISTEN}; both must be the folded name, or nothing arrives.
		 */
		@Test
		public void testAMixedCasePrefixDeliversAppendNotifications ( ) throws Exception {
			DataSource dataSource = PostgresContainer.dataSource(image);
			try ( EventStorage storage = build("MixedCase_", dataSource, true) ) {
				AtomicInteger notifications = new AtomicInteger();
				storage.subscribe(new EventStorage.EventStoreListener() {
					@Override public void notify ( EventStorage.AppendsToEventStoreNotification n ) { notifications.incrementAndGet(); }
					@Override public void notify ( EventStorage.BookmarkPlacedNotification n ) { }
				});

				append(storage);
				waitForNotification(notifications);

				assertEquals(1, notifications.get(), "the LISTEN and the pg_notify name the same channel");
			} finally {
				PostgresContainer.closeDataSource(image);
			}
		}

		/**
		 * Two spellings, one store: what one appends the other reads, on the tables the database
		 * folded both prefixes to. The second store validates only, so it is also the {@code VALIDATE}
		 * deployment finding the tables the first one created.
		 */
		@Test
		public void testTheMixedCaseAndLowercaseSpellingsAreTheSameStore ( ) throws Exception {
			DataSource dataSource = PostgresContainer.dataSource(image);
			try {
				try ( EventStorage upper = build("SameStore_", dataSource, true) ) {
					append(upper);
				}
				try ( EventStorage lower = build("samestore_", dataSource, false) ) {
					List<StoredEvent> events = lower.query(EventFilter.matchAll(), STREAM, null, Limit.none());
					assertEquals(1, events.size(), "the event appended through the mixed-case spelling");
					assertEquals(EventType.named("PrefixCaseProbe"), events.get(0).type());
				}
			} finally {
				PostgresContainer.closeDataSource(image);
			}
		}

		/** Not an identifier PostgreSQL parses unquoted, so refused before any SQL is issued. */
		@Test
		public void testAPrefixStartingWithADigitIsRefusedByTheBuilder ( ) {
			DataSource dataSource = PostgresContainer.dataSource(image);
			try {
				assertThrows(IllegalArgumentException.class, ( ) -> build("1tenant_", dataSource, true));
			} finally {
				PostgresContainer.closeDataSource(image);
			}
		}

		// ------------------------------------------------------------ helpers

		private static final EventStreamId STREAM = EventStreamId.forContext("prefixcase");

		private EventStorage build ( String prefix, DataSource dataSource, boolean ensure ) {
			PostgresEventStorage.Builder builder = PostgresEventStorage.newBuilder()
				.name("unit-test").prefix(prefix).dataSource(dataSource);
			return ( ensure ? builder.ensureDatabase() : builder.validateDatabase() ).build();
		}

		private void append ( EventStorage storage ) {
			storage.append(AppendCriteria.none(), STREAM, List.of(
				new EventToStore(STREAM, EventType.named("PrefixCaseProbe"), "{}", Tags.none(), null)));
		}

		private void waitForNotification ( AtomicInteger notifications ) throws InterruptedException {
			for ( int attempt = 0; attempt < 100 && notifications.get() == 0; attempt++ ) {
				Thread.sleep(50);
			}
		}

		private boolean tableExists ( DataSource dataSource, String tableName ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
				  Statement statement = connection.createStatement();
				  ResultSet rs = statement.executeQuery(
					"SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = '" + tableName + "')") ) {
				return rs.next() && rs.getBoolean(1);
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
