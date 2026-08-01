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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Pins down that the optimistic-locking check compares over {@code (tx, position)} — the order events
 * are actually read in — and not over {@code event_position} alone.
 * <p>
 * The two orders genuinely disagree. {@code event_position} comes from a sequence at insert time,
 * {@code event_tx} from {@code pg_current_xact_id()}, and the two are assigned independently, so a
 * transaction can end up with a lower position and a higher tx than one that committed before it. Such
 * an event sorts <em>after</em> the reference for every reader while carrying a lower position, so a
 * position-only check does not see it and the append succeeds against a history the store no longer
 * agrees with — silently, with no exception raised.
 * <p>
 * <b>Why this test lives here and not in the TCK.</b> Reproducing the inversion deterministically needs
 * a position to be reserved out of band, ahead of the transaction that eventually uses it — raw SQL
 * against the sequence, which is not something the storage SPI can express. The TCK's
 * {@code ConcurrentOptimisticLockingTest} cannot stand in for it either: it races <em>conditional</em>
 * appends on one stream, and those are serialized by the advisory lock, so no inversion can arise
 * between them. The inversion needs a writer that does not take that lock — an unconditional append, an
 * import, or a raw writer, all of which are lock-free by design.
 * <p>
 * Rather than race threads and hope for the interleaving, the setup reserves the low position up front
 * and commits the inverted event at a controlled moment, so the scenario is exact and cannot flake.
 */
public class PostgresLockCheckOrderingTest {

	abstract static class Tests {

		final String image;

		Tests ( String image ) {
			this.image = image;
		}

		@Test
		public void testLockCheckSeesEventWithLowerPositionButHigherTx ( ) throws Exception {
			String prefix = "lockorder_";
			DataSource dataSource = PostgresContainer.dataSource(image);

			EventStorage storage = PostgresEventStorage.newBuilder()
				.name("unit-test")
				.prefix(prefix)
				.dataSource(dataSource)
				.initializeDatabase()
				.build();

			try {
				EventStreamId stream = EventStreamId.forContext("account").withPurpose("42");
				Tags boundaryTags = Tags.of("account", "42");
				EventQuery boundary = EventQuery.forEvents(EventTypesFilter.any(), boundaryTags);

				// 1. Reserve a position out of band. Nothing has consumed it yet, so every event the
				//    library appends from here on carries a HIGHER position than this one.
				long reservedPosition = reserveNextPosition(dataSource, prefix);

				// 2. Append through the library: higher position, and a transaction that commits now.
				storage.append(AppendCriteria.none(), Optional.of(stream),
					List.of(event(stream, "MoneyDeposited", boundaryTags)));

				// 3. Read the boundary the way a decider would, and take the reference it would use.
				List<StoredEvent> seen = storage
					.query(boundary, Optional.of(stream), null, Limit.none(), QueryDirection.FORWARD)
					.toList();
				assertEquals(1, seen.size(), "expected the appended event to be readable");
				EventReference reference = seen.get(0).reference();
				assertTrue(reference.position() > reservedPosition,
					"the reserved position must be lower than the appended event's, or the inversion is not set up");

				// 4. Now commit an event at the reserved (lower) position. Its transaction starts after
				//    the one above committed, so it takes a HIGHER tx: position and tx now disagree.
				insertAtPosition(dataSource, prefix, reservedPosition, stream, "MoneyWithdrawn", boundaryTags);

				// 5. Every reader sorts that event AFTER the reference, despite the lower position —
				//    the read path orders by (event_tx, event_position).
				List<StoredEvent> replay = storage
					.query(boundary, Optional.of(stream), null, Limit.none(), QueryDirection.FORWARD)
					.toList();
				assertEquals(2, replay.size(), "expected both events to be readable");
				EventReference inverted = replay.get(1).reference();
				assertEquals(reservedPosition, inverted.position(),
					"the event with the LOWER position must be read last, or the inversion did not happen");
				assertTrue(inverted.happenedAfter(reference),
					"the inverted event must count as happening after the reference");

				// 6. ...so it is a new relevant fact, and the lock check has to see it. Comparing on
				//    event_position alone would not: reservedPosition < reference.position().
				assertThrows(OptimisticLockingException.class,
					() -> storage.append(AppendCriteria.of(boundary, reference), Optional.of(stream),
						List.of(event(stream, "MoneyWithdrawn", boundaryTags))),
					"appending against a stale reference must conflict on an event that sorts after it");

				// 7. Control: the check is not simply rejecting everything. Against the reference a
				//    reader would hold now, the very same append succeeds.
				List<StoredEvent> appended = storage.append(AppendCriteria.of(boundary, inverted),
					Optional.of(stream), List.of(event(stream, "MoneyWithdrawn", boundaryTags)));
				assertEquals(1, appended.size(), "appending against the current reference must succeed");
			} finally {
				storage.close();
			}
		}

		private EventToStore event ( EventStreamId stream, String type, Tags tags ) {
			return new EventToStore(stream, new EventType(type), "{}", null, tags, null);
		}

		/**
		 * Consumes one value from the events table's position sequence without inserting anything, so a
		 * row can be given that position later, from a transaction that by then holds a higher tx.
		 */
		private long reserveNextPosition ( DataSource dataSource, String prefix ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
				  PreparedStatement stmt = connection.prepareStatement(
					  "SELECT nextval(pg_get_serial_sequence(?, 'event_position'))") ) {
				stmt.setString(1, prefix + "events");
				try ( ResultSet rs = stmt.executeQuery() ) {
					assertTrue(rs.next(), "expected the position sequence to yield a value");
					return rs.getLong(1);
				}
			}
		}

		/**
		 * Inserts an event at an explicitly chosen position, letting {@code event_tx} take its default,
		 * so the row lands with that position and whatever transaction id this insert is assigned.
		 */
		private void insertAtPosition ( DataSource dataSource, String prefix, long position,
				EventStreamId stream, String type, Tags tags ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
				  PreparedStatement insert = connection.prepareStatement(
					  "INSERT INTO " + prefix + "events "
					  + "(event_position, event_id, stream_context, stream_purpose, event_type, event_data, event_tags) "
					  + "VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, ?)") ) {
				insert.setLong(1, position);
				insert.setString(2, UUID.randomUUID().toString());
				insert.setString(3, stream.context());
				insert.setString(4, stream.purpose());
				insert.setString(5, type);
				insert.setString(6, "{}");
				// Encode the tags the way the library does, so the query filter matches them.
				insert.setArray(7, connection.createArrayOf("text", tags.toStrings().toArray(new String[0])));
				insert.executeUpdate();
			}
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
