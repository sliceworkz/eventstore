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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.spi.EventStorage.AppendsToEventStoreNotification;
import org.sliceworkz.eventstore.spi.EventStorage.BookmarkPlacedNotification;
import org.sliceworkz.eventstore.spi.EventStorage.EventStoreListener;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A monitor whose socket dies without saying so notices, and replaces it.
 * <p>
 * The monitoring connections go through a TCP proxy that can, on command, stop carrying bytes on the
 * connections it holds open — without closing either side. That is what a half-open connection is: the
 * client's socket is fine, its writes succeed, and nothing ever comes back. No FIN, no RST, nothing the
 * driver could turn into an exception; only a deadline on a read, or a probe, can tell it from a quiet
 * channel.
 */
class PostgresMonitorLivenessTest {

	/** generous enough not to be flaky, far below "forever", which is what is being tested against */
	private static final Duration MUST_RETURN_WITHIN = Duration.ofSeconds(30);

	private static final EventStreamId STREAM = EventStreamId.forContext("liveness").withPurpose("p");

	@BeforeAll
	static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

	@AfterAll
	static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

	/**
	 * Forwards TCP connections to the database, and can turn every connection it currently carries into a
	 * black hole: bytes in either direction are swallowed, neither side is closed. Connections accepted
	 * afterwards are carried normally, so a reconnect through the same port succeeds.
	 */
	static final class BlackholingProxy implements AutoCloseable {

		private final ServerSocket server;
		private final String targetHost;
		private final int targetPort;
		private final AtomicInteger generation = new AtomicInteger();
		private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
		private final ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor();
		private volatile boolean closed;

		BlackholingProxy ( String targetHost, int targetPort ) throws IOException {
			this.targetHost = targetHost;
			this.targetPort = targetPort;
			this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			pumps.execute(this::accept);
		}

		int port ( ) {
			return server.getLocalPort();
		}

		/** every connection open right now goes silent in both directions, without being closed */
		void blackholeOpenConnections ( ) {
			generation.incrementAndGet();
		}

		private void accept ( ) {
			while ( !closed ) {
				try {
					Socket client = server.accept();
					Socket upstream = new Socket(targetHost, targetPort);
					client.setTcpNoDelay(true);
					upstream.setTcpNoDelay(true);
					sockets.add(client);
					sockets.add(upstream);
					int connectionGeneration = generation.get();
					pumps.execute(() -> pump(client, upstream, connectionGeneration));
					pumps.execute(() -> pump(upstream, client, connectionGeneration));
				} catch ( IOException e ) {
					if ( !closed ) {
						throw new IllegalStateException("proxy accept failed", e);
					}
				}
			}
		}

		private void pump ( Socket from, Socket to, int connectionGeneration ) {
			byte[] buffer = new byte[8192];
			try {
				InputStream in = from.getInputStream();
				OutputStream out = to.getOutputStream();
				int read;
				while ( (read = in.read(buffer)) >= 0 ) {
					if ( generation.get() == connectionGeneration ) {
						out.write(buffer, 0, read);
						out.flush();
					}
					// else swallowed: the other side sees nothing, and sees no close either
				}
			} catch ( IOException e ) {
				// one side went away
			} finally {
				if ( generation.get() == connectionGeneration ) {
					// a live connection propagates its close; a black-holed one propagates nothing, that is the point
					closeQuietly(from);
					closeQuietly(to);
				}
			}
		}

		@Override
		public void close ( ) {
			closed = true;
			closeQuietly(server);
			sockets.forEach(BlackholingProxy::closeQuietly);
			pumps.shutdownNow();
		}

		private static void closeQuietly ( AutoCloseable closeable ) {
			try {
				closeable.close();
			} catch ( Exception e ) {
				// closing
			}
		}
	}

	static class RecordingListener implements EventStoreListener {
		final List<AppendsToEventStoreNotification> appends = new CopyOnWriteArrayList<>();
		final List<BookmarkPlacedNotification> bookmarks = new CopyOnWriteArrayList<>();
		@Override public void notify ( AppendsToEventStoreNotification newEventsInStore ) { appends.add(newEventsInStore); }
		@Override public void notify ( BookmarkPlacedNotification bookmarkPlaced ) { bookmarks.add(bookmarkPlaced); }
	}

	/** the database behind the proxy: host and port of the container, as the driver sees them */
	private static URI databaseAddress ( ) {
		return URI.create(PostgresContainer.jdbcUrl(PostgresContainer.IMAGE_PG18, "integration-tests-db").substring("jdbc:".length()));
	}

	/** a small monitoring pool through the proxy; small so that no idle connection sits in it dead after a black-holing */
	private static HikariDataSource monitoringPoolThrough ( BlackholingProxy proxy ) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl("jdbc:postgresql://127.0.0.1:" + proxy.port() + "/integration-tests-db");
		config.setUsername("sa");
		config.setPassword("pwd");
		config.setPoolName("monitoring-through-proxy");
		config.setMaximumPoolSize(2);
		config.setMinimumIdle(0);
		config.setValidationTimeout(1000);
		return new HikariDataSource(config);
	}

	private static double gauge ( MeterRegistry registry, String channel ) {
		return registry.get("sliceworkz.eventstore.notifications.up").tag("channel", channel).gauge().value();
	}

	private static StoredEvent append ( PostgresEventStorage storage ) {
		return storage.append(AppendCriteria.none(), STREAM,
			List.of(new EventToStore(STREAM, EventType.ofType("SomethingHappened"), "{}", Tags.none(), null))).getFirst();
	}

	private static boolean announced ( RecordingListener listener, StoredEvent event ) {
		return listener.appends.stream().anyMatch(n -> !event.reference().happenedAfter(n.atLeastUntil()));
	}

	@Test
	void aMonitorWhoseSocketDiesSilentlyDropsItAndListensAgain ( ) throws Exception {
		URI database = databaseAddress();
		DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
		MeterRegistry registry = new SimpleMeterRegistry();
		try ( BlackholingProxy proxy = new BlackholingProxy(database.getHost(), database.getPort());
			  HikariDataSource monitoring = monitoringPoolThrough(proxy);
			  PostgresEventStorage storage = PostgresEventStorage.newBuilder()
					.name("liveness")
					.prefix("liveness_")
					.dataSource(main)
					.monitoringDataSource(monitoring)
					.databaseInitMode(DatabaseInitMode.INITIALIZE)
					.meterRegistry(registry)
					.notificationProbeInterval(Duration.ofSeconds(1))
					.build() ) {

			RecordingListener listener = new RecordingListener();
			storage.subscribe(listener);
			assertTrue(storage.isNotificationsAvailable());

			// through the proxy, notifications arrive as they would directly
			StoredEvent before = append(storage);
			awaitTrue(() -> announced(listener, before), "a notification should get through the proxy while it forwards");

			// from here on the two monitoring connections carry nothing, and nothing tells the driver so
			AtomicBoolean sawTheGaugeDrop = new AtomicBoolean();
			Thread sampler = Thread.ofVirtual().start(() -> {
				while ( !Thread.currentThread().isInterrupted() ) {
					if ( gauge(registry, "event_appended") == 0d ) {
						sawTheGaugeDrop.set(true);
					}
					try { Thread.sleep(10); } catch ( InterruptedException e ) { return; }
				}
			});
			proxy.blackholeOpenConnections();

			// this NOTIFY is delivered to a session whose client will never receive it. Nothing replays it:
			// what this test asks is that the *next* one is not lost as well
			StoredEvent duringTheBlackhole = append(storage);

			// the probe finds the dead connection, the monitor drops it and connects again -- through the
			// proxy, which carries the new connection -- and an append made afterwards is announced
			awaitTrue(sawTheGaugeDrop::get, "the probe should have found the dead connection and the monitor dropped it, taking the gauge to 0");
			awaitTrue(storage::isNotificationsAvailable, "the monitors should have listened again on fresh connections");
			StoredEvent after = append(storage);
			awaitTrue(() -> announced(listener, after),
				"no append notification arrived after the monitoring sockets went silent: the monitor is still "
				+ "reading a dead connection, which is exactly the failure nothing but a probe can detect");
			storage.bookmark("reader", duringTheBlackhole.reference(), Tags.none());
			awaitTrue(() -> !listener.bookmarks.isEmpty(), "the bookmark monitor should have recovered the same way");

			sampler.interrupt();
			assertTrue(sawTheGaugeDrop.get(), "the gauge should have read 0 between dropping the dead connection and listening again");
			assertEquals(1d, gauge(registry, "event_appended"));
			assertEquals(1d, gauge(registry, "bookmark_placed"));
		} finally {
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}

	@Test
	void probingDoesNotDisturbALiveChannel ( ) throws Exception {
		DataSource main = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
		MeterRegistry registry = new SimpleMeterRegistry();
		try ( PostgresEventStorage storage = PostgresEventStorage.newBuilder()
					.name("liveness-quiet")
					.prefix("liveness_quiet_")
					.dataSource(main)
					.databaseInitMode(DatabaseInitMode.INITIALIZE)
					.meterRegistry(registry)
					// far more often than anyone would configure, so that probes interleave with traffic
					.notificationProbeInterval(Duration.ofMillis(200))
					.build() ) {

			RecordingListener listener = new RecordingListener();
			storage.subscribe(listener);
			AtomicBoolean sawTheGaugeDrop = new AtomicBoolean();

			StoredEvent last = null;
			for ( int i = 0; i < 8; i++ ) {
				Thread.sleep(300);   // quiet long enough for a probe between every two appends
				last = append(storage);
				if ( gauge(registry, "event_appended") == 0d || gauge(registry, "bookmark_placed") == 0d ) {
					sawTheGaugeDrop.set(true);
				}
			}
			StoredEvent newest = last;
			awaitTrue(() -> announced(listener, newest), "every append should still be announced with probes interleaved");
			storage.bookmark("reader", newest.reference(), Tags.none());
			awaitTrue(() -> !listener.bookmarks.isEmpty(), "bookmarks should still be announced with probes interleaved");

			assertFalse(sawTheGaugeDrop.get(), "a probe on a live connection must never take a monitor down");
			assertTrue(storage.isNotificationsAvailable());
		} finally {
			PostgresContainer.closeDataSource(PostgresContainer.IMAGE_PG18);
		}
	}

	private static void awaitTrue ( BooleanSupplier condition, String message ) throws Exception {
		long deadline = System.nanoTime() + MUST_RETURN_WITHIN.toNanos();
		while ( System.nanoTime() < deadline ) {
			if ( condition.getAsBoolean() ) {
				return;
			}
			Thread.sleep(100);
		}
		fail(message);
	}
}
