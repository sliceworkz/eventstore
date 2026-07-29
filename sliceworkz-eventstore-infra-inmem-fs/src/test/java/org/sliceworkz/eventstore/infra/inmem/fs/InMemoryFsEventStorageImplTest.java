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
package org.sliceworkz.eventstore.infra.inmem.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorage.ImportMode;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

public class InMemoryFsEventStorageImplTest {

	private static boolean eventFileExists ( Path eventsDir, String prefix ) throws IOException {
		try ( Stream<Path> paths = Files.list(eventsDir) ) {
			return paths.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
		}
	}

	private static Path findEventFile ( Path eventsDir, String prefix ) throws IOException {
		try ( Stream<Path> paths = Files.list(eventsDir) ) {
			return paths.filter(p -> p.getFileName().toString().startsWith(prefix))
					.findFirst().orElseThrow();
		}
	}

	sealed interface TestEvent {
		record CustomerRegistered ( String name ) implements TestEvent { }
		record CustomerNameChanged ( String name ) implements TestEvent { }
	}

	@Test
	void testEventRoundTrip ( @TempDir Path tempDir ) throws IOException {
		EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");

		// First instance: append events
		{
			EventStore store = InMemoryFsEventStorage.newBuilder()
					.directory(tempDir)
					.name("test-store")
					.buildStore();

			EventStream<TestEvent> stream = store.getEventStream(streamId, TestEvent.class);
			stream.append(AppendCriteria.none(), List.of(
					Event.of(new TestEvent.CustomerRegistered("John"), Tags.of("customer", "123")),
					Event.of(new TestEvent.CustomerNameChanged("Jane"), Tags.of("customer", "123"))
			));
		}

		// Verify JSON files exist on disk (filenames include dynamic timestamps)
		Path eventsDir = tempDir.resolve("events");
		assertTrue(eventFileExists(eventsDir, "0000000001-00001-0-"));
		assertTrue(eventFileExists(eventsDir, "0000000001-00002-0-"));

		// Second instance: reload from disk
		{
			EventStore store = InMemoryFsEventStorage.newBuilder()
					.directory(tempDir)
					.name("test-store-2")
					.buildStore();

			EventStream<TestEvent> stream = store.getEventStream(streamId, TestEvent.class);
			List<Event<TestEvent>> events = stream.query(EventQuery.matchAll()).toList();

			assertEquals(2, events.size());
			assertEquals("CustomerRegistered", events.get(0).type().name());
			assertEquals("CustomerNameChanged", events.get(1).type().name());
			assertTrue(events.get(0).tags().containsAll(Tags.of("customer", "123")));
		}
	}

	@Test
	void testBookmarkRoundTrip ( @TempDir Path tempDir ) {
		EventStreamId streamId = EventStreamId.forContext("orders").withPurpose("default");

		// First instance: append events and place bookmark
		EventReference bookmarkRef;
		{
			EventStorage storage = InMemoryFsEventStorage.newBuilder()
					.directory(tempDir)
					.name("bookmark-test")
					.build();
			EventStore store = EventStoreFactory.get().eventStore(storage);

			EventStream<TestEvent> stream = store.getEventStream(streamId, TestEvent.class);
			stream.append(AppendCriteria.none(), List.of(
					Event.of(new TestEvent.CustomerRegistered("Alice"), Tags.none())
			));

			List<Event<TestEvent>> events = stream.query(EventQuery.matchAll()).toList();
			bookmarkRef = events.get(0).reference();
			storage.bookmark("my-projection", bookmarkRef, Tags.none());
		}

		// Verify bookmark file exists
		assertTrue(Files.exists(tempDir.resolve("bookmarks/my-projection.json")));

		// Second instance: verify bookmark loaded
		{
			EventStorage storage = InMemoryFsEventStorage.newBuilder()
					.directory(tempDir)
					.name("bookmark-test-2")
					.build();

			var loaded = storage.getBookmark("my-projection");
			assertTrue(loaded.isPresent());
			assertEquals(bookmarkRef.id(), loaded.get().id());
			assertEquals(bookmarkRef.position(), loaded.get().position());
			assertEquals(bookmarkRef.tx(), loaded.get().tx());
		}
	}

	@Test
	void testRemoveBookmark ( @TempDir Path tempDir ) {
		EventStreamId streamId = EventStreamId.forContext("ctx").withPurpose("p");

		EventStorage storage = InMemoryFsEventStorage.newBuilder()
				.directory(tempDir)
				.name("remove-test")
				.build();
		EventStore store = EventStoreFactory.get().eventStore(storage);

		EventStream<TestEvent> stream = store.getEventStream(streamId, TestEvent.class);
		stream.append(AppendCriteria.none(), List.of(
				Event.of(new TestEvent.CustomerRegistered("Bob"), Tags.none())
		));

		List<Event<TestEvent>> events = stream.query(EventQuery.matchAll()).toList();
		storage.bookmark("temp-reader", events.get(0).reference(), Tags.none());

		assertTrue(Files.exists(tempDir.resolve("bookmarks/temp-reader.json")));

		storage.removeBookmark("temp-reader");

		assertTrue(storage.getBookmark("temp-reader").isEmpty());
		assertTrue(Files.notExists(tempDir.resolve("bookmarks/temp-reader.json")));

		// Verify removal survives restart
		EventStorage storage2 = InMemoryFsEventStorage.newBuilder()
				.directory(tempDir)
				.name("remove-test-2")
				.build();
		assertTrue(storage2.getBookmark("temp-reader").isEmpty());
	}

	@Test
	void testEmptyDirectory ( @TempDir Path tempDir ) {
		EventStore store = InMemoryFsEventStorage.newBuilder()
				.directory(tempDir)
				.name("empty-test")
				.buildStore();

		EventStreamId streamId = EventStreamId.forContext("ctx").withPurpose("p");
		EventStream<TestEvent> stream = store.getEventStream(streamId, TestEvent.class);
		List<Event<TestEvent>> events = stream.query(EventQuery.matchAll()).toList();
		assertTrue(events.isEmpty());
	}

	@Test
	void testBuildStoreConvenience ( @TempDir Path tempDir ) {
		EventStore store = InMemoryFsEventStorage.newBuilder()
				.directory(tempDir)
				.buildStore();
		assertNotNull(store);

		EventStreamId streamId = EventStreamId.forContext("test").withPurpose("1");
		EventStream<TestEvent> stream = store.getEventStream(streamId, TestEvent.class);
		stream.append(AppendCriteria.none(), List.of(
				Event.of(new TestEvent.CustomerRegistered("Test"), Tags.none())
		));

		assertEquals(1, stream.query(EventQuery.matchAll()).count());
	}

	@Test
	void testEventDataPreserved ( @TempDir Path tempDir ) throws IOException {
		EventStreamId streamId = EventStreamId.forContext("ctx").withPurpose("p");

		// Append event
		EventStore store = InMemoryFsEventStorage.newBuilder()
				.directory(tempDir)
				.name("data-test")
				.buildStore();

		EventStream<TestEvent> stream = store.getEventStream(streamId, TestEvent.class);
		stream.append(AppendCriteria.none(), List.of(
				Event.of(new TestEvent.CustomerRegistered("John Doe"), Tags.of("customer", "42"))
		));

		// Verify JSON file is human-readable
		String json = Files.readString(findEventFile(tempDir.resolve("events"), "0000000001-00001-0-"));
		assertTrue(json.contains("\"context\" : \"ctx\""));
		assertTrue(json.contains("\"purpose\" : \"p\""));
		assertTrue(json.contains("\"type\" : \"CustomerRegistered\""));
		assertTrue(json.contains("\"customer\""));

		// Reload and verify data is intact
		EventStore store2 = InMemoryFsEventStorage.newBuilder()
				.directory(tempDir)
				.name("data-test-2")
				.buildStore();

		EventStream<TestEvent> stream2 = store2.getEventStream(streamId, TestEvent.class);
		List<Event<TestEvent>> events = stream2.query(EventQuery.matchAll()).toList();
		assertEquals(1, events.size());

		TestEvent data = events.get(0).data();
		assertTrue(data instanceof TestEvent.CustomerRegistered);
		assertEquals("John Doe", ((TestEvent.CustomerRegistered) data).name());
	}

	@Test
	void testIdempotencyKeyIsStillKnownAfterAReload ( @TempDir Path tempDir ) {
		EventStreamId streamId = EventStreamId.forContext("ctx").withPurpose("p");

		// First instance: append an event carrying an idempotency key
		{
			EventStore store = InMemoryFsEventStorage.newBuilder()
					.directory(tempDir)
					.name("idempotency-test")
					.buildStore();

			EventStream<TestEvent> stream = store.getEventStream(streamId, TestEvent.class);
			List<Event<TestEvent>> appended = stream.append(AppendCriteria.none(),
					Collections.singletonList(Event.of(new TestEvent.CustomerRegistered("John"), Tags.none()).withIdempotencyKey("k-1")));
			assertEquals(1, appended.size());
		}

		// Second instance: the reloaded store restores its event log from disk, and must restore what it
		// knows about idempotency along with it — otherwise a retry after a restart writes a duplicate
		EventStore store2 = InMemoryFsEventStorage.newBuilder()
				.directory(tempDir)
				.name("idempotency-test-2")
				.buildStore();

		EventStream<TestEvent> stream2 = store2.getEventStream(streamId, TestEvent.class);
		List<Event<TestEvent>> duplicate = stream2.append(AppendCriteria.none(),
				Collections.singletonList(Event.of(new TestEvent.CustomerRegistered("John"), Tags.none()).withIdempotencyKey("k-1")));

		assertTrue(duplicate.isEmpty(), "an idempotency key used before the reload must still deduplicate");
		assertEquals(1, stream2.query(EventQuery.matchAll()).toList().size());
	}

	@Test
	void testImportedEventsArePersisted ( @TempDir Path tempDir ) {
		EventStreamId streamId = EventStreamId.forContext("ctx").withPurpose("p");
		EventId id = EventId.create();
		LocalDateTime timestamp = LocalDateTime.of(2020, 1, 2, 3, 4, 5);

		// First instance: import an event straight into storage
		{
			EventStorage storage = InMemoryFsEventStorage.newBuilder()
					.directory(tempDir)
					.name("import-test")
					.build();

			storage.importEvents(
					List.of(new EventToImport(streamId, EventType.ofType("CustomerRegistered"), id,
							"{\"name\":\"John\"}", null, Tags.of("customer", "42"), timestamp, "imported-key")),
					ImportMode.FAIL_ON_EXISTING_ID);
		}

		// Second instance: imports must reach disk like appends do, not live only in memory
		EventStorage reloaded = InMemoryFsEventStorage.newBuilder()
				.directory(tempDir)
				.name("import-test-2")
				.build();

		Optional<StoredEvent> found = reloaded.getEventById(id);
		assertTrue(found.isPresent(), "an imported event must survive a restart");
		assertEquals(timestamp, found.get().timestamp());
		assertEquals("imported-key", found.get().idempotencyKey());
	}

}
