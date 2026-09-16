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
package org.sliceworkz.eventstore.impl.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Upcast;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndPayload;
import org.sliceworkz.eventstore.impl.serde.EventPayloadSerializerDeserializer.TypeAndSerializedPayload;

/**
 * The typed serde follows a chain of upcasters until it reaches a current type, traces a query for a
 * current type back through every hop, and checks what the upcasters declare against each other once
 * the registrations are complete. Below the store, so that the checks are pinned by the messages
 * they produce; {@code UpcastChainTest} in the TCK pins the same contract per backend.
 */
class UpcastChainSerdeTest {

	// --- three versions of one event, the first two legacy ----------------------------------------

	sealed interface Current {
		record V3 ( String name, int age ) implements Current { }
		record Churned ( ) implements Current { }
	}

	sealed interface Legacy {
		@LegacyEvent(upcast = V1ToV2.class)
		record V1 ( String name ) implements Legacy { }
		@LegacyEvent(upcast = V2ToV3.class)
		record V2 ( String name, String age ) implements Legacy { }
	}

	public static class V1ToV2 implements Upcast<Legacy.V1, Legacy.V2> {
		@Override public List<Legacy.V2> upcast ( Legacy.V1 e ) { return List.of(new Legacy.V2(e.name(), "0")); }
		@Override public Set<Class<? extends Legacy.V2>> targetTypes ( ) { return Set.of(Legacy.V2.class); }
	}

	public static class V2ToV3 implements Upcast<Legacy.V2, Current.V3> {
		@Override public List<Current.V3> upcast ( Legacy.V2 e ) { return List.of(new Current.V3(e.name(), Integer.parseInt(e.age()))); }
		@Override public Set<Class<? extends Current.V3>> targetTypes ( ) { return Set.of(Current.V3.class); }
	}

	private static EventPayloadSerializerDeserializer serdeOver ( Class<?> current, Class<?>... legacy ) {
		EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed().registerEventTypes(current);
		for ( Class<?> root : legacy ) {
			serde.registerLegacyEventTypes(root);
		}
		return serde.validate();
	}

	private static TypeAndSerializedPayload stored ( String type, String json ) {
		return new TypeAndSerializedPayload(EventType.ofType(type), json);
	}

	@Test
	void aLegacyEventUpcastsThroughEveryHopToACurrentType ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Legacy.class);

		List<TypeAndPayload> fromV1 = serde.deserialize(stored("V1", "{\"name\":\"John\"}"));
		assertEquals(List.of(new TypeAndPayload(EventType.ofType("V3"), new Current.V3("John", 0))), fromV1);

		List<TypeAndPayload> fromV2 = serde.deserialize(stored("V2", "{\"name\":\"Jane\",\"age\":\"42\"}"));
		assertEquals(List.of(new TypeAndPayload(EventType.ofType("V3"), new Current.V3("Jane", 42))), fromV2);
	}

	@Test
	void aQueryForTheCurrentTypeTracesBackThroughEveryHop ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Legacy.class);

		assertEquals(Set.of(EventType.ofType("V3"), EventType.ofType("V2"), EventType.ofType("V1")),
				serde.determineLegacyTypes(Set.of(EventType.ofType("V3"))));
		// a legacy type is never a current one: a query for it fetches nothing but itself
		assertEquals(Set.of(EventType.ofType("V2")), serde.determineLegacyTypes(Set.of(EventType.ofType("V2"))));
		assertEquals(Set.of(EventType.ofType("Churned")), serde.determineLegacyTypes(Set.of(EventType.ofType("Churned"))));
	}

	@Test
	void aReadBeforeValidateRunsTheSameCheck ( ) {
		EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed()
				.registerEventTypes(Current.class)
				.registerLegacyEventTypes(Legacy.class);

		assertEquals(new Current.V3("John", 0), serde.deserialize(stored("V1", "{\"name\":\"John\"}")).getFirst().eventData());
		assertTrue(serde.determineLegacyTypes(Set.of(EventType.ofType("V3"))).contains(EventType.ofType("V1")));
	}

	// --- an upcaster's declaration is checked once the registrations are complete ---------------------

	sealed interface Elsewhere {
		record Moved ( String name ) implements Elsewhere { }
	}

	@LegacyEvent(upcast = ToAnUnregisteredType.class)
	record Orphan ( String name ) { }

	public static class ToAnUnregisteredType implements Upcast<Orphan, Elsewhere.Moved> {
		@Override public List<Elsewhere.Moved> upcast ( Orphan e ) { return List.of(new Elsewhere.Moved(e.name())); }
		@Override public Set<Class<? extends Elsewhere.Moved>> targetTypes ( ) { return Set.of(Elsewhere.Moved.class); }
	}

	@Test
	void anUpcasterNamingATargetThatIsNotRegisteredIsRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> serdeOver(Current.class, Orphan.class));

		assertTrue(e.getMessage().contains(ToAnUnregisteredType.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(Elsewhere.Moved.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("not registered"), e.getMessage());

		// registering the root it needs, in whatever order, is the fix
		EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed()
				.registerEventTypes(Current.class)
				.registerLegacyEventTypes(Orphan.class)
				.registerEventTypes(Elsewhere.class)
				.validate();
		assertEquals(new Elsewhere.Moved("x"), serde.deserialize(stored("Orphan", "{\"name\":\"x\"}")).getFirst().eventData());
	}

	sealed interface AnotherV3Hierarchy {
		record V3 ( String name ) implements AnotherV3Hierarchy { }
	}

	@LegacyEvent(upcast = ToTheOtherV3.class)
	record Impostor ( String name ) { }

	public static class ToTheOtherV3 implements Upcast<Impostor, AnotherV3Hierarchy.V3> {
		@Override public List<AnotherV3Hierarchy.V3> upcast ( Impostor e ) { return List.of(new AnotherV3Hierarchy.V3(e.name())); }
		@Override public Set<Class<? extends AnotherV3Hierarchy.V3>> targetTypes ( ) { return Set.of(AnotherV3Hierarchy.V3.class); }
	}

	@Test
	void anUpcasterNamingAnotherClassUnderARegisteredStoredNameIsRejected ( ) {
		// the stored name 'V3' is registered, but for a different class: a match on the name alone
		// would hand the caller an event of a class the stream never registered
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> serdeOver(Current.class, Impostor.class));

		assertTrue(e.getMessage().contains(AnotherV3Hierarchy.V3.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(Current.V3.class.getName()), e.getMessage());
	}

	sealed interface Cyclic {
		@LegacyEvent(upcast = AToB.class)
		record A ( String name ) implements Cyclic { }
		@LegacyEvent(upcast = BToA.class)
		record B ( String name ) implements Cyclic { }
	}

	public static class AToB implements Upcast<Cyclic.A, Cyclic.B> {
		@Override public List<Cyclic.B> upcast ( Cyclic.A e ) { return List.of(new Cyclic.B(e.name())); }
		@Override public Set<Class<? extends Cyclic.B>> targetTypes ( ) { return Set.of(Cyclic.B.class); }
	}

	public static class BToA implements Upcast<Cyclic.B, Cyclic.A> {
		@Override public List<Cyclic.A> upcast ( Cyclic.B e ) { return List.of(new Cyclic.A(e.name())); }
		@Override public Set<Class<? extends Cyclic.A>> targetTypes ( ) { return Set.of(Cyclic.A.class); }
	}

	@Test
	void upcastersFormingACycleAreRejected ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> serdeOver(Current.class, Cyclic.class));

		assertTrue(e.getMessage().contains("cycle"), e.getMessage());
		assertTrue(e.getMessage().contains(Cyclic.A.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(Cyclic.B.class.getName()), e.getMessage());
	}

	// --- a sealed interface among the targets stands for every type under it -------------------------

	@LegacyEvent(upcast = ToWhateverIsCurrent.class)
	record Broad ( String name ) { }

	public static class ToWhateverIsCurrent implements Upcast<Broad, Current> {
		@Override public List<Current> upcast ( Broad e ) { return List.of(new Current.V3(e.name(), 1), new Current.Churned()); }
		@Override public Set<Class<? extends Current>> targetTypes ( ) { return Set.of(Current.class); }
	}

	@Test
	void aSealedInterfaceAmongTheTargetsStandsForEveryTypeUnderIt ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Broad.class);

		assertEquals(2, serde.deserialize(stored("Broad", "{\"name\":\"x\"}")).size());
		assertTrue(serde.determineLegacyTypes(Set.of(EventType.ofType("V3"))).contains(EventType.ofType("Broad")));
		assertTrue(serde.determineLegacyTypes(Set.of(EventType.ofType("Churned"))).contains(EventType.ofType("Broad")));
	}

	// --- what an upcaster produces is checked against what it declared -------------------------------

	@LegacyEvent(upcast = DeclaresNothingProducesSomething.class)
	record Understated ( String name ) { }

	public static class DeclaresNothingProducesSomething implements Upcast<Understated, Current> {
		@Override public List<Current> upcast ( Understated e ) { return List.of(new Current.Churned()); }
		@Override public Set<Class<? extends Current>> targetTypes ( ) { return Set.of(); }
	}

	@Test
	void anUpcasterProducingATypeItDidNotDeclareFailsTheReadNamingIt ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Understated.class);

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> serde.deserialize(stored("Understated", "{\"name\":\"x\"}")));

		assertEquals(EventType.ofType("Understated"), e.getEventType());
		assertTrue(e.getMessage().contains(DeclaresNothingProducesSomething.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(Current.Churned.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("targetTypes()"), e.getMessage());
	}

	@LegacyEvent(upcast = ThrowsOnTheSecondHop.class)
	record Doomed ( String name ) { }

	public static class ThrowsOnTheSecondHop implements Upcast<Doomed, Legacy.V2> {
		@Override public List<Legacy.V2> upcast ( Doomed e ) { return List.of(new Legacy.V2(e.name(), "not a number")); }
		@Override public Set<Class<? extends Legacy.V2>> targetTypes ( ) { return Set.of(Legacy.V2.class); }
	}

	@Test
	void anUpcasterThrowingOnALaterHopIsNamedWithTheStoredType ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Legacy.class, Doomed.class);

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> serde.deserialize(stored("Doomed", "{\"name\":\"x\"}")));

		// the stored event is the one a caller can dead-letter; the upcaster that threw is the one to fix
		assertEquals(EventType.ofType("Doomed"), e.getEventType());
		assertTrue(e.getMessage().contains(V2ToV3.class.getName()), e.getMessage());
		assertInstanceOf(NumberFormatException.class, e.getCause());
	}

}
