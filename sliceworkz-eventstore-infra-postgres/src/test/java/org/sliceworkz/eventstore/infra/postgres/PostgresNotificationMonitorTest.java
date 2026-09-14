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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage.AppendsToEventStoreNotification;
import org.sliceworkz.eventstore.spi.EventStorage.BookmarkPlacedNotification;
import org.sliceworkz.eventstore.spi.EventStorage.EventStoreListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * What a LISTEN/NOTIFY monitor does with what arrives on its channel, without a database.
 * <p>
 * A channel is a database-wide name: any session can {@code NOTIFY} on it, and the trigger of another
 * release of this library may not agree with this one on the payload. Each monitor is the only one its
 * storage has, and its death is silent — a virtual thread ending takes nothing else with it — so the
 * steps that turn a payload into a notification and hand it to the listeners must not be able to end
 * the monitor, whatever the payload and whatever a listener throws. These scenarios drive those steps
 * directly; {@code PostgresNotificationStartupTest} does the same through {@code pg_notify} on a live
 * store.
 */
class PostgresNotificationMonitorTest {

	/** what the append trigger emits: {@code eventTx} rendered as a JSON string, the rest as-is */
	private static final String VALID_APPEND = """
		{"streamContext":"customer","streamPurpose":"42","eventPosition":7,"eventTx":"1001","eventId":"evt-7"}""";
	private static final String VALID_BOOKMARK = """
		{"reader":"projection-a","eventPosition":7,"eventTx":"1001","eventId":"evt-7"}""";

	/** a DataSource the monitors never reach: the parse and delivery steps are called directly here */
	private static final DataSource NEVER_CONNECTS = new DataSource() {
		@Override public Connection getConnection ( ) throws SQLException { throw new SQLException("not in this test"); }
		@Override public Connection getConnection ( String username, String password ) throws SQLException { return getConnection(); }
		@Override public PrintWriter getLogWriter ( ) { return null; }
		@Override public void setLogWriter ( PrintWriter out ) { /* unused */ }
		@Override public void setLoginTimeout ( int seconds ) { /* unused */ }
		@Override public int getLoginTimeout ( ) { return 0; }
		@Override public Logger getParentLogger ( ) throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
		@Override public <T> T unwrap ( Class<T> iface ) throws SQLException { throw new SQLException("not a wrapper"); }
		@Override public boolean isWrapperFor ( Class<?> iface ) { return false; }
	};

	static class RecordingListener implements EventStoreListener {
		final List<AppendsToEventStoreNotification> appends = new CopyOnWriteArrayList<>();
		final List<BookmarkPlacedNotification> bookmarks = new CopyOnWriteArrayList<>();
		@Override public void notify ( AppendsToEventStoreNotification newEventsInStore ) { appends.add(newEventsInStore); }
		@Override public void notify ( BookmarkPlacedNotification bookmarkPlaced ) { bookmarks.add(bookmarkPlaced); }
	}

	/** throws an {@code Error}, not an exception: the wider of the two things a listener can do */
	static class ErroringListener implements EventStoreListener {
		@Override public void notify ( AppendsToEventStoreNotification newEventsInStore ) { throw new AssertionError("a test double that asserts"); }
		@Override public void notify ( BookmarkPlacedNotification bookmarkPlaced ) { throw new AssertionError("a test double that asserts"); }
	}

	private final List<EventStoreListener> listeners = new CopyOnWriteArrayList<>();
	private final RecordingListener recording = new RecordingListener();
	private final PostgresEventStorageImpl storage = new PostgresLegacyEventStorageImpl(
		"monitor-under-test", NEVER_CONNECTS, NEVER_CONNECTS, Limit.none(), "mon_", false, new SimpleMeterRegistry());
	private final PostgresEventStorageImpl.NewEventsAppendedMonitor appendMonitor =
		storage.new NewEventsAppendedMonitor("append-monitor", listeners, NEVER_CONNECTS, new CountDownLatch(1));
	private final PostgresEventStorageImpl.BookmarkPlacedMonitor bookmarkMonitor =
		storage.new BookmarkPlacedMonitor("bookmark-monitor", listeners, NEVER_CONNECTS, new CountDownLatch(1));

	@AfterEach
	void closeStorage ( ) {
		storage.close();
	}

	@Test
	void aValidAppendPayloadParsesIntoTheStreamAndReferenceItNames ( ) {
		AppendsToEventStoreNotification notification = appendMonitor.parse(VALID_APPEND).orElseThrow();

		assertEquals(EventStreamId.forContext("customer").withPurpose("42"), notification.stream());
		assertEquals(EventReference.of(EventId.of("evt-7"), 7, 1001), notification.atLeastUntil());
	}

	@Test
	void anAppendPayloadThatIsNotJsonParsesIntoNothingRatherThanThrowing ( ) {
		assertEquals(Optional.empty(), assertDoesNotThrow(() -> appendMonitor.parse("not json at all")));
		assertEquals(Optional.empty(), assertDoesNotThrow(() -> appendMonitor.parse("")));
		assertEquals(Optional.empty(), assertDoesNotThrow(() -> appendMonitor.parse("{\"unexpected\":true}")));
	}

	/**
	 * The hole a parser-typed catch leaves: the JSON is fine, and the conversion into an
	 * {@code EventReference} is what throws.
	 */
	@Test
	void anAppendPayloadThatParsesIntoNoValidReferenceParsesIntoNothingRatherThanThrowing ( ) {
		// a position EventReference refuses
		assertEquals(Optional.empty(), assertDoesNotThrow(() -> appendMonitor.parse(
			"{\"streamContext\":\"c\",\"streamPurpose\":\"p\",\"eventPosition\":0,\"eventTx\":\"1\",\"eventId\":\"x\"}")));
		// no id at all -- what a stale row-level trigger body emits, with every field null
		assertEquals(Optional.empty(), assertDoesNotThrow(() -> appendMonitor.parse(
			"{\"streamContext\":null,\"streamPurpose\":null,\"eventPosition\":null,\"eventTx\":null,\"eventId\":null}")));
	}

	@Test
	void aListenerThrowingAnErrorDoesNotStarveTheOneBehindItNorEndTheMonitor ( ) {
		listeners.add(new ErroringListener());
		listeners.add(recording);

		assertDoesNotThrow(() -> appendMonitor.deliver(appendMonitor.parse(VALID_APPEND).orElseThrow()));
		assertDoesNotThrow(() -> bookmarkMonitor.deliver(bookmarkMonitor.parse(VALID_BOOKMARK).orElseThrow()));

		assertEquals(1, recording.appends.size(), "the listener behind the failing one should still be notified");
		assertEquals(1, recording.bookmarks.size(), "the listener behind the failing one should still be notified");
	}

	@Test
	void aValidBookmarkPayloadParsesIntoTheReaderAndReferenceItNames ( ) {
		BookmarkPlacedNotification notification = bookmarkMonitor.parse(VALID_BOOKMARK).orElseThrow();

		assertEquals("projection-a", notification.reader());
		assertEquals(EventReference.of(EventId.of("evt-7"), 7, 1001), notification.bookmark());
	}

	@Test
	void aBookmarkPayloadThatCannotBecomeANotificationParsesIntoNothingRatherThanThrowing ( ) {
		assertEquals(Optional.empty(), assertDoesNotThrow(() -> bookmarkMonitor.parse("not json at all")));
		assertEquals(Optional.empty(), assertDoesNotThrow(() -> bookmarkMonitor.parse(
			"{\"reader\":\"r\",\"eventPosition\":0,\"eventTx\":\"1\",\"eventId\":\"x\"}")));
		assertEquals(Optional.empty(), assertDoesNotThrow(() -> bookmarkMonitor.parse(
			"{\"reader\":\"r\",\"eventPosition\":1,\"eventTx\":\"1\",\"eventId\":null}")));
	}
}
