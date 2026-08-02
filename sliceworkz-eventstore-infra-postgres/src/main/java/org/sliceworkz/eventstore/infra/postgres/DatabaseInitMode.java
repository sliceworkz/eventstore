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
	 * Create missing database objects, bring the functions and triggers up to date, then validate.
	 * <p>
	 * This is the default mode, and it is safe to run repeatedly and concurrently — the scripts run as
	 * one transaction under a per-prefix advisory lock, so several instances starting together queue
	 * rather than race on the system catalogs.
	 * <p>
	 * <strong>Tables, columns and indexes are only ever created, never altered.</strong> An existing
	 * table keeps its definition; a missing index is added. The functions and triggers, by contrast, are
	 * brought to the definition this release ships: the functions via {@code CREATE OR REPLACE}, the
	 * triggers by comparing the installed shape and recreating only when it differs. Without that, a
	 * changed function body would never reach a database that already had the old one, and the store
	 * would report success while its notifications were dead.
	 * <p>
	 * This is not a migration mechanism: there is no version marker, and a change that needs
	 * {@code ALTER TABLE} still has to be applied by hand. See {@code SCHEMA-MIGRATION.md} in this
	 * module.
	 */
	ENSURE,

	/**
	 * Drop all event store objects — tables, and the functions the triggers use — and recreate them
	 * from scratch, then validate. The drop and the recreate are one transaction.
	 * <p>
	 * <strong>Warning:</strong> This mode is destructive — all existing event data will be lost.
	 * Use only for test environments, fresh deployments, or when a clean slate is explicitly needed.
	 */
	INITIALIZE

}
