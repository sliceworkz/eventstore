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

import java.util.Optional;

/**
 * The codec behind {@link ShreddingCodec#withholdingAll()}: no keys, no key store, every protected
 * value withheld.
 * <p>
 * It exists so that a reader with no entitlement to personal data can still open typed streams over
 * events that carry it. Registering an event type that declares a {@link Shreddable} fails on a store
 * with no codec, and that is right for a writer; a reader that only wants the non-personal payload
 * needs a codec that answers, and this one answers {@link Unsealed.Withheld} for everything.
 * <p>
 * A singleton, since it holds nothing.
 */
final class WithholdingShreddingCodec implements ShreddingCodec {

	static final WithholdingShreddingCodec INSTANCE = new WithholdingShreddingCodec();

	private static final String REASON = "this store's codec withholds every protected value (ShreddingCodec.withholdingAll())";

	private WithholdingShreddingCodec ( ) {
		// nothing to hold
	}

	@Override
	public Sealed seal ( String plaintext, DataSubject subject ) {
		if ( subject == null ) {
			throw new IllegalArgumentException("subject cannot be null");
		}
		throw new ShreddingException(
				"cannot seal a value for subject %s: this store's codec holds no keys (ShreddingCodec.withholdingAll()) and is for reading only"
						.formatted(subject));
	}

	@Override
	public Optional<String> unseal ( Sealed sealed ) {
		// Cannot say "withheld", and must not say "erased".
		throw new ShreddingException(REASON + "; read it through open(Sealed), which can say so");
	}

	@Override
	public Unsealed open ( Sealed sealed ) {
		if ( sealed == null ) {
			throw new IllegalArgumentException("sealed cannot be null");
		}
		return new Unsealed.Withheld(REASON);
	}

	@Override
	public ErasureReport shred ( DataSubject subject, ErasureReason reason ) {
		throw new UnsupportedOperationException(
				"this store's codec holds no keys (ShreddingCodec.withholdingAll()), so it cannot erase subject %s; erase through a store whose codec holds the keys"
						.formatted(subject));
	}

	@Override
	public String toString ( ) {
		return "WithholdingShreddingCodec";
	}

}
