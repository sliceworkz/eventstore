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
import org.sliceworkz.eventstore.events.Upcast;
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
	 * The historical hierarchy, as the reading side declares it. Its {@code LegacyPlaced} shares its
	 * simple name — which is the stored name — with {@link Written#LegacyPlaced}, which is how a legacy
	 * event gets written here in the first place: a class annotated {@code @LegacyEvent} cannot be
	 * registered as a current type, so it cannot append.
	 */
	interface Historical {
		@LegacyEvent(upcast = ThrowingUpcast.class)
		record LegacyPlaced ( String orderId ) { }
	}

	/** The same stored event type, unannotated, so it can be appended. */
	interface Written {
		record LegacyPlaced ( String orderId ) { }
	}

	public static class ThrowingUpcast implements Upcast<Historical.LegacyPlaced, CurrentEvent> {
		@Override
		public List<CurrentEvent> upcast ( Historical.LegacyPlaced historicalEvent ) {
			throw new IllegalArgumentException("legacy id %s does not satisfy the current rule".formatted(historicalEvent.orderId()));
		}
		@Override
		public Set<Class<? extends CurrentEvent>> targetTypes ( ) {
			return Set.of(CurrentEvent.Renamed.class);
		}
	}

	@LegacyEvent(upcast = NoNoArgConstructorUpcast.class)
	record LegacyUninstantiable ( String orderId ) { }

	public static class NoNoArgConstructorUpcast implements Upcast<LegacyUninstantiable, CurrentEvent> {
		public NoNoArgConstructorUpcast ( String required ) { /* deliberately not a no-arg constructor */ }
		@Override
		public List<CurrentEvent> upcast ( LegacyUninstantiable historicalEvent ) { return List.of(); }
		@Override
		public Set<Class<? extends CurrentEvent>> targetTypes ( ) { return Set.of(CurrentEvent.Renamed.class); }
	}

	// --- misconfiguration: fails at getEventStream, before anything is read or written --------------

	@ForEachBackend
	void anUninstantiableUpcasterIsRejectedAtStreamCreation ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(streamId, CurrentEvent.class, LegacyUninstantiable.class));

		// the whole point: the old bare RuntimeException(NoSuchMethodException) named neither class
		assertTrue(e.getMessage().contains(NoNoArgConstructorUpcast.class.getName()),
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

		assertEquals(EventType.ofType("Unwritable"), e.getEventType());
		assertTrue(e.getMessage().contains("Unwritable"), e.getMessage());
		assertTrue(e.getCause() != null, "Jackson's own failure should be preserved as the cause");

		// nothing was stored
		assertEquals(0, eventStore().getEventStream(streamId, UnwritableEvent.class).query(EventQuery.matchAll()).count());
	}

	// --- read side ----------------------------------------------------------------------------------

	@ForEachBackend
	void aStoredTypeThisStreamCannotMapFailsOnRead ( ) {
		eventStore().getEventStream(streamId, ShippingEvent.class)
				.append(AppendCriteria.none(), Event.of(new ShippingEvent.ParcelShipped("parcel-1"), Tags.none()));

		EventStream<OrderEvent> orderStream = eventStore().getEventStream(streamId, OrderEvent.class);

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> orderStream.query(EventQuery.matchAll()).toList());

		assertEquals(EventType.ofType("ParcelShipped"), e.getEventType());
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
				() -> eventStore().getEventStream(streamId, Object.class).query(EventQuery.matchAll()).toList());

		assertEquals(EventType.ofType("OrderPlaced"), e.getEventType());
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
		List<Event<Object>> raw = eventStore().getEventStream(EventStreamId.anyContext()).getEventById(reference.id());
		assertEquals(1, raw.size(), "the reference should identify a real stored event");
		assertEquals(EventType.ofType("Unreadable"), raw.getFirst().type());

		// the same failure on the read path, carrying the same reference
		EventDeserializationException onRead = assertThrows(EventDeserializationException.class,
				() -> eventStore().getEventStream(streamId, UnreadableEvent.class).query(EventQuery.matchAll()).toList());
		assertEquals(reference.id(), onRead.getReference().orElseThrow().id());
	}

	@ForEachBackend
	void anUpcasterThrowingIsReportedAsSuchRatherThanAsAParseFailure ( ) {
		// write the legacy event under its stored name, then read it back through a stream that upcasts it
		eventStore().getEventStream(streamId, Written.LegacyPlaced.class)
				.append(AppendCriteria.none(), Event.of(new Written.LegacyPlaced("order-1"), Tags.none()));

		EventStream<CurrentEvent> current = eventStore().getEventStream(streamId, CurrentEvent.class, Historical.LegacyPlaced.class);

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> current.query(EventQuery.matchAll()).toList());

		assertTrue(e.getMessage().contains(ThrowingUpcast.class.getName()),
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
				new EventDeserializationException(EventType.ofType("X"), "some message", cause);

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
				.towards(projection).build();

		ProjectorException e = assertThrows(ProjectorException.class, projector::run);

		// A Projector wraps everything it catches, so the type of the cause is the only thing that
		// separates "this event will never be readable" from "the database was briefly unavailable".
		EventDeserializationException poison = assertInstanceOf(EventDeserializationException.class, e.getCause());
		assertEquals(EventType.ofType("ParcelShipped"), poison.getEventType());

		// ProjectorException's own reference is the last event *handled* -- never the offending one,
		// which never reached the projection. getReference() is what names the poison event.
		EventReference offending = poison.getReference().orElseThrow();
		assertFalse(offending.equals(e.getEventReference()),
				"the two references answer different questions and should not be confused");

		// resuming past it makes progress possible again
		assertEquals(1, projection.handled, "the readable event before the poison one was handled");
	}

	static class CountingProjection implements Projection<OrderEvent> {
		int handled = 0;
		@Override
		public EventQuery eventQuery ( ) { return EventQuery.matchAll(); }
		@Override
		public void when ( Event<OrderEvent> event ) { handled++; }
	}

}
