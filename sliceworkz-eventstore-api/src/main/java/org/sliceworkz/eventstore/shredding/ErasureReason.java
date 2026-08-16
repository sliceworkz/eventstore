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
package org.sliceworkz.eventstore.shredding;

/**
 * Why a {@link DataSubject}'s keys were destroyed, recorded alongside the shredded key.
 * <p>
 * This is the audit trail. Shredding leaves the events untouched — the ciphertext stays byte-identical
 * forever — so the key store row is the only place that records that an erasure happened, when, and on
 * what authority. Under GDPR Article 17 the erasure itself is the obligation and the accountability
 * principle of Article 5(2) is what makes the record worth keeping, so write something a data
 * protection officer could act on rather than {@code "erased"}.
 * <pre>{@code
 * eventStore.erase(DataSubject.of("customer", "alice-42"),
 *                  ErasureReason.of("GDPR art.17 erasure request #4711, approved by DPO 2026-08-16"));
 * }</pre>
 * A {@link ShreddingKeyStore} is expected to persist this next to the shredded key, never to interpret it.
 *
 * @param value free text describing the authority for the erasure
 *
 * @see org.sliceworkz.eventstore.EventStore#erase(DataSubject, ErasureReason)
 * @see ErasureReport
 */
public record ErasureReason ( String value ) {

	/**
	 * @throws IllegalArgumentException if the value is null or blank — an erasure with no recorded
	 *                                  reason is exactly the audit gap this type exists to close
	 */
	public ErasureReason {
		if ( value == null || value.isBlank() ) {
			throw new IllegalArgumentException("ErasureReason value must not be null or blank: record why the data was erased");
		}
	}

	/**
	 * @param value free text describing the authority for the erasure
	 * @return the reason
	 */
	public static ErasureReason of ( String value ) {
		return new ErasureReason(value);
	}

	@Override
	public String toString ( ) {
		return value;
	}

}
