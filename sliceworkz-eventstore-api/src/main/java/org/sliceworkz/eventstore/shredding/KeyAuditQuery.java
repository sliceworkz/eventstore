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

import java.util.Set;

/**
 * Which keys a {@link ShreddingAudit} should report on.
 * <p>
 * Every part is optional and narrows the result; the defaults report everything, newest first, up to
 * {@link #DEFAULT_LIMIT}.
 * <pre>{@code
 * KeyAuditQuery.all()                                    // most recent keys, live and shredded
 * KeyAuditQuery.all().onlyShredded()                     // the erasure log
 * KeyAuditQuery.forSubject("customer", "alice-42")       // one person, every category
 * KeyAuditQuery.all().withCategory("marketing")          // one retention category across subjects
 * KeyAuditQuery.forKeys(keysOnAnEvent)                   // the keys an event carries as dek: tags
 * }</pre>
 *
 * <h2>By key id: the join back from an event</h2>
 * An event says which keys its payload was sealed under — its {@code dek:} tags, and the envelope of
 * each protected value — and nothing else. Whether those keys still exist is the key store's to say, so
 * a reader that has an event in hand and wants to render "erased on …" rather than "protected" asks
 * {@link #forKeys(Set)}. On a SQL key store that is a primary-key lookup. A key filter narrows the
 * other parts rather than replacing them: under a subject filter it reports only the keys that satisfy
 * both, and a key the store never held answers nothing rather than failing.
 *
 * <h2>Always bounded</h2>
 * The limit is not optional. A key store holds one row per subject per category and never prunes the
 * shredded ones, so a store that has been running for years has more keys than anything wants to page
 * through by accident — and unlike an event query, there is no cursor here to resume from.
 *
 * @param subjectType which kind of subject, or null for any
 * @param subjectId   which subject, or null for any; only meaningful together with a type
 * @param category    which retention category, or null for any
 * @param keys        which keys, by id, or null for any; never empty
 * @param shreddedOnly report only keys whose material has been destroyed
 * @param limit       how many records at most
 *
 * @see ShreddingAudit#keys(KeyAuditQuery)
 */
public record KeyAuditQuery ( String subjectType, String subjectId, String category, Set<KeyId> keys, boolean shreddedOnly, int limit ) {

	/**
	 * How many records a query reports when it does not say.
	 */
	public static final int DEFAULT_LIMIT = 500;

	/**
	 * @throws IllegalArgumentException if the limit is not positive, an id is given without a type, or
	 *                                  the key set is empty or holds a null
	 */
	public KeyAuditQuery {
		if ( limit <= 0 ) {
			throw new IllegalArgumentException("KeyAuditQuery limit must be positive");
		}
		if ( subjectId != null && subjectType == null ) {
			throw new IllegalArgumentException("a subjectId without a subjectType matches subjects of every type, which is never what is meant");
		}
		if ( keys != null ) {
			if ( keys.isEmpty() ) {
				throw new IllegalArgumentException("an empty key set matches nothing, which is never what is meant; use null for any key");
			}
			// a loop rather than contains(null): an immutable Set.of(...) throws on that call
			for ( KeyId key : keys ) {
				if ( key == null ) {
					throw new IllegalArgumentException("KeyAuditQuery keys cannot contain null");
				}
			}
			keys = Set.copyOf(keys);
		}
	}

	/**
	 * A query over every key, the shape every query had before keys could be named. Kept so a caller
	 * building one from its parts need not know about the key filter.
	 *
	 * @param subjectType  which kind of subject, or null for any
	 * @param subjectId    which subject, or null for any; only meaningful together with a type
	 * @param category     which retention category, or null for any
	 * @param shreddedOnly report only keys whose material has been destroyed
	 * @param limit        how many records at most
	 */
	public KeyAuditQuery ( String subjectType, String subjectId, String category, boolean shreddedOnly, int limit ) {
		this(subjectType, subjectId, category, null, shreddedOnly, limit);
	}

	/**
	 * @return every key, newest first, up to {@link #DEFAULT_LIMIT}
	 */
	public static KeyAuditQuery all ( ) {
		return new KeyAuditQuery(null, null, null, null, false, DEFAULT_LIMIT);
	}

	/**
	 * @param subjectType which kind of subject
	 * @param subjectId   which subject, or null for every subject of that type
	 * @return every key held for one data subject, in every category
	 */
	public static KeyAuditQuery forSubject ( String subjectType, String subjectId ) {
		return new KeyAuditQuery(subjectType, subjectId, null, null, false, DEFAULT_LIMIT);
	}

	/**
	 * @param subject the data subject, category included
	 * @return every key held for exactly that subject and category
	 */
	public static KeyAuditQuery forSubject ( DataSubject subject ) {
		return new KeyAuditQuery(subject.type(), subject.id(), subject.category(), null, false, DEFAULT_LIMIT);
	}

	/**
	 * The keys with these ids — typically the {@code dek:} tags of one event, or of a page of them.
	 * <p>
	 * The limit defaults to the number of keys asked for, since a key id names at most one record: a
	 * caller asking about the keys on a page of events gets an answer for every one of them.
	 *
	 * @param keys which keys; must not be null or empty
	 * @return every record for those keys, live or shredded
	 * @throws IllegalArgumentException if the set is null or empty, or holds a null
	 */
	public static KeyAuditQuery forKeys ( Set<KeyId> keys ) {
		if ( keys == null ) {
			throw new IllegalArgumentException("KeyAuditQuery keys cannot be null; use all() for every key");
		}
		return new KeyAuditQuery(null, null, null, keys, false, Math.max(keys.size(), 1));
	}

	/**
	 * @param key which key
	 * @return the record for that key, live or shredded, as a one-element query
	 * @throws IllegalArgumentException if the key is null
	 */
	public static KeyAuditQuery forKey ( KeyId key ) {
		if ( key == null ) {
			throw new IllegalArgumentException("KeyAuditQuery key cannot be null");
		}
		return forKeys(Set.of(key));
	}

	/**
	 * @param category which retention category
	 * @return the same query narrowed to one category
	 */
	public KeyAuditQuery withCategory ( String category ) {
		return new KeyAuditQuery(subjectType, subjectId, category, keys, shreddedOnly, limit);
	}

	/**
	 * @param keys which keys, by id; must not be null or empty
	 * @return the same query narrowed to those keys
	 * @throws IllegalArgumentException if the set is null or empty, or holds a null
	 */
	public KeyAuditQuery withKeys ( Set<KeyId> keys ) {
		if ( keys == null ) {
			throw new IllegalArgumentException("KeyAuditQuery keys cannot be null; a query without a key filter is the same query");
		}
		return new KeyAuditQuery(subjectType, subjectId, category, keys, shreddedOnly, limit);
	}

	/**
	 * @return the same query narrowed to destroyed keys — the erasure log
	 */
	public KeyAuditQuery onlyShredded ( ) {
		return new KeyAuditQuery(subjectType, subjectId, category, keys, true, limit);
	}

	/**
	 * @param limit how many records at most
	 * @return the same query with a different bound
	 */
	public KeyAuditQuery withLimit ( int limit ) {
		return new KeyAuditQuery(subjectType, subjectId, category, keys, shreddedOnly, limit);
	}

}
