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
package org.sliceworkz.eventstore.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * Tests verifying how the EventStore handles deserialization when event record
 * schemas evolve — specifically when properties are added to or removed from
 * event records after events have already been persisted.
 * <p>
 * These tests demonstrate that Jackson's default FAIL_ON_UNKNOWN_PROPERTIES
 * setting causes deserialization failures when reading events whose stored JSON
 * contains properties that no longer exist in the current record definition.
 */
public class EventDeserializationEvolutionTest {

	EventStorage eventStorage;
	EventStore eventStore;
	EventStreamId streamId = EventStreamId.forContext("deserialization-evolution-test");

	@BeforeEach
	public void setUp() {
		this.eventStorage = createEventStorage();
		this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
	}

	@AfterEach
	public void tearDown() {
		destroyEventStorage(eventStorage);
	}

	public EventStorage createEventStorage() {
		return InMemoryEventStorage.newBuilder().build();
	}

	public void destroyEventStorage(EventStorage storage) {
	}

	// --- Original event definitions (V1) ---

	sealed interface OrderEventV1 {
		record OrderPlaced(String orderId, String product) implements OrderEventV1 {}
	}

	// --- V2: New property added (email) ---

	sealed interface OrderEventV2WithAddedField {
		record OrderPlaced(String orderId, String product, String email) implements OrderEventV2WithAddedField {}
	}

	// --- V3: Property removed (product removed) ---

	sealed interface OrderEventV3WithRemovedField {
		record OrderPlaced(String orderId) implements OrderEventV3WithRemovedField {}
	}

	/**
	 * When a new property is added to a record, old events (stored without that
	 * property) should deserialize successfully with the new field set to null.
	 */
	@Test
	void readingEventsAfterAddingNewPropertyToRecord_shouldSetNewFieldToNull() {
		// Store an event using the original V1 schema
		EventStream<OrderEventV1> v1Stream = eventStore.getEventStream(streamId, OrderEventV1.class);
		v1Stream.append(AppendCriteria.none(),
				Event.of(new OrderEventV1.OrderPlaced("order-1", "Widget"), Tags.none()));

		// Now read the same stream using V2 schema (which has an additional "email" field)
		EventStream<OrderEventV2WithAddedField> v2Stream = eventStore.getEventStream(streamId,
				OrderEventV2WithAddedField.class);
		List<Event<OrderEventV2WithAddedField>> events = v2Stream.query(EventQuery.matchAll()).toList();

		assertEquals(1, events.size());
		OrderEventV2WithAddedField.OrderPlaced data = (OrderEventV2WithAddedField.OrderPlaced) events.get(0).data();
		assertEquals("order-1", data.orderId());
		assertEquals("Widget", data.product());
		assertNull(data.email(), "New field should be null when reading old events that don't have it");
	}

	/**
	 * When a property is removed from a record, old events (stored with that
	 * property) should fail to deserialize because Jackson's default
	 * FAIL_ON_UNKNOWN_PROPERTIES is enabled, causing an UnrecognizedPropertyException.
	 */
	@Test
	void readingEventsAfterRemovingPropertyFromRecord_shouldFailWithUnrecognizedProperty() {
		// Store an event using the original V1 schema (has orderId + product)
		EventStream<OrderEventV1> v1Stream = eventStore.getEventStream(streamId, OrderEventV1.class);
		v1Stream.append(AppendCriteria.none(),
				Event.of(new OrderEventV1.OrderPlaced("order-1", "Widget"), Tags.none()));

		// Now read the same stream using V3 schema (product field removed)
		EventStream<OrderEventV3WithRemovedField> v3Stream = eventStore.getEventStream(streamId,
				OrderEventV3WithRemovedField.class);

		// This should fail because the stored JSON contains "product" which is unknown to V3
		RuntimeException exception = assertThrows(RuntimeException.class,
				() -> v3Stream.query(EventQuery.matchAll()).toList());

		assertEquals(true, exception.getMessage().contains("Failed to deserialize event data"),
				"Expected deserialization failure due to unknown property, but got: " + exception.getMessage());
	}
}
