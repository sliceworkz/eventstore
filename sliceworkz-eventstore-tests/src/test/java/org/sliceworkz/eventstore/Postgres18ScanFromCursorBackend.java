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
 * PostgreSQL 18 with the <b>experimental</b> {@link ConditionalAppendCheck#SCAN_FROM_CURSOR} shape for
 * the DCB check, so the whole TCK — {@code OptimisticLockingTest},
 * {@code ConcurrentOptimisticLockingTest}, {@code AppendCriteriaTest},
 * {@code EventQueryUntilBoundaryTest}, all of it — holds the alternative SQL to the same boundary
 * contract as the shipped shape. A check that is faster and admits an append it should refuse is not
 * an optimisation, and no benchmark run would notice; the TCK is what would.
 * <p>
 * Registered from this repo-internal module rather than the published testing module because the shape
 * is an experiment: it lives and dies with the branch measuring it.
 */
public class Postgres18ScanFromCursorBackend extends AbstractPostgresBackend {

	public Postgres18ScanFromCursorBackend ( ) {
		super(PostgresContainer.IMAGE_PG18);
	}

	@Override
	public String name ( ) {
		return super.name() + ":scan-from-cursor";
	}

	@Override
	public EventStorage createEventStorage ( StorageOptions options ) {
		EventStorage storage = super.createEventStorage(options);
		// The shape is read per append, so setting it on the built storage covers every operation a
		// scenario will run. Same pattern as setConditionalAppendPlanning.
		((PostgresEventStorageImpl) storage)
				.setConditionalAppendCheck(ConditionalAppendCheck.SCAN_FROM_CURSOR);
		return storage;
	}

}
