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
import java.util.Set;

import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventName;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.testing.AbstractEventStoreTest;
import org.sliceworkz.eventstore.testing.ForEachBackend;

/**
 * The stored event name, and what a class is allowed to do to it.
 * <p>
 * The name in the {@code event_type} column is what a query filters on and what the deserializer keys its
 * mappings by, so it is wire format: once an event is written, that string is history and cannot be rewritten.
 * By default it is {@link Class#getSimpleName()}, which makes the Java identifier a wire commitment — renaming
 * or moving an event class silently orphans every event already stored under the old name, and two bounded
 * contexts cannot both own a {@code Created}. {@link EventName} decouples the two, and its
 * {@link EventName#aliases()} let a rename be absorbed on the read side.
 * <p>
 * Aliases and upcasts solve neighbouring problems and this scenario keeps the boundary visible: an alias is a
 * pure rename — same payload shape, one annotation edit — while {@link org.sliceworkz.eventstore.events.LegacyEvent}
 * plus {@link org.sliceworkz.eventstore.events.Upcast} exist for a payload whose shape changed, which needs a
 * class describing the old shape to survive. {@link UpcastTest} covers the latter.
 */
public class EventTypeNamingTest extends AbstractEventStoreTest {

	private final EventStreamId streamId = EventStreamId.forContext("naming");

	// ------------------------------------------------------------------------------------------------
	// the default: the simple name, exactly as before @EventName existed
	// ------------------------------------------------------------------------------------------------

	@ForEachBackend
	void testUnannotatedEventStoresItsSimpleName ( ) {
		EventStream<PlainEvent> stream = eventStore().getEventStream(streamId, PlainEvent.class);
		stream.append(AppendCriteria.none(), Event.of(new PlainEvent.SomethingHappened("x"), Tags.none()));

		assertEquals(EventType.ofType("SomethingHappened"), rawTypes().get(0));
	}

	// ------------------------------------------------------------------------------------------------
	// @EventName decouples the stored name from the class identifier
	// ------------------------------------------------------------------------------------------------

	@ForEachBackend
	void testAnnotatedEventStoresTheDeclaredName ( ) {
		EventStream<NamedEvent> stream = eventStore().getEventStream(streamId, NamedEvent.class);
		stream.append(AppendCriteria.none(), Event.of(new NamedEvent.SomethingHappened("x"), Tags.none()));

		assertEquals(EventType.ofType("naming.SomethingHappened"), rawTypes().get(0));

		// and the declared name is what the type filter and the reported type use, not the class identifier
		List<Event<NamedEvent>> read = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(NamedEvent.SomethingHappened.class), Tags.none())).toList();
		assertEquals(1, read.size());
		assertEquals(EventType.ofType("naming.SomethingHappened"), read.get(0).type());
		assertEquals(EventType.ofType("naming.SomethingHappened"), read.get(0).storedType());
	}

	/**
	 * Two event classes with the same simple name are a startup failure on the default scheme. Naming them
	 * apart is what lets two bounded contexts each own a {@code Created} in one store.
	 */
	@ForEachBackend
	void testSameSimpleNameCoexistsOnceDisambiguated ( ) {
		EventStream<Object> stream = eventStore().getEventStream(streamId, Set.of(Sales.class, Hr.class));

		stream.append(AppendCriteria.none(), Event.of(new Sales.Created("O-1"), Tags.none()));
		stream.append(AppendCriteria.none(), Event.of(new Hr.Created("V-1"), Tags.none()));

		assertEquals(List.of(EventType.ofType("sales.Created"), EventType.ofType("hr.Created")), rawTypes());

		// each filter selects only its own class, though both classes are called Created
		assertEquals(List.of(new Sales.Created("O-1")),
				stream.query(EventQuery.forEvents(EventTypesFilter.of(Sales.Created.class), Tags.none()))
						.map(Event::data).toList());
		assertEquals(List.of(new Hr.Created("V-1")),
				stream.query(EventQuery.forEvents(EventTypesFilter.of(Hr.Created.class), Tags.none()))
						.map(Event::data).toList());
	}

	@ForEachBackend
	void testDuplicateNameIsRejectedAndNamesBothClasses ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(streamId, Set.of(Sales.class, ClashesWithSales.class)));

		assertTrue(e.getMessage().startsWith("duplicate event name sales.Created"), e.getMessage());
		assertTrue(e.getMessage().contains(Sales.Created.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(ClashesWithSales.Created.class.getName()), e.getMessage());
	}

	/**
	 * An alias claims a name just as a canonical value does, so it collides just as loudly. Silently letting
	 * one class shadow another's name is the failure this whole mechanism exists to prevent.
	 */
	@ForEachBackend
	void testAliasCollidingWithAnotherClassIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(streamId, Set.of(PlainEvent.class, ClashesWithPlainViaAlias.class)));

		assertTrue(e.getMessage().startsWith("duplicate event name SomethingHappened"), e.getMessage());
	}

	// ------------------------------------------------------------------------------------------------
	// renaming a class: aliases
	// ------------------------------------------------------------------------------------------------

	/**
	 * The hazard this exists for. Without the alias, history written under the old class name is unreadable —
	 * see {@link #testRenameWithoutAnAliasBreaksHistory()}.
	 */
	@ForEachBackend
	void testAliasReadsHistoryWrittenUnderTheOldName ( ) {
		appendUnderTheOldName();

		List<Event<Renamed>> events = eventStore().<Renamed>getEventStream(streamId, Renamed.class)
				.query(EventQuery.matchAll()).toList();

		assertEquals(1, events.size());
		assertEquals(new Renamed.CustomerOnboarded("John"), events.get(0).data());

		// the alias is reported as the stored type and the canonical name as the current type, exactly as an
		// upcast reports them -- so the read side can still tell what is actually on disk
		assertEquals(EventType.ofType("CustomerOnboarded"), events.get(0).type());
		assertEquals(EventType.ofType("CustomerRegistered"), events.get(0).storedType());
	}

	@ForEachBackend
	void testRenameWithoutAnAliasBreaksHistory ( ) {
		appendUnderTheOldName();

		RuntimeException e = assertThrows(RuntimeException.class,
				() -> eventStore().getEventStream(streamId, RenamedWithoutAlias.class)
						.query(EventQuery.matchAll()).toList());

		assertTrue(rootCauseMessage(e).contains("No mapping found for event type 'CustomerRegistered'"), rootCauseMessage(e));
	}

	/**
	 * Aliases are read-only. If an append could write one, a rename would produce a store holding both names
	 * forever and the alias could never be retired.
	 */
	@ForEachBackend
	void testAliasIsNeverWritten ( ) {
		eventStore().getEventStream(streamId, Renamed.class)
				.append(AppendCriteria.none(), Event.of(new Renamed.CustomerOnboarded("Jane"), Tags.none()));

		assertEquals(List.of(EventType.ofType("CustomerOnboarded")), rawTypes());
	}

	/**
	 * A query names a class, so it has to reach the events that class can read — otherwise a rename would
	 * quietly shrink every type-filtered projection to the events written since the rename.
	 */
	@ForEachBackend
	void testQueryOnTheRenamedClassAlsoSelectsAliasedHistory ( ) {
		appendUnderTheOldName();

		EventStream<Renamed> stream = eventStore().getEventStream(streamId, Renamed.class);
		stream.append(AppendCriteria.none(), Event.of(new Renamed.CustomerOnboarded("Jane"), Tags.none()));

		List<Event<Renamed>> selected = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(Renamed.CustomerOnboarded.class), Tags.none())).toList();

		assertEquals(List.of(new Renamed.CustomerOnboarded("John"), new Renamed.CustomerOnboarded("Jane")),
				selected.stream().map(Event::data).toList());
		assertEquals(List.of(EventType.ofType("CustomerRegistered"), EventType.ofType("CustomerOnboarded")),
				selected.stream().map(Event::storedType).toList());
	}

	// ------------------------------------------------------------------------------------------------
	// naming and upcasting are independent
	// ------------------------------------------------------------------------------------------------

	/**
	 * A legacy class exists only to describe a shape that is already in storage, so its name is pinned to
	 * whatever was written — which on the default scheme means the class itself can never be renamed or moved
	 * out of the way. {@link EventName} unpins it, and the upcast target is named by the same rules, so both
	 * ends of the mapping stay under the author's control rather than the refactoring tool's.
	 */
	@ForEachBackend
	void testEventNameAppliesToLegacyTypesAndUpcastTargets ( ) {
		appendUnderTheOldName();

		EventStream<Current> stream = eventStore().getEventStream(streamId, Set.of(Current.class), Set.of(Historical.class));
		stream.append(AppendCriteria.none(), Event.of(new Current.Onboarded("Jane", "unknown"), Tags.none()));

		// the legacy class is called Registered and lives in Historical, yet reads the CustomerRegistered
		// events; the current class is called Onboarded and writes customer.Onboarded
		assertEquals(List.of(EventType.ofType("CustomerRegistered"), EventType.ofType("customer.Onboarded")), rawTypes());

		List<Event<Current>> events = stream.query(
				EventQuery.forEvents(EventTypesFilter.of(Current.Onboarded.class), Tags.none())).toList();

		assertEquals(List.of(new Current.Onboarded("John", "upcast"), new Current.Onboarded("Jane", "unknown")),
				events.stream().map(Event::data).toList());
		assertEquals(List.of(EventType.ofType("customer.Onboarded"), EventType.ofType("customer.Onboarded")),
				events.stream().map(Event::type).toList());
		assertEquals(List.of(EventType.ofType("CustomerRegistered"), EventType.ofType("customer.Onboarded")),
				events.stream().map(Event::storedType).toList());
	}

	// ------------------------------------------------------------------------------------------------
	// annotation validation
	// ------------------------------------------------------------------------------------------------

	@ForEachBackend
	void testBlankNameIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(streamId, BlankName.class));
		assertTrue(e.getMessage().contains("must declare a non-blank name"), e.getMessage());
	}

	@ForEachBackend
	void testAliasRepeatingTheCanonicalNameIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> eventStore().getEventStream(streamId, SelfAlias.class));
		assertTrue(e.getMessage().contains("repeats its own name"), e.getMessage());
	}

	// ------------------------------------------------------------------------------------------------

	private void appendUnderTheOldName ( ) {
		eventStore().getEventStream(streamId, BeforeRename.class)
				.append(AppendCriteria.none(), Event.of(new BeforeRename.CustomerRegistered("John"), Tags.none()));
	}

	/** The names actually on disk, read without any type mapping so nothing is upcast or aliased away. */
	private List<EventType> rawTypes ( ) {
		return eventStore().getEventStream(streamId).query(EventQuery.matchAll()).map(Event::storedType).toList();
	}

	private static String rootCauseMessage ( Throwable t ) {
		Throwable cause = t;
		while ( cause.getCause() != null ) {
			cause = cause.getCause();
		}
		return String.valueOf(cause.getMessage());
	}

	// ------------------------------------------------------------------------------------------------
	// event definitions
	// ------------------------------------------------------------------------------------------------

	public sealed interface PlainEvent {
		record SomethingHappened ( String value ) implements PlainEvent { }
	}

	public sealed interface NamedEvent {
		@EventName("naming.SomethingHappened")
		record SomethingHappened ( String value ) implements NamedEvent { }
	}

	public sealed interface Sales {
		@EventName("sales.Created")
		record Created ( String orderId ) implements Sales { }
	}

	public sealed interface Hr {
		@EventName("hr.Created")
		record Created ( String vacancyId ) implements Hr { }
	}

	public sealed interface ClashesWithSales {
		@EventName("sales.Created")
		record Created ( String somethingElse ) implements ClashesWithSales { }
	}

	public sealed interface ClashesWithPlainViaAlias {
		@EventName(value = "Unrelated", aliases = "SomethingHappened")
		record Unrelated ( String value ) implements ClashesWithPlainViaAlias { }
	}

	/** How the event was defined -- and stored -- before the rename. */
	public sealed interface BeforeRename {
		record CustomerRegistered ( String name ) implements BeforeRename { }
	}

	/** The same event after the class was renamed: same shape, so an alias is all it takes. */
	public sealed interface Renamed {
		@EventName(value = "CustomerOnboarded", aliases = "CustomerRegistered")
		record CustomerOnboarded ( String name ) implements Renamed { }
	}

	/** The same rename done as an ordinary refactor, which is what silently orphans the history. */
	public sealed interface RenamedWithoutAlias {
		record CustomerOnboarded ( String name ) implements RenamedWithoutAlias { }
	}

	/** The current shape: a component was added, so this is an upcast and not an alias. */
	public sealed interface Current {
		@EventName("customer.Onboarded")
		record Onboarded ( String name, String source ) implements Current { }
	}

	/**
	 * The old shape, renamed and moved out of the way in code while keeping the name it was stored under.
	 */
	public sealed interface Historical {
		@EventName("CustomerRegistered")
		@LegacyEvent(upcast = RegisteredUpcaster.class)
		record Registered ( String name ) implements Historical { }
	}

	public static class RegisteredUpcaster implements Upcast<Historical.Registered, Current.Onboarded> {

		@Override
		public List<Current.Onboarded> upcast ( Historical.Registered historicalEvent ) {
			return List.of(new Current.Onboarded(historicalEvent.name(), "upcast"));
		}

		@Override
		public Set<Class<? extends Current.Onboarded>> targetTypes ( ) {
			return Set.of(Current.Onboarded.class);
		}

	}

	public sealed interface BlankName {
		@EventName("  ")
		record Whatever ( String value ) implements BlankName { }
	}

	public sealed interface SelfAlias {
		@EventName(value = "Whatever", aliases = "Whatever")
		record Whatever ( String value ) implements SelfAlias { }
	}

}
