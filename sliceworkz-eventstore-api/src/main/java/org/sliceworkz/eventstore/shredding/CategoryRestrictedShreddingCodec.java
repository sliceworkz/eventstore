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
import java.util.Set;
import java.util.TreeSet;

/**
 * A {@link ShreddingCodec} over another, handling only the {@link DataSubject#category() categories} it
 * was given.
 * <p>
 * Obtained through {@link ShreddingCodec#restrictedTo(Set)}. The decision is made on the category the
 * envelope carries in the clear, so a withheld value costs no key lookup and no round trip to whatever
 * holds the keys.
 * <h2>What it is, and is not</h2>
 * It is the declaration, in configuration, that this process handles some categories of personal data
 * and not others — the data-minimisation boundary a service states about itself, honoured by the read
 * path and the append path alike. It is not a security boundary: the process still holds the codec it
 * wraps. The boundary that cannot be argued with from inside the JVM is the key store's own refusal,
 * {@link ShreddingKeyStore.KeyResolution.Denied}, and the two compose — a restricted codec over a key
 * store that also refuses gives the cheap answer for the categories it never asks about and the hard
 * one for the rest.
 * <h2>Symmetric, deliberately</h2>
 * Sealing is restricted to the same categories as unsealing, so "the categories this process handles"
 * is a single statement rather than two. A process that has the plaintext of a category it may not
 * read is holding data it was configured not to handle, and an append that would seal it fails before
 * anything is stored.
 * <h2>Erasure and audit pass through</h2>
 * Erasing a subject destroys every key the subject holds, in every category, exactly as on the wrapped
 * codec. The alternative — erasing only the permitted categories — loses because an erasure that
 * reports success while leaving data readable is the worst outcome an erasure can have; a process not
 * entitled to erase should not be handed {@link org.sliceworkz.eventstore.EventStore#erase} at all.
 *
 * @see ShreddingCodec#restrictedTo(Set)
 * @see Shreddable.Withheld
 */
public final class CategoryRestrictedShreddingCodec implements ShreddingCodec {

	private final ShreddingCodec delegate;
	private final Set<String> categories;

	/**
	 * @param delegate   the codec that does the sealing and unsealing for the permitted categories
	 * @param categories the categories this codec handles
	 * @throws IllegalArgumentException if the delegate is null, or the set is null, empty, or holds a null
	 *                                  or blank category
	 */
	public CategoryRestrictedShreddingCodec ( ShreddingCodec delegate, Set<String> categories ) {
		if ( delegate == null ) {
			throw new IllegalArgumentException("delegate cannot be null");
		}
		if ( categories == null || categories.isEmpty() ) {
			throw new IllegalArgumentException(
					"categories cannot be null or empty; a codec handling no category is ShreddingCodec.withholdingAll()");
		}
		for ( String category : categories ) {
			if ( category == null || category.isBlank() ) {
				throw new IllegalArgumentException("a category cannot be null or blank");
			}
		}
		this.delegate = delegate;
		this.categories = Set.copyOf(categories);
	}

	/**
	 * The codec this one restricts.
	 *
	 * @return the wrapped codec
	 */
	public ShreddingCodec delegate ( ) {
		return delegate;
	}

	/**
	 * The categories this codec seals and unseals.
	 *
	 * @return the permitted categories, never empty
	 */
	public Set<String> categories ( ) {
		return categories;
	}

	/**
	 * @param subject whose data
	 * @return true if this codec handles the subject's category
	 */
	public boolean permits ( DataSubject subject ) {
		return subject != null && categories.contains(subject.category());
	}

	@Override
	public Sealed seal ( String plaintext, DataSubject subject ) {
		if ( subject == null ) {
			throw new IllegalArgumentException("subject cannot be null");
		}
		if ( !permits(subject) ) {
			// Before the delegate sees the plaintext: a process configured not to handle a category must
			// not seal it either, and the failure names the category rather than the value.
			throw new ShreddingException(
					"this codec handles categories %s only and cannot seal a value in category '%s' for subject %s"
							.formatted(sortedCategories(), subject.category(), subject));
		}
		return delegate.seal(plaintext, subject);
	}

	@Override
	public Optional<String> unseal ( Sealed sealed ) {
		// The two-answer method cannot say "withheld", and pretending it is erased is the one thing this
		// class exists to avoid. Nothing in the library calls it; a caller that does gets the answer
		// through the exception rather than through a lie.
		return switch ( open(sealed) ) {
			case Unsealed.Plaintext plaintext -> Optional.of(plaintext.json());
			case Unsealed.Erased erased -> Optional.empty();
			case Unsealed.Withheld withheld -> throw new ShreddingException(
					"the value is withheld (%s); read it through open(Sealed), which can say so".formatted(withheld.reason()));
		};
	}

	@Override
	public Unsealed open ( Sealed sealed ) {
		if ( sealed == null ) {
			throw new IllegalArgumentException("sealed cannot be null");
		}
		if ( !permits(sealed.subject()) ) {
			return new Unsealed.Withheld(
					"category '%s' is outside this codec's %s".formatted(sealed.subject().category(), sortedCategories()));
		}
		return delegate.open(sealed);
	}

	@Override
	public ErasureReport shred ( DataSubject subject, ErasureReason reason ) {
		return delegate.shred(subject, reason);
	}

	@Override
	public Optional<ShreddingAudit> audit ( ) {
		return delegate.audit();
	}

	/**
	 * Closes the wrapped codec.
	 */
	@Override
	public void close ( ) {
		delegate.close();
	}

	private String sortedCategories ( ) {
		return new TreeSet<>(categories).toString();
	}

	@Override
	public String toString ( ) {
		return "CategoryRestrictedShreddingCodec[%s over %s]".formatted(sortedCategories(), delegate);
	}

}
