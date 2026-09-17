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
package org.sliceworkz.eventstore.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The default {@link Upcaster#targetTypes()} is the {@code TARGET_EVENT} type argument, read off the
 * implementing class, and refused where the declaration does not fix one event class. Pinned here,
 * below the store; {@code UpcastChainSerdeTest} pins how the serde reports the refusal and
 * {@code UpcastChainTest} in the TCK reads a chain through derived defaults per backend.
 */
class UpcasterTest {

	sealed interface Current {
		record Registered ( String name ) implements Current { }
		record Churned ( ) implements Current { }
	}

	record Legacy ( String name ) { }

	// --- the shapes the default reads ------------------------------------------------------------------

	static class Direct implements Upcaster<Legacy, Current.Registered> {
		@Override public List<Current.Registered> upcast ( Legacy e ) { return List.of(new Current.Registered(e.name())); }
	}

	static abstract class Base<LEGACY, TARGET> implements Upcaster<LEGACY, TARGET> { }

	static class ThroughAGenericSuperclass extends Base<Legacy, Current.Registered> {
		@Override public List<Current.Registered> upcast ( Legacy e ) { return List.of(new Current.Registered(e.name())); }
	}

	interface Registering<LEGACY> extends Upcaster<LEGACY, Current.Registered> { }

	static class ThroughAGenericSuperinterface implements Registering<Legacy> {
		@Override public List<Current.Registered> upcast ( Legacy e ) { return List.of(new Current.Registered(e.name())); }
	}

	static class ASubclassOfAnUpcaster extends Direct { }

	@Test
	void theDefaultIsTheTargetTypeArgumentOfADirectImplementation ( ) {
		assertEquals(Set.of(Current.Registered.class), new Direct().targetTypes());
	}

	@Test
	void theDefaultFollowsATypeVariableBoundOnAGenericSuperclass ( ) {
		assertEquals(Set.of(Current.Registered.class), new ThroughAGenericSuperclass().targetTypes());
	}

	@Test
	void theDefaultFollowsATypeArgumentFixedOnASuperinterface ( ) {
		assertEquals(Set.of(Current.Registered.class), new ThroughAGenericSuperinterface().targetTypes());
	}

	@Test
	void theDefaultIsReadThroughASubclassOfTheImplementingClass ( ) {
		assertEquals(Set.of(Current.Registered.class), new ASubclassOfAnUpcaster().targetTypes());
	}

	// --- the shapes it refuses: nothing in the declaration says which event classes are produced ------

	static class OverASealedInterface implements Upcaster<Legacy, Current> {
		@Override public List<Current> upcast ( Legacy e ) { return List.of(); }
	}

	@SuppressWarnings("rawtypes")
	static class Raw implements Upcaster {
		@Override public List upcast ( Object e ) { return List.of(); }
	}

	static class LeftOpen<TARGET> extends Base<Legacy, TARGET> {
		@Override public List<TARGET> upcast ( Legacy e ) { return List.of(); }
	}

	@Test
	void aSealedInterfaceAsTheTypeArgumentIsNotReadAsEveryTypeUnderIt ( ) {
		// an upcaster that splits or drops its event is typed this way, and which of the types it
		// produces is nothing the declaration says: it declares them itself
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> new OverASealedInterface().targetTypes());

		assertTrue(e.getMessage().contains(OverASealedInterface.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains(Current.class.getName()), e.getMessage());
		assertTrue(e.getMessage().contains("targetTypes()"), e.getMessage());
	}

	@Test
	void aRawUpcasterCannotDeriveItsTargets ( ) {
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> new Raw().targetTypes());
		assertTrue(e.getMessage().contains(Raw.class.getName()), e.getMessage());
	}

	@Test
	void aTypeVariableTheClassLeavesOpenCannotDeriveItsTargets ( ) {
		// the instance is a LeftOpen<Registered>, but erasure keeps that from the class: nothing to read
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> new LeftOpen<Current.Registered>().targetTypes());
		assertTrue(e.getMessage().contains(LeftOpen.class.getName()), e.getMessage());
	}

	// --- an override is what it always was ------------------------------------------------------------

	static class Dropping implements Upcaster<Legacy, Current> {
		@Override public List<Current> upcast ( Legacy e ) { return List.of(); }
		@Override public Set<Class<? extends Current>> targetTypes ( ) { return Set.of(); }
	}

	@Test
	void anOverrideIsNotSecondGuessed ( ) {
		assertEquals(Set.of(), new Dropping().targetTypes());
	}

}
