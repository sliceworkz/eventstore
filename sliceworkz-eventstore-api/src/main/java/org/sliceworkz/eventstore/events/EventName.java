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
 * Declares the name an event class is stored under, where that name cannot be its simple class name.
 * <p>
 * <b>Most event classes should not carry this annotation.</b> The intended setup is the plain one: the
 * class is called what the event is called, that name is the stored name, and there is one place to
 * read it. Annotating every event up front buys nothing — a string literal is exactly as permanent a
 * commitment as a class name, so the rename problem below is not avoided by it, only moved from the
 * class name to a literal that now has to be kept in step with the class name for as long as both
 * exist. Reach for the annotation when the class name and the stored name genuinely have to differ,
 * which is the two cases below, and nowhere else.
 * <p>
 * {@link EventType#of(Class)} is the one place a class is turned into a stored type name, and it is
 * {@link Class#getSimpleName()} unless the class carries this annotation. That name is wire format: it is
 * written into storage with every event, it is what an {@link org.sliceworkz.eventstore.query.EventTypesFilter}
 * matches on, and it is what a stream's type mappings are keyed by when an event is read back. So the
 * simple name has two consequences a class name does not normally have, and this annotation is the way
 * to opt out of both:
 * <ul>
 *   <li><b>Renaming an event class is a migration.</b> Every event already written keeps the old name,
 *       and a class claiming a new one no longer reads its own history. Annotating the renamed class
 *       with the name it was stored under keeps the history readable with no upcaster and no
 *       {@code UPDATE} on the events table:
 *       <pre>{@code
 *       @EventName("CustomerRegistered")
 *       record CustomerSignedUp ( String id, String name ) implements CustomerEvent { }
 *       }</pre></li>
 *   <li><b>Names are global to a storage, not scoped to a stream.</b> Two bounded contexts sharing a
 *       store cannot both write a {@code Created}: the rows are indistinguishable, so registering both
 *       on one stream fails and a read spanning both contexts resolves one context's payload with the
 *       other's class. Giving one of them a distinct stored name — {@code @EventName("OrderCreated")} —
 *       keeps the class name the domain wants and the stored name unique.</li>
 * </ul>
 * <p>
 * The name is exactly the string given: it is not qualified, prefixed or otherwise derived, and the
 * class name plays no part once the annotation is present. It must be non-blank and carry no leading
 * or trailing whitespace; a value that does not satisfy that fails with {@link IllegalArgumentException}
 * from {@link EventType#of(Class)}, which a stream's type registration reaches before anything is read
 * or written. Beyond that, treat the value the way you would a database column name: pick it once,
 * and keep it.
 * <p>
 * The annotation is not inherited, and it names one concrete event class. On a sealed interface in an
 * event hierarchy it only renames the alias under which
 * {@link org.sliceworkz.eventstore.query.EventTypesFilter#of(Class...)} expands that interface to its
 * permitted event classes, since an interface is never a stored type itself. It combines freely with
 * {@link LegacyEvent}: a legacy class can carry the stored name of the history it upcasts, whatever
 * the class is called.
 * <p>
 * What the annotation does <em>not</em> do is enforce uniqueness across contexts. Nothing can, since
 * streams are opened independently and a storage never sees every type mapping at once; a store that
 * hosts several contexts still needs its stored names unique by convention, this annotation being the
 * tool where a class name cannot be.
 *
 * @see EventType#of(Class)
 * @see LegacyEvent
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventName {

	/**
	 * The stored type name.
	 *
	 * @return the name the annotated class is stored under; non-blank, no leading or trailing whitespace
	 */
	String value();

}
