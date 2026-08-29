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
package org.sliceworkz.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.events.EventDeserializationException;
import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.projection.ProjectorException;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventTypesFilter;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

/**
 * Every exception this library throws has to survive Java serialization, and the reference it names
 * has to survive with it.
 *
 * <p>A {@code Throwable} is {@code Serializable}, so a field on one that is not makes the whole
 * exception unserializable -- and the symptom is uniquely unhelpful, because whatever was carrying the
 * exception across a process boundary reports a {@code NotSerializableException} <em>instead of</em>
 * the failure. The real error is not logged, not wrapped, not chained: it is replaced. That is how
 * this went unnoticed. {@code OptimisticLockingException}, the single most commonly thrown type here,
 * held an {@code Optional} and an {@code EventFilter} and could not be serialized at all; a forked JMH
 * benchmark hitting a genuine DCB conflict died with a serialization complaint and exit code 1, naming
 * nothing about the conflict.
 *
 * <p>These are cheap tests for a failure mode that is otherwise only ever discovered in a harness
 * nobody suspects.
 */
class ExceptionSerializationTest {

	@Test
	void anOptimisticLockingExceptionSurvivesSerializationWithItsReference ( ) throws Exception {
		EventReference reference = new EventReference(new EventId("11111111-1111-1111-1111-111111111111"),
				42L, 7L, 0);
		EventFilter filter = EventFilter.forEvents(EventTypesFilter.any(), Tags.of("sku", "SKU-1"));

		OptimisticLockingException original =
				new OptimisticLockingException(filter, Optional.of(reference));
		OptimisticLockingException restored = roundTrip(original);

		assertEquals(original.getMessage(), restored.getMessage());
		assertEquals(Optional.of(reference), restored.getExpectedLastEventReference());

		// the filter is deliberately transient: it is a query shape over six further types, wanted by
		// nobody across a boundary, and the message already names it in text
		assertNull(restored.getFilter());
		assertTrue(restored.getMessage().contains("SKU-1"));
	}

	@Test
	void anEmptyBoundaryConflictStillReportsAnEmptyOptionalAfterSerialization ( ) throws Exception {
		// the getter is documented as never null; holding the reference unwrapped is what keeps that
		// true on the far side, where a serialized Optional would have arrived as null
		OptimisticLockingException restored = roundTrip(new OptimisticLockingException(
				EventFilter.forEvents(EventTypesFilter.any(), Tags.none()), Optional.empty()));

		assertNotNull(restored.getExpectedLastEventReference());
		assertTrue(restored.getExpectedLastEventReference().isEmpty());
	}

	@Test
	void aProjectorExceptionSurvivesWithTheEventItFailedOn ( ) throws Exception {
		EventReference reference = new EventReference(new EventId("22222222-2222-2222-2222-222222222222"),
				9L, 3L, 1);

		ProjectorException restored =
				roundTrip(new ProjectorException(new IllegalStateException("projection blew up"), reference));

		assertEquals(reference, restored.getEventReference());
		assertNotNull(restored.getCause());
		assertEquals("projection blew up", restored.getCause().getMessage());
	}

	@Test
	void anEventDeserializationExceptionSurvivesWithTheTypeAndReferenceThatIdentifyIt ( ) throws Exception {
		// this one has the most to lose: the type and the reference are its entire diagnostic value,
		// and an instance arriving without them says only that something somewhere could not be read
		EventReference reference = new EventReference(new EventId("33333333-3333-3333-3333-333333333333"),
				5L, 2L, 0);

		EventDeserializationException restored = roundTrip(
				new EventDeserializationException(EventType.ofType("CustomerRegistered"), "no mapping found")
						.withReference(reference));

		assertEquals(EventType.ofType("CustomerRegistered"), restored.getEventType());
		assertEquals(Optional.of(reference), restored.getReference());
	}

	@Test
	void aDeserializedReferenceStillSatisfiesItsInvariants ( ) throws Exception {
		// A record deserializes through its canonical constructor rather than by field injection, so
		// the validation is re-applied on the way in. That is what makes committing EventReference to a
		// serialized form cheap: no stream can conjure one with a null id or a position of zero.
		EventReference reference = new EventReference(new EventId("44444444-4444-4444-4444-444444444444"),
				1L, 1L, 0);
		assertEquals(reference, roundTrip(reference));

		assertThrows(IllegalArgumentException.class,
				() -> new EventReference(new EventId("x"), 0L, 1L, 0));
	}

	@SuppressWarnings ( "unchecked" )
	private static <T> T roundTrip ( T value ) throws IOException, ClassNotFoundException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try ( ObjectOutputStream out = new ObjectOutputStream(bytes) ) {
			out.writeObject(value);
		}
		try ( ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) ) {
			return (T) in.readObject();
		}
	}
}
