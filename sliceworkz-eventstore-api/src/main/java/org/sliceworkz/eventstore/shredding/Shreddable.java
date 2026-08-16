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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A value in an event that holds one data subject's personal data, and that can be made permanently
 * unreadable by destroying that subject's key.
 * <p>
 * Wrapping a record component in {@code Shreddable} does three things at once: it declares in the type
 * system that the component is personal data, it binds that data to a {@link DataSubject}, and it makes
 * "this has been erased" a state the component can actually be in. The value is encrypted on append and
 * decrypted on read; erasure destroys the key rather than touching the event, so the stored event stays
 * byte-identical forever and the log remains genuinely append-only.
 *
 * <h2>Declaring events</h2>
 * <pre>{@code
 * public sealed interface PaymentEvent {
 *
 *     record PartyDetails(String name, String iban) { }
 *
 *     record TransferMade(
 *             String transferId,
 *             Money  amount,
 *             String fromCustomerId,                 // pseudonymous — survives erasure
 *             String toCustomerId,                   // pseudonymous — survives erasure
 *             Shreddable<PartyDetails> from,         // Alice's personal data
 *             Shreddable<PartyDetails> to            // Bob's personal data
 *     ) implements PaymentEvent { }
 * }
 * }</pre>
 *
 * <h2>Appending</h2>
 * The caller names whose data it is; the store mints a key per {@link DataSubject} on first sight,
 * seals each value under the key for its own subject, and tags the event with the key ids it used.
 * <pre>{@code
 * DataSubject alice = DataSubject.of("customer", "alice-42");
 * DataSubject bob   = DataSubject.of("customer", "bob-77");
 *
 * payments.append(AppendCriteria.none(), Event.of(
 *         new TransferMade("t-9001", Money.eur("250.00"), "alice-42", "bob-77",
 *                          Shreddable.of(new PartyDetails("Alice Martin", "BE68 5390 0754 7034"), alice),
 *                          Shreddable.of(new PartyDetails("Bob Jansen",   "NL91 ABNA 0417 1643 00"), bob)),
 *         Tags.of(Tag.of("customer", "alice-42"), Tag.of("customer", "bob-77"))));
 * }</pre>
 *
 * <h2>Reading</h2>
 * The common path needs no ceremony:
 * <pre>{@code
 * String payer = transfer.from().map(PartyDetails::name).orElse("[erased]");
 * }</pre>
 * Where the erased case deserves its own rendering, the sealed hierarchy makes the compiler ask:
 * <pre>{@code
 * String payer = switch ( transfer.from() ) {
 *     case Present<PartyDetails>(var party, var subject) -> party.name();
 *     case Shredded<PartyDetails>(var subject, var key)  -> "customer " + subject.id() + " (erased)";
 * };
 * }</pre>
 * Erasing Alice leaves everything else intact — the amount, the transfer id, the pseudonymous customer
 * ids, and Bob's details under his own key:
 * <pre>{@code
 * eventStore.erase(alice, ErasureReason.of("GDPR art.17 request #4711"));
 *
 * transfer.amount();   // EUR 250.00                              unchanged
 * transfer.from();     // Shredded[customer/alice-42/default, …]  gone
 * transfer.to();       // Present[PartyDetails[Bob Jansen, …]]    unaffected
 * }</pre>
 *
 * <h2>Why a wrapper rather than an annotation on a plain field</h2>
 * <ul>
 *   <li><b>A shredded event stays constructible.</b> The component is never null — it is a
 *       {@code Shreddable} holding nothing — so a record whose compact constructor rejects a null value
 *       still builds after erasure. Nulling a field instead turns any validating event into a poison
 *       event that fails every query and every projection over its stream, permanently.</li>
 *   <li><b>Erased is distinguishable from absent.</b> {@link Shredded} is a state; {@code null} is not.
 *       Nor can a primitive express one, which is why {@code Shreddable<Integer>} works where an erased
 *       {@code int} silently reads as zero.</li>
 *   <li><b>Collections and nesting need no special handling.</b> A {@code Shreddable} anywhere in the
 *       payload — inside a {@code List}, as a {@code Map} value, several levels down — is sealed and
 *       unsealed by its own serializer.</li>
 *   <li><b>Two subjects in one event work.</b> Each value carries its own subject and therefore its own
 *       key, which no per-event or per-field-annotation scheme can express.</li>
 * </ul>
 *
 * <h2>Configuration is required, and fails fast</h2>
 * Opening a stream whose registered event types contain a {@code Shreddable} component on a store with
 * no {@link ShreddingCodec} configured throws {@link IllegalArgumentException} at
 * {@code getEventStream}, before anything is read or written. Personal data silently stored in the
 * clear is not a failure mode worth having.
 *
 * @param <T> the type of the protected value
 *
 * @see DataSubject
 * @see ShreddingCodec
 * @see org.sliceworkz.eventstore.EventStore#erase(DataSubject, ErasureReason)
 */
public sealed interface Shreddable<T> permits Shreddable.Present, Shreddable.Shredded {

	/**
	 * Whose data this is. Available whether or not the value is still readable — a shredded value still
	 * says which subject and category it belonged to, which is what lets a projection render
	 * "customer alice-42 (erased)" without consulting the key store.
	 *
	 * @return the data subject, never null
	 */
	DataSubject subject ( );

	/**
	 * @return true if the key protecting this value has been destroyed and the value can never be read again
	 */
	boolean isShredded ( );

	/**
	 * @return true if the value is still readable
	 */
	default boolean isPresent ( ) {
		return !isShredded();
	}

	/**
	 * The value if it is still readable, empty if it has been shredded.
	 *
	 * @return the value as an {@link Optional}
	 */
	Optional<T> toOptional ( );

	/**
	 * Applies a function to the value if it is still readable, keeping the subject and key otherwise.
	 * <p>
	 * The usual way to read one field out of a protected value:
	 * <pre>{@code
	 * t.from().map(PartyDetails::name).orElse("[erased]")
	 * }</pre>
	 *
	 * @param <R> the result type
	 * @param fn  the mapping function, applied only when the value is present
	 * @return a shreddable holding the mapped value, or the same shredded state
	 */
	<R> Shreddable<R> map ( Function<? super T, ? extends R> fn );

	/**
	 * @param fallback what to return when the value has been shredded; may be null
	 * @return the value, or the fallback
	 */
	T orElse ( T fallback );

	/**
	 * @param supplier produces the replacement when the value has been shredded
	 * @return the value, or the supplied replacement
	 */
	T orElseGet ( Supplier<? extends T> supplier );

	/**
	 * Wraps a value as personal data belonging to a subject, ready to be appended.
	 * <p>
	 * Nothing is encrypted here — sealing happens on append, when the store resolves the subject to a
	 * key. Two values for the same subject in the same event share one key; values for different
	 * subjects do not.
	 *
	 * @param <T>     the type of the protected value
	 * @param value   the personal data; must not be null, since a shreddable holding null would be
	 *                indistinguishable from one that has been erased
	 * @param subject whose data it is
	 * @return a present shreddable
	 * @throws IllegalArgumentException if the value or the subject is null
	 */
	static <T> Shreddable<T> of ( T value, DataSubject subject ) {
		return new Present<>(value, subject);
	}

	/**
	 * A value whose key still exists, holding readable personal data.
	 *
	 * @param <T>     the type of the protected value
	 * @param value   the personal data, never null
	 * @param subject whose data it is
	 */
	record Present<T> ( T value, DataSubject subject ) implements Shreddable<T> {

		/**
		 * @throws IllegalArgumentException if the value or the subject is null
		 */
		public Present {
			if ( value == null ) {
				throw new IllegalArgumentException("Shreddable value must not be null: a null value cannot be told apart from an erased one");
			}
			if ( subject == null ) {
				throw new IllegalArgumentException("Shreddable subject must not be null: the store cannot tell whose data this is, and so cannot erase it");
			}
		}

		@Override
		public boolean isShredded ( ) {
			return false;
		}

		@Override
		public Optional<T> toOptional ( ) {
			return Optional.of(value);
		}

		@Override
		public <R> Shreddable<R> map ( Function<? super T, ? extends R> fn ) {
			return new Present<>(fn.apply(value), subject);
		}

		@Override
		public T orElse ( T fallback ) {
			return value;
		}

		@Override
		public T orElseGet ( Supplier<? extends T> supplier ) {
			return value;
		}

	}

	/**
	 * A value whose key has been destroyed. The ciphertext is still in the event and always will be;
	 * there is no longer anything that can read it.
	 * <p>
	 * The subject and key id are kept deliberately: they are what a projection needs to render the gap
	 * honestly, and what an auditor needs to tie the gap to the key store row recording when and why
	 * the erasure happened.
	 *
	 * @param <T>     the type the value had
	 * @param subject whose data it was
	 * @param key     the key that was destroyed
	 */
	record Shredded<T> ( DataSubject subject, KeyId key ) implements Shreddable<T> {

		/**
		 * @throws IllegalArgumentException if the subject or the key is null
		 */
		public Shredded {
			if ( subject == null ) {
				throw new IllegalArgumentException("Shreddable subject must not be null");
			}
			if ( key == null ) {
				throw new IllegalArgumentException("Shredded key must not be null");
			}
		}

		@Override
		public boolean isShredded ( ) {
			return true;
		}

		@Override
		public Optional<T> toOptional ( ) {
			return Optional.empty();
		}

		@SuppressWarnings("unchecked")
		@Override
		public <R> Shreddable<R> map ( Function<? super T, ? extends R> fn ) {
			// nothing to map -- a Shredded holds no value, so the same instance serves every element type
			return (Shreddable<R>) this;
		}

		@Override
		public T orElse ( T fallback ) {
			return fallback;
		}

		@Override
		public T orElseGet ( Supplier<? extends T> supplier ) {
			return supplier.get();
		}

	}

}
