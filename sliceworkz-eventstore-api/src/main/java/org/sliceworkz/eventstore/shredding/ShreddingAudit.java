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

}
