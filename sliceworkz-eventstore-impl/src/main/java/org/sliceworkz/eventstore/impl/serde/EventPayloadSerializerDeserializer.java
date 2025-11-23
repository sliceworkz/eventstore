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
package org.sliceworkz.eventstore.impl.serde;

import java.util.Set;

import org.sliceworkz.eventstore.events.EventType;

/**
 * Interface for serializing and deserializing event payloads to/from JSON.
 * <p>
 * This abstraction handles the conversion between domain event objects and their JSON representation
 * for storage. It supports two distinct modes of operation:
 * <ul>
 *   <li><b>Typed Mode:</b> Events are mapped to/from Java classes using Jackson, providing type safety
 *       and supporting features like sealed interfaces, upcasting, and GDPR compliance.</li>
 *   <li><b>Raw Mode:</b> Events remain as JSON strings without type mapping, useful for schema-less
 *       event processing.</li>
 * </ul>
 * <p>
 * The serializer/deserializer supports advanced features including:
 * <ul>
 *   <li><b>Data Erasure:</b> Fields annotated with {@link org.sliceworkz.eventstore.events.Erasable}
 *       or {@link org.sliceworkz.eventstore.events.PartlyErasable} are stored separately to support GDPR
 *       "right to be forgotten" requirements</li>
 *   <li><b>Event Upcasting:</b> Historical events annotated with {@link org.sliceworkz.eventstore.events.LegacyEvent}
 *       are automatically transformed to current event types using registered upcast functions</li>
 *   <li><b>Sealed Interfaces:</b> Java sealed interfaces are introspected to discover all permitted event types</li>
 * </ul>
 *
 * <h2>Factory Methods:</h2>
 * <pre>{@code
 * // Create typed serializer/deserializer
 * EventPayloadSerializerDeserializer typed = EventPayloadSerializerDeserializer.typed();
 * typed.registerEventTypes(CustomerEvent.class);
 *
 * // Create raw serializer/deserializer
 * EventPayloadSerializerDeserializer raw = EventPayloadSerializerDeserializer.raw();
 * }</pre>
 *
 * @see TypedEventPayloadSerializerDeserializer
 * @see RawEventPayloadSerializerDeserializer
 * @see AbstractEventPayloadSerializerDeserializer
 */
public interface EventPayloadSerializerDeserializer {

	/**
	 * Serializes a domain event object to its JSON representation.
	 * <p>
	 * The payload is split into two parts:
	 * <ul>
	 *   <li><b>Immutable data:</b> Fields that should be retained permanently</li>
	 *   <li><b>Erasable data:</b> Fields marked with {@code @Erasable} or {@code @PartlyErasable}
	 *       that may be deleted for GDPR compliance</li>
	 * </ul>
	 *
	 * @param payload the domain event object to serialize
	 * @return the serialized event including type and separated immutable/erasable payloads
	 * @see org.sliceworkz.eventstore.events.Erasable
	 * @see org.sliceworkz.eventstore.events.PartlyErasable
	 */
	TypeAndSerializedPayload serialize(Object payload);

	/**
	 * Deserializes a JSON representation back to a domain event object.
	 * <p>
	 * Merges immutable and erasable data (if present) to reconstruct the complete event object.
	 * For typed mode, the event type name is used to determine the target Java class.
	 * For raw mode, returns a Jackson JsonNode.
	 *
	 * @param serialized the serialized event including type and separated payloads
	 * @return the deserialized event type and data object
	 * @throws RuntimeException if deserialization fails or type mapping is not found
	 */
	TypeAndPayload deserialize(TypeAndSerializedPayload serialized);

	/**
	 * Checks whether this serializer/deserializer can deserialize events of the given type name.
	 * <p>
	 * For typed mode, returns true if the event type has been registered via {@link #registerEventTypes(Class)}
	 * or {@link #registerLegacyEventTypes(Class)}. For raw mode, always returns false since no type
	 * mapping is required.
	 *
	 * @param eventTypeName the event type name to check
	 * @return true if this serializer can deserialize the event type, false otherwise
	 */
	boolean canDeserialize(String eventTypeName);

	/**
	 * Registers current domain event types for serialization/deserialization.
	 * <p>
	 * For sealed interfaces, all permitted subclasses are automatically registered.
	 * For concrete classes, only that specific class is registered.
	 * <p>
	 * Events registered via this method must NOT be annotated with {@link org.sliceworkz.eventstore.events.LegacyEvent}.
	 *
	 * @param rootClass the event root class or sealed interface to register
	 * @return this serializer for method chaining
	 * @throws IllegalArgumentException if the interface is not sealed or if event names conflict
	 */
	EventPayloadSerializerDeserializer registerEventTypes ( Class<?> rootClass );

	/**
	 * Registers historical/legacy event types that require upcasting to current types.
	 * <p>
	 * Events registered via this method MUST be annotated with {@link org.sliceworkz.eventstore.events.LegacyEvent}
	 * and must specify an {@link org.sliceworkz.eventstore.events.Upcast} implementation to transform
	 * the legacy event to the current event type.
	 *
	 * @param rootClass the legacy event root class or sealed interface to register
	 * @return this serializer for method chaining
	 * @throws RuntimeException if legacy events are not properly annotated with upcast configuration
	 */
	EventPayloadSerializerDeserializer registerLegacyEventTypes ( Class<?> rootClass );

	/**
	 * Determines all legacy event types that upcast to the specified current types.
	 * <p>
	 * This method traces back the upcasting chain to find all historical event type names
	 * that should be included when querying for events of the current types. This ensures
	 * that queries match both current and legacy events.
	 *
	 * @param currentTypes the set of current event types to trace back
	 * @return the set containing both current types and all legacy types that upcast to them
	 */
	Set<EventType> determineLegacyTypes ( Set<EventType> currentTypes );

	/**
	 * Indicates whether this serializer/deserializer operates in typed mode.
	 * <p>
	 * Typed mode means events are mapped to/from Java objects using Jackson, providing type safety
	 * and supporting features like sealed interfaces, upcasting, and GDPR compliance. Raw mode
	 * works with JSON strings directly without type mapping.
	 * <p>
	 * This information is used for observability and metrics tagging to distinguish between
	 * typed and raw event streams.
	 *
	 * @return true if this is a typed serializer/deserializer, false if raw
	 */
	boolean isTyped ( );
	

	/**
	 * Creates a typed serializer/deserializer that maps events to/from Java objects.
	 * <p>
	 * This is the standard mode for type-safe event handling with full support for sealed interfaces,
	 * upcasting, and GDPR compliance features.
	 *
	 * @return a new typed serializer/deserializer instance
	 * @see TypedEventPayloadSerializerDeserializer
	 */
	public static EventPayloadSerializerDeserializer typed ( ) {
		return new TypedEventPayloadSerializerDeserializer();
	}

	/**
	 * Creates a raw serializer/deserializer that works with JSON strings directly.
	 * <p>
	 * This mode is useful for schema-less event processing where event types are not
	 * statically known. Events are deserialized as Jackson JsonNode objects.
	 *
	 * @return a new raw serializer/deserializer instance
	 * @see RawEventPayloadSerializerDeserializer
	 */
	public static EventPayloadSerializerDeserializer raw ( ) {
		return new RawEventPayloadSerializerDeserializer();
	}

	/**
	 * Container for an event type and its deserialized data object.
	 *
	 * @param type the event type
	 * @param eventData the deserialized event data (Java object for typed mode, JsonNode for raw mode)
	 */
	public record TypeAndPayload ( EventType type, Object eventData ) { }

	/**
	 * Container for an event type and its serialized JSON payloads.
	 * <p>
	 * The payload is split into immutable and erasable parts to support GDPR compliance.
	 * The erasable payload may be null if the event contains no erasable fields.
	 *
	 * @param type the event type
	 * @param immutablePayload the JSON string for immutable event data
	 * @param erasablePayload the JSON string for erasable event data (may be null)
	 */
	public record TypeAndSerializedPayload ( EventType type, String immutablePayload, String erasablePayload ) { }

}
