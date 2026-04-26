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

import java.util.List;

import javax.sql.DataSource;

import com.github.f4b6a3.uuid.UuidCreator;

import org.sliceworkz.eventstore.events.EventId;
import org.sliceworkz.eventstore.query.Limit;

/**
 * Legacy PostgreSQL-backed event storage implementation for PostgreSQL versions 13–17.
 * <p>
 * Generates UUIDv7 identifiers in Java and binds them as a {@code ?::uuid} parameter, since the
 * native server-side {@code uuidv7()} function is only available from PostgreSQL 18 onwards.
 * Selected automatically by {@link PostgresEventStorage.Builder#build()} when the connected
 * server reports a major version below 18.
 * <p>
 * Once PG17 support is dropped this class can be deleted along with the corresponding branch in
 * the builder, leaving the version-agnostic {@link PostgresEventStorageImpl} as the sole
 * implementation.
 */
public class PostgresLegacyEventStorageImpl extends PostgresEventStorageImpl {

	public PostgresLegacyEventStorageImpl ( String name, DataSource dataSource, DataSource monitoringDataSource, Limit absoluteLimit, String prefix ) {
		super(name, dataSource, monitoringDataSource, absoluteLimit, prefix);
	}

	@Override
	protected String appendValuesRowFragment ( ) {
		return "(?::uuid, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) ";
	}

	@Override
	protected void bindEventIdParameter ( List<Object> parameters ) {
		EventId id = new EventId(UuidCreator.getTimeOrderedEpochPlus1().toString());
		parameters.add(id.value());
	}

}
