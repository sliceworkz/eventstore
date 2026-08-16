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
 * The key store could not be reached, or a sealed value could not be processed for a reason other
 * than its key having been destroyed.
 *
 * <h2>This is never how a shredded value is reported</h2>
 * A key that is gone is not a failure — it is the mechanism working. It surfaces as
 * {@link Shreddable.Shredded} on the event, and {@link ShreddingCodec#unseal} signals it by returning
 * an empty {@code Optional}. This exception is for everything else: a Vault outage, an expired token,
 * a connection timeout, a corrupt envelope, an algorithm the codec does not implement.
 * <p>
 * The distinction is load-bearing, and getting it wrong is the worst failure this subsystem has.
 * Collapse the two and a transient key-store blip renders every protected value as erased — and
 * projections, which are at-least-once and advance a bookmark past what they have handled, write that
 * into read models permanently and never revisit it. An outage becomes silent, irreversible data loss
 * in every downstream copy.
 * <p>
 * This is the same retryable-versus-not split the library already draws between
 * {@code EventStorageException} (retry with backoff) and {@code EventDeserializationException} (never
 * worth retrying). A {@code ShreddingException} is on the retryable side: the same read may well
 * succeed once the key store is reachable again.
 *
 * @see ShreddingCodec#unseal(ShreddingCodec.Sealed)
 * @see Shreddable.Shredded
 */
public class ShreddingException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * @param message what could not be done
	 */
	public ShreddingException ( String message ) {
		super(message);
	}

	/**
	 * @param message what could not be done
	 * @param cause   the underlying failure
	 */
	public ShreddingException ( String message, Throwable cause ) {
		super(message, cause);
	}

}
