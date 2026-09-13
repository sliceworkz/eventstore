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

import java.util.ArrayList;
import java.util.List;

/**
 * What erasing a subject across every category actually destroyed.
 * <p>
 * Returned by {@link org.sliceworkz.eventstore.EventStore#eraseAllCategories}. It is one
 * {@link ErasureReport} per category the subject held live keys under, so a data protection officer can
 * see not only how many keys went but which slices of the person's data they protected — "default and
 * marketing, nothing under financial" — without a second audit query.
 * <p>
 * Like the per-category report it reports keys, not events; the affected events are a {@code dek:} tag
 * query per key in {@link #shreddedKeys()}, see {@link ErasureReport}.
 *
 * <h2>An empty report is not an error</h2>
 * {@link #isNoop()} means the subject held no live keys under any category: nothing was ever appended
 * for them, or every category was erased already. A category that never held a key, or whose keys were
 * already shredded, is simply absent from {@link #categories()} — there is nothing to report for it.
 *
 * @param subjectType what kind of subject was erased, e.g. {@code "customer"}
 * @param subjectId   the pseudonymous identifier of the subject within that type
 * @param reason      the recorded authority for the erasure
 * @param categories  one report per category that held live keys, each naming the keys it destroyed; empty
 *                    if the subject held none
 *
 * @see org.sliceworkz.eventstore.EventStore#eraseAllCategories(String, String, ErasureReason)
 * @see ErasureReport
 */
public record SubjectErasureReport ( String subjectType, String subjectId, ErasureReason reason, List<ErasureReport> categories ) {

	/**
	 * Defensively copies the category reports, and checks that each of them is about this subject.
	 *
	 * @throws IllegalArgumentException if any component is null or blank, if a category report names a
	 *                                  different subject, or if two reports name the same category
	 */
	public SubjectErasureReport {
		if ( subjectType == null || subjectType.isBlank() ) {
			throw new IllegalArgumentException("SubjectErasureReport subjectType must not be null or blank");
		}
		if ( subjectId == null || subjectId.isBlank() ) {
			throw new IllegalArgumentException("SubjectErasureReport subjectId must not be null or blank");
		}
		if ( reason == null ) {
			throw new IllegalArgumentException("SubjectErasureReport reason must not be null");
		}
		if ( categories == null ) {
			throw new IllegalArgumentException("SubjectErasureReport categories must not be null; use an empty list when nothing was erased");
		}
		categories = List.copyOf(categories);
		List<String> seen = new ArrayList<>();
		for ( ErasureReport category : categories ) {
			DataSubject subject = category.subject();
			if ( !subject.type().equals(subjectType) || !subject.id().equals(subjectId) ) {
				throw new IllegalArgumentException(
						"SubjectErasureReport for %s/%s cannot carry a report about %s".formatted(subjectType, subjectId, subject));
			}
			if ( seen.contains(subject.category()) ) {
				throw new IllegalArgumentException(
						"SubjectErasureReport for %s/%s carries category '%s' twice".formatted(subjectType, subjectId, subject.category()));
			}
			seen.add(subject.category());
		}
	}

	/**
	 * The keys destroyed across every category, in no particular order.
	 *
	 * @return every key named by a category report; empty if the subject held none
	 */
	public List<KeyId> shreddedKeys ( ) {
		List<KeyId> keys = new ArrayList<>();
		for ( ErasureReport category : categories ) {
			keys.addAll(category.shreddedKeys());
		}
		return List.copyOf(keys);
	}

	/**
	 * The categories under which keys were destroyed.
	 *
	 * @return the category names, in the order the reports carry them; empty if the subject held no keys
	 */
	public List<String> categoriesErased ( ) {
		return categories.stream().map(report -> report.subject().category()).toList();
	}

	/**
	 * @return how many keys were destroyed, across every category
	 */
	public int keysShredded ( ) {
		int keys = 0;
		for ( ErasureReport category : categories ) {
			keys += category.keysShredded();
		}
		return keys;
	}

	/**
	 * @return true if the subject held no live keys under any category, so nothing was erased
	 */
	public boolean isNoop ( ) {
		return keysShredded() == 0;
	}

	@Override
	public String toString ( ) {
		return "erased %s/%s across %s: %d key(s) shredded (%s)"
				.formatted(subjectType, subjectId, categoriesErased(), keysShredded(), reason);
	}

}
