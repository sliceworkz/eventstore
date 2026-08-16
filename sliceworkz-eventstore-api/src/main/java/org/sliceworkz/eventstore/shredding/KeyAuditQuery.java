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
 * Which keys a {@link ShreddingAudit} should report on.
 * <p>
 * Every part is optional and narrows the result; the defaults report everything, newest first, up to
 * {@link #DEFAULT_LIMIT}.
 * <pre>{@code
 * KeyAuditQuery.all()                                    // most recent keys, live and shredded
 * KeyAuditQuery.all().onlyShredded()                     // the erasure log
 * KeyAuditQuery.forSubject("customer", "alice-42")       // one person, every category
 * KeyAuditQuery.all().withCategory("marketing")          // one retention category across subjects
 * }</pre>
 *
 * <h2>Always bounded</h2>
 * The limit is not optional. A key store holds one row per subject per category and never prunes the
 * shredded ones, so a store that has been running for years has more keys than anything wants to page
 * through by accident — and unlike an event query, there is no cursor here to resume from.
 *
 * @param subjectType which kind of subject, or null for any
 * @param subjectId   which subject, or null for any; only meaningful together with a type
 * @param category    which retention category, or null for any
 * @param shreddedOnly report only keys whose material has been destroyed
 * @param limit       how many records at most
 *
 * @see ShreddingAudit#keys(KeyAuditQuery)
 */
public record KeyAuditQuery ( String subjectType, String subjectId, String category, boolean shreddedOnly, int limit ) {

	/**
	 * How many records a query reports when it does not say.
	 */
	public static final int DEFAULT_LIMIT = 500;

	/**
	 * @throws IllegalArgumentException if the limit is not positive, or an id is given without a type
	 */
	public KeyAuditQuery {
		if ( limit <= 0 ) {
			throw new IllegalArgumentException("KeyAuditQuery limit must be positive");
		}
		if ( subjectId != null && subjectType == null ) {
			throw new IllegalArgumentException("a subjectId without a subjectType matches subjects of every type, which is never what is meant");
		}
	}

	/**
	 * @return every key, newest first, up to {@link #DEFAULT_LIMIT}
	 */
	public static KeyAuditQuery all ( ) {
		return new KeyAuditQuery(null, null, null, false, DEFAULT_LIMIT);
	}

	/**
	 * @param subjectType which kind of subject
	 * @param subjectId   which subject, or null for every subject of that type
	 * @return every key held for one data subject, in every category
	 */
	public static KeyAuditQuery forSubject ( String subjectType, String subjectId ) {
		return new KeyAuditQuery(subjectType, subjectId, null, false, DEFAULT_LIMIT);
	}

	/**
	 * @param subject the data subject, category included
	 * @return every key held for exactly that subject and category
	 */
	public static KeyAuditQuery forSubject ( DataSubject subject ) {
		return new KeyAuditQuery(subject.type(), subject.id(), subject.category(), false, DEFAULT_LIMIT);
	}

	/**
	 * @param category which retention category
	 * @return the same query narrowed to one category
	 */
	public KeyAuditQuery withCategory ( String category ) {
		return new KeyAuditQuery(subjectType, subjectId, category, shreddedOnly, limit);
	}

	/**
	 * @return the same query narrowed to destroyed keys — the erasure log
	 */
	public KeyAuditQuery onlyShredded ( ) {
		return new KeyAuditQuery(subjectType, subjectId, category, true, limit);
	}

	/**
	 * @param limit how many records at most
	 * @return the same query with a different bound
	 */
	public KeyAuditQuery withLimit ( int limit ) {
		return new KeyAuditQuery(subjectType, subjectId, category, shreddedOnly, limit);
	}

}
