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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventSerializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcaster;
import org.sliceworkz.eventstore.projection.Projection;
import org.sliceworkz.eventstore.projection.Projector;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * Pins down how the store reports a payload it cannot convert, in either direction.
 *
 * <h2>The three kinds, and why they are typed differently</h2>
 * <ul>
 *   <li><b>Misconfiguration</b> — a {@code @LegacyEvent} on a class registered as current, a current
 *       class registered as legacy, an upcaster that cannot be instantiated. All are properties of the
 *       {@code Class} handed to {@code getEventStream}, all fail at stream creation before anything is
 *       read or written, and all are {@link IllegalArgumentException} — the same type the two
 *       registration checks that were already typed (duplicate event name, non-sealed interface) use.</li>
 *   <li><b>Write side</b> — {@link EventSerializationException} from {@code append}, for a payload that
 *       cannot be written. The event is not stored.</li>
 *   <li><b>Read side</b> — {@link EventDeserializationException} while a query result is consumed, for a
 *       stored event this stream's mappings cannot read. The storage read succeeded; the event is a
 *       poison event, and {@link EventDeserializationException#getReference()} names it.</li>
 * </ul>
 * None of the three is worth retrying, which is what separates them from
 * {@link org.sliceworkz.eventstore.spi.EventStorageException}. Before these types existed all of it
 * arrived as a bare {@code RuntimeException} and could only be told apart by matching on message text.
 */
public class SerdeFailureTest extends AbstractEventStoreTest {

	private final EventStreamId streamId = EventStreamId.forContext("serde-failure").withPurpose("p");

	// --- event definitions -------------------------------------------------------------------------

	sealed interface OrderEvent {
		record OrderPlaced ( String orderId ) implements OrderEvent { }
	}

	/** A second hierarchy, written to the same stream, that {@code OrderEvent} has no mapping for. */
	sealed interface ShippingEvent {
		record ParcelShipped ( String parcelId ) implements ShippingEvent { }
	}

	/** Serializes fine, cannot be read back: the derived accessor emits a property no component matches. */
	sealed interface UnreadableEvent {
		default String getDerived ( ) { return "x"; }
		record Unreadable ( String value ) implements UnreadableEvent { }
	}

	/** A payload Jackson cannot write at all: the accessor throws. */
	sealed interface UnwritableEvent {
		record Unwritable ( String value ) implements UnwritableEvent {
			@Override
			public String value ( ) { throw new IllegalStateException("this value cannot be read"); }
		}
	}

	// --- upcasters ---------------------------------------------------------------------------------

	sealed interface CurrentEvent {
		record Renamed ( String orderId ) implements CurrentEvent { }
	}

	/**
	 * The legacy hierarchy, as the reading side declares it. Its {@code LegacyPlaced} shares its
	 * simple name — which is the stored name — with {@link Written#LegacyPlaced}, which is how a legacy
	 * event gets written here in the first place: a class annotated {@code @LegacyEvent} cannot be
	 * registered as a current type, so it cannot append.
	 */
	interface Legacy {
		@LegacyEvent(upcaster = ThrowingUpcaster.class)
		record LegacyPlaced ( String orderId ) { }
	}

	/** The same stored event type, unannotated, so it can be appended. */
	interface Written {
		record LegacyPlaced ( String orderId ) { }
	}

	public static class ThrowingUpcaster implements Upcaster<Legacy.LegacyPlaced, CurrentEvent> {
		@Override
		public List<CurrentEvent> upcast ( Legacy.LegacyPlaced legacyEvent ) {
			throw new IllegalArgumentException("legacy id %s does not satisfy the current rule".formatted(legacyEvent.orderId()));
		}
		@Override
		public Set<Class<? extends CurrentEvent>> targetTypes ( ) {
			return Set.of(CurrentEvent.Renamed.class);
		}
	}

	@LegacyEvent(upcaster = NoNoArgConstructorUpcaster.class)
	record LegacyUninstantiable ( String orderId ) { }

	public static class NoNoArgConstructorUpcaster implements Upcaster<LegacyUninstantiable, CurrentEvent> {
		public NoNoArgConstructorUpcaster ( String required ) { /* deliberately not a no-arg constructor */ }
		@Override
		public List<CurrentEvent> upcast ( LegacyUninstantiable legacyEvent ) { return List.of(); }
		@Override
		public Set<Class<? extends CurrentEvent>> targetTypes ( ) { return Set.of(CurrentEvent.Renamed.class); }
	}

	// --- misconfiguration: fails at getEventStream, before anything is read or written --------------

	@ForEachBackend
	void anUninstantiableUpcasterIsRejectedAtStreamCreation ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(streamId, CurrentEvent.class, LegacyUninstantiable.class));

		// the whole point: the old bare RuntimeException(NoSuchMethodException) named neither class
		assertTrue(e.getMessage().contains(NoNoArgConstructorUpcaster.class.getName()),
				"the upcaster that could not be instantiated should be named: " + e.getMessage());
		assertTrue(e.getMessage().contains(LegacyUninstantiable.class.getName()),
				"the legacy event declaring it should be named: " + e.getMessage());
		assertInstanceOf(NoSuchMethodException.class, e.getCause(),
				"the reflective failure should be preserved as the cause");
	}

	// --- write side ---------------------------------------------------------------------------------

	@ForEachBackend
	void anUnwritablePayloadFailsTheAppendWithASerializationException ( ) {
		EventStream<UnwritableEvent> stream = eventStore().getEventStream(streamId, UnwritableEvent.class);

		EventSerializationException e = assertThrows(EventSerializationException.class,
				() -> stream.append(AppendCriteria.none(), Event.of(new UnwritableEvent.Unwritable("v"), Tags.none())));

		assertEquals(EventType.named("Unwritable"), e.getEventType());
		assertTrue(e.getMessage().contains("Unwritable"), e.getMessage());
		assertTrue(e.getCause() != null, "Jackson's own failure should be preserved as the cause");

		// nothing was stored
		assertEquals(0, eventStore().getEventStream(streamId, UnwritableEvent.class).query(EventQuery.matchAll()).size());
	}

	// --- read side ----------------------------------------------------------------------------------

	@ForEachBackend
	void aStoredTypeThisStreamCannotMapFailsOnRead ( ) {
		eventStore().getEventStream(streamId, ShippingEvent.class)
				.append(AppendCriteria.none(), Event.of(new ShippingEvent.ParcelShipped("parcel-1"), Tags.none()));

		EventStream<OrderEvent> orderStream = eventStore().getEventStream(streamId, OrderEvent.class);

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> orderStream.query(EventQuery.matchAll()));

		assertEquals(EventType.named("ParcelShipped"), e.getEventType());
		assertTrue(e.getMessage().contains("No mapping found for event type 'ParcelShipped'"), e.getMessage());
		// the known mappings are listed, so the reader can see what this stream *can* read
		assertTrue(e.getMessage().contains("OrderPlaced"), e.getMessage());
	}

	@ForEachBackend
	void aStreamWithNoMappingsAtAllSaysSo ( ) {
		eventStore().getEventStream(streamId, OrderEvent.class)
				.append(AppendCriteria.none(), Event.of(new OrderEvent.OrderPlaced("order-1"), Tags.none()));

		// A typed stream registered with nothing is a different mistake from one registered with the
		// wrong thing, and the message says which. Object.class is the way to reach it: passing at least
		// one root class selects the typed serde, and Object.class contributes no mappings to it.
		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> eventStore().getEventStream(streamId, Object.class).query(EventQuery.matchAll()));

		assertEquals(EventType.named("OrderPlaced"), e.getEventType());
		assertTrue(e.getMessage().contains("Pass the Event root Class when creating the EventStream"), e.getMessage());
	}

	@ForEachBackend
	void aDeserializationFailureNamesTheStoredEventThatFailed ( ) {
		EventStream<UnreadableEvent> stream = eventStore().getEventStream(streamId, UnreadableEvent.class);

		// append() reads its own events back, so the failure surfaces there
		EventDeserializationException onAppend = assertThrows(EventDeserializationException.class,
				() -> stream.append(AppendCriteria.none(), Event.of(new UnreadableEvent.Unreadable("v"), Tags.none())));

		EventReference reference = onAppend.getReference()
				.orElseThrow(() -> new AssertionError("the stream layer should attach the reference of the failing stored event"));

		// and it is genuinely the offending event: raw mode has no mapping to fail on, so it reads back
		List<Event<String>> raw = eventStore().getRawEventStream(EventStreamId.anyContext()).getEventById(reference.id())
				.orElseThrow(() -> new AssertionError("the reference should identify a real stored event"));
		assertEquals(1, raw.size());
		assertEquals(EventType.named("Unreadable"), raw.getFirst().type());

		// the same failure on the read path, carrying the same reference
		EventDeserializationException onRead = assertThrows(EventDeserializationException.class,
				() -> eventStore().getEventStream(streamId, UnreadableEvent.class).query(EventQuery.matchAll()));
		assertEquals(reference.id(), onRead.getReference().orElseThrow().id());
	}

	@ForEachBackend
	void anUpcasterThrowingIsReportedAsSuchRatherThanAsAParseFailure ( ) {
		// write the legacy event under its stored name, then read it back through a stream that upcasts it
		eventStore().getEventStream(streamId, Written.LegacyPlaced.class)
				.append(AppendCriteria.none(), Event.of(new Written.LegacyPlaced("order-1"), Tags.none()));

		EventStream<CurrentEvent> current = eventStore().getEventStream(streamId, CurrentEvent.class, Legacy.LegacyPlaced.class);

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> current.query(EventQuery.matchAll()));

		assertTrue(e.getMessage().contains(ThrowingUpcaster.class.getName()),
				"the upcaster that threw should be named, not just the event: " + e.getMessage());
		assertInstanceOf(IllegalArgumentException.class, e.getCause(),
				"what the upcaster threw should be the cause");
		assertTrue(e.getReference().isPresent());
	}

	// --- one wrapping layer, not two ----------------------------------------------------------------

	@ForEachBackend
	void theCauseIsTheUnderlyingFailureAndNotASecondWrapper ( ) {
		eventStore().getEventStream(streamId, UnreadableEvent.class);   // registers the mapping

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> eventStore().getEventStream(streamId, UnreadableEvent.class)
						.append(AppendCriteria.none(), Event.of(new UnreadableEvent.Unreadable("v"), Tags.none())));

		assertFalse(e.getCause() instanceof EventDeserializationException,
				"the serde used to wrap its own exception a second time, burying the useful message");
		assertTrue(e.getMessage().contains(UnreadableEvent.Unreadable.class.getName()),
				"the target record should be named in the message itself: " + e.getMessage());
	}

	@ForEachBackend
	void withReferenceKeepsMessageCauseAndStackTrace ( ) {
		Throwable cause = new IllegalStateException("boom");
		EventDeserializationException original =
				new EventDeserializationException(EventType.named("X"), "some message", cause);

		EventDeserializationException withRef = original.withReference(EventReference.create(1, 1));

		assertEquals("some message", withRef.getMessage());
		assertSame(cause, withRef.getCause());
		assertTrue(withRef.getReference().isPresent());
		assertEquals(List.of(original.getStackTrace()), List.of(withRef.getStackTrace()));
		// a reference already attached is not replaced by an outer layer
		assertSame(withRef, withRef.withReference(EventReference.create(2, 2)));
	}

	// --- through a Projector -------------------------------------------------------------------------

	@ForEachBackend
	void aProjectorReportsThePoisonEventThroughItsCause ( ) {
		EventStream<OrderEvent> writable = eventStore().getEventStream(streamId, OrderEvent.class);
		writable.append(AppendCriteria.none(), Event.of(new OrderEvent.OrderPlaced("order-1"), Tags.none()));
		eventStore().getEventStream(streamId, ShippingEvent.class)
				.append(AppendCriteria.none(), Event.of(new ShippingEvent.ParcelShipped("parcel-2"), Tags.none()));

		CountingProjection projection = new CountingProjection();
		Projector<OrderEvent> projector = Projector.<OrderEvent>from(eventStore().getEventStream(streamId, OrderEvent.class))
				.into(projection).build();

		ProjectorException e = assertThrows(ProjectorException.class, projector::run);

		// A Projector wraps everything it catches, so the type of the cause is the only thing that
		// separates "this event will never be readable" from "the database was briefly unavailable".
		EventDeserializationException poison = assertInstanceOf(EventDeserializationException.class, e.getCause());
		assertEquals(EventType.named("ParcelShipped"), poison.getEventType());

		// ProjectorException's own reference is the last event *handled* -- never the offending one,
		// which never reached the projection. getReference() is what names the poison event.
		EventReference offending = poison.getReference().orElseThrow();
		assertFalse(offending.equals(e.getEventReference()),
				"the two references answer different questions and should not be confused");

		// A page is read whole, so the poison event fails its batch before any event of that batch is
		// handed out: the readable event before it was not handled either, and is not re-handled when
		// the batch comes round again. The alternative -- handing out the events before the poison one
		// -- loses because the batch is rolled back to where it started anyway, so those events would
		// be applied once more on every retry.
		assertEquals(0, projection.handled, "nothing of the batch holding the poison event reached the projection");
		assertNull(e.getEventReference(), "no event was handled, so there is no last handled event");
	}

	static class CountingProjection implements Projection<OrderEvent> {
		int handled = 0;
		@Override
		public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override
		public void when ( Event<OrderEvent> event ) { handled++; }
	}

}
