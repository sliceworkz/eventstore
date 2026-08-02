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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the stored name of an event type, decoupling it from the Java class identifier.
 * <p>
 * Without this annotation the name written to storage is {@link Class#getSimpleName()}. That makes the
 * class name a wire-format commitment that looks like an ordinary refactor to every IDE: rename the class
 * and every event already written keeps the old name, with nothing left to map it back onto. It also makes
 * the simple name globally significant within a storage, so two bounded contexts cannot both own a
 * {@code Created} or a {@code StatusChanged}.
 * <p>
 * {@code @EventName} fixes both. The name is explicit, greppable, and independent of where the class lives:
 *
 * <pre>{@code
 * @EventName("sales.OrderCreated")
 * record Created(String orderId, int amountCents) implements SalesEvent { }
 *
 * @EventName("hr.VacancyCreated")
 * record Created(String vacancyId, String department) implements HrEvent { }
 * }</pre>
 *
 * <h2>Renaming a class: use an alias</h2>
 * Stored events are immutable, so a class rename has to be absorbed on the read side. Declare the old name
 * as an alias and history keeps deserializing:
 *
 * <pre>{@code
 * // was: record CustomerRegistered(String name) implements CustomerEvent { }
 * @EventName(value = "CustomerOnboarded", aliases = "CustomerRegistered")
 * record CustomerOnboarded(String name) implements CustomerEvent { }
 * }</pre>
 *
 * Aliases are <strong>read-only</strong>. New events are always written under {@link #value()}; an alias is
 * only ever matched against names already in storage. On an event read through an alias,
 * {@link Event#storedType()} reports the alias and {@link Event#type()} reports the canonical name, exactly
 * as for an upcast. Queries filtering on the class match both: a query for {@code CustomerOnboarded} also
 * selects events stored as {@code CustomerRegistered}.
 *
 * <h2>Alias or upcast?</h2>
 * <ul>
 *   <li><strong>Alias</strong> — the <em>name</em> changed, the <em>shape</em> did not. The old JSON still
 *       deserializes onto the current record as-is. One annotation edit; no extra class, no upcaster.</li>
 *   <li><strong>{@link LegacyEvent} + {@link Upcast}</strong> — the <em>shape</em> changed: components added,
 *       removed, retyped or restructured, or one event split into several. The old JSON no longer fits the
 *       current record, so a class describing the old shape must survive to deserialize it, and an upcaster
 *       converts the result.</li>
 * </ul>
 * A rename that also reshapes the payload is an upcast, not an alias. If in doubt: can the bytes already in
 * storage be read straight onto today's record? If yes it is an alias.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>The name must not be blank, and neither must any alias.</li>
 *   <li>An alias must differ from {@link #value()}.</li>
 *   <li>Within one set of registered event classes every name — canonical or alias — must be unique.
 *       Registering two classes that claim the same name fails at stream creation, naming both classes.</li>
 *   <li>Not {@code @Inherited}: event types are records and sealed-interface implementations, so there is no
 *       superclass chain to inherit through, and silently sharing a stored name between two classes is
 *       precisely the hazard this annotation exists to remove. Annotate each event type itself.</li>
 * </ul>
 *
 * <h2>Compatibility</h2>
 * A class without this annotation keeps the exact behaviour it had before the annotation existed: its stored
 * name is its simple name. Adopting {@code @EventName} on a class that already has history in storage
 * requires listing the previous name as an alias — otherwise those events become unreadable.
 *
 * @see EventType
 * @see LegacyEvent
 * @see Upcast
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventName {

	/**
	 * The canonical stored name for this event type.
	 * <p>
	 * This is the name written to storage for every new event of this type, the name matched by
	 * {@link org.sliceworkz.eventstore.query.EventTypesFilter}, and the name reported by
	 * {@link Event#type()}.
	 *
	 * @return the stored event type name (must not be blank)
	 */
	String value();

	/**
	 * Additional names this event type answers to when reading, typically names it was stored under before
	 * a rename.
	 * <p>
	 * Aliases are never written. They exist so that events already in storage under a previous name keep
	 * deserializing onto the current class, and so that queries filtering on the current class also select
	 * them.
	 *
	 * @return the read-only alias names (each must be non-blank and must differ from {@link #value()})
	 */
	String[] aliases() default {};

}
