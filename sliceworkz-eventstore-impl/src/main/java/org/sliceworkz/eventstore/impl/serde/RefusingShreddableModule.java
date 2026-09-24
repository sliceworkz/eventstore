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

import org.sliceworkz.eventstore.shredding.Shreddable;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Teaches the mapper of a store with no {@link ShreddingCodec} to refuse a {@link Shreddable} rather
 * than write it as an ordinary record.
 * <p>
 * Registering an event type that declares a {@code Shreddable} component already fails at stream
 * creation, but that check reads declarations: it walks record components, type arguments and array
 * elements, and stops at a component declared as an interface or a non-record class, whose runtime
 * value it cannot know. A {@code Shreddable} reached only through such a component — an interface
 * over several party shapes, an {@code Object}-typed detail — registers without complaint, and without
 * this module Jackson would then serialize a {@link Shreddable.Present} as the record it is:
 * {@code {"value": ..., "subject": {...}}}, personal data in the clear under a subject that names whose
 * it is, with no key to destroy. The write is silent, and what a read makes of that JSON is whatever
 * Jackson makes of it behind that component — never a {@code Shreddable}, and never an error saying
 * what happened.
 * <p>
 * So a codec-less mapper carries a serializer keyed on the {@code Shreddable} type, exactly as the
 * sealing one is, which throws for every {@code Shreddable} it is handed. The append then fails as an
 * {@link org.sliceworkz.eventstore.events.EventSerializationException} with nothing stored — the same
 * outcome the registration check reaches earlier where it can. The message names the subject and never
 * the value. The raw serde carries it too, since a raw stream appends whatever object it is handed
 * through the same mapper.
 * <p>
 * The alternative — widening the registration walk into arbitrary classes — loses because it cannot be
 * made complete: an interface-typed component may hold any implementation on the classpath, and an
 * {@code Object} anything at all. A check on the value at the moment it is written is the only one that
 * sees what is actually there.
 * <p>
 * There is no deserializer here. A codec-less mapper is never asked to read a {@code Shreddable}-typed
 * value: the registration check keeps such declarations off the store, and a value stored behind an
 * interface or {@code Object} is read back as whatever Jackson makes of the JSON, never as a
 * {@code Shreddable}.
 *
 * @see ShreddableModule
 * @see AbstractEventPayloadSerializerDeserializer#declaresShreddable(Class)
 */
final class RefusingShreddableModule extends SimpleModule {

	private static final long serialVersionUID = 1L;

	@SuppressWarnings({ "rawtypes", "unchecked" })
	RefusingShreddableModule ( ) {
		super("sliceworkz-shreddable-refusing");
		addSerializer(Shreddable.class, (ValueSerializer) new RefusingSerializer());
	}

	private static final class RefusingSerializer extends ValueSerializer<Shreddable<?>> {

		@Override
		public void serialize ( Shreddable<?> value, JsonGenerator generator, SerializationContext context ) {
			throw new IllegalStateException(
					"cannot store a Shreddable value (subject %s): this store has no ShreddingCodec configured, so personal data would be written in the clear and could never be erased. The component holding it is declared as an interface or a non-record class, which is why registering the event type did not refuse it. Configure shredding on the storage builder, or via EventStore.on(storage).shredding(codec)."
							.formatted(value.subject()));
		}

	}

}
