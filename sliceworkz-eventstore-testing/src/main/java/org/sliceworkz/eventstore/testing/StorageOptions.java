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

/**
 * Storage configuration a scenario asks its backend for.
 * <p>
 * Most scenarios take {@link #defaults()}. The options exist because a handful of scenarios test
 * storage configuration itself and cannot use a stock store: a result-limit scenario needs a store
 * built with that limit, a prefix scenario needs a prefixed one, and an import scenario needs two
 * independent stores side by side in the same backend.
 * <p>
 * A backend applies what it can and ignores the rest — {@code prefix} is meaningless for an
 * in-memory store, and a backend that cannot honour {@code resultLimit} simply returns a store
 * without one, which is why {@link EventStoreBackend.Capability} exists to skip such scenarios
 * rather than let them fail.
 *
 * @param discriminator distinguishes several stores requested by the same scenario; backends turn
 *                      this into whatever keeps stores apart (a name, a table prefix, a directory)
 * @param resultLimit   absolute cap on rows a query may return, or {@code null} for the default
 * @param prefix        table/namespace prefix, or {@code null} for the backend default
 */
public record StorageOptions ( String discriminator, Integer resultLimit, String prefix ) {

	private static final StorageOptions DEFAULTS = new StorageOptions("default", null, null);

	/**
	 * Stock options: one store, backend defaults throughout.
	 *
	 * @return the default options
	 */
	public static StorageOptions defaults ( ) {
		return DEFAULTS;
	}

	/**
	 * @param discriminator distinguishes this store from others the same scenario asks for
	 * @return a copy with the given discriminator
	 */
	public StorageOptions withDiscriminator ( String discriminator ) {
		return new StorageOptions(discriminator, resultLimit, prefix);
	}

	/**
	 * @param resultLimit absolute cap on rows a query may return
	 * @return a copy with the given result limit
	 */
	public StorageOptions withResultLimit ( int resultLimit ) {
		return new StorageOptions(discriminator, resultLimit, prefix);
	}

	/**
	 * @param prefix table/namespace prefix
	 * @return a copy with the given prefix
	 */
	public StorageOptions withPrefix ( String prefix ) {
		return new StorageOptions(discriminator, resultLimit, prefix);
	}

}
