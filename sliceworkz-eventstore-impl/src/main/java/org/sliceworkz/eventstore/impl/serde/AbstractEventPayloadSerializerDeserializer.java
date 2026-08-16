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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

import org.sliceworkz.eventstore.events.EventSerializationException;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base class for event payload serializers, holding the single Jackson mapper every event is written
 * through and the optional {@link ShreddingCodec} that protects {@link Shreddable} values.
 *
 * <h2>One document, not two</h2>
 * An event is serialized to a single JSON document. Personal data is not held apart from the rest of
 * the payload; it sits in place, encrypted, as a sealed envelope written by {@link ShreddableModule}.
 * <p>
 * This replaces an earlier design that split every payload across two documents — one for
 * {@code @Erasable} fields, one for everything else — reconciled on read by a deep merge, with erasure
 * performed by an operator nulling the erasable column. That design had three problems the split itself
 * caused: the merge replaced JSON arrays wholesale, so a collection of partly-personal elements silently
 * lost its non-personal fields on every ordinary read; nulling the column left validating records
 * permanently unreadable; and "erased" was indistinguishable from "never held any personal data".
 *
 * <h2>Legacy reads</h2>
 * Events written under that design are still readable. When a stored event carries a non-null erasable
 * payload, both documents are parsed and {@link #deepMerge(ObjectNode, ObjectNode) deep-merged} before
 * being bound to the record, exactly as before. Nothing writes a second document any more, so the
 * merge path only ever sees events that predate this change — including ones whose erasable data an
 * operator has already nulled, which still read back with those fields absent.
 *
 * @see ShreddableModule
 * @see TypedEventPayloadSerializerDeserializer
 * @see RawEventPayloadSerializerDeserializer
 */
public abstract class AbstractEventPayloadSerializerDeserializer implements EventPayloadSerializerDeserializer {

	/**
	 * The one mapper every payload is read and written through.
	 * <p>
	 * {@code FAIL_ON_UNKNOWN_PROPERTIES} is enabled explicitly: it defaulted to enabled in Jackson 2.x
	 * and is disabled by default in Jackson 3.x, and the store relies on it to reject events whose
	 * serialized form cannot round-trip back onto the record.
	 */
	protected final JsonMapper objectMapper;

	/**
	 * Seals and unseals {@link Shreddable} values, or null on a store with no shredding configured — in
	 * which case registering an event type that declares a {@code Shreddable} component fails.
	 */
	protected final ShreddingCodec shreddingCodec;

	/**
	 * Builds a serde with no shredding support. Event types declaring a {@link Shreddable} component
	 * cannot be registered on it.
	 */
	protected AbstractEventPayloadSerializerDeserializer ( ) {
		this(null);
	}

	/**
	 * @param shreddingCodec seals and unseals protected values, or null for a store without shredding
	 */
	protected AbstractEventPayloadSerializerDeserializer ( ShreddingCodec shreddingCodec ) {
		this.shreddingCodec = shreddingCodec;

		JsonMapper.Builder builder = JsonMapper.builder()
				.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

		ShreddableModule shreddableModule = null;
		if ( shreddingCodec != null ) {
			shreddableModule = new ShreddableModule(shreddingCodec);
			builder.addModule(shreddableModule);
		}

		this.objectMapper = builder.build();

		if ( shreddableModule != null ) {
			// The module needs the mapper it was registered on in order to convert a protected value to
			// and from JSON, and that mapper does not exist until the line above has run.
			shreddableModule.bindMapper(objectMapper);
		}
	}

	@Override
	public TypeAndSerializedPayload serialize ( Object payload ) {
		EventType eventType = payload == null ? null : EventType.of(payload);
		ShreddableModule.beginCollectingSealedKeys();
		try {
			// The second payload is always null: nothing writes the immutable/erasable split any more.
			// The column stays for the events that were written with it.
			String json = objectMapper.writeValueAsString(payload);
			return new TypeAndSerializedPayload(eventType, json, null, ShreddableModule.collectedSealedKeys());

		} catch (Exception e) {
			// The payload class is named explicitly rather than left to the cause: a Jackson failure
			// reports the field path it choked on, which is only half of "which event cannot be stored".
			throw new EventSerializationException(eventType,
					"Failed to serialize event data for type '%s' (%s): %s".formatted(
							eventType == null ? "?" : eventType.name(),
							payload == null ? "null payload" : payload.getClass().getName(),
							e.getMessage()),
					e);

		} finally {
			ShreddableModule.stopCollectingSealedKeys();
		}
	}

	/**
	 * Merges a legacy erasable document into its immutable counterpart.
	 * <p>
	 * Only reached for events written before payloads became a single document. Note the array
	 * behaviour, which is the defect that made the split untenable: an array in the source replaces the
	 * one in the target wholesale rather than merging element by element, so a collection whose elements
	 * held both personal and non-personal fields came back with the non-personal ones gone. It is kept
	 * exactly as it was, because changing it now would read historical events differently from the way
	 * they have always been read.
	 *
	 * @param target the immutable document, mutated in place
	 * @param source the erasable document to merge into it
	 */
	protected void deepMerge ( ObjectNode target, ObjectNode source ) {
		source.properties().forEach(entry -> {
			String key = entry.getKey();
			var value = entry.getValue();

			if ( value.isObject() && target.has(key) && target.get(key).isObject() ) {
				// Recursively merge nested objects
				deepMerge((ObjectNode) target.get(key), (ObjectNode) value);
			} else {
				// Replace or add the field
				target.set(key, value);
			}
		});
	}

	/**
	 * Whether an event class holds personal data anywhere in its payload, and therefore needs a
	 * {@link ShreddingCodec} to be stored at all.
	 * <p>
	 * Used to fail at stream creation rather than at the first append: a store with no shredding
	 * configured that accepted such an event would write personal data in the clear, with no key to
	 * destroy and nothing to say so.
	 * <p>
	 * The walk covers what the serializer can actually protect — record components, the type arguments
	 * of generic ones, and the element types of containers — and deliberately does not descend into
	 * arbitrary non-record classes, whose fields Jackson may not serialize at all.
	 *
	 * @param clazz the event class to inspect
	 * @return true if a {@link Shreddable} appears anywhere in its payload
	 */
	public static boolean declaresShreddable ( Class<?> clazz ) {
		return declaresShreddable(clazz, new HashSet<>());
	}

	private static boolean declaresShreddable ( Type type, Set<Type> visited ) {
		if ( type == null || !visited.add(type) ) {
			return false;
		}

		if ( type instanceof ParameterizedType parameterized ) {
			if ( declaresShreddable(parameterized.getRawType(), visited) ) {
				return true;
			}
			for ( Type argument : parameterized.getActualTypeArguments() ) {
				if ( declaresShreddable(argument, visited) ) {
					return true;
				}
			}
			return false;
		}

		if ( type instanceof Class<?> clazz ) {
			if ( Shreddable.class.isAssignableFrom(clazz) ) {
				return true;
			}
			if ( clazz.isArray() ) {
				return declaresShreddable(clazz.getComponentType(), visited);
			}
			if ( clazz.isRecord() ) {
				for ( RecordComponent component : clazz.getRecordComponents() ) {
					if ( declaresShreddable(component.getGenericType(), visited) ) {
						return true;
					}
				}
			}
		}

		return false;
	}

}
