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

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.sliceworkz.eventstore.shredding.DataSubject;
import org.sliceworkz.eventstore.shredding.KeyId;
import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingCodec.Sealed;
import org.sliceworkz.eventstore.shredding.ShreddingException;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Teaches a Jackson mapper to read and write {@link Shreddable} values as sealed envelopes.
 * <p>
 * A present value is serialized to its own JSON, encrypted by the {@link ShreddingCodec} under the key
 * for its {@link DataSubject}, and written in place of the value:
 * <pre>{@code
 * "from": { "alg": "A256GCM",
 *           "dek": "k-7f2a91c4",
 *           "sub": { "type": "customer", "id": "alice-42", "category": "default" },
 *           "iv":  "yQ3mR1…",
 *           "ct":  "8Kd2vRhT…" }
 * }</pre>
 * Reading reverses it, and a key that no longer resolves yields {@link Shreddable.Shredded} rather than
 * a failure.
 * <p>
 * Because this is an ordinary Jackson serializer keyed on the {@code Shreddable} type, it applies
 * wherever one appears — a top-level record component, several levels down a nested record, inside a
 * {@code List}, as a {@code Map} value. That is the whole reason the wrapper replaced the annotation
 * plus view-based document split, which had to reconcile two documents on read and silently dropped
 * the non-personal fields of collection elements doing it.
 *
 * <h2>Why the mapper is bound after construction</h2>
 * Sealing a value means serializing it to a string first, and unsealing means parsing one back — both
 * need the very mapper this module is being registered on, which does not exist yet while the module is
 * being built. The mapper is therefore handed back in through {@link #bindMapper(JsonMapper)} as soon as
 * it has been constructed. Using the same mapper rather than a private one is deliberate: it keeps a
 * protected value's own serialization identical to what it would have been unprotected, and it lets a
 * {@code Shreddable} nested inside a protected value work like any other.
 *
 * @see Shreddable
 * @see ShreddingCodec
 */
public final class ShreddableModule extends SimpleModule {

	private static final long serialVersionUID = 1L;

	private static final String FIELD_ALGORITHM = "alg";
	private static final String FIELD_KEY = "dek";
	private static final String FIELD_SUBJECT = "sub";
	private static final String FIELD_SUBJECT_TYPE = "type";
	private static final String FIELD_SUBJECT_ID = "id";
	private static final String FIELD_SUBJECT_CATEGORY = "category";
	private static final String FIELD_IV = "iv";
	private static final String FIELD_CIPHERTEXT = "ct";

	/**
	 * The keys sealed under during the serialization currently running on this thread.
	 * <p>
	 * The append path tags each event with the keys its payload was sealed under, which is what makes
	 * "every event holding data for this key" an ordinary tag query. Only the serializer knows which
	 * keys those were, and it is several Jackson frames below the call that needs them, so they are
	 * collected here for the duration of one {@code serialize} call. Serializing a payload happens
	 * entirely on the calling thread, and the collection is installed and removed by that same call, so
	 * nothing leaks between events or between threads.
	 */
	private static final ThreadLocal<Set<KeyId>> SEALED_KEYS = new ThreadLocal<>();

	private final ShreddingCodec codec;

	/**
	 * The mapper this module was registered on, published after construction. Volatile because the
	 * thread that builds the mapper is not necessarily the thread that first serializes through it.
	 */
	private volatile JsonMapper mapper;

	/**
	 * @param codec seals and unseals the values; must not be null
	 * @throws IllegalArgumentException if the codec is null
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public ShreddableModule ( ShreddingCodec codec ) {
		super("sliceworkz-shreddable");
		if ( codec == null ) {
			throw new IllegalArgumentException("codec cannot be null");
		}
		this.codec = codec;
		addSerializer(Shreddable.class, (ValueSerializer) new ShreddableSerializer());
		addDeserializer(Shreddable.class, (ValueDeserializer) new ShreddableDeserializer(null));
	}

	/**
	 * Hands this module the mapper it was registered on, so that protected values can be converted to
	 * and from JSON exactly as they would be if they were not protected.
	 *
	 * @param mapper the mapper this module belongs to
	 */
	public void bindMapper ( JsonMapper mapper ) {
		this.mapper = mapper;
	}

	/**
	 * Starts collecting the keys sealed under on this thread, for the duration of one serialization.
	 */
	static void beginCollectingSealedKeys ( ) {
		SEALED_KEYS.set(new LinkedHashSet<>());
	}

	/**
	 * The keys sealed under since {@link #beginCollectingSealedKeys()}, in the order they were first used.
	 *
	 * @return the key ids, empty if nothing was sealed
	 */
	static Set<KeyId> collectedSealedKeys ( ) {
		Set<KeyId> collected = SEALED_KEYS.get();
		return collected == null ? Set.of() : Set.copyOf(collected);
	}

	/**
	 * Stops collecting and releases the thread local, whether the serialization succeeded or not.
	 */
	static void stopCollectingSealedKeys ( ) {
		SEALED_KEYS.remove();
	}

	private static void recordSealedKey ( KeyId keyId ) {
		Set<KeyId> collected = SEALED_KEYS.get();
		if ( collected != null ) {
			collected.add(keyId);
		}
	}

	private JsonMapper mapper ( ) {
		JsonMapper current = mapper;
		if ( current == null ) {
			throw new IllegalStateException("ShreddableModule was used before its mapper was bound; this is a bug in the serde setup");
		}
		return current;
	}

	/**
	 * Writes a present value as a sealed envelope.
	 * <p>
	 * A {@link Shreddable.Shredded} cannot be written: there is no plaintext left to seal, and inventing
	 * one would replace erased personal data with a placeholder that later reads could not tell from the
	 * real thing. Re-appending an event whose personal data has been erased is a programming error, and
	 * it fails here rather than quietly writing a hole.
	 */
	private final class ShreddableSerializer extends ValueSerializer<Shreddable<?>> {

		@Override
		public void serialize ( Shreddable<?> value, JsonGenerator generator, SerializationContext context ) {
			if ( value instanceof Shreddable.Shredded<?> shredded ) {
				throw new IllegalArgumentException(
						"cannot append a value whose personal data has already been erased (subject %s, key %s). Reading an event, erasing its subject and appending it again would store a placeholder indistinguishable from real data; build the new event from data you still hold."
								.formatted(shredded.subject(), shredded.key()));
			}

			Shreddable.Present<?> present = (Shreddable.Present<?>) value;
			String plaintext = mapper().writeValueAsString(present.value());
			Sealed sealed = codec.seal(plaintext, present.subject());
			recordSealedKey(sealed.key());

			generator.writeStartObject();
			generator.writeStringProperty(FIELD_ALGORITHM, sealed.alg());
			generator.writeStringProperty(FIELD_KEY, sealed.key().value());
			generator.writeName(FIELD_SUBJECT);
			generator.writeStartObject();
			generator.writeStringProperty(FIELD_SUBJECT_TYPE, sealed.subject().type());
			generator.writeStringProperty(FIELD_SUBJECT_ID, sealed.subject().id());
			generator.writeStringProperty(FIELD_SUBJECT_CATEGORY, sealed.subject().category());
			generator.writeEndObject();
			generator.writeStringProperty(FIELD_IV, sealed.iv());
			generator.writeStringProperty(FIELD_CIPHERTEXT, sealed.ciphertext());
			generator.writeEndObject();
		}

	}

	/**
	 * Reads a sealed envelope back, decrypting it when the key still exists.
	 * <p>
	 * The value type comes from {@link #createContextual}: a {@code Shreddable<PartyDetails>} has to know
	 * to parse the decrypted JSON as a {@code PartyDetails}, and that is only knowable from the declared
	 * type at the point of use.
	 */
	private final class ShreddableDeserializer extends ValueDeserializer<Shreddable<?>> {

		private final JavaType valueType;

		private ShreddableDeserializer ( JavaType valueType ) {
			this.valueType = valueType;
		}

		@Override
		public ValueDeserializer<?> createContextual ( DeserializationContext context, BeanProperty property ) {
			JavaType shreddableType = shreddableTypeOf(context.getContextualType());
			if ( shreddableType == null && property != null ) {
				shreddableType = shreddableTypeOf(property.getType());
			}
			if ( shreddableType == null || shreddableType.containedTypeCount() == 0 ) {
				// A raw Shreddable, with no type argument to parse the decrypted JSON as. Left unbound so
				// the failure names the property rather than the whole event, at the point it is read.
				return new ShreddableDeserializer(null);
			}
			return new ShreddableDeserializer(shreddableType.containedType(0));
		}

		/**
		 * Finds the {@code Shreddable<...>} inside a declared type, descending through the container
		 * types Jackson resolves element-by-element — {@code List<Shreddable<T>>},
		 * {@code Map<String, Shreddable<T>>}, and arrays.
		 */
		private JavaType shreddableTypeOf ( JavaType type ) {
			if ( type == null ) {
				return null;
			}
			if ( Shreddable.class.isAssignableFrom(type.getRawClass()) ) {
				return type;
			}
			JavaType contentType = type.getContentType();
			return contentType == null ? null : shreddableTypeOf(contentType);
		}

		@Override
		public Shreddable<?> deserialize ( JsonParser parser, DeserializationContext context ) {
			JsonNode node = context.readTree(parser);

			if ( !node.isObject() || !node.has(FIELD_ALGORITHM) || !node.has(FIELD_CIPHERTEXT) ) {
				// Not a sealed envelope. The realistic cause is a record component that used to be a plain
				// (or @Erasable) field and is now declared Shreddable: events written before the change
				// hold the bare value here. Nothing can be inferred from it -- least of all whose data it
				// is -- so this fails rather than guessing a subject and quietly leaving old personal data
				// unprotected and unerasable.
				throw new IllegalStateException(
						"expected a sealed value but found %s. An event written before this component became Shreddable cannot be read as one: migrate the stored events (EventStoreImporter.transform) or read the old shape through a @LegacyEvent and upcast it."
								.formatted(node.isObject() ? "a JSON object with no '%s'/'%s'".formatted(FIELD_ALGORITHM, FIELD_CIPHERTEXT) : node.getNodeType()));
			}

			Sealed sealed = new Sealed(
					node.get(FIELD_ALGORITHM).asString(),
					KeyId.of(node.get(FIELD_KEY).asString()),
					subjectOf(node.get(FIELD_SUBJECT)),
					node.get(FIELD_IV).asString(),
					node.get(FIELD_CIPHERTEXT).asString());

			Optional<String> plaintext = codec.unseal(sealed);
			if ( plaintext.isEmpty() ) {
				return new Shreddable.Shredded<>(sealed.subject(), sealed.key());
			}

			if ( valueType == null ) {
				throw new IllegalStateException(
						"cannot read a raw Shreddable: declare the component as Shreddable<YourType> so the decrypted value can be parsed");
			}
			return new Shreddable.Present<>(mapper().readValue(plaintext.get(), valueType), sealed.subject());
		}

		private DataSubject subjectOf ( JsonNode node ) {
			if ( node == null || !node.isObject() ) {
				throw new ShreddingException(
						"a sealed value carries no readable subject; the envelope is malformed and the value cannot be attributed or erased");
			}
			return new DataSubject(
					node.get(FIELD_SUBJECT_TYPE).asString(),
					node.get(FIELD_SUBJECT_ID).asString(),
					node.get(FIELD_SUBJECT_CATEGORY).asString());
		}

	}

}
