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
 * {@link AbstractPostgresBackend} pinned to PostgreSQL 17, with the no-argument constructor a
 * {@code ServiceLoader} needs.
 * <p>
 * PostgreSQL 17 has no native {@code uuidv7()}, so this exercises
 * {@code PostgresLegacyEventStorageImpl} — a genuinely different code path from
 * {@link Postgres18Backend}, which is why both are worth running.
 */
public class Postgres17Backend extends AbstractPostgresBackend {

	/** Creates the backend for {@link PostgresContainer#IMAGE_PG17}. */
	public Postgres17Backend ( ) {
		super(PostgresContainer.IMAGE_PG17);
	}

}
