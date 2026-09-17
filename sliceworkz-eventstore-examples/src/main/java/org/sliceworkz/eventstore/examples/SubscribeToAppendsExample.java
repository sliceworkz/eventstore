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
package org.sliceworkz.eventstore.examples;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventSource;
import org.sliceworkz.eventstore.stream.EventStreamEventuallyConsistentAppendListener;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Follows every append to a PostgreSQL-backed store, the way a second process would: the store's
 * LISTEN/NOTIFY monitors wake the subscriber, which then reads what it has not seen yet.
 * <p>
 * <b>This is the one example that needs a database.</b> The others run against the in-memory store,
 * but a subscription only shows something when <em>another</em> process appends, and an in-memory
 * store is not shared between processes. So this one needs:
 * <ul>
 *   <li>a reachable PostgreSQL database. The builder's default init mode creates the tables and the
 *       {@code btree_gin} extension itself, given a role allowed to (see the postgres module README
 *       for the privileges).</li>
 *   <li>a {@code db.properties} describing the pooled and the monitoring connection. This module
 *       deliberately does not ship one: a file of placeholders on the classpath would make the store
 *       try to connect to {@code <host>} instead of saying what is missing. Copy the template at
 *       {@code sliceworkz-eventstore-infra-postgres/src/main/quickstart/db.properties}, fill it in, and
 *       either pass its path as the first argument or put it where the builder looks on its own — the
 *       system property {@code eventstore.db.config}, the environment variable
 *       {@code EVENTSTORE_DB_CONFIG}, or {@code ./db.properties} in the working directory. Without any
 *       of these, {@code buildStore()} fails naming every location it tried.</li>
 * </ul>
 * Then append from another process — a second store built on the same {@code db.properties} — and
 * watch the events arrive here.
 */
public class SubscribeToAppendsExample {

	public static void main ( String[] args ) throws IOException {

		// database backed eventstore, configured from the db.properties passed as an argument or found by the builder
		PostgresEventStorage.Builder builder = PostgresEventStorage.newBuilder();
		if ( args.length > 0 ) {
			builder.configuration(Path.of(args[0]));
		}

		// closing the store ends the subscription below and stops the LISTEN/NOTIFY monitors
		try ( EventStore eventstore = builder.buildStore() ) {

			// we open a (readonly) eventstream that sees all events
			EventSource<String> stream = eventstore.getRawEventStream(EventStreamId.anyContext());

			// the newest stored event is our starting point: head() names it without reading it (absent for an empty stream: follow from the beginning)
			Handle<EventReference> lastSeen = Handle.of(stream.head().orElse(null));

			System.out.println("following all events as from " + lastSeen.get());

			stream.subscribe(new EventStreamEventuallyConsistentAppendListener() {

				@Override
				public synchronized EventReference eventsAppended(EventReference atLeastUntil) {

					// each time we are notified, we query any events after the last we've seen ...
					List<Event<String>> events = stream.query(EventQuery.matchAll(), lastSeen.get());
					events.forEach(System.out::println);

					// and change our reference point
					if ( ! events.isEmpty() ) {
						lastSeen.set(events.getLast().reference());
					}
					return lastSeen.get();
				}

			});

			System.out.println("press enter to exit ...");
			System.in.read();
			System.out.println("exiting.");
		}

	}

	public static class Handle<T> {
		private T value;
		public Handle ( T value ) {
			this.value = value;
		}
		T get ( ) {
			return value;
		}

		void set ( T value ) {
			this.value = value;
		}
		static <T> Handle<T> of ( T value ) {
			return new Handle<> ( value );
		}
	}
}
