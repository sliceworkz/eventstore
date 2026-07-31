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
package org.sliceworkz.eventstore.testing;

import java.util.Optional;

import javax.sql.DataSource;

import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * A storage implementation the shared scenarios can be run against.
 * <p>
 * Implement this once for your {@link EventStorage}, register it (see {@link EventStoreBackends}),
 * and every scenario in {@code org.sliceworkz.eventstore.testing.tck} runs against it. This is the
 * whole extension point: scenarios never name a concrete storage class.
 * <p>
 * Contract for implementors:
 * <ul>
 *   <li>{@link #createEventStorage(StorageOptions)} must return a store that is <em>empty</em>. It
 *       is called before every single test method, and scenarios assert on absolute counts, so a
 *       store carrying data from a previous test fails them. A backend sharing one durable database
 *       across tests must drop and recreate its schema here.</li>
 *   <li>{@link #destroyEventStorage(EventStorage)} must release whatever the store holds (thread
 *       pools, file handles). It runs after every test method, including failing ones.</li>
 *   <li>Both are called on the same thread, in strict alternation, per test method.</li>
 * </ul>
 * Anything expensive and shareable — a container, a connection pool — belongs in the backend
 * instance rather than in the per-test store, and should be torn down in {@link #close()}.
 */
public interface EventStoreBackend {

	/**
	 * Short, stable, human-readable name. It ends up in the test display name
	 * ({@code testQueryOneEvent [postgres:18]}), so keep it terse and unambiguous.
	 *
	 * @return the backend name
	 */
	String name ( );

	/**
	 * Creates an <em>empty</em> store. Called before every test method.
	 *
	 * @param options what the scenario needs of the store
	 * @return a fresh, empty storage
	 */
	EventStorage createEventStorage ( StorageOptions options );

	/**
	 * Releases a store handed out by {@link #createEventStorage(StorageOptions)}. Called after
	 * every test method.
	 * <p>
	 * The default closes it, which is all most backends need: {@link EventStorage#close()} is
	 * contractually required to release everything the storage created and to block until it has.
	 * Override only to release something the backend itself created around the store — a pool it
	 * handed in, say, which the storage will deliberately not close.
	 *
	 * @param storage the storage to release; never {@code null}
	 */
	default void destroyEventStorage ( EventStorage storage ) {
		storage.close();
	}

	/**
	 * Releases resources shared across tests (containers, pools). Called once, when the whole run
	 * against this backend is over. The default does nothing.
	 */
	default void close ( ) {
	}

	/**
	 * Whether this backend supports an optional part of the contract. Scenarios requiring an
	 * unsupported capability are skipped rather than failed — see {@link ForEachBackend#requires()}.
	 *
	 * @param capability the capability in question
	 * @return {@code true} if supported; the default supports everything except
	 *         {@link Capability#RAW_STORAGE_ACCESS}
	 */
	default boolean supports ( Capability capability ) {
		return capability != Capability.RAW_STORAGE_ACCESS;
	}

	/**
	 * A {@link DataSource} reaching the store's underlying database directly, bypassing the
	 * event store entirely.
	 * <p>
	 * This exists for exactly one kind of scenario: verifying that the store behaves correctly
	 * after its data is changed <em>behind its back</em> — an operator erasing personal data with
	 * an {@code UPDATE}. Such a scenario cannot be written against the SPI by definition, and is
	 * skipped on backends that return empty here.
	 *
	 * @param storage the storage whose database is wanted
	 * @return the underlying DataSource, or empty if the backend is not SQL-backed
	 */
	default Optional<DataSource> dataSource ( EventStorage storage ) {
		return Optional.empty();
	}

	/**
	 * Optional parts of the {@link EventStorage} contract.
	 * <p>
	 * A backend may legitimately not implement these; scenarios that need them are skipped.
	 */
	enum Capability {

		/**
		 * {@link EventStorage#importEvents(java.util.List, EventStorage.ImportMode)} is implemented.
		 * The SPI default throws {@code UnsupportedOperationException}, so this is genuinely optional.
		 */
		IMPORT,

		/** Several independent stores can coexist in one backend, kept apart by a table/name prefix. */
		TABLE_PREFIX,

		/** A store can be built with an absolute cap on query result size. */
		RESULT_LIMIT,

		/**
		 * {@link #dataSource(EventStorage)} returns a usable handle on the underlying database, so
		 * a scenario can modify stored data out of band.
		 */
		RAW_STORAGE_ACCESS

	}

}
