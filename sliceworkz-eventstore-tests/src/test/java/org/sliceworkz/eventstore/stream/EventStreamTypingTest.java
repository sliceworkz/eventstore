/*
 * Sliceworkz Eventstore - a Java/Postgres DCB Eventstore implementation
 * Copyright © 2025 Sliceworkz / XTi (info@sliceworkz.org)
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
package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;

public class EventStreamTypingTest {
	
	@Test
	void testOK () {
		InMemoryEventStorage.newBuilder().buildStore().getEventStream(EventStreamId.anyContext().anyPurpose(), CorrectEvent.class);
	}

	@Test
	void testNOK () {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
		InMemoryEventStorage.newBuilder().buildStore().getEventStream(EventStreamId.anyContext().anyPurpose(), IncorrectEvent.class);
		});
		assertEquals("interface org.sliceworkz.eventstore.stream.EventStreamTypingTest$IncorrectEvent$IncorrectCustomerCorrectEvent should be sealed to allow Event Type determination", e.getMessage());
	}

	public sealed interface CorrectEvent {
		
		public sealed interface CustomerCorrectEvent extends CorrectEvent {
			
			public record CustomerRegisteredEvent ( String name ) implements CustomerCorrectEvent { }
			
		}
		
		// non-sealed will work, so we need a placeholder event
		public sealed interface PlaceholderCorrectEvent extends CorrectEvent {
			
			public record PlaceHolderEvent ( ) implements PlaceholderCorrectEvent { }
			
		}
		
		public record JustAnotherEvent ( String someInfo ) implements CorrectEvent { }

	}

	public sealed interface IncorrectEvent {
		
		// non-sealed, not ok - will generate an error as type enumeration will not be possible
		public non-sealed interface IncorrectCustomerCorrectEvent extends IncorrectEvent {
			
			public record CustomerRegisteredEvent ( String name ) implements IncorrectCustomerCorrectEvent { }
			
		}
		
	}

}
