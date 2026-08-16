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
/**
 * Erasing personal data from an append-only log, by destroying keys rather than rewriting events.
 *
 * <h2>The problem</h2>
 * Events are immutable historical facts; GDPR Article 17 gives individuals the right to have their
 * personal data erased. Rewriting or deleting events to satisfy that is not really available: it breaks
 * the log's ordering guarantees, strands bookmarks, and — on a database with write-ahead logging,
 * replicas and backups — does not actually erase anything for as long as any of those retain the old
 * row.
 *
 * <h2>The approach</h2>
 * Personal data is wrapped in a {@link org.sliceworkz.eventstore.shredding.Shreddable}, bound to a
 * {@link org.sliceworkz.eventstore.shredding.DataSubject}, and encrypted on append under a key held
 * for that subject. Erasure destroys the key. The event is never touched, so it stays byte-identical
 * wherever it has already been copied, and every copy becomes unreadable at the same instant.
 * <pre>{@code
 * record CustomerRegistered(
 *         String customerId,                       // pseudonymous — survives erasure
 *         Shreddable<String> name,
 *         Shreddable<String> email) implements CustomerEvent { }
 *
 * DataSubject alice = DataSubject.of("customer", "alice-42");
 *
 * stream.append(AppendCriteria.none(), Event.of(
 *         new CustomerRegistered("alice-42",
 *                                Shreddable.of("Alice Martin", alice),
 *                                Shreddable.of("alice@example.org", alice)),
 *         Tags.of("customer", "alice-42")));
 *
 * // later
 * eventStore.erase(alice, ErasureReason.of("GDPR art.17 request #4711"));
 *
 * // the event still reads; the personal data does not
 * event.data().customerId();              // "alice-42"
 * event.data().name();                    // Shredded[customer/alice-42/default, k-7f2a91c4]
 * event.data().name().orElse("[erased]"); // "[erased]"
 * }</pre>
 *
 * <h2>What is in this package</h2>
 * <ul>
 *   <li>{@link org.sliceworkz.eventstore.shredding.Shreddable} — the wrapper, and the type-level
 *       declaration that a component is personal data</li>
 *   <li>{@link org.sliceworkz.eventstore.shredding.DataSubject} — whose data it is, and under which
 *       retention category</li>
 *   <li>{@link org.sliceworkz.eventstore.shredding.ShreddingCodec} — the outer seam: encryption and key
 *       handling together, for an implementation that keeps keys in an HSM</li>
 *   <li>{@link org.sliceworkz.eventstore.shredding.ShreddingKeyStore} — the narrow seam: keep the
 *       shipped AES-256-GCM encryption, hold the keys elsewhere</li>
 *   <li>{@link org.sliceworkz.eventstore.shredding.ErasureReason} and
 *       {@link org.sliceworkz.eventstore.shredding.ErasureReport} — the audit trail, since the events
 *       themselves record nothing about the erasure</li>
 * </ul>
 *
 * <h2>Two rules that are easy to break</h2>
 * <ul>
 *   <li><b>The subject id must not itself be personal data.</b> It is stored in the clear and survives
 *       erasure by construction. Use a customer number, not an email address.</li>
 *   <li><b>"Key destroyed" and "key store unreachable" are different answers.</b> An implementation
 *       that reports an outage as an erasure will have its projections write those gaps into read
 *       models permanently. See {@link org.sliceworkz.eventstore.shredding.ShreddingException}.</li>
 * </ul>
 *
 * <h2>What this does not do</h2>
 * Erasure does not notify anything. Read models, caches, search indexes and downstream systems that
 * already copied the personal data keep their copies, and projections hold bookmarks so they never
 * re-read the affected events. Re-projecting after an erasure is the application's responsibility.
 */
package org.sliceworkz.eventstore.shredding;
