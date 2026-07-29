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
package org.sliceworkz.eventstore.spi;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.infra.postgres.util.PostgresContainer;
import org.sliceworkz.eventstore.migration.EventStoreImporter;
import org.sliceworkz.eventstore.migration.ImportReport;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage.AppendsToEventStoreNotification;
import org.sliceworkz.eventstore.spi.EventStorage.BookmarkPlacedNotification;
import org.sliceworkz.eventstore.spi.EventStorage.EventStoreListener;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.ImportMode;
import org.sliceworkz.eventstore.spi.EventStorage.QueryDirection;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Shared compliance scenarios for {@link EventStorage#importEvents(List, ImportMode)} and
 * {@link EventStoreImporter}, run against every storage backend so both behave identically.
 * <p>
 * These operate purely at the SPI level: events are written with {@link EventToStore} and read back as
 * {@link StoredEvent}, exactly as an import does. No domain classes, no serde, no upcasting.
 */
class EventImportTest {

	abstract static class Tests {

		private EventStorage source;
		private EventStorage target;

		private final EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");
		private final EventStreamId otherStream = EventStreamId.forContext("other").withPurpose("default");

		@BeforeEach
		public void setUp ( ) {
			this.source = createEventStorage("importsrc");
			this.target = createEventStorage("importtgt");
		}

		@AfterEach
		public void tearDown ( ) {
			destroyEventStorage(source);
			destroyEventStorage(target);
		}

		abstract EventStorage createEventStorage ( String discriminator );

		void destroyEventStorage ( EventStorage storage ) {
		}

		// --- helpers ---

		private EventToStore event ( EventStreamId stream, String type, String payload, String idempotencyKey ) {
			return new EventToStore(stream, EventType.ofType(type), payload, null, Tags.of("kind", type), idempotencyKey);
		}

		private List<StoredEvent> appendTo ( EventStorage storage, EventToStore... events ) {
			return storage.append(AppendCriteria.none(), Optional.of(events[0].stream()), List.of(events));
		}

		private List<StoredEvent> allEventsIn ( EventStorage storage ) {
			return storage.query(EventQuery.matchAll(), Optional.empty(), null, Limit.none(), QueryDirection.FORWARD).toList();
		}

		private List<EventToImport> toImport ( List<StoredEvent> storedEvents ) {
			return storedEvents.stream().map(EventToImport::from).toList();
		}

		private List<String> idsOf ( List<StoredEvent> storedEvents ) {
			return storedEvents.stream().map(e -> e.reference().id().value()).toList();
		}

		/** Seeds the source with three events, the middle one carrying an idempotency key. */
		private List<StoredEvent> seedSource ( ) {
			appendTo(source, event(stream, "First", "{\"a\":1}", null));
			appendTo(source, event(stream, "Second", "{\"b\":2}", "key-2"));
			appendTo(source, event(otherStream, "Third", "{\"c\":3}", null));
			return allEventsIn(source);
		}

		// --- storage level: what an import preserves ---

		@Test
		void testImportPreservesIdentityTimestampTagsAndIdempotencyKey ( ) {
			List<StoredEvent> sourceEvents = seedSource();
			assertEquals(3, sourceEvents.size());

			List<StoredEvent> imported = target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

			assertEquals(3, imported.size());
			for ( int i = 0; i < sourceEvents.size(); i++ ) {
				StoredEvent original = sourceEvents.get(i);
				StoredEvent copy = imported.get(i);

				assertEquals(original.reference().id(), copy.reference().id(), "event id must survive the import");
				assertEquals(original.timestamp(), copy.timestamp(), "timestamp must survive the import");
				assertEquals(original.idempotencyKey(), copy.idempotencyKey(), "idempotency key must survive the import");
				assertEquals(original.type(), copy.type());
				assertEquals(original.stream(), copy.stream());
				assertEquals(original.tags(), copy.tags());
			}

			// and reading them back out of the target gives the same thing
			List<StoredEvent> readBack = allEventsIn(target);
			assertEquals(idsOf(sourceEvents), idsOf(readBack));
			assertEquals("key-2", readBack.get(1).idempotencyKey());
		}

		@Test
		void testImportAssignsFreshPositions ( ) {
			// target already holds an event, so imported events cannot land on the source's positions
			appendTo(target, event(stream, "Existing", "{\"x\":0}", null));

			List<StoredEvent> sourceEvents = seedSource();
			List<StoredEvent> imported = target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

			assertEquals(3, imported.size());
			for ( int i = 0; i < imported.size(); i++ ) {
				assertNotEquals(sourceEvents.get(i).reference().position(), imported.get(i).reference().position(),
						"position is assigned by the target, never copied");
			}
			// positions are increasing, so the source order is preserved
			for ( int i = 1; i < imported.size(); i++ ) {
				assertTrue(imported.get(i - 1).reference().happenedBefore(imported.get(i).reference()));
			}
			assertEquals(4, allEventsIn(target).size());
		}

		@Test
		void testImportPreservesErasableData ( ) {
			appendTo(source, new EventToStore(stream, EventType.ofType("WithErasable"), "{\"keep\":1}", "{\"secret\":\"x\"}", Tags.none(), null));
			List<StoredEvent> sourceEvents = allEventsIn(source);

			target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

			StoredEvent copy = allEventsIn(target).getFirst();
			assertTrue(copy.immutableData().contains("keep"));
			assertNotNull(copy.erasableData());
			assertTrue(copy.erasableData().contains("secret"));
		}

		@Test
		void testImportSpansMultipleStreamsInOneCall ( ) {
			List<StoredEvent> sourceEvents = seedSource();

			target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

			List<StoredEvent> inApp = target.query(EventQuery.matchAll(), Optional.of(stream), null, Limit.none(), QueryDirection.FORWARD).toList();
			List<StoredEvent> inOther = target.query(EventQuery.matchAll(), Optional.of(otherStream), null, Limit.none(), QueryDirection.FORWARD).toList();
			assertEquals(2, inApp.size());
			assertEquals(1, inOther.size());
		}

		@Test
		void testImportedEventIsRetrievableByItsOriginalId ( ) {
			List<StoredEvent> sourceEvents = seedSource();
			target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

			EventId originalId = sourceEvents.getFirst().reference().id();
			Optional<StoredEvent> found = target.getEventById(originalId);

			assertTrue(found.isPresent());
			assertEquals(originalId, found.get().reference().id());
		}

		@Test
		void testImportNotifiesListeners ( ) {
			List<StoredEvent> sourceEvents = seedSource();
			AtomicInteger notifications = new AtomicInteger();
			EventStoreListener listener = new EventStoreListener() {
				@Override
				public void notify ( AppendsToEventStoreNotification newEventsInStore ) {
					notifications.incrementAndGet();
				}

				@Override
				public void notify ( BookmarkPlacedNotification bookmarkPlaced ) {
				}
			};
			target.subscribe(listener);

			target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

			// in-memory notifies inline, Postgres delivers over LISTEN/NOTIFY on a monitor thread
			await().atMost(Duration.ofSeconds(5))
					.pollInterval(Duration.ofMillis(100))
					.until(() -> notifications.get() > 0);
		}

		// --- storage level: conflicts ---

		@Test
		void testFailOnExistingIdRaisesOnReimport ( ) {
			List<StoredEvent> sourceEvents = seedSource();
			target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

			EventImportConflictException conflict = assertThrows(EventImportConflictException.class,
					() -> target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID));

			assertEquals(EventImportConflictException.Kind.DUPLICATE_EVENT_ID, conflict.kind());
			assertEquals(3, allEventsIn(target).size(), "a rejected batch must leave nothing behind");
		}

		@Test
		void testSkipExistingIdIsIdempotent ( ) {
			List<StoredEvent> sourceEvents = seedSource();
			target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

			List<StoredEvent> second = target.importEvents(toImport(sourceEvents), ImportMode.SKIP_EXISTING_ID);

			assertTrue(second.isEmpty(), "everything was already there, so nothing is imported");
			assertEquals(3, allEventsIn(target).size());
		}

		@Test
		void testSkipExistingIdResumesPartialImport ( ) {
			List<StoredEvent> sourceEvents = seedSource();

			// first attempt got only the first two across
			target.importEvents(toImport(sourceEvents.subList(0, 2)), ImportMode.FAIL_ON_EXISTING_ID);

			List<StoredEvent> resumed = target.importEvents(toImport(sourceEvents), ImportMode.SKIP_EXISTING_ID);

			assertEquals(1, resumed.size(), "only the event that never landed is imported");
			assertEquals(sourceEvents.get(2).reference().id(), resumed.getFirst().reference().id());
			assertEquals(idsOf(sourceEvents), idsOf(allEventsIn(target)));
		}

		@Test
		void testDuplicateIdempotencyKeyIsFatalInBothModes ( ) {
			// the target already used this key on this stream, for a completely different event
			appendTo(target, event(stream, "Other", "{\"z\":9}", "key-2"));
			List<StoredEvent> sourceEvents = seedSource();

			EventImportConflictException failing = assertThrows(EventImportConflictException.class,
					() -> target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID));
			assertEquals(EventImportConflictException.Kind.DUPLICATE_IDEMPOTENCY_KEY, failing.kind());

			// skipping is about identifiers only, so it must not absorb this
			EventImportConflictException skipping = assertThrows(EventImportConflictException.class,
					() -> target.importEvents(toImport(sourceEvents), ImportMode.SKIP_EXISTING_ID));
			assertEquals(EventImportConflictException.Kind.DUPLICATE_IDEMPOTENCY_KEY, skipping.kind());

			assertEquals(1, allEventsIn(target).size(), "nothing of the rejected batch may survive");
		}

		@Test
		void testSameIdempotencyKeyOnAnotherStreamDoesNotCollide ( ) {
			// keys are scoped per stream, so this must not stand in the way of the import
			appendTo(target, event(otherStream, "Other", "{\"z\":9}", "key-2"));
			List<StoredEvent> sourceEvents = seedSource();

			target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

			assertEquals(4, allEventsIn(target).size());
		}

		@Test
		void testDuplicateIdWithinOneBatchIsRejected ( ) {
			List<StoredEvent> sourceEvents = seedSource();
			List<EventToImport> batch = new ArrayList<>(toImport(sourceEvents));
			batch.add(batch.getFirst());

			assertThrows(IllegalArgumentException.class, () -> target.importEvents(batch, ImportMode.FAIL_ON_EXISTING_ID));
			assertTrue(allEventsIn(target).isEmpty());
		}

		@Test
		void testInvalidJsonPayloadIsRejected ( ) {
			List<StoredEvent> sourceEvents = seedSource();
			List<EventToImport> batch = List.of(toImport(sourceEvents).getFirst().withImmutableData("not json at all"));

			assertThrows(EventStorageException.class, () -> target.importEvents(batch, ImportMode.FAIL_ON_EXISTING_ID));
			assertTrue(allEventsIn(target).isEmpty());
		}

		@Test
		void testEmptyImportIsANoOp ( ) {
			assertTrue(target.importEvents(List.of(), ImportMode.FAIL_ON_EXISTING_ID).isEmpty());
			assertTrue(allEventsIn(target).isEmpty());
		}

		// --- importer level ---

		@Test
		void testImporterCopiesTheWholeStore ( ) {
			List<StoredEvent> sourceEvents = seedSource();

			ImportReport report = EventStoreImporter.from(source).to(target).run();

			assertEquals(3, report.read());
			assertEquals(3, report.imported());
			assertEquals(0, report.dropped());
			assertEquals(0, report.skipped());
			assertEquals(sourceEvents.getLast().reference(), report.sourceTo());
			assertNull(report.sourceFrom());
			assertNotNull(report.firstTargetReference());
			assertNotNull(report.lastTargetReference());
			assertEquals(idsOf(sourceEvents), idsOf(allEventsIn(target)));
		}

		@Test
		void testImporterHonoursBatchSizeAndReportsProgress ( ) {
			IntStream.range(0, 7).forEach(i -> appendTo(source, event(stream, "Bulk", "{\"i\":%d}".formatted(i), null)));

			List<ImportReport> progress = new ArrayList<>();
			ImportReport report = EventStoreImporter.from(source).to(target)
					.batchSize(2)
					.onProgress(progress::add)
					.run();

			assertEquals(7, report.imported());
			assertEquals(4, progress.size(), "7 events in batches of 2 means 4 batches");
			assertEquals(7, progress.getLast().imported());
			assertEquals(idsOf(allEventsIn(source)), idsOf(allEventsIn(target)));
		}

		@Test
		void testImporterRemapsTheStream ( ) {
			seedSource();
			EventStreamId archive = EventStreamId.forContext("archive").withPurpose("default");

			EventStoreImporter.from(source).to(target)
					.transform(src -> Optional.of(EventToImport.from(src).withStream(archive)))
					.run();

			List<StoredEvent> imported = allEventsIn(target);
			assertEquals(3, imported.size());
			assertTrue(imported.stream().allMatch(e -> archive.equals(e.stream())));
		}

		@Test
		void testImporterDropsEventsTheTransformationDiscards ( ) {
			seedSource();

			ImportReport report = EventStoreImporter.from(source).to(target)
					.transform(src -> "Second".equals(src.type().name()) ? Optional.empty() : Optional.of(EventToImport.from(src)))
					.run();

			assertEquals(3, report.read());
			assertEquals(1, report.dropped());
			assertEquals(2, report.imported());
			assertEquals(2, allEventsIn(target).size());
		}

		@Test
		void testImporterCatchesUpAfterAnEarlierRun ( ) {
			seedSource();

			ImportReport first = EventStoreImporter.from(source).to(target).run();
			assertEquals(3, first.imported());

			// source moves on after the first run finished
			appendTo(source, event(stream, "Fourth", "{\"d\":4}", null));
			appendTo(source, event(stream, "Fifth", "{\"e\":5}", null));

			ImportReport catchUp = EventStoreImporter.from(source).to(target)
					.after(first.sourceTo())
					.run();

			assertEquals(2, catchUp.read(), "only what the source gained since the boundary is read");
			assertEquals(2, catchUp.imported());
			assertEquals(first.sourceTo(), catchUp.sourceFrom());
			assertEquals(idsOf(allEventsIn(source)), idsOf(allEventsIn(target)));
		}

		@Test
		void testImporterIsBoundedAtTheSourceHead ( ) {
			seedSource();

			// nothing new since the head, so a run starting there does no work at all
			EventReference head = allEventsIn(source).getLast().reference();
			ImportReport report = EventStoreImporter.from(source).to(target).after(head).run();

			assertEquals(0, report.read());
			assertEquals(0, report.imported());
			assertTrue(allEventsIn(target).isEmpty());
		}

		@Test
		void testImporterOnAnEmptySourceDoesNothing ( ) {
			ImportReport report = EventStoreImporter.from(source).to(target).run();

			assertEquals(0, report.read());
			assertNull(report.sourceTo());
			assertTrue(allEventsIn(target).isEmpty());
		}

		@Test
		void testImporterCanCloneWithinOneStoreWithoutRunningAway ( ) {
			seedSource();
			EventStreamId clone = EventStreamId.forContext("clone").withPurpose("default");

			// source and target are the same storage: only the head boundary keeps this from
			// re-reading the clones it is writing and looping forever
			ImportReport report = EventStoreImporter.from(source).to(source)
					.batchSize(2)
					.transform(src -> Optional.of(EventToImport.from(src)
							.withId(EventId.create())
							.withStream(clone)
							.withIdempotencyKey(null)))
					.run();

			assertEquals(3, report.read());
			assertEquals(3, report.imported());
			assertEquals(6, allEventsIn(source).size());

			List<StoredEvent> cloned = source.query(EventQuery.matchAll(), Optional.of(clone), null, Limit.none(), QueryDirection.FORWARD).toList();
			assertEquals(3, cloned.size());
			assertFalse(idsOf(cloned).stream().anyMatch(idsOf(allEventsIn(source).subList(0, 3))::contains),
					"the clone must carry fresh identifiers");
		}

		@Test
		void testImporterResumesAnInterruptedRun ( ) {
			seedSource();

			// a first run that dies part way: emulated by importing only the first batch
			EventStoreImporter.from(source).to(target)
					.batchSize(1)
					.transform(src -> "First".equals(src.type().name()) ? Optional.of(EventToImport.from(src)) : Optional.empty())
					.run();
			assertEquals(1, allEventsIn(target).size());

			ImportReport resumed = EventStoreImporter.from(source).to(target)
					.mode(ImportMode.SKIP_EXISTING_ID)
					.run();

			assertEquals(3, resumed.read());
			assertEquals(2, resumed.imported());
			assertEquals(1, resumed.skipped());
			assertEquals(idsOf(allEventsIn(source)), idsOf(allEventsIn(target)));
		}

		@Test
		void testImporterRequiresATarget ( ) {
			assertThrows(IllegalStateException.class, () -> EventStoreImporter.from(source).run());
		}

	}

	@Nested
	class OnInMem extends Tests {
		@Override
		EventStorage createEventStorage ( String discriminator ) {
			return InMemoryEventStorage.newBuilder().name(discriminator).build();
		}
	}

	@Nested
	class OnPostgres17 extends Tests {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG17); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG17); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG17); }

		private DataSource dataSource;

		@Override
		EventStorage createEventStorage ( String discriminator ) {
			// one DataSource shared by source and target: asking the container for a second one would
			// close the first. The two stores are kept apart by their table prefix instead.
			if ( dataSource == null ) {
				dataSource = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG17);
			}
			return PostgresEventStorage.newBuilder()
					.name(discriminator)
					.prefix(discriminator + "_")
					.dataSource(dataSource)
					.initializeDatabase()
					.build();
		}

		@Override
		void destroyEventStorage ( EventStorage storage ) {
			((PostgresEventStorageImpl)storage).stop();
		}
	}

	@Nested
	class OnPostgres18 extends Tests {

		@BeforeAll
		static void startContainer ( ) { PostgresContainer.start(PostgresContainer.IMAGE_PG18); }

		@AfterAll
		static void stopContainer ( ) { PostgresContainer.stop(PostgresContainer.IMAGE_PG18); PostgresContainer.cleanup(PostgresContainer.IMAGE_PG18); }

		private DataSource dataSource;

		@Override
		EventStorage createEventStorage ( String discriminator ) {
			if ( dataSource == null ) {
				dataSource = PostgresContainer.dataSource(PostgresContainer.IMAGE_PG18);
			}
			return PostgresEventStorage.newBuilder()
					.name(discriminator)
					.prefix(discriminator + "_")
					.dataSource(dataSource)
					.initializeDatabase()
					.build();
		}

		@Override
		void destroyEventStorage ( EventStorage storage ) {
			((PostgresEventStorageImpl)storage).stop();
		}
	}

}
