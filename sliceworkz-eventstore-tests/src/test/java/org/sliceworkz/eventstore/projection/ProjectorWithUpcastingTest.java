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
package org.sliceworkz.eventstore.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.AbstractEventStoreTest;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.projection.Projector.ProjectorMetrics;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

/**
 * Tests that the Projector correctly advances past events that upcast to zero enriched events,
 * avoiding infinite re-querying and ensuring bookmarks advance past vanished events.
 */
public class ProjectorWithUpcastingTest extends AbstractEventStoreTest {

	// =========================================================================
	// Domain model: original events as stored
	// =========================================================================

	sealed interface OriginalEvent {
		record ImportantAction ( String value ) implements OriginalEvent { }
		record ObsoleteAuditLog ( String message ) implements OriginalEvent { }
	}

	// =========================================================================
	// Domain model: current events (after upcasting)
	// =========================================================================

	sealed interface CurrentEvent {
		record ImportantAction ( String value ) implements CurrentEvent { }
	}

	// =========================================================================
	// Legacy events for upcasting
	// =========================================================================

	sealed interface LegacyEvents {
		@LegacyEvent(upcast = FilterAuditLogUpcaster.class)
		record ObsoleteAuditLog ( String message ) implements LegacyEvents { }
	}

	// =========================================================================
	// Upcaster: filters out obsolete audit logs (produces zero events)
	// =========================================================================

	public static class FilterAuditLogUpcaster implements Upcast<LegacyEvents.ObsoleteAuditLog, CurrentEvent> {
		@Override
		public List<CurrentEvent> upcast ( LegacyEvents.ObsoleteAuditLog historicalEvent ) {
			return List.of();
		}
		@Override
		public Set<Class<? extends CurrentEvent>> targetTypes ( ) {
			return Set.of();
		}
	}

	// =========================================================================
	// Projection: tracks important actions
	// =========================================================================

	static class TrackingProjection implements ProjectionWithoutMetaData<CurrentEvent> {
		List<String> values = new ArrayList<>();
		@Override
		public EventQuery eventQuery ( ) {
			return EventQuery.matchAll();
		}
		@Override
		public void when ( CurrentEvent event ) {
			if ( event instanceof CurrentEvent.ImportantAction a ) {
				values.add(a.value());
			}
		}
	}

	// =========================================================================
	// Test infrastructure
	// =========================================================================

	EventStreamId streamId = EventStreamId.forContext("projector-upcast-test");

	@Override
	public EventStorage createEventStorage ( ) {
		return InMemoryEventStorage.newBuilder().build();
	}

	private void appendOriginal ( EventStream<OriginalEvent> stream, OriginalEvent event ) {
		stream.append(AppendCriteria.none(), Event.of(event, Tags.none()));
	}

	// =========================================================================
	// Tests
	// =========================================================================

	@Test
	void testProjectorAdvancesPastVanishedEventsToFindSubsequentEvents ( ) {
		EventStream<OriginalEvent> originalStream = eventStore().getEventStream(streamId, OriginalEvent.class);
		appendOriginal(originalStream, new OriginalEvent.ImportantAction("first"));
		appendOriginal(originalStream, new OriginalEvent.ObsoleteAuditLog("audit1"));
		appendOriginal(originalStream, new OriginalEvent.ObsoleteAuditLog("audit2"));
		appendOriginal(originalStream, new OriginalEvent.ImportantAction("last"));

		EventStream<CurrentEvent> stream = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

		TrackingProjection projection = new TrackingProjection();
		Projector<CurrentEvent> projector = Projector.from(stream).towards(projection).inBatchesOf(1).build();
		ProjectorMetrics metrics = projector.run();

		assertEquals(List.of("first", "last"), projection.values);
		assertEquals(2, metrics.eventsHandled());
		assertNotNull(metrics.lastEventReference());
	}

	@Test
	void testProjectorTerminatesWhenAllEventsVanish ( ) {
		EventStream<OriginalEvent> originalStream = eventStore().getEventStream(streamId, OriginalEvent.class);
		appendOriginal(originalStream, new OriginalEvent.ObsoleteAuditLog("audit1"));
		appendOriginal(originalStream, new OriginalEvent.ObsoleteAuditLog("audit2"));

		EventStream<CurrentEvent> stream = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

		TrackingProjection projection = new TrackingProjection();
		Projector<CurrentEvent> projector = Projector.from(stream).towards(projection).build();
		ProjectorMetrics metrics = projector.run();

		assertEquals(0, projection.values.size());
		assertEquals(0, metrics.eventsHandled());
		assertNotNull(metrics.lastEventReference());
	}

	@Test
	void testBookmarkAdvancesPastVanishedEvents ( ) {
		EventStream<OriginalEvent> originalStream = eventStore().getEventStream(streamId, OriginalEvent.class);
		appendOriginal(originalStream, new OriginalEvent.ImportantAction("first"));
		appendOriginal(originalStream, new OriginalEvent.ObsoleteAuditLog("audit1"));
		appendOriginal(originalStream, new OriginalEvent.ObsoleteAuditLog("audit2"));

		EventStream<CurrentEvent> stream = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

		TrackingProjection projection = new TrackingProjection();
		Projector<CurrentEvent> projector = Projector.from(stream)
			.towards(projection)
			.bookmarkProgress().withReader("test-reader").readBeforeEachExecution().done()
			.inBatchesOf(1)
			.build();

		projector.run();
		assertEquals(List.of("first"), projection.values);

		// Bookmark should have advanced past the vanished events
		var bookmark = stream.getBookmark("test-reader");
		assertTrue(bookmark.isPresent());

		// Append a new important event after the vanished ones
		originalStream.append(AppendCriteria.none(), Event.of(new OriginalEvent.ImportantAction("second"), Tags.none()));

		// Second run should pick up only the new event
		projector.run();
		assertEquals(List.of("first", "second"), projection.values);
	}

	@Test
	void testVanishedEventsAtStartWithBatchSize1 ( ) {
		EventStream<OriginalEvent> originalStream = eventStore().getEventStream(streamId, OriginalEvent.class);
		appendOriginal(originalStream, new OriginalEvent.ObsoleteAuditLog("audit1"));
		appendOriginal(originalStream, new OriginalEvent.ObsoleteAuditLog("audit2"));
		appendOriginal(originalStream, new OriginalEvent.ImportantAction("important"));

		EventStream<CurrentEvent> stream = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

		TrackingProjection projection = new TrackingProjection();
		Projector<CurrentEvent> projector = Projector.from(stream).towards(projection).inBatchesOf(1).build();
		ProjectorMetrics metrics = projector.run();

		assertEquals(List.of("important"), projection.values);
		assertEquals(1, metrics.eventsHandled());
	}

	@Test
	void testRunSingleBatchAdvancesPastVanishedEvents ( ) {
		EventStream<OriginalEvent> originalStream = eventStore().getEventStream(streamId, OriginalEvent.class);
		appendOriginal(originalStream, new OriginalEvent.ObsoleteAuditLog("audit1"));
		appendOriginal(originalStream, new OriginalEvent.ImportantAction("important"));

		EventStream<CurrentEvent> stream = eventStore().getEventStream(streamId, CurrentEvent.class, LegacyEvents.class);

		TrackingProjection projection = new TrackingProjection();
		Projector<CurrentEvent> projector = Projector.from(stream).towards(projection).inBatchesOf(1).build();

		// First single batch: the vanished event — cursor should advance
		ProjectorMetrics metrics1 = projector.runSingleBatch();
		assertEquals(0, projection.values.size());
		assertNotNull(metrics1.lastEventReference());

		// Second single batch: the important event
		ProjectorMetrics metrics2 = projector.runSingleBatch();
		assertEquals(List.of("important"), projection.values);
	}

}
