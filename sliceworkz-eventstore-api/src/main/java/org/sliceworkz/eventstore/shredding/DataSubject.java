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
 * Whose personal data a {@link Shreddable} value holds, and under which retention category.
 * <p>
 * A data subject is the unit of erasure: {@link org.sliceworkz.eventstore.EventStore#erase} takes one
 * and destroys the keys held for it, which makes every {@link Shreddable} sealed under those keys
 * unreadable everywhere at once — in the events table, in WAL, on replicas and in every backup.
 *
 * <h2>The subject id must not itself be personal data</h2>
 * The id is stored in the sealed envelope in plaintext and is used to key the key store, so it
 * survives erasure by construction. Use a pseudonymous identifier — a customer number, an account id,
 * a surrogate key. An email address or a national identity number as the subject id defeats the whole
 * mechanism, because the identifier that remains after shredding is itself the personal data.
 * <p>
 * This is the same discipline the library already asks for when tagging events: {@code Tag.of("customer",
 * customerId)} is safe to store and index precisely because {@code customerId} is pseudonymous.
 *
 * <h2>Category: what one erasure erases</h2>
 * Keys are held per {@code (type, id, category)}, not per subject, so a subject's data can be erased
 * in parts. "Erase marketing data, retain financial records for the statutory period" is an ordinary
 * request, and a single key per subject makes it impossible to honour: shredding would take the
 * financial history with it.
 * <p>
 * Most events need only {@link #DEFAULT_CATEGORY}, which {@link #of(String, String)} applies. Reach for
 * a category when parts of a subject's data are governed by different retention rules:
 * <pre>{@code
 * DataSubject marketing = DataSubject.of("customer", "alice-42").withCategory("marketing");
 * DataSubject financial = DataSubject.of("customer", "alice-42").withCategory("financial");
 *
 * // erases the marketing data only; the financial history keeps decrypting
 * eventStore.erase(marketing, ErasureReason.of("GDPR art.17 request #4711"));
 * }</pre>
 *
 * <h2>Examples</h2>
 * <pre>{@code
 * DataSubject alice = DataSubject.of("customer", "alice-42");
 * DataSubject bob   = DataSubject.of("customer", "bob-77");
 *
 * // one event, two subjects, two keys — erasing one leaves the other readable
 * new TransferMade("t-9001", amount, "alice-42", "bob-77",
 *                  Shreddable.of(alicesDetails, alice),
 *                  Shreddable.of(bobsDetails,   bob));
 * }</pre>
 *
 * @param type     what kind of subject this is, e.g. {@code "customer"}, {@code "employee"}, {@code "patient"}
 * @param id       the pseudonymous identifier of the subject within that type
 * @param category which slice of the subject's data this covers; {@link #DEFAULT_CATEGORY} unless set
 *
 * @see Shreddable
 * @see ShreddingKeyStore
 * @see org.sliceworkz.eventstore.EventStore#erase(DataSubject, ErasureReason)
 */
public record DataSubject ( String type, String id, String category ) {

	/**
	 * The category applied when none is given, mirroring {@code EventStreamId.DEFAULT_PURPOSE}: a
	 * public constant so an interop layer can bind the same value the library does rather than copying
	 * the literal.
	 */
	public static final String DEFAULT_CATEGORY = "default";

	/**
	 * Validates that all three parts are present and non-blank.
	 * <p>
	 * Blank parts are rejected rather than normalised because a key held for a blank subject silently
	 * pools unrelated people's data under one key, and shredding it would erase all of them.
	 *
	 * @throws IllegalArgumentException if any part is null or blank
	 */
	public DataSubject {
		if ( type == null || type.isBlank() ) {
			throw new IllegalArgumentException("DataSubject type must not be null or blank");
		}
		if ( id == null || id.isBlank() ) {
			throw new IllegalArgumentException("DataSubject id must not be null or blank");
		}
		if ( category == null || category.isBlank() ) {
			throw new IllegalArgumentException("DataSubject category must not be null or blank; use DataSubject.of(type, id) for the default category");
		}
	}

	/**
	 * A subject under the {@link #DEFAULT_CATEGORY}, which is what most events need.
	 *
	 * @param type what kind of subject this is
	 * @param id   the pseudonymous identifier — never personal data itself, see the class javadoc
	 * @return the data subject
	 */
	public static DataSubject of ( String type, String id ) {
		return new DataSubject(type, id, DEFAULT_CATEGORY);
	}

	/**
	 * The same subject under a different retention category, and therefore under a different key.
	 *
	 * @param category which slice of the subject's data the returned subject covers
	 * @return a new data subject; this one is unchanged
	 */
	public DataSubject withCategory ( String category ) {
		return new DataSubject(type, id, category);
	}

	@Override
	public String toString ( ) {
		return "%s/%s/%s".formatted(type, id, category);
	}

}
