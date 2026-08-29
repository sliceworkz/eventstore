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
 * An amount in minor units with its currency, as a payload component rather than a domain concept:
 * it exists so that events carry a nested record instead of a flat bag of scalars, because a nested
 * record is what a real payload looks like and it costs the serializer more than a {@code long}.
 *
 * @param cents  the amount in minor units, which may be negative for a refund
 * @param currency  ISO 4217 code
 */
public record Money ( long cents, String currency ) {

	public static Money euro ( long cents ) {
		return new Money(cents, "EUR");
	}
}
