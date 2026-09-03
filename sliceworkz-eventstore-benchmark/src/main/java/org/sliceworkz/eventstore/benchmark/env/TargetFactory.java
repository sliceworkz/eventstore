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

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.EventStoreFactory;
import org.sliceworkz.eventstore.MeterOptions;
import org.sliceworkz.eventstore.benchmark.env.TargetSpec.SchemaMode;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.infra.inmem.shredding.InMemoryShreddingKeyStore;
import org.sliceworkz.eventstore.infra.postgres.DataSourceFactory;
import org.sliceworkz.eventstore.infra.postgres.DatabaseInitMode;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorage;
import org.sliceworkz.eventstore.infra.postgres.PostgresEventStorageImpl;
import org.sliceworkz.eventstore.infra.postgres.shredding.PostgresShreddingKeyStore;
import org.sliceworkz.eventstore.shredding.AesGcmShreddingCodec;
import org.sliceworkz.eventstore.shredding.ShreddingCodec;
import org.sliceworkz.eventstore.spi.EventStorage;
import org.sliceworkz.eventstore.testing.backend.PostgresContainer;

/**
 * Opens a {@link BenchmarkTarget} from a {@link TargetSpec}.
 *
 * <p>The store and the storage are built <b>separately</b> rather than through {@code buildStore()},
 * because a benchmark needs both handles and {@code buildStore()} returns only the store. That means
 * reproducing the one thing {@code buildStore()} does that the plain factory does not: passing the
 * shredding codec to {@code EventStoreFactory.eventStore(storage, registry, options, codec)}. A
 * store assembled without it rejects every {@code crm} event at {@code getEventStream}, and does so
 * at stream-creation time rather than on the append -- which reads as a configuration bug in the
 * benchmark rather than in the wiring, so it is worth being explicit about.
 */
public final class TargetFactory {

	/**
	 * Set by the launcher on a JMH fork so the fork attaches to the container the launcher already
	 * started, instead of starting one of its own.
	 *
	 * @see #open(TargetSpec, String)
	 */
	public static final String INHERITED_JDBC_URL_PROPERTY = "benchmark.jdbc.url";

	/** Credentials of the harness's containers, which the URL alone does not carry. */
	public static final String INHERITED_USER_PROPERTY = "benchmark.jdbc.user";
	public static final String INHERITED_PASSWORD_PROPERTY = "benchmark.jdbc.password";

	private TargetFactory ( ) { }

	/** A pool over a URL handed down from the launcher. Small: one fork does not need many. */
	private static DataSource poolFor ( String jdbcUrl ) {
		com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
		config.setJdbcUrl(jdbcUrl);
		config.setUsername(System.getProperty(INHERITED_USER_PROPERTY, "sa"));
		config.setPassword(System.getProperty(INHERITED_PASSWORD_PROPERTY, "pwd"));
		config.setMinimumIdle(1);
		return new com.zaxxer.hikari.HikariDataSource(config);
	}

	/**
	 * Opens a target against the objects under {@code prefix}.
	 *
	 * @param spec how the store is configured
	 * @param prefix the table prefix, i.e. which corpus this attaches to; ignored by the in-memory
	 *        backend, which separates stores by name instead. Must satisfy the backend's own
	 *        validation ({@code [a-zA-Z0-9_]+_}, at most 32 characters).
	 */
	public static BenchmarkTarget open ( TargetSpec spec, String prefix ) {
		return switch ( spec.backend() ) {
			case INMEM -> openInMemory(spec, prefix);
			case POSTGRES -> openPostgres(spec, prefix);
		};
	}

	private static BenchmarkTarget openInMemory ( TargetSpec spec, String prefix ) {
		InMemoryEventStorage.Builder builder = InMemoryEventStorage.newBuilder()
				.name(prefix == null || prefix.isBlank() ? "benchmark" : prefix);
		if ( spec.resultLimit() != null ) {
			builder.resultLimit(spec.resultLimit());
		}

		EventStorage storage = builder.build();
		ShreddingCodec codec = spec.shredding()
				? AesGcmShreddingCodec.over(new InMemoryShreddingKeyStore())
				: null;

		MeterRegistry registry = registryFor(spec);
		EventStore store = EventStoreFactory.get().eventStore(storage, registry, meterOptionsFor(spec), codec);

		return new BenchmarkTarget(spec, prefix, store, storage, null, false, registry);
	}

	private static BenchmarkTarget openPostgres ( TargetSpec spec, String prefix ) {
		DataSource dataSource;
		DataSource monitoringDataSource;
		boolean ownsDataSource;

		switch ( spec.server() ) {
			case TESTCONTAINERS -> {
				String inherited = System.getProperty(INHERITED_JDBC_URL_PROPERTY);
				if ( inherited != null ) {
					// A JMH fork is a fresh JVM.  Left to itself it would call PostgresContainer and
					// start a *second* container -- one per fork, each with an empty schema and none of
					// them holding the corpus.  The launcher therefore starts the container once and
					// passes its URL down; see JmhRunner.
					dataSource = poolFor(inherited);
					monitoringDataSource = dataSource;
					ownsDataSource = true;
				} else {
					// one container per JVM per image, and the pool belongs to the container harness --
					// closing it here would break every other target sharing the same image
					dataSource = PostgresContainer.dataSource(spec.image());
					monitoringDataSource = dataSource;
					ownsDataSource = false;
				}
			}
			case EXTERNAL -> {
				// two datasources on purpose: LISTEN/NOTIFY does not survive a transaction pooler, so
				// db.properties keeps a direct 'nonpooled' alongside the pooled one
				dataSource = DataSourceFactory.fromConfiguration("pooled");
				monitoringDataSource = DataSourceFactory.fromConfiguration("nonpooled");
				ownsDataSource = true;
			}
			default -> throw new IllegalStateException("unreachable: " + spec.server());
		}

		try {
			PostgresEventStorage.Builder builder = PostgresEventStorage.newBuilder()
					.name("benchmark")
					.prefix(prefix)
					.dataSource(dataSource)
					.monitoringDataSource(monitoringDataSource)
					.databaseInitMode(initModeFor(spec.schemaMode()))
					.notificationStartupTimeout(spec.notificationStartupTimeout())
					.conditionalAppendPlanning(switch ( spec.appendPlanning() ) {
						case PER_APPEND -> PostgresEventStorageImpl.ConditionalAppendPlanning.PER_APPEND;
						case SERVER_DEFAULT -> PostgresEventStorageImpl.ConditionalAppendPlanning.SERVER_DEFAULT;
					})
					.conditionalAppendCheck(switch ( spec.appendCheck() ) {
						case SCAN_FROM_CURSOR -> PostgresEventStorageImpl.ConditionalAppendCheck.SCAN_FROM_CURSOR;
						case BY_CRITERIA -> PostgresEventStorageImpl.ConditionalAppendCheck.BY_CRITERIA;
						case NOT_EXISTS -> PostgresEventStorageImpl.ConditionalAppendCheck.NOT_EXISTS;
					});
			if ( spec.resultLimit() != null ) {
				builder.resultLimit(spec.resultLimit());
			}

			EventStorage storage = builder.build();

			// ensure-schema.sql creates <prefix>shredding_keys unconditionally, so a key store on this
			// store's own database needs no extra builder call -- only a schema that has been ensured
			// at least once, which provisioning guarantees
			ShreddingCodec codec = spec.shredding()
					? AesGcmShreddingCodec.over(PostgresShreddingKeyStore.on(dataSource, prefix))
					: null;

			MeterRegistry registry = registryFor(spec);
			EventStore store = EventStoreFactory.get().eventStore(storage, registry, meterOptionsFor(spec), codec);

			return new BenchmarkTarget(spec, prefix, store, storage, dataSource, ownsDataSource, registry);
		} catch ( RuntimeException e ) {
			// the storage never reached the caller, so nothing else will close what this created
			if ( ownsDataSource ) {
				closeQuietly(dataSource, e);
				closeQuietly(monitoringDataSource, e);
			}
			throw e;
		}
	}

	private static void closeQuietly ( DataSource dataSource, RuntimeException attachTo ) {
		if ( dataSource instanceof AutoCloseable closeable ) {
			try {
				closeable.close();
			} catch ( Exception suppressed ) {
				attachTo.addSuppressed(suppressed);
			}
		}
	}

	private static DatabaseInitMode initModeFor ( SchemaMode schemaMode ) {
		return switch ( schemaMode ) {
			case ENSURE -> DatabaseInitMode.ENSURE;
			case VALIDATE -> DatabaseInitMode.VALIDATE;
			case NONE -> DatabaseInitMode.NONE;
		};
	}

	/**
	 * A store cannot be built without a registry -- the constructor rejects null -- so "metrics off" is
	 * a {@link CompositeMeterRegistry} with no children, whose meters are no-ops. That is the closest
	 * the API allows to not instrumenting at all, and it is what the metrics-cost comparison measures
	 * against.
	 */
	private static MeterRegistry registryFor ( TargetSpec spec ) {
		return switch ( spec.metrics() ) {
			case OFF -> new CompositeMeterRegistry();
			case CAPPED, UNLIMITED -> new SimpleMeterRegistry();
		};
	}

	private static MeterOptions meterOptionsFor ( TargetSpec spec ) {
		return switch ( spec.metrics() ) {
			// with a no-op registry the cap changes nothing measurable, but the store keeps its own
			// per-purpose state keyed on the tags it asks for, so the bound still has to be applied
			case OFF -> MeterOptions.withoutPurposeBreakdown();
			case CAPPED -> MeterOptions.defaults();
			case UNLIMITED -> MeterOptions.withUnlimitedPurposeTagValues();
		};
	}
}
