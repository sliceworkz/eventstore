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
package org.sliceworkz.eventstore.benchmark.domain;

import org.sliceworkz.eventstore.shredding.Shreddable;

/**
 * Customer records, and the only context in the suite holding personal data.
 *
 * <p>It exists so that the <b>shredding</b> dimension measures something real rather than an
 * artificial field marked sensitive: a customer's contact details genuinely are personal data, a
 * genuine erasure request genuinely destroys the key, and the cost being measured -- an AES-GCM seal
 * on every append and an unseal (or a key-store lookup) on every read -- is the cost a real
 * deployment pays.
 *
 * <p><b>This context cannot be opened without a shredding codec configured.</b> Registering a type
 * that declares a {@code Shreddable} fails at {@code getEventStream}, before anything is read or
 * written, rather than storing personal data in the clear. That is why the other five contexts hold
 * none: shredding is a dimension the suite switches off, and a context that refuses to open without
 * it could not participate.
 *
 * <p>{@code customerId} is deliberately a customer number and never an email address. It becomes the
 * {@code DataSubject} id, is stored in the clear inside the sealed envelope, and survives erasure by
 * construction -- so a personal identifier there would defeat the whole mechanism.
 */
public sealed interface CrmEvent {

	record CustomerRegistered ( String customerId, Shreddable<ContactDetails> details, String segment ) implements CrmEvent { }

	record CustomerAddressChanged ( String customerId, Shreddable<Address> address ) implements CrmEvent { }

	record NewsletterSubscribed ( String customerId, String topic ) implements CrmEvent { }

	record NewsletterUnsubscribed ( String customerId, String topic, String reason ) implements CrmEvent { }
}
