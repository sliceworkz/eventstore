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

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.MeterOptions;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingKeyStore;
import org.sliceworkz.eventstore.spi.EventStorage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Base class for tests that need an {@link EventStore} over some {@link EventStorage}.
 * <p>
 * It owns the store lifecycle: a fresh, empty storage before each test, torn down after. Where that
 * storage comes from is decided one of two ways.
 * <p>
 * <b>Against every registered backend</b> — annotate the tests with {@link ForEachBackend} and the
 * storage is supplied by each {@link EventStoreBackend} in turn. This is how the shared TCK
 * scenarios run, and how a third-party storage implementation runs them:
 * <pre>{@code
 * class MyScenarios extends AbstractEventStoreTest {
 *     @ForEachBackend
 *     void appendedEventIsQueryable ( ) {
 *         eventStore().getEventStream(...).append(...);
 *     }
 * }
 * }</pre>
 * <b>Against one storage you build yourself</b> — override {@link #createEventStorage()} and use a
 * plain {@code @Test}:
 * <pre>{@code
 * class MyStorageTest extends AbstractEventStoreTest {
 *     @Override protected EventStorage createEventStorage ( ) {
 *         return MyEventStorage.newBuilder().build();
 *     }
 *     @Test void whatever ( ) { ... }
 * }
 * }</pre>
 * Override {@link #storageOptions()} when the scenario needs a specially configured store (a result
 * limit, a table prefix) rather than a stock one.
 */
public abstract class AbstractEventStoreTest {

	private EventStoreBackend backend;
	private EventStorage eventStorage;
	private EventStore eventStore;

	/**
	 * Called by {@link ForEachBackend}'s extension before each invocation. Not for subclasses —
	 * override {@link #createEventStorage()} to supply a storage by hand.
	 *
	 * @param backend the backend for the invocation about to run
	 */
	final void useBackend ( EventStoreBackend backend ) {
		this.backend = backend;
	}

	@BeforeEach
	public void setUp ( ) {
		this.eventStorage = createEventStorage();
		this.eventStore = EventStoreFactory.get().eventStore(eventStorage);
	}

	@AfterEach
	public void tearDown ( ) {
		// the store first, then the storage under it: that is the order the lifecycle contract
		// prescribes, and dropping the store's reference instead would leak its notification
		// executors -- a pair of them per test method
		if ( eventStore != null ) {
			eventStore.close();
			eventStore = null;
		}
		if ( eventStorage != null ) {
			destroyEventStorage(eventStorage);
			eventStorage = null;
		}
	}

	/**
	 * What the scenario needs of its store. The default is a stock store; override to ask for a
	 * result limit or a prefix.
	 *
	 * @return the options passed to the backend
	 */
	protected StorageOptions storageOptions ( ) {
		return StorageOptions.defaults();
	}

	/**
	 * Produces the storage under test. The default asks the backend bound by {@link ForEachBackend};
	 * override to build one directly, which also makes {@link #storageOptions()} irrelevant.
	 *
	 * @return a fresh, empty storage
	 */
	protected EventStorage createEventStorage ( ) {
		return backend().createEventStorage(storageOptions());
	}

	/**
	 * Releases a storage produced by {@link #createEventStorage()}. The default delegates to the
	 * backend; override alongside {@link #createEventStorage()} when building storage by hand.
	 *
	 * @param storage the storage to release
	 */
	protected void destroyEventStorage ( EventStorage storage ) {
		if ( backend != null ) {
			backend.destroyEventStorage(storage);
		}
	}

	/**
	 * @return the event store over {@link #eventStorage()}, valid for the duration of one test
	 */
	protected EventStore eventStore ( ) {
		return eventStore;
	}

	/**
	 * @return the storage under test, valid for the duration of one test
	 */
	protected EventStorage eventStorage ( ) {
		return eventStorage;
	}

	/**
	 * An event store over the same storage that can protect and erase personal data.
	 * <p>
	 * Built on the key store this backend supplies — the SQL table on PostgreSQL, the file-backed one on
	 * inmem-fs, in-memory otherwise — so the shredding scenarios exercise each backend's own key
	 * storage rather than the same in-memory implementation every time.
	 * <p>
	 * Created fresh on each call, and the caller need not close it: the store does not own the storage,
	 * and none of the shipped key stores owns a resource the storage will not release.
	 *
	 * @return a store with shredding configured, over {@link #eventStorage()}
	 */
	protected EventStore eventStoreWithShredding ( ) {
		return eventStoreWithShredding(backend().shreddingKeyStore(eventStorage()));
	}

	/**
	 * An event store over the same storage, protecting personal data with a key store of your choosing.
	 * <p>
	 * For scenarios that need to drive the key store directly — asserting what an erasure recorded, or
	 * standing in a key store that fails, to check that an outage is not reported as an erasure.
	 *
	 * @param shreddingKeyStore where keys are minted, resolved and destroyed
	 * @return a store with shredding configured, over {@link #eventStorage()}
	 */
	protected EventStore eventStoreWithShredding ( ShreddingKeyStore shreddingKeyStore ) {
		return EventStoreFactory.get().eventStore(eventStorage(), new SimpleMeterRegistry(), MeterOptions.defaults(),
				AesGcmShreddingCodec.over(shreddingKeyStore));
	}

	/**
	 * The backend supplying this invocation's storage.
	 *
	 * @return the bound backend
	 * @throws IllegalStateException if the test is not annotated {@link ForEachBackend} and does not
	 *                               override {@link #createEventStorage()}
	 */
	protected EventStoreBackend backend ( ) {
		if ( backend == null ) {
			throw new IllegalStateException(
					"""
					No backend bound. Annotate the test with @ForEachBackend to run it against every \
					registered EventStoreBackend, or override createEventStorage() to build one \
					directly.""");
		}
		return backend;
	}

	/**
	 * Direct database access to the storage under test, bypassing the event store — see
	 * {@link EventStoreBackend#dataSource(EventStorage)}. Empty unless the backend is SQL-backed.
	 *
	 * @return the underlying DataSource, if there is one
	 */
	protected Optional<DataSource> dataSource ( ) {
		return backend == null ? Optional.empty() : backend.dataSource(eventStorage);
	}

	/**
	 * Blocks until {@code waitForCriterion} holds, for up to three seconds.
	 * <p>
	 * For assertions on eventually-consistent listeners, where an append returns before every
	 * listener has been notified. Polls every 100ms; fails the test on timeout.
	 *
	 * @param waitForCriterion the condition to wait for
	 */
	protected void waitBecauseOfEventualConsistency ( BooleanSupplier waitForCriterion ) {
		waitBecauseOfEventualConsistency(waitForCriterion, Duration.ofMillis(3000));
	}

	/**
	 * Blocks until {@code waitForCriterion} holds, for up to {@code atMost}.
	 *
	 * @param waitForCriterion the condition to wait for
	 * @param atMost           how long to keep polling before failing
	 */
	protected void waitBecauseOfEventualConsistency ( BooleanSupplier waitForCriterion, Duration atMost ) {
		await()
			.atMost(atMost)
			.with()
			.pollInterval(Duration.ofMillis(100))
			.until(waitForCriterion::getAsBoolean);
	}

}
