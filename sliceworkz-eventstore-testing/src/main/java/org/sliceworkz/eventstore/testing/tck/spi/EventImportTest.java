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
package org.sliceworkz.eventstore.testing.tck.spi;

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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.migration.EventStoreImporter;
import org.sliceworkz.eventstore.migration.ImportReport;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
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
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;
import org.sliceworkz.eventstore.spi.EventImportConflictException;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.spi.EventStorageException;
import org.sliceworkz.eventstore.spi.EventToImport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.sliceworkz.eventstore.testing.StorageOptions;
import org.sliceworkz.eventstore.testing.EventStoreBackend.Capability;

/**
 * Shared compliance scenarios for {@link EventStorage#importEvents(List, ImportMode)} and
 * {@link EventStoreImporter}, run against every storage backend so both behave identically.
 * <p>
 * These operate purely at the SPI level: events are written with {@link EventToStore} and read back as
 * {@link StoredEvent}, exactly as an import does. No domain classes, no serde, no upcasting.
 */
public class EventImportTest extends AbstractEventStoreTest {

	private EventStorage source;
	private EventStorage target;

	private final EventStreamId stream = EventStreamId.forContext("app").withPurpose("default");
	private final EventStreamId otherStream = EventStreamId.forContext("other").withPurpose("default");

	@Override
	protected StorageOptions storageOptions ( ) {
		return StorageOptions.defaults().withDiscriminator("importsrc");
	}

	@BeforeEach
	void openSourceAndTarget ( ) {
		// the base class store is the source; an import needs a second, independent one to write into
		this.source = eventStorage();
		this.target = backend().createEventStorage(StorageOptions.defaults().withDiscriminator("importtgt"));
	}

	@AfterEach
	void closeTarget ( ) {
		if ( target != null ) {
			backend().destroyEventStorage(target);
			target = null;
		}
	}

	// --- helpers ---

	private EventToStore event ( EventStreamId stream, String type, String payload, String idempotencyKey ) {
		return new EventToStore(stream, EventType.ofType(type), payload, Tags.of("kind", type), idempotencyKey);
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

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
	void testImportSpansMultipleStreamsInOneCall ( ) {
		List<StoredEvent> sourceEvents = seedSource();

		target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

		List<StoredEvent> inApp = target.query(EventQuery.matchAll(), Optional.of(stream), null, Limit.none(), QueryDirection.FORWARD).toList();
		List<StoredEvent> inOther = target.query(EventQuery.matchAll(), Optional.of(otherStream), null, Limit.none(), QueryDirection.FORWARD).toList();
		assertEquals(2, inApp.size());
		assertEquals(1, inOther.size());
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImportedEventIsRetrievableByItsOriginalId ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

		EventId originalId = sourceEvents.getFirst().reference().id();
		Optional<StoredEvent> found = target.getEventById(originalId);

		assertTrue(found.isPresent());
		assertEquals(originalId, found.get().reference().id());
	}

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
	void testFailOnExistingIdRaisesOnReimport ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

		EventImportConflictException conflict = assertThrows(EventImportConflictException.class,
				() -> target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID));

		assertEquals(EventImportConflictException.Kind.DUPLICATE_EVENT_ID, conflict.kind());
		assertEquals(3, allEventsIn(target).size(), "a rejected batch must leave nothing behind");
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testSkipExistingIdIsIdempotent ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

		List<StoredEvent> second = target.importEvents(toImport(sourceEvents), ImportMode.SKIP_EXISTING_ID);

		assertTrue(second.isEmpty(), "everything was already there, so nothing is imported");
		assertEquals(3, allEventsIn(target).size());
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testSkipExistingIdResumesPartialImport ( ) {
		List<StoredEvent> sourceEvents = seedSource();

		// first attempt got only the first two across
		target.importEvents(toImport(sourceEvents.subList(0, 2)), ImportMode.FAIL_ON_EXISTING_ID);

		List<StoredEvent> resumed = target.importEvents(toImport(sourceEvents), ImportMode.SKIP_EXISTING_ID);

		assertEquals(1, resumed.size(), "only the event that never landed is imported");
		assertEquals(sourceEvents.get(2).reference().id(), resumed.getFirst().reference().id());
		assertEquals(idsOf(sourceEvents), idsOf(allEventsIn(target)));
	}

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
	void testSameIdempotencyKeyOnAnotherStreamDoesNotCollide ( ) {
		// keys are scoped per stream, so this must not stand in the way of the import
		appendTo(target, event(otherStream, "Other", "{\"z\":9}", "key-2"));
		List<StoredEvent> sourceEvents = seedSource();

		target.importEvents(toImport(sourceEvents), ImportMode.FAIL_ON_EXISTING_ID);

		assertEquals(4, allEventsIn(target).size());
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testDuplicateIdWithinOneBatchIsRejected ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		List<EventToImport> batch = new ArrayList<>(toImport(sourceEvents));
		batch.add(batch.getFirst());

		assertThrows(IllegalArgumentException.class, () -> target.importEvents(batch, ImportMode.FAIL_ON_EXISTING_ID));
		assertTrue(allEventsIn(target).isEmpty());
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testInvalidJsonPayloadIsRejected ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		List<EventToImport> batch = List.of(toImport(sourceEvents).getFirst().withImmutableData("not json at all"));

		assertThrows(EventStorageException.class, () -> target.importEvents(batch, ImportMode.FAIL_ON_EXISTING_ID));
		assertTrue(allEventsIn(target).isEmpty());
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testEmptyImportIsANoOp ( ) {
		assertTrue(target.importEvents(List.of(), ImportMode.FAIL_ON_EXISTING_ID).isEmpty());
		assertTrue(allEventsIn(target).isEmpty());
	}

	// --- importer level ---

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterIsBoundedAtTheSourceHead ( ) {
		seedSource();

		// nothing new since the head, so a run starting there does no work at all
		EventReference head = allEventsIn(source).getLast().reference();
		ImportReport report = EventStoreImporter.from(source).to(target).after(head).run();

		assertEquals(0, report.read());
		assertEquals(0, report.imported());
		assertTrue(allEventsIn(target).isEmpty());
	}

	// --- importer: selecting what to copy ---
	//
	// The proof that a selection is pushed into the storage query rather than applied afterwards is
	// report.read(): the transformation is the identity here, so every source event the importer read
	// was imported, and read() equal to the number of matching events means nothing else was read.

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterScopedToAStreamReadsOnlyThatStream ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		List<StoredEvent> onStream = sourceEvents.stream().filter(e -> stream.equals(e.stream())).toList();
		assertEquals(2, onStream.size());

		ImportReport report = EventStoreImporter.from(source).to(target)
				.stream(stream)
				.run();

		assertEquals(2, report.read(), "only the events of the selected stream are read");
		assertEquals(0, report.dropped());
		assertEquals(2, report.imported());
		assertEquals(sourceEvents.getLast().reference(), report.sourceTo(), "the boundary stays the source head, not the stream's");
		assertEquals(idsOf(onStream), idsOf(allEventsIn(target)));
		assertTrue(allEventsIn(target).stream().allMatch(e -> stream.equals(e.stream())), "imported events keep their stream");
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterScopedToAWildcardStreamReadsEveryPurposeOfTheContext ( ) {
		EventStreamId secondPurpose = EventStreamId.forContext("app").withPurpose("second");
		appendTo(source, event(stream, "First", "{\"a\":1}", null));
		appendTo(source, event(secondPurpose, "Second", "{\"b\":2}", null));
		appendTo(source, event(otherStream, "Third", "{\"c\":3}", null));

		ImportReport report = EventStoreImporter.from(source).to(target)
				.stream(EventStreamId.forContext("app").anyPurpose())
				.run();

		assertEquals(2, report.read());
		assertEquals(2, report.imported());
		assertTrue(allEventsIn(target).stream().allMatch(e -> "app".equals(e.stream().context())));
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterMatchingATagReadsOnlyTaggedEvents ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		// every seeded event is tagged kind:<type>, so a tag selects exactly one of them, across streams
		StoredEvent third = sourceEvents.stream().filter(e -> "Third".equals(e.type().name())).findFirst().orElseThrow();

		ImportReport report = EventStoreImporter.from(source).to(target)
				.matching(EventFilter.forEvents(EventTypesFilter.any(), Tags.of("kind", "Third")))
				.run();

		assertEquals(1, report.read(), "only the tagged event is read");
		assertEquals(0, report.dropped());
		assertEquals(1, report.imported());
		assertEquals(idsOf(List.of(third)), idsOf(allEventsIn(target)));
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterMatchingEventTypesReadsOnlyThoseTypes ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		List<StoredEvent> wanted = sourceEvents.stream().filter(e -> !"Second".equals(e.type().name())).toList();

		ImportReport report = EventStoreImporter.from(source).to(target)
				.matching(EventFilter.forEvents(EventTypesFilter.of(Set.of(EventType.ofType("First"), EventType.ofType("Third"))), Tags.none()))
				.run();

		assertEquals(2, report.read());
		assertEquals(2, report.imported());
		assertEquals(idsOf(wanted), idsOf(allEventsIn(target)));
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterStreamAndFilterCombine ( ) {
		seedSource();

		// kind:Third is on otherStream, so scoping to stream leaves nothing; kind:Second is on it
		ImportReport none = EventStoreImporter.from(source).to(target)
				.stream(stream)
				.matching(EventFilter.forEvents(EventTypesFilter.any(), Tags.of("kind", "Third")))
				.run();
		assertEquals(0, none.read());
		assertEquals(0, none.imported());

		ImportReport one = EventStoreImporter.from(source).to(target)
				.stream(stream)
				.matching(EventFilter.forEvents(EventTypesFilter.any(), Tags.of("kind", "Second")))
				.run();
		assertEquals(1, one.read());
		assertEquals(1, one.imported());
		assertEquals("Second", allEventsIn(target).getFirst().type().name());
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterSelectionSeesOnlySelectedEventsInTheTransformation ( ) {
		seedSource();
		List<String> offered = new ArrayList<>();

		EventStoreImporter.from(source).to(target)
				.stream(otherStream)
				.transform(src -> { offered.add(src.type().name()); return Optional.of(EventToImport.from(src)); })
				.run();

		assertEquals(List.of("Third"), offered, "the transformation is only handed what the selection read");
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterSelectionCatchesUpAfterAnEarlierRun ( ) {
		seedSource();
		EventFilter onStreamFirstOrFourth = EventFilter.forEvents(EventTypesFilter.of(Set.of(EventType.ofType("First"), EventType.ofType("Fourth"))), Tags.none());

		ImportReport first = EventStoreImporter.from(source).to(target).matching(onStreamFirstOrFourth).run();
		assertEquals(1, first.imported());

		// the source moves on: one matching event, one not
		appendTo(source, event(stream, "Fourth", "{\"d\":4}", null));
		appendTo(source, event(stream, "Fifth", "{\"e\":5}", null));

		ImportReport catchUp = EventStoreImporter.from(source).to(target)
				.matching(onStreamFirstOrFourth)
				.after(first.sourceTo())
				.run();

		assertEquals(1, catchUp.read(), "only the matching event the source gained since the boundary is read");
		assertEquals(1, catchUp.imported());
		assertEquals(first.sourceTo(), catchUp.sourceFrom());
		assertEquals(List.of("First", "Fourth"), allEventsIn(target).stream().map(e -> e.type().name()).toList());
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterHonoursAnEarlierUntilOnTheFilter ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		EventReference second = sourceEvents.get(1).reference();

		ImportReport report = EventStoreImporter.from(source).to(target)
				.matching(EventFilter.matchAll().until(second))
				.run();

		assertEquals(2, report.read(), "the filter's until is inclusive and bounds the run");
		assertEquals(2, report.imported());
		assertEquals(second, report.sourceTo(), "the report names the boundary the run was actually bounded by");

		// so a follow-up started after that report continues from the filter's boundary
		ImportReport rest = EventStoreImporter.from(source).to(target).after(report.sourceTo()).run();
		assertEquals(1, rest.imported());
		assertEquals(idsOf(sourceEvents), idsOf(allEventsIn(target)));
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterUntilPastTheHeadIsBoundedAtTheHead ( ) {
		List<StoredEvent> sourceEvents = seedSource();
		EventReference head = sourceEvents.getLast().reference();

		// an until later than anything in the source cannot widen the run past the head
		EventReference beyond = new EventReference(EventId.create(), head.position() + 1000, head.tx() + 1000, 0);
		ImportReport report = EventStoreImporter.from(source).to(target)
				.matching(EventFilter.matchAll().until(beyond))
				.run();

		assertEquals(3, report.imported());
		assertEquals(head, report.sourceTo());
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterMatchingNothingReadsNothing ( ) {
		seedSource();

		ImportReport report = EventStoreImporter.from(source).to(target)
				.matching(EventFilter.matchNone())
				.run();

		assertEquals(0, report.read());
		assertEquals(0, report.imported());
		assertTrue(allEventsIn(target).isEmpty());
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterSelectionOnAnEmptySourceDoesNothing ( ) {
		ImportReport report = EventStoreImporter.from(source).to(target)
				.stream(stream)
				.matching(EventFilter.matchAll().until(new EventReference(EventId.create(), 1, 1, 0)))
				.run();

		assertEquals(0, report.read());
		assertNull(report.sourceTo(), "an empty source has no boundary, whatever the filter carries");
	}

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterOnAnEmptySourceDoesNothing ( ) {
		ImportReport report = EventStoreImporter.from(source).to(target).run();

		assertEquals(0, report.read());
		assertNull(report.sourceTo());
		assertTrue(allEventsIn(target).isEmpty());
	}

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
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

	@ForEachBackend(requires = Capability.IMPORT)
	void testImporterRequiresATarget ( ) {
		assertThrows(IllegalStateException.class, () -> EventStoreImporter.from(source).run());
	}

}
