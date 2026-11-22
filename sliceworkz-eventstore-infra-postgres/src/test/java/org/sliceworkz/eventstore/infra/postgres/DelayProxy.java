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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * A TCP proxy that delivers every chunk it reads a fixed delay later, in order, in each direction: a
 * database a network hop away, for the append-pipelining harness, where the kernel offers no
 * {@code netem}. Not a test.
 *
 * <pre>java ... DelayProxy &lt;listenPort&gt; &lt;targetHost&gt; &lt;targetPort&gt; &lt;oneWayDelayMicros&gt;</pre>
 *
 * The delay is one way, so a round trip costs twice it, plus the proxy's own forwarding. Chunks are
 * delayed from when they were read, not serialized behind each other, so the proxy adds latency and
 * not a bandwidth limit.
 */
public final class DelayProxy {

	record Chunk ( long dueNanos, byte[] bytes ) { }

	private static final Chunk END = new Chunk(0, new byte[0]);

	public static void main ( String[] args ) throws IOException {
		int listenPort = Integer.parseInt(args[0]);
		String targetHost = args[1];
		int targetPort = Integer.parseInt(args[2]);
		long delayNanos = Long.parseLong(args[3]) * 1000;
		try ( ServerSocket server = new ServerSocket() ) {
			server.setReuseAddress(true);
			server.bind(new InetSocketAddress("127.0.0.1", listenPort));
			System.out.printf("delay proxy :%d -> %s:%d, %dus each way%n", listenPort, targetHost, targetPort, delayNanos / 1000);
			while ( true ) {
				Socket client = server.accept();
				Socket target = new Socket(targetHost, targetPort);
				client.setTcpNoDelay(true);
				target.setTcpNoDelay(true);
				pipe(client, target, delayNanos);
				pipe(target, client, delayNanos);
			}
		}
	}

	static void pipe ( Socket from, Socket to, long delayNanos ) throws IOException {
		InputStream in = from.getInputStream();
		OutputStream out = to.getOutputStream();
		LinkedBlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
		Thread.ofPlatform().daemon().start(() -> {
			byte[] buffer = new byte[64 * 1024];
			try {
				int n;
				while ( (n = in.read(buffer)) > 0 ) {
					queue.put(new Chunk(System.nanoTime() + delayNanos, Arrays.copyOf(buffer, n)));
				}
			} catch ( IOException | InterruptedException e ) {
				// the connection is gone
			}
			queue.add(END);
		});
		Thread.ofPlatform().daemon().start(() -> {
			try {
				while ( true ) {
					Chunk chunk = queue.take();
					if ( chunk == END ) {
						break;
					}
					// parked, not spun: the proxy shares the machine with what it measures, and both modes
					// pay the same overshoot of the park
					long wait;
					while ( (wait = chunk.dueNanos() - System.nanoTime()) > 0 ) {
						LockSupport.parkNanos(wait);
					}
					out.write(chunk.bytes());
					out.flush();
				}
			} catch ( IOException | InterruptedException e ) {
				// the connection is gone
			}
			try {
				from.close();
				to.close();
			} catch ( IOException e ) {
				// closing anyway
			}
		});
	}
}
