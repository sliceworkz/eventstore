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

/**
 * A customer's contact details. Personal data: it only ever appears inside a
 * {@link org.sliceworkz.eventstore.shredding.Shreddable}, never bare on an event.
 *
 * <p>Note what is <em>not</em> here: the customer id. That is the subject identifier, it is stored in
 * the clear in the sealed envelope, and it survives erasure by construction -- so it has to be a
 * customer number and never an email address.
 */
public record ContactDetails ( String fullName, String email, String phone, Address address ) { }
