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
package org.sliceworkz.eventstore.testing.tck.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventName;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Event names are global to a storage, not scoped to a stream.
 * <p>
 * A stream scopes <em>reads</em>; it is not part of the identity of a type. Two classes that resolve to the
 * same {@link EventType} therefore write indistinguishable {@code event_type} values into one table even when
 * they live in different contexts. Registering both on one stream is caught loudly ("duplicate event name"),
 * but nothing catches the cross-stream case, and a read that spans both — a wildcard stream, the raw/import
 * path, a store-wide projection — resolves the payload by name alone.
 * <p>
 * The consequence is not a clean failure. {@code FAIL_ON_UNKNOWN_PROPERTIES} rejects a reader that is a strict
 * <em>subset</em> of what was written, but a reader that is a <em>superset</em> deserializes happily and
 * defaults the components it did not find. That is the case pinned down here: the wrong class, silently
 * populated with another context's data. Under the default naming scheme the only thing standing between an
 * application and that outcome is that no two of its event classes happen to share a simple name.
 * <p>
 * {@link EventName} is the fix, and the second half of this scenario is the contract that matters: distinct
 * names, no confusion.
 */
public class EventTypeNameCollisionTest extends AbstractEventStoreTest {

	private final EventStreamId sales = EventStreamId.forContext("sales");
	private final EventStreamId hr = EventStreamId.forContext("hr");
	private final EventStreamId everything = EventStreamId.anyContext().anyPurpose();

	/**
	 * Two contexts, two unrelated {@code Created} classes, one stored name. Reading across both with the
	 * <em>wrong</em> class succeeds and fabricates data.
	 */
	@ForEachBackend
	void testCollidingSimpleNamesDeserializeAsTheWrongClass ( ) {
		eventStore().getEventStream(sales, Sales.class)
				.append(AppendCriteria.none(), Event.of(new Sales.Created("O-1", 4200), Tags.none()));
		eventStore().getEventStream(hr, Hr.class)
				.append(AppendCriteria.none(), Event.of(new Hr.Created("O-9", 1, "legal"), Tags.none()));

		// both contexts wrote the very same event_type
		assertEquals(List.of(EventType.ofType("Created"), EventType.ofType("Created")),
				eventStore().getEventStream(everything).query(EventQuery.matchAll()).map(Event::storedType).toList());

		// a stream registered for Hr only, reading across contexts, silently reconstructs the sales event as an
		// Hr.Created: every component it recognises is filled from the other context's payload and the one it
		// does not find is defaulted away. No exception, no warning on the read, wrong data.
		List<Event<Hr>> read = eventStore().<Hr>getEventStream(everything, Hr.class)
				.query(EventQuery.matchAll()).toList();

		assertEquals(2, read.size());
		assertEquals(new Hr.Created("O-1", 4200, null), read.get(0).data());
		assertEquals(new Hr.Created("O-9", 1, "legal"), read.get(1).data());

		// and a type filter naming the Hr class selects the sales event too -- one name, one filter value
		assertEquals(2, eventStore().getEventStream(everything, Hr.class)
				.query(EventQuery.forEvents(EventTypesFilter.of(Hr.Created.class), Tags.none())).count());
	}

	/**
	 * The narrow case that does fail: a reader whose record has fewer components than the payload carries.
	 * Worth pinning down precisely because it is the exception — it is what makes the collision look like it
	 * would be caught, when the scenario above shows it usually is not.
	 */
	@ForEachBackend
	void testCollidingNamesFailOnlyWhenTheReaderIsNarrower ( ) {
		eventStore().getEventStream(sales, Sales.class)
				.append(AppendCriteria.none(), Event.of(new Sales.Created("O-1", 4200), Tags.none()));

		RuntimeException e = assertThrows(RuntimeException.class,
				() -> eventStore().getEventStream(everything, Support.class).query(EventQuery.matchAll()).toList());

		assertTrue(rootCauseMessage(e).contains("amountCents"), rootCauseMessage(e));
	}

	/**
	 * The same two contexts with distinct {@link EventName}s. Each stored name resolves to exactly one class,
	 * so a cross-context read can no longer confuse them: the filter selects only its own events, and an
	 * unfiltered read of a name this stream has no mapping for fails loudly instead of inventing a value.
	 */
	@ForEachBackend
	void testDistinctEventNamesRemoveTheCollision ( ) {
		eventStore().getEventStream(sales, NamedSales.class)
				.append(AppendCriteria.none(), Event.of(new NamedSales.Created("O-1", 4200), Tags.none()));
		eventStore().getEventStream(hr, NamedHr.class)
				.append(AppendCriteria.none(), Event.of(new NamedHr.Created("O-9", 1, "legal"), Tags.none()));

		assertEquals(List.of(EventType.ofType("sales.Created"), EventType.ofType("hr.Created")),
				eventStore().getEventStream(everything).query(EventQuery.matchAll()).map(Event::storedType).toList());

		// filtering on the Hr class across all contexts now yields the Hr event and nothing else
		List<Event<NamedHr>> read = eventStore().<NamedHr>getEventStream(everything, NamedHr.class)
				.query(EventQuery.forEvents(EventTypesFilter.of(NamedHr.Created.class), Tags.none())).toList();
		assertEquals(List.of(new NamedHr.Created("O-9", 1, "legal")), read.stream().map(Event::data).toList());

		// and reading everything through that stream reports the unmapped name rather than misreading it
		RuntimeException e = assertThrows(RuntimeException.class,
				() -> eventStore().getEventStream(everything, NamedHr.class).query(EventQuery.matchAll()).toList());
		assertTrue(rootCauseMessage(e).contains("No mapping found for event type 'sales.Created'"), rootCauseMessage(e));
	}

	private static String rootCauseMessage ( Throwable t ) {
		Throwable cause = t;
		while ( cause.getCause() != null ) {
			cause = cause.getCause();
		}
		return String.valueOf(cause.getMessage());
	}

	// ------------------------------------------------------------------------------------------------

	public sealed interface Sales {
		record Created ( String orderId, int amountCents ) implements Sales { }
	}

	/** Same simple name, one component more -- the shape that silently absorbs a Sales.Created. */
	public sealed interface Hr {
		record Created ( String orderId, int amountCents, String department ) implements Hr { }
	}

	/** Same simple name, one component fewer -- the only shape that refuses. */
	public sealed interface Support {
		record Created ( String orderId ) implements Support { }
	}

	public sealed interface NamedSales {
		@EventName("sales.Created")
		record Created ( String orderId, int amountCents ) implements NamedSales { }
	}

	public sealed interface NamedHr {
		@EventName("hr.Created")
		record Created ( String orderId, int amountCents, String department ) implements NamedHr { }
	}

}
