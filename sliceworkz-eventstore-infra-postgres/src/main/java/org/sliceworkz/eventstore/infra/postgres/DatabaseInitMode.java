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
package org.sliceworkz.eventstore.infra.postgres;

/**
 * Controls how the database schema is handled during {@link PostgresEventStorage} startup.
 * <p>
 * Each mode defines a different level of schema management, from fully hands-off to
 * destructive re-creation. The mode is set on the {@link PostgresEventStorage.Builder}
 * via {@link PostgresEventStorage.Builder#databaseInitMode(DatabaseInitMode)} or via
 * the convenience methods {@link PostgresEventStorage.Builder#validateDatabase()},
 * {@link PostgresEventStorage.Builder#ensureDatabase()}, and
 * {@link PostgresEventStorage.Builder#initializeDatabase()}.
 *
 * @see PostgresEventStorage.Builder
 */
public enum DatabaseInitMode {

	/**
	 * Assume the database schema exists. No validation, no creation.
	 * <p>
	 * Use this in environments where the schema is managed externally (e.g., by a DBA
	 * or migration tool) and startup time should be minimized.
	 */
	NONE,

	/**
	 * Validate that all required database objects exist and are correctly defined.
	 * Throws {@link org.sliceworkz.eventstore.spi.EventStorageException} if anything is
	 * missing or malformed.
	 * <p>
	 * No objects are created or modified. This is a read-only check.
	 */
	VALIDATE,

	/**
	 * Create missing database objects if they do not exist, leaving existing objects untouched.
	 * After creation, the schema is validated.
	 * <p>
	 * This is the default mode. It is safe to run repeatedly — existing tables, indexes,
	 * functions, and triggers are not modified. If an existing object has an incompatible
	 * definition, the subsequent validation will detect and report it.
	 */
	ENSURE,

	/**
	 * Drop all event store objects and recreate them from scratch. After creation, the
	 * schema is validated.
	 * <p>
	 * <strong>Warning:</strong> This mode is destructive — all existing event data will be lost.
	 * Use only for test environments, fresh deployments, or when a clean slate is explicitly needed.
	 */
	INITIALIZE

}
