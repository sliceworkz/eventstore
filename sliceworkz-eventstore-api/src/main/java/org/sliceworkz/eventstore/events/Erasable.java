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
package org.sliceworkz.eventstore.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or record component containing personal data that can be completely erased for GDPR compliance.
 * <p>
 * Under regulations like GDPR (General Data Protection Regulation), individuals have the "right to be forgotten,"
 * requiring organizations to delete personal data upon request. However, in event-sourced systems, events are
 * immutable historical facts that should not be deleted or modified. This annotation provides a mechanism to
 * identify which fields contain personal data that can be erased while preserving the event structure.
 * <p>
 * When a field is marked with {@code @Erasable}, it indicates that the field's value can be replaced with
 * a sentinel value (such as null or a placeholder like "ERASED") without losing the semantic meaning of
 * the event. The event itself remains in the store with its timestamp, type, and other non-personal metadata
 * intact, but the personal data is removed.
 * <p>
 * Key characteristics:
 * <ul>
 *   <li>Applied to fields or record components containing personal data</li>
 *   <li>Indicates data that can be completely removed without breaking event semantics</li>
 *   <li>Works with both class fields and record components (Java 14+)</li>
 *   <li>Can be used as a meta-annotation on custom GDPR annotations</li>
 *   <li>Complemented by {@link PartlyErasable} for nested objects with mixed erasability</li>
 * </ul>
 *
 * <h2>GDPR Right to Erasure (Right to be Forgotten):</h2>
 * GDPR Article 17 grants individuals the right to have their personal data erased under certain conditions.
 * This annotation helps identify which event fields contain such data, enabling:
 * <ul>
 *   <li>Automated discovery of personal data fields via reflection</li>
 *   <li>Selective data erasure while maintaining event immutability</li>
 *   <li>Audit trails showing what data was erased and when</li>
 *   <li>Compliance reporting for data protection authorities</li>
 * </ul>
 *
 * <h2>Basic Example:</h2>
 * <pre>{@code
 * public record CustomerRegistered(
 *     String customerId,           // Not erasable - needed for event correlation
 *
 *     @Erasable
 *     String name,                 // Can be erased - personal data
 *
 *     @Erasable
 *     String email,                // Can be erased - personal data
 *
 *     LocalDateTime registeredAt   // Not erasable - temporal metadata
 * ) implements CustomerEvent {}
 *
 * // After erasure request, the event becomes:
 * // CustomerRegistered(customerId="123", name=null, email=null, registeredAt=2024-01-15T10:30:00)
 * }</pre>
 *
 * <h2>Nested Objects with PartlyErasable:</h2>
 * <pre>{@code
 * public record Address(
 *     @Erasable
 *     String street,        // Personal data
 *
 *     @Erasable
 *     String houseNumber,   // Personal data
 *
 *     String zipCode        // Not personal in isolation
 * ) {}
 *
 * public record CustomerRegistered(
 *     String customerId,
 *
 *     @Erasable
 *     String name,
 *
 *     @PartlyErasable       // This Address contains erasable fields
 *     Address address
 * ) implements CustomerEvent {}
 * }</pre>
 *
 * <h2>Custom Meta-Annotation:</h2>
 * <pre>{@code
 * // Define domain-specific GDPR annotation
 * @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
 * @Retention(RetentionPolicy.RUNTIME)
 * @Erasable  // Meta-annotation
 * public @interface PersonalData {
 *     String category();
 *     String purpose();
 * }
 *
 * // Use in events
 * public record CustomerRegistered(
 *     String customerId,
 *
 *     @PersonalData(category = "IDENTITY", purpose = "Customer identification")
 *     String name,
 *
 *     @PersonalData(category = "CONTACT", purpose = "Communication")
 *     String email
 * ) implements CustomerEvent {}
 * }</pre>
 *
 * <h2>Discovering Erasable Fields via Reflection:</h2>
 * <pre>{@code
 * public List<Field> findErasableFields(Class<?> eventClass) {
 *     return Arrays.stream(eventClass.getDeclaredFields())
 *         .filter(field -> field.isAnnotationPresent(Erasable.class) ||
 *                         hasMetaAnnotation(field, Erasable.class))
 *         .toList();
 * }
 *
 * private boolean hasMetaAnnotation(Field field, Class<? extends Annotation> metaAnnotation) {
 *     return Arrays.stream(field.getAnnotations())
 *         .anyMatch(ann -> ann.annotationType().isAnnotationPresent(metaAnnotation));
 * }
 * }</pre>
 *
 * <h2>Implementation Approach:</h2>
 * <p>
 * The event store can implement erasure by:
 * <ol>
 *   <li>Reading the event from storage</li>
 *   <li>Using reflection to find all {@code @Erasable} fields</li>
 *   <li>Setting those fields to null or a sentinel value like "ERASED"</li>
 *   <li>Persisting the modified JSON representation back to storage</li>
 *   <li>Recording the erasure operation in an audit log</li>
 * </ol>
 * <p>
 * Alternatively, erasure can be performed at the serialization level using Jackson's {@code @JsonView}
 * or custom serializers that exclude or redact erasable fields.
 *
 * @see PartlyErasable
 * @see Event
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Erasable {

}