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
package org.sliceworkz.eventstore.benchmark.env;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A TCP proxy that delays everything it forwards by a fixed time in each direction, so a server on the
 * same machine answers as one a network hop away.
 *
 * <p>For measuring what the distance between the application and the database costs: point
 * {@code db.properties} at the proxy's port and run a profile as usual. Each direction is a queue: a
 * reader stamps every chunk with when it is due and a writer forwards it then, so order is kept and the
 * delay is added to every round trip once each way, however many are in flight.
 *
 * <p>Kernel-level shaping ({@code tc qdisc ... netem delay}) is the better instrument where it is
 * available, since it adds nothing of its own; this is for a machine without it. It does add something:
 * two thread hand-offs per direction, measured at about 0.4ms of round trip on a four-core VM with no
 * delay configured. Measure the round trip through it (a timed {@code SELECT 1} in {@code psql}) and
 * quote that, not the configured delay.
 */
public final class DelayProxy {

	private static final Logger LOGGER = LoggerFactory.getLogger(DelayProxy.class);

	private DelayProxy ( ) { }

	/** listens on loopback at {@code listenPort} until the process ends */
	public static void run ( int listenPort, String targetHost, int targetPort, long oneWayMicros ) throws IOException {
		long delayNanos = oneWayMicros * 1_000L;
		try ( ServerSocket server = new ServerSocket(listenPort, 512, InetAddress.getLoopbackAddress()) ) {
			LOGGER.info("delaying {} -> {}:{} by {}µs each way", listenPort, targetHost, targetPort, oneWayMicros);
			while ( true ) {
				Socket client = server.accept();
				Socket upstream;
				try {
					upstream = new Socket(targetHost, targetPort);
				} catch ( IOException e ) {
					LOGGER.warn("cannot reach {}:{}", targetHost, targetPort, e);
					client.close();
					continue;
				}
				client.setTcpNoDelay(true);
				upstream.setTcpNoDelay(true);
				forward(client, upstream, delayNanos);
				forward(upstream, client, delayNanos);
			}
		}
	}

	private record Chunk ( long dueNanos, byte[] bytes ) { }

	private static final Chunk END = new Chunk(0, null);

	private static void forward ( Socket from, Socket to, long delayNanos ) {
		BlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
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
				// the connection ended; the writer closes both sides
			} finally {
				queue.add(END);
			}
		});
		Thread.ofPlatform().daemon().start(() -> {
			try ( OutputStream out = to.getOutputStream() ) {
				for ( Chunk chunk = queue.take(); chunk != END; chunk = queue.take() ) {
					long wait;
					while ( ( wait = chunk.dueNanos() - System.nanoTime() ) > 0 ) {
						LockSupport.parkNanos(wait);
					}
					out.write(chunk.bytes());
					out.flush();
				}
			} catch ( IOException | InterruptedException e ) {
				// the connection ended
			} finally {
				try { from.close(); } catch ( IOException e ) { /* closing */ }
				try { to.close(); } catch ( IOException e ) { /* closing */ }
			}
		});
	}
}
