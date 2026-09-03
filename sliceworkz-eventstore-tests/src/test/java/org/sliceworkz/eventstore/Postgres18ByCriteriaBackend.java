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
package org.sliceworkz.eventstore;

import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl.ConditionalAppendCheck;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.testing.StorageOptions;
import org.sliceworkz.eventstore.testing.backend.AbstractPostgresBackend;
import org.sliceworkz.eventstore.testing.backend.PostgresContainer;

/**
 * PostgreSQL 18 with the <b>experimental</b> {@link ConditionalAppendCheck#BY_CRITERIA} shape: the
 * check derived per append from the criteria — the ordered probe when an expected reference is
 * present, the custom-planned {@code NOT EXISTS} when not. This is the candidate default, so the
 * whole TCK holds <em>both branches and the routing between them</em> to the boundary contract:
 * the locking scenarios exercise cursor-bearing criteria, {@code AppendCriteriaTest} and the
 * empty-stream expectation exercise the no-cursor branch, and {@code ConcurrentOptimisticLockingTest}
 * races the boundary whichever branch it lands on.
 * <p>
 * Registered from this repo-internal module rather than the published testing module because the
 * shape is an experiment: it lives and dies with the branch measuring it.
 */
public class Postgres18ByCriteriaBackend extends AbstractPostgresBackend {

	public Postgres18ByCriteriaBackend ( ) {
		super(PostgresContainer.IMAGE_PG18);
	}

	@Override
	public String name ( ) {
		return super.name() + ":by-criteria";
	}

	@Override
	public EventStorage createEventStorage ( StorageOptions options ) {
		EventStorage storage = super.createEventStorage(options);
		((PostgresEventStorageImpl) storage)
				.setConditionalAppendCheck(ConditionalAppendCheck.BY_CRITERIA);
		return storage;
	}

}
