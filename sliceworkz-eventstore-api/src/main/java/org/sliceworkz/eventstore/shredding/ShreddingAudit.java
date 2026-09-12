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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reading what a key store holds, without being able to decrypt anything with it.
 * <p>
 * Erasure leaves no trace in the events — that is the point of it — so the key store is the only place
 * that records which subjects hold protected data, which erasures have happened, when and on whose
 * authority. This is how an operations console or a compliance report reads that, and it is deliberately
 * the <em>only</em> way: {@link ShreddingKeyStore} itself hands out key material, and nothing that only
 * needs to report should be given it.
 *
 * <h2>Key material never leaves through here</h2>
 * {@link KeyRecord} carries no key, and there is no method that returns one. A dashboard credential
 * granted this interface can see <em>that</em> data is protected and <em>when</em> it was erased, and
 * can never see <em>what</em> it was. That separation is the whole reason this is not simply another
 * method on the key store.
 *
 * <h2>Optional, like leases</h2>
 * A key store that cannot answer these questions — one fronting a KMS that does not enumerate, say —
 * returns empty from {@link ShreddingKeyStore#audit()} and callers do without. The shipped key stores
 * all implement it.
 *
 * <h2>What it cannot tell you</h2>
 * Which <em>events</em> hold data under a key is not answered here: the key store has never seen an
 * event. Each event is tagged with the keys its payload was sealed under, so that is an ordinary tag
 * query on the event store:
 * <pre>{@code
 * for ( KeyRecord key : audit.keys(KeyAuditQuery.forSubject("customer", "alice-42")) ) {
 *     stream.query(EventQuery.forEvents(EventTypesFilter.any(), Tags.of(KeyId.TAG_KEY, key.id().value())))
 *           .forEach(…);
 * }
 * }</pre>
 *
 * The other direction is answered here: an event carries its keys, and {@link KeyAuditQuery#forKeys}
 * says whether those keys still exist, so a reader holding an event can tell "protected" from "erased"
 * without a key of its own.
 *
 * @see ShreddingKeyStore#audit()
 * @see KeyId#TAG_KEY
 */
public interface ShreddingAudit {

	/**
	 * The keys matching a query, newest first.
	 *
	 * @param query which keys to report on
	 * @return the matching records, never null
	 * @throws ShreddingException if the key store cannot be reached
	 * @throws IllegalArgumentException if the query is null
	 */
	List<KeyRecord> keys ( KeyAuditQuery query );

	/**
	 * How many subjects currently hold at least one live key, and how many keys have been destroyed.
	 * <p>
	 * The cheap summary a dashboard opens with, so that a screen showing "how much personal data is
	 * under management" does not have to enumerate every key to say so.
	 *
	 * @return the totals
	 * @throws ShreddingException if the key store cannot be reached
	 */
	ShreddingTotals totals ( );

	/**
	 * The same totals, broken down by {@link DataSubject#category() category}: which categories of
	 * personal data this store holds at all, and how much sits under each.
	 * <p>
	 * A category is the unit of erasure and the unit of access — what {@code EventStore.erase} takes
	 * and what a reader is granted through {@code ShreddingCodec.restrictedTo} — and nothing but the
	 * key store knows which categories exist: they are chosen by whoever writes an event, and the
	 * events carry them only inside sealed envelopes. So this is the inventory an operator reads before
	 * deciding which categories each service may open, and the only way to learn a category name
	 * without already knowing it. Computing it from {@link #keys} instead is wrong on any store that
	 * holds more keys than the query's limit.
	 * <p>
	 * A category with no keys left at all cannot appear: erasure keeps the row, so a category every
	 * key of which has been destroyed is reported with zero live keys rather than dropped.
	 *
	 * <p>
	 * The shipped key stores all answer it. The default throws, as the optional SPI methods on
	 * {@code EventStorage} do, so an audit written before it existed keeps compiling and a caller
	 * learns that it cannot answer rather than reading an empty inventory as "no personal data here".
	 *
	 * @return one entry per category that holds or has held a key, most live subjects first, then by
	 *         category name; never null
	 * @throws ShreddingException if the key store cannot be reached
	 * @throws UnsupportedOperationException if this audit cannot break its totals down by category
	 */
	default List<CategoryTotals> categories ( ) {
		throw new UnsupportedOperationException(
				"this ShreddingAudit (%s) does not report totals per category".formatted(getClass().getName()));
	}

	/**
	 * One key, as far as anything that must not decrypt is allowed to see it.
	 *
	 * @param id         names the key, and is what the events carry as a {@code dek:} tag
	 * @param subject    whose data it protects
	 * @param createdAt  when it was minted
	 * @param shreddedAt when it was destroyed, empty while it still exists
	 * @param reason     why it was destroyed, empty while it still exists
	 */
	record KeyRecord ( KeyId id, DataSubject subject, Instant createdAt, Optional<Instant> shreddedAt, Optional<ErasureReason> reason ) {

		/**
		 * Normalises nulls in the two optional components, so a store can build one from a nullable row.
		 *
		 * @throws IllegalArgumentException if the id, subject or creation time is null
		 */
		public KeyRecord {
			if ( id == null ) {
				throw new IllegalArgumentException("KeyRecord id must not be null");
			}
			if ( subject == null ) {
				throw new IllegalArgumentException("KeyRecord subject must not be null");
			}
			if ( createdAt == null ) {
				throw new IllegalArgumentException("KeyRecord createdAt must not be null");
			}
			shreddedAt = shreddedAt == null ? Optional.empty() : shreddedAt;
			reason = reason == null ? Optional.empty() : reason;
		}

		/**
		 * A key that still exists.
		 *
		 * @param id        names the key
		 * @param subject   whose data it protects
		 * @param createdAt when it was minted
		 * @return the record
		 */
		public static KeyRecord live ( KeyId id, DataSubject subject, Instant createdAt ) {
			return new KeyRecord(id, subject, createdAt, Optional.empty(), Optional.empty());
		}

		/**
		 * @return true if the key material has been destroyed
		 */
		public boolean isShredded ( ) {
			return shreddedAt.isPresent();
		}

	}

	/**
	 * How many subjects hold protected data, and how much has been erased.
	 *
	 * @param subjectsWithLiveKeys distinct data subjects holding at least one key that still exists
	 * @param liveKeys             keys that still exist
	 * @param shreddedKeys         keys whose material has been destroyed
	 */
	record ShreddingTotals ( long subjectsWithLiveKeys, long liveKeys, long shreddedKeys ) { }

	/**
	 * The {@link ShreddingTotals} of one category.
	 * <p>
	 * Subjects are counted per {@code (type, id)} within the category, so a subject holding data in two
	 * categories counts once in each — which is what "how many people's marketing data do we hold" asks.
	 *
	 * @param category             the {@link DataSubject#category() category}
	 * @param subjectsWithLiveKeys distinct data subjects holding at least one key that still exists in it
	 * @param liveKeys             keys in it that still exist
	 * @param shreddedKeys         keys in it whose material has been destroyed
	 */
	record CategoryTotals ( String category, long subjectsWithLiveKeys, long liveKeys, long shreddedKeys ) {

		/**
		 * @throws IllegalArgumentException if the category is null or blank
		 */
		public CategoryTotals {
			if ( category == null || category.isBlank() ) {
				throw new IllegalArgumentException("CategoryTotals category must not be null or blank");
			}
		}

		/**
		 * @return the totals without the category, for a caller summing or comparing them
		 */
		public ShreddingTotals totals ( ) {
			return new ShreddingTotals(subjectsWithLiveKeys, liveKeys, shreddedKeys);
		}

	}

}
