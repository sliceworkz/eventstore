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
 * {@link AbstractPostgresBackend} pinned to PostgreSQL 18, with the no-argument constructor a
 * {@code ServiceLoader} needs.
 * <p>
 * PostgreSQL 18 provides native {@code uuidv7()}, so this exercises the current
 * {@code PostgresEventStorageImpl} rather than the legacy path {@link Postgres17Backend} takes.
 */
public class Postgres18Backend extends AbstractPostgresBackend {

	/** Creates the backend for {@link PostgresContainer#IMAGE_PG18}. */
	public Postgres18Backend ( ) {
		super(PostgresContainer.IMAGE_PG18);
	}

}
