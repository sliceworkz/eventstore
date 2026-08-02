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
package org.sliceworkz.eventstore.testing.backend;

/**
 * {@link AbstractPostgresBackend} pinned to PostgreSQL 16, with the no-argument constructor a
 * {@code ServiceLoader} needs.
 * <p>
 * PostgreSQL 16 is the oldest major version the library supports, which is the whole reason this
 * backend exists: a support claim nothing runs against is a claim, not a fact. Before it was added,
 * the compliance run covered 17 and 18 only while the documentation promised considerably older
 * versions.
 * <p>
 * Like {@link Postgres17Backend} it has no native {@code uuidv7()} and so exercises
 * {@code PostgresLegacyEventStorageImpl}.
 */
public class Postgres16Backend extends AbstractPostgresBackend {

	/** Creates the backend for {@link PostgresContainer#IMAGE_PG16}. */
	public Postgres16Backend ( ) {
		super(PostgresContainer.IMAGE_PG16);
	}

}
