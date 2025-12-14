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

import com.fasterxml.jackson.annotation.JsonView;

/**
 * Marks a field containing a nested object that has some fields annotated with {@link Erasable}.
 * <p>
 * While {@link Erasable} marks fields that can be completely removed, {@code @PartlyErasable} indicates
 * that a field contains a complex object (such as a record, class, or collection) where only certain
 * nested fields should be erased for GDPR compliance. This annotation serves as a marker to indicate
 * that erasure logic needs to recurse into the nested object to find and erase the appropriate fields.
 * <p>
 * This annotation integrates with Jackson's {@code @JsonView} mechanism to enable selective serialization
 * of event data. When events are serialized for erasure purposes, Jackson can use the {@code PartlyErasable}
 * view to include only non-erasable fields, effectively redacting personal data during JSON generation.
 * <p>
 * Key use cases:
 * <ul>
 *   <li>Complex value objects containing both personal and non-personal data</li>
 *   <li>Address records where street/number are personal but zip code is not</li>
 *   <li>Nested objects in event hierarchies requiring selective field erasure</li>
 *   <li>Collections of objects where elements contain erasable fields</li>
 * </ul>
 *
 * <h2>Basic Example - Address Value Object:</h2>
 * <pre>{@code
 * public record Address(
 *     @Erasable
 *     String street,           // Personal data - erasable
 *
 *     @Erasable
 *     String houseNumber,      // Personal data - erasable
 *
 *     String zipCode,          // Not personal in isolation - not erasable
 *
 *     String country           // Geographic data - not erasable
 * ) {}
 *
 * public record CustomerRegistered(
 *     String customerId,
 *
 *     @Erasable
 *     String name,
 *
 *     @PartlyErasable          // Address contains some erasable fields
 *     Address address
 * ) implements CustomerEvent {}
 *
 * // Before erasure:
 * // CustomerRegistered(
 * //   customerId="123",
 * //   name="John Doe",
 * //   address=Address(street="Main St", houseNumber="42", zipCode="12345", country="USA")
 * // )
 *
 * // After erasure:
 * // CustomerRegistered(
 * //   customerId="123",
 * //   name=null,
 * //   address=Address(street=null, houseNumber=null, zipCode="12345", country="USA")
 * // )
 * }</pre>
 *
 * <h2>Multi-Level Nesting:</h2>
 * <pre>{@code
 * public record ContactInfo(
 *     @Erasable
 *     String email,
 *
 *     @Erasable
 *     String phone
 * ) {}
 *
 * public record Person(
 *     @Erasable
 *     String name,
 *
 *     @PartlyErasable
 *     ContactInfo contactInfo
 * ) {}
 *
 * public record CustomerRegistered(
 *     String customerId,
 *
 *     @PartlyErasable          // Person contains nested PartlyErasable ContactInfo
 *     Person person
 * ) implements CustomerEvent {}
 * }</pre>
 *
 * <h2>Collections of Partly Erasable Objects:</h2>
 * <pre>{@code
 * public record OrderLine(
 *     String productId,
 *     int quantity,
 *
 *     @Erasable
 *     String specialInstructions  // Customer's personal notes
 * ) {}
 *
 * public record OrderPlaced(
 *     String orderId,
 *     String customerId,
 *
 *     @PartlyErasable
 *     List<OrderLine> orderLines  // Each OrderLine contains erasable fields
 * ) implements OrderEvent {}
 * }</pre>
 *
 * <h2>Integration with Jackson JsonView:</h2>
 * <p>
 * The {@code @PartlyErasable} annotation is meta-annotated with {@code @JsonView(PartlyErasable.class)},
 * enabling integration with Jackson's view mechanism for selective serialization:
 * <pre>{@code
 * // Serialize event excluding erasable fields
 * ObjectMapper mapper = new ObjectMapper();
 * String jsonWithoutPersonalData = mapper
 *     .writerWithView(PartlyErasable.class)
 *     .writeValueAsString(event);
 *
 * // This JSON will exclude all @Erasable fields while preserving structure
 * }</pre>
 *
 * <h2>Discovering Partly Erasable Fields via Reflection:</h2>
 * <pre>{@code
 * public void erasePersonalData(Object event) throws Exception {
 *     for (Field field : event.getClass().getDeclaredFields()) {
 *         field.setAccessible(true);
 *
 *         if (field.isAnnotationPresent(Erasable.class)) {
 *             // Completely erase this field
 *             field.set(event, null);
 *         }
 *         else if (field.isAnnotationPresent(PartlyErasable.class)) {
 *             // Recurse into nested object to erase its @Erasable fields
 *             Object nested = field.get(event);
 *             if (nested != null) {
 *                 erasePersonalData(nested);  // Recursive call
 *             }
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Design Rationale:</h2>
 * <p>
 * The distinction between {@code @Erasable} and {@code @PartlyErasable} enables:
 * <ul>
 *   <li><b>Semantic Clarity:</b> Makes it explicit which fields need recursive processing</li>
 *   <li><b>Performance Optimization:</b> Avoids unnecessary reflection on simple fields</li>
 *   <li><b>Tooling Support:</b> Enables IDE warnings if nested objects lack appropriate annotations</li>
 *   <li><b>Jackson Integration:</b> Leverages existing JSON view mechanisms for serialization</li>
 *   <li><b>Audit Compliance:</b> Clear documentation of data structures containing personal data</li>
 * </ul>
 *
 * @see Erasable
 * @see Event
 * @see com.fasterxml.jackson.annotation.JsonView
 */
@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@JsonView(PartlyErasable.class) // we just reuse the Erasable class
public @interface PartlyErasable {

}