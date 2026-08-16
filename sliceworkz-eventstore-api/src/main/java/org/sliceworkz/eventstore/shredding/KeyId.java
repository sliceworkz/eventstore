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
 * Names the key a {@link Shreddable} value was sealed under. Opaque to this library.
 * <p>
 * The value is written into the sealed envelope of every event it protects, and is carried on the
 * event as a {@code dek:} tag so that "every event under this key" is an ordinary tag query served by
 * the existing index. It is never interpreted here: a {@link ShreddingKeyStore} is free to make it a
 * random identifier, a KMS ARN, a Vault path or a key version.
 *
 * <h2>It must be random, never derived from the subject</h2>
 * The {@code dek:} tag is stored and indexed, so a key id computed as {@code sha256(email)} — or any
 * other deterministic function of the subject — is re-identifiable by dictionary attack over any small
 * domain, and survives the shredding it is supposed to enable. Mint key ids randomly and keep the
 * association to the {@link DataSubject} inside the key store, which is the thing erasure destroys.
 *
 * @param value the identifier, in whatever form the key store uses
 *
 * @see ShreddingKeyStore
 * @see ShreddingCodec.Sealed
 */
public record KeyId ( String value ) {

	/**
	 * The tag key under which an event carries the key ids its payload was sealed with.
	 * <p>
	 * The append path adds one {@code dek:<id>} tag per distinct key used, so finding every event that
	 * holds data protected by a given key is an ordinary tag query served by the existing index, with no
	 * extra column and no table scan:
	 * <pre>{@code
	 * stream.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of(KeyId.TAG_KEY, key.value())))
	 * }</pre>
	 * The tags are left in place when the key is destroyed. A {@code dek:} tag naming a key that no
	 * longer exists is a useful tombstone: it says an erasure touched this event, without saying what
	 * was erased.
	 */
	public static final String TAG_KEY = "dek";

	/**
	 * @throws IllegalArgumentException if the value is null or blank
	 */
	public KeyId {
		if ( value == null || value.isBlank() ) {
			throw new IllegalArgumentException("KeyId value must not be null or blank");
		}
	}

	/**
	 * @param value the identifier
	 * @return the key id
	 */
	public static KeyId of ( String value ) {
		return new KeyId(value);
	}

	@Override
	public String toString ( ) {
		return value;
	}

}
