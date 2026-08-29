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
package org.sliceworkz.eventstore.benchmark.env;

import java.util.Optional;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.spi.EventStorage;

/**
 * An opened store, together with the handles a benchmark needs that an {@link EventStore} does not
 * expose.
 *
 * <p>Three of them, each earning its place:
 *
 * <ul>
 *   <li>the {@link EventStorage}, because {@code importEvents} is on the SPI and is the only way to
 *       write a corpus at speed -- and the only way at all to write a legacy event;</li>
 *   <li>the {@link DataSource}, because restoring a corpus between iterations, capturing
 *       {@code EXPLAIN (ANALYZE, BUFFERS)} for a read workload and reading the server's settings all
 *       happen below the store;</li>
 *   <li>the {@link MeterRegistry}, because the library's own meters are the cheapest available
 *       account of where a measured millisecond went.</li>
 * </ul>
 *
 * <p><b>Closing order is the whole reason this is a class rather than a record of three fields.</b>
 * The store must close before the storage, and a {@code DataSource} the suite created must close
 * after both -- the other order leaves the LISTEN/NOTIFY monitors retrying against a dead pool,
 * which they cannot distinguish from a database outage. A {@code DataSource} handed in from outside
 * (a shared container pool) is never closed here.
 */
public final class BenchmarkTarget implements AutoCloseable {

	private final TargetSpec spec;
	private final String prefix;
	private final EventStore store;
	private final EventStorage storage;
	private final DataSource dataSource;
	private final boolean ownsDataSource;
	private final MeterRegistry meterRegistry;

	private boolean closed;

	BenchmarkTarget ( TargetSpec spec, String prefix, EventStore store, EventStorage storage,
			DataSource dataSource, boolean ownsDataSource, MeterRegistry meterRegistry ) {
		this.spec = spec;
		this.prefix = prefix;
		this.store = store;
		this.storage = storage;
		this.dataSource = dataSource;
		this.ownsDataSource = ownsDataSource;
		this.meterRegistry = meterRegistry;
	}

	/** How this target is configured. */
	public TargetSpec spec ( ) {
		return spec;
	}

	/**
	 * The table prefix this store's objects live under, which is the corpus it is attached to. Empty
	 * for the in-memory backend, which separates stores by name instead.
	 */
	public String prefix ( ) {
		return prefix;
	}

	public EventStore store ( ) {
		return store;
	}

	public EventStorage storage ( ) {
		return storage;
	}

	/** The database beneath the store, absent for the in-memory backend. */
	public Optional<DataSource> dataSource ( ) {
		return Optional.ofNullable(dataSource);
	}

	public MeterRegistry meterRegistry ( ) {
		return meterRegistry;
	}

	/** Whether this target can answer SQL -- and so whether plan capture and restore are available. */
	public boolean isSqlBacked ( ) {
		return dataSource != null;
	}

	@Override
	public void close ( ) {
		if ( closed ) {
			return;
		}
		closed = true;

		// store, then storage, then any pool this created: see the class comment
		RuntimeException failure = null;
		try {
			store.close();
		} catch ( RuntimeException e ) {
			failure = e;
		}
		try {
			storage.close();
		} catch ( RuntimeException e ) {
			failure = addSuppressed(failure, e);
		}
		if ( ownsDataSource && dataSource instanceof AutoCloseable closeable ) {
			try {
				closeable.close();
			} catch ( Exception e ) {
				failure = addSuppressed(failure, new IllegalStateException("closing the benchmark DataSource failed", e));
			}
		}
		if ( failure != null ) {
			throw failure;
		}
	}

	private static RuntimeException addSuppressed ( RuntimeException first, RuntimeException next ) {
		if ( first == null ) {
			return next;
		}
		first.addSuppressed(next);
		return first;
	}
}
