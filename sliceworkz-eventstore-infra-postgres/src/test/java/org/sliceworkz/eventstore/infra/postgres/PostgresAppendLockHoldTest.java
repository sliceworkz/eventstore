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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * A conditional append holds its stream's advisory lock for the server's own work only, never while the
 * server waits on the client.
 * <p>
 * The appends go through a TCP proxy that delays everything by {@link #ONE_WAY} in each direction, which is
 * what a database a network hop away looks like. Meanwhile an observer on a direct connection watches
 * {@code pg_locks} for the stream's append lock and records the state of the session holding it. A lock
 * held across a round trip shows its holder {@code idle in transaction} -- waiting for the client's next
 * message -- for a whole round trip; a lock taken, used and released in one pipeline only ever shows it
 * {@code active}. The control is a session that takes the lock and waits, which the same observer must
 * see idle, or it would see nothing at all.
 * <p>
 * Every scenario appends more often than pgjdbc's {@code prepareThreshold} (five), because the driver
 * sends a statement differently once it is server-prepared, and that is where the pipeline can come apart
 * (see {@code PostgresEventStorageImpl.conditionalAppendStatement}).
 */
class PostgresAppendLockHoldTest {

	private static final Duration ONE_WAY = Duration.ofMillis(40);

	/** how long a holder's state must have lasted to count: well under a round trip of 2 × ONE_WAY */
	private static final Duration SETTLED = Duration.ofMillis(20);

	/** comfortably past pgjdbc's default prepareThreshold of five */
	private static final int APPENDS = 12;

	private static final EventStreamId STREAM = EventStreamId.forContext("lockhold").withPurpose("p");

	@BeforeAll
	static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

	@AfterAll
	static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

	@Test
	void theObserverSeesALockHeldWhileItsHolderWaitsOnTheClient ( ) throws Exception {
		DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
		try ( PostgresEventStorageImpl storage = storage(main, main, DatabaseInitMode.RECREATE, Duration.ofSeconds(10));
			  Observer observer = new Observer(main, storage.appendLockKey(STREAM));
			  PostgresLockTimeoutTest.Holder holder = new PostgresLockTimeoutTest.Holder(main, storage.appendLockKey(STREAM)) ) {
			observer.awaitAnyState();
			assertTrue(observer.states().contains("idle in transaction"), observer.states().toString());
		}
	}

	@Test
	void aConditionalAppendHoldsItsLockOnlyWhileTheServerWorks ( ) throws Exception {
		assertLockNeverWaitsOnTheClient(Duration.ofSeconds(10));
	}

	@Test
	void soDoesOneWithoutALockTimeout ( ) throws Exception {
		assertLockNeverWaitsOnTheClient(Duration.ZERO);
	}

	private void assertLockNeverWaitsOnTheClient ( Duration lockTimeout ) throws Exception {
		DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
		try ( PostgresEventStorageImpl storage = storage(main, main, DatabaseInitMode.RECREATE, lockTimeout);
			  DelayingProxy proxy = new DelayingProxy(databaseAddress(), ONE_WAY);
			  HikariDataSource distant = poolThrough(proxy);
			  PostgresEventStorageImpl distantStorage = storage(distant, main, DatabaseInitMode.NONE, lockTimeout);
			  Observer observer = new Observer(main, storage.appendLockKey(STREAM)) ) {

			// an empty boundary on an empty stream, then a current reference each time: all admitted
			StoredEvent last = null;
			for ( int i = 0; i < APPENDS; i++ ) {
				List<StoredEvent> stored = distantStorage.append(AppendCriteria.of(EventFilter.matchAll(), last == null ? null : last.reference()), STREAM, oneEvent(null));
				assertEquals(1, stored.size());
				last = stored.getFirst();
			}

			// a reference that is stale: refused, and nothing stored
			StoredEvent stale = last;
			distantStorage.append(AppendCriteria.of(EventFilter.matchAll(), stale.reference()), STREAM, oneEvent(null));
			assertThrows(OptimisticLockingException.class, () ->
					distantStorage.append(AppendCriteria.of(EventFilter.matchAll(), stale.reference()), STREAM, oneEvent(null)));

			// a key stored before, under a boundary the keyed event does not match: swallowed, as before
			AppendCriteria nothingElse = AppendCriteria.of(EventFilter.forTypes(EventType.named("SomethingElse")), null);
			for ( int i = 0; i < APPENDS; i++ ) {
				assertEquals(1, distantStorage.append(nothingElse, STREAM, oneEvent("k-" + i)).size());
				assertEquals(0, distantStorage.append(nothingElse, STREAM, oneEvent("k-" + i)).size());
			}

			assertFalse(observer.states().contains("idle in transaction"),
					"the append lock was held while its holder waited on the client: " + observer.states());
			assertEquals(APPENDS + 1 + APPENDS, eventCount(main));
		}
	}

	private static PostgresEventStorageImpl storage ( DataSource main, DataSource monitoring, DatabaseInitMode mode, Duration lockTimeout ) {
		return (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
				.name("lock-hold")
				.prefix("lockhold_")
				.dataSource(main)
				.monitoringDataSource(monitoring)
				.databaseInitMode(mode)
				.lockTimeout(lockTimeout)
				.build();
	}

	private static List<EventToStore> oneEvent ( String idempotencyKey ) {
		return List.of(new EventToStore(STREAM, EventType.named("SomethingHappened"), "{}", Tags.none(), idempotencyKey));
	}

	private static long eventCount ( DataSource dataSource ) throws SQLException {
		try ( Connection connection = dataSource.getConnection();
			  ResultSet rs = connection.createStatement().executeQuery("SELECT count(*) FROM lockhold_events") ) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static URI databaseAddress ( ) {
		return URI.create(PostgresContainer.jdbcUrl(PostgresContainer.IMAGE_PG18, "integration-tests-db").substring("jdbc:".length()));
	}

	private static HikariDataSource poolThrough ( DelayingProxy proxy ) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + proxy.port() + "/integration-tests-db");
		config.setUsername("sa");
		config.setPassword("pwd");
		config.setPoolName("main-through-delaying-proxy");
		config.setMaximumPoolSize(2);
		return new HikariDataSource(config);
	}

	/** forwards to the database, delaying every chunk by a fixed time in each direction and keeping their order */
	static final class DelayingProxy implements AutoCloseable {

		private final ServerSocket server;
		private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
		private volatile boolean closed;

		DelayingProxy ( URI target, Duration oneWay ) throws IOException {
			server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			Thread.ofPlatform().daemon().start(() -> {
				while ( !closed ) {
					try {
						Socket client = server.accept();
						Socket upstream = new Socket(target.getHost(), target.getPort());
						client.setTcpNoDelay(true);
						upstream.setTcpNoDelay(true);
						sockets.add(client);
						sockets.add(upstream);
						pump(client, upstream, oneWay.toNanos());
						pump(upstream, client, oneWay.toNanos());
					} catch ( IOException e ) {
						// closed
					}
				}
			});
		}

		int port ( ) {
			return server.getLocalPort();
		}

		private record Chunk ( long dueNanos, byte[] bytes ) { }

		private static void pump ( Socket from, Socket to, long delayNanos ) {
			BlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
			Chunk end = new Chunk(0, null);
			Thread.ofPlatform().daemon().start(() -> {
				try ( InputStream in = from.getInputStream() ) {
					byte[] buffer = new byte[65536];
					int read;
					while ( ( read = in.read(buffer) ) > 0 ) {
						byte[] bytes = new byte[read];
						System.arraycopy(buffer, 0, bytes, 0, read);
						queue.add(new Chunk(System.nanoTime() + delayNanos, bytes));
					}
				} catch ( IOException e ) {
					// one side went away
				} finally {
					queue.add(end);
				}
			});
			Thread.ofPlatform().daemon().start(() -> {
				try ( OutputStream out = to.getOutputStream() ) {
					for ( Chunk chunk = queue.take(); chunk != end; chunk = queue.take() ) {
						long wait;
						while ( ( wait = chunk.dueNanos() - System.nanoTime() ) > 0 ) {
							LockSupport.parkNanos(wait);
						}
						out.write(chunk.bytes());
						out.flush();
					}
				} catch ( IOException | InterruptedException e ) {
					// one side went away
				} finally {
					closeQuietly(from);
					closeQuietly(to);
				}
			});
		}

		@Override
		public void close ( ) {
			closed = true;
			closeQuietly(server);
			sockets.forEach(DelayingProxy::closeQuietly);
		}

		private static void closeQuietly ( AutoCloseable closeable ) {
			try {
				closeable.close();
			} catch ( Exception e ) {
				// closing
			}
		}
	}

	/** watches the state of whichever session holds the given advisory lock, every few milliseconds */
	private static final class Observer implements AutoCloseable {

		private final Set<String> states = ConcurrentHashMap.newKeySet();
		private final AtomicBoolean running = new AtomicBoolean(true);
		private final Thread thread;

		Observer ( DataSource dataSource, long key ) throws SQLException {
			Connection connection = dataSource.getConnection();
			// A state counts once it has lasted SETTLED: the two views are not read as one, so a session that
			// has just committed and received its next BEGIN can show the moment between that BEGIN and the
			// rest of its pipeline beside the lock of the transaction before. A wait on the client lasts a
			// round trip, two ONE_WAYs, far longer.
			PreparedStatement query = connection.prepareStatement("""
					SELECT a.state FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
					WHERE l.locktype = 'advisory' AND l.granted AND l.classid::bigint = ? AND l.objid::bigint = ? AND l.objsubid = 1
					  AND clock_timestamp() - a.state_change > make_interval(secs => ?)""");
			// a bigint advisory key is split over classid (its high half) and objid (its low half)
			query.setLong(1, key >>> 32);
			query.setLong(2, key & 0xffffffffL);
			query.setDouble(3, SETTLED.toMillis() / 1000d);
			thread = Thread.ofPlatform().daemon().start(() -> {
				try ( connection; query ) {
					while ( running.get() ) {
						try ( ResultSet rs = query.executeQuery() ) {
							while ( rs.next() ) {
								states.add(String.valueOf(rs.getString(1)));
							}
						}
						Thread.sleep(5);
					}
				} catch ( SQLException | InterruptedException e ) {
					states.add("observer failed: " + e);
				}
			});
		}

		void awaitAnyState ( ) throws InterruptedException {
			long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			while ( states.isEmpty() && System.nanoTime() < deadline ) {
				Thread.sleep(5);
			}
		}

		Set<String> states ( ) {
			return Set.copyOf(states);
		}

		@Override
		public void close ( ) throws InterruptedException {
			running.set(false);
			thread.join();
		}
	}
}
