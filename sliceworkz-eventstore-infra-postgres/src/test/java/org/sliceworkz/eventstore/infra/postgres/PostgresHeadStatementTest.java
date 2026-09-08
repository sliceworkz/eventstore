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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * The statement behind {@code EventStorage.head} on PostgreSQL, checked as text.
 * <p>
 * Nothing else would notice a regression here: the head is correct whatever columns the statement
 * selects, and the compliance suite can only tell that it answers. What makes it cheap regardless of
 * how large the event at the head is -- three columns off the stream position index, no payload --
 * and what makes it sound -- the same visibility barrier every read goes through -- are properties of
 * the SQL, so they are pinned on the SQL.
 */
public class PostgresHeadStatementTest {

	@Test
	public void testTheHeadReadsNoPayloadColumns ( ) {
		String sql = PostgresEventStorageImpl.headSql("pfx_", Optional.of(EventStreamId.forContext("account").withPurpose("42")));

		assertTrue(sql.contains("event_position"), sql);
		assertTrue(sql.contains("event_tx"), sql);
		assertTrue(sql.contains("event_id"), sql);
		assertFalse(sql.contains("event_data"), "the head must not read the payload: " + sql);
		assertFalse(sql.contains("event_tags"), "the head must not read the tags: " + sql);
		assertFalse(sql.contains("event_type"), "the head must not read the type: " + sql);
	}

	@Test
	public void testTheHeadIsTheNewestVisibleEventOfTheStream ( ) {
		String sql = PostgresEventStorageImpl.headSql("pfx_", Optional.of(EventStreamId.forContext("account").withPurpose("42")));

		assertTrue(sql.contains("FROM pfx_events"), sql);
		assertTrue(sql.contains("event_tx < pg_snapshot_xmin(pg_current_snapshot())"),
				"the head must sit behind the same visibility barrier as every read: " + sql);
		assertTrue(sql.contains("stream_context = ?"), sql);
		assertTrue(sql.contains("stream_purpose = ?"), sql);
		assertTrue(sql.contains("ORDER BY event_tx::xid8 DESC, event_position DESC"),
				"the head is the newest event in the (tx, position) order every read uses: " + sql);
		assertTrue(sql.contains("LIMIT 1"), sql);
	}

	@Test
	public void testAWildcardStreamAsksForTheStorageWideHead ( ) {
		String anyPurpose = PostgresEventStorageImpl.headSql("pfx_", Optional.of(EventStreamId.forContext("account").anyPurpose()));
		assertTrue(anyPurpose.contains("stream_context = ?"), anyPurpose);
		assertFalse(anyPurpose.contains("stream_purpose"), anyPurpose);

		String anyStream = PostgresEventStorageImpl.headSql("pfx_", Optional.of(EventStreamId.anyContext().anyPurpose()));
		assertFalse(anyStream.contains("stream_context"), anyStream);
		assertFalse(anyStream.contains("stream_purpose"), anyStream);

		String noStream = PostgresEventStorageImpl.headSql("pfx_", Optional.empty());
		assertFalse(noStream.contains("stream_context"), noStream);
		assertFalse(noStream.contains("stream_purpose"), noStream);
	}

}
