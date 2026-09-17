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
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.LegacyEvent;
import org.sliceworkz.eventstore.events.Upcaster;
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
		@LegacyEvent(upcaster = V1ToV2.class)
		record V1 ( String name ) implements Legacy { }
		@LegacyEvent(upcaster = V2ToV3.class)
		record V2 ( String name, String age ) implements Legacy { }
	}

	// neither overrides targetTypes(): one target each, derived from the type argument -- for V1ToV2 a
	// legacy class, so the derived declaration is followed on to the next hop like a written one
	public static class V1ToV2 implements Upcaster<Legacy.V1, Legacy.V2> {
		@Override public List<Legacy.V2> upcast ( Legacy.V1 e ) { return List.of(new Legacy.V2(e.name(), "0")); }
	}

	public static class V2ToV3 implements Upcaster<Legacy.V2, Current.V3> {
		@Override public List<Current.V3> upcast ( Legacy.V2 e ) { return List.of(new Current.V3(e.name(), Integer.parseInt(e.age()))); }
	}

	private static EventPayloadSerializerDeserializer serdeOver ( Class<?> current, Class<?>... legacy ) {
		EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed().registerEventTypes(current);
		for ( Class<?> root : legacy ) {
			serde.registerLegacyEventTypes(root);
		}
		return serde.validate();
	}

	private static TypeAndSerializedPayload stored ( String type, String json ) {
		return new TypeAndSerializedPayload(EventType.named(type), json);
	}

	@Test
	void aLegacyEventUpcastsThroughEveryHopToACurrentType ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Legacy.class);

		List<TypeAndPayload> fromV1 = serde.deserialize(stored("V1", "{\"name\":\"John\"}"));
		assertEquals(List.of(new TypeAndPayload(EventType.named("V3"), new Current.V3("John", 0))), fromV1);

		List<TypeAndPayload> fromV2 = serde.deserialize(stored("V2", "{\"name\":\"Jane\",\"age\":\"42\"}"));
		assertEquals(List.of(new TypeAndPayload(EventType.named("V3"), new Current.V3("Jane", 42))), fromV2);
	}

	@Test
	void aQueryForTheCurrentTypeTracesBackThroughEveryHop ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Legacy.class);

		assertEquals(Set.of(EventType.named("V3"), EventType.named("V2"), EventType.named("V1")),
				serde.determineLegacyTypes(Set.of(EventType.named("V3"))));
		// a legacy type is never a current one, so nothing traces back to it; the stream refuses a
		// filter naming one before this is asked, on what legacyTypesAmong answers below
		assertEquals(Set.of(EventType.named("V2")), serde.determineLegacyTypes(Set.of(EventType.named("V2"))));
		assertEquals(Set.of(EventType.named("Churned")), serde.determineLegacyTypes(Set.of(EventType.named("Churned"))));
	}

	@Test
	void theLegacyTypesAmongAFilterAreNamedWithTheCurrentTypesTheirChainsEndIn ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Legacy.class);

		// each legacy type is mapped to the end of its chain, not to its next hop; a current type and a
		// name this serde does not register are not legacy
		assertEquals(Map.of(EventType.named("V1"), Set.of(EventType.named("V3")), EventType.named("V2"), Set.of(EventType.named("V3"))),
				serde.legacyTypesAmong(Set.of(EventType.named("V1"), EventType.named("V2"), EventType.named("V3"), EventType.named("Churned"), EventType.named("Unknown"))));
		assertEquals(Map.of(), serde.legacyTypesAmong(Set.of(EventType.named("V3"), EventType.named("Unknown"))));
		// raw mode registers no legacy types
		assertEquals(Map.of(), EventPayloadSerializerDeserializer.raw().legacyTypesAmong(Set.of(EventType.named("V1"))));
	}

	@Test
	void aReadBeforeValidateRunsTheSameCheck ( ) {
		EventPayloadSerializerDeserializer serde = EventPayloadSerializerDeserializer.typed()
				.registerEventTypes(Current.class)
				.registerLegacyEventTypes(Legacy.class);

		assertEquals(new Current.V3("John", 0), serde.deserialize(stored("V1", "{\"name\":\"John\"}")).getFirst().eventData());
		assertTrue(serde.determineLegacyTypes(Set.of(EventType.named("V3"))).contains(EventType.named("V1")));
	}

	// --- an upcaster's declaration is checked once the registrations are complete ---------------------

	sealed interface Elsewhere {
		record Moved ( String name ) implements Elsewhere { }
	}

	@LegacyEvent(upcaster = ToAnUnregisteredType.class)
	record Orphan ( String name ) { }

	/** No override: the target is derived from the type argument, and checked exactly as a written one. */
	public static class ToAnUnregisteredType implements Upcaster<Orphan, Elsewhere.Moved> {
		@Override public List<Elsewhere.Moved> upcast ( Orphan e ) { return List.of(new Elsewhere.Moved(e.name())); }
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

	@LegacyEvent(upcaster = ToTheOtherV3.class)
	record Impostor ( String name ) { }

	public static class ToTheOtherV3 implements Upcaster<Impostor, AnotherV3Hierarchy.V3> {
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
		@LegacyEvent(upcaster = AToB.class)
		record A ( String name ) implements Cyclic { }
		@LegacyEvent(upcaster = BToA.class)
		record B ( String name ) implements Cyclic { }
	}

	public static class AToB implements Upcaster<Cyclic.A, Cyclic.B> {
		@Override public List<Cyclic.B> upcast ( Cyclic.A e ) { return List.of(new Cyclic.B(e.name())); }
		@Override public Set<Class<? extends Cyclic.B>> targetTypes ( ) { return Set.of(Cyclic.B.class); }
	}

	public static class BToA implements Upcaster<Cyclic.B, Cyclic.A> {
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

	@LegacyEvent(upcaster = ToWhateverIsCurrent.class)
	record Broad ( String name ) { }

	public static class ToWhateverIsCurrent implements Upcaster<Broad, Current> {
		@Override public List<Current> upcast ( Broad e ) { return List.of(new Current.V3(e.name(), 1), new Current.Churned()); }
		@Override public Set<Class<? extends Current>> targetTypes ( ) { return Set.of(Current.class); }
	}

	@Test
	void aSealedInterfaceAmongTheTargetsStandsForEveryTypeUnderIt ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Broad.class);

		assertEquals(2, serde.deserialize(stored("Broad", "{\"name\":\"x\"}")).size());
		assertTrue(serde.determineLegacyTypes(Set.of(EventType.named("V3"))).contains(EventType.named("Broad")));
		assertTrue(serde.determineLegacyTypes(Set.of(EventType.named("Churned"))).contains(EventType.named("Broad")));
	}

	// --- the default targetTypes() needs one event class as the type argument -------------------------

	@LegacyEvent(upcaster = LeavesTheTargetsUndeclared.class)
	record Vague ( String name ) { }

	/** Typed over the sealed hierarchy and not overriding targetTypes(): nothing says which types it produces. */
	public static class LeavesTheTargetsUndeclared implements Upcaster<Vague, Current> {
		@Override public List<Current> upcast ( Vague e ) { return List.of(new Current.Churned()); }
	}

	@Test
	void anUpcasterWhoseTargetsCannotBeDerivedIsRejectedAtRegistration ( ) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> serdeOver(Current.class, Vague.class));

		// reported like the other registration checks: the upcaster, the legacy event and the fix
		assertTrue(e.getMessage().contains(LeavesTheTargetsUndeclared.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(Vague.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("targetTypes()"), e.getMessage());
		assertTrue(e.getMessage().contains(Current.class.getName()), e.getMessage());
		assertInstanceOf(IllegalStateException.class, e.getCause());
	}

	// --- what an upcaster produces is checked against what it declared -------------------------------

	@LegacyEvent(upcaster = DeclaresNothingProducesSomething.class)
	record Understated ( String name ) { }

	public static class DeclaresNothingProducesSomething implements Upcaster<Understated, Current> {
		@Override public List<Current> upcast ( Understated e ) { return List.of(new Current.Churned()); }
		@Override public Set<Class<? extends Current>> targetTypes ( ) { return Set.of(); }
	}

	@Test
	void anUpcasterProducingATypeItDidNotDeclareFailsTheReadNamingIt ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Understated.class);

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> serde.deserialize(stored("Understated", "{\"name\":\"x\"}")));

		assertEquals(EventType.named("Understated"), e.getEventType());
		assertTrue(e.getMessage().contains(DeclaresNothingProducesSomething.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(Current.Churned.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("targetTypes()"), e.getMessage());
	}

	@LegacyEvent(upcaster = ThrowsOnTheSecondHop.class)
	record Doomed ( String name ) { }

	public static class ThrowsOnTheSecondHop implements Upcaster<Doomed, Legacy.V2> {
		@Override public List<Legacy.V2> upcast ( Doomed e ) { return List.of(new Legacy.V2(e.name(), "not a number")); }
		@Override public Set<Class<? extends Legacy.V2>> targetTypes ( ) { return Set.of(Legacy.V2.class); }
	}

	@Test
	void anUpcasterThrowingOnALaterHopIsNamedWithTheStoredType ( ) {
		EventPayloadSerializerDeserializer serde = serdeOver(Current.class, Legacy.class, Doomed.class);

		EventDeserializationException e = assertThrows(EventDeserializationException.class,
				() -> serde.deserialize(stored("Doomed", "{\"name\":\"x\"}")));

		// the stored event is the one a caller can dead-letter; the upcaster that threw is the one to fix
		assertEquals(EventType.named("Doomed"), e.getEventType());
		assertTrue(e.getMessage().contains(V2ToV3.class.getName()), e.getMessage());
		assertInstanceOf(NumberFormatException.class, e.getCause());
	}

}
