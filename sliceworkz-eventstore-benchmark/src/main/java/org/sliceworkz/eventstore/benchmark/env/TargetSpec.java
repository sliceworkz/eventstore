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

import java.time.Duration;

/**
 * Which store to measure, and how it is configured.
 *
 * <p>Everything here is a property of the <em>store</em> rather than of its contents; what the store
 * holds is a {@code CorpusSpec}. The split matters because one corpus is measured through several
 * targets -- the same ten million events read once with metrics off and once with an unlimited
 * purpose cap -- and because a corpus is expensive to build while a target is free to open.
 *
 * @param backend which storage implementation
 * @param server where PostgreSQL comes from; ignored for {@link Backend#INMEM}
 * @param image the container image for {@link PostgresServer#TESTCONTAINERS}, e.g. {@code postgres:18}
 * @param metrics how much instrumentation the store carries
 * @param shredding whether a shredding codec is configured, which the {@code crm} context requires
 * @param resultLimit the storage-wide absolute result limit, or {@code null} for none
 * @param schemaMode what the store is allowed to do to the schema when it opens
 * @param notificationStartupTimeout how long {@code build()} waits for LISTEN/NOTIFY to register
 * @param appendPlanning how PostgreSQL may plan the DCB check; ignored for {@link Backend#INMEM}
 * @param appendCheck which SQL shape the DCB check is stated in; ignored for {@link Backend#INMEM}
 */
public record TargetSpec (
		Backend backend,
		PostgresServer server,
		String image,
		MetricsMode metrics,
		boolean shredding,
		Integer resultLimit,
		SchemaMode schemaMode,
		Duration notificationStartupTimeout,
		AppendPlanning appendPlanning,
		AppendCheck appendCheck ) {

	/** Which storage implementation is under measurement. */
	public enum Backend {
		/**
		 * The zero-IO baseline. Not a deployment target -- its job is to answer "what does the library
		 * cost on top of the database", which no PostgreSQL measurement alone can separate out.
		 */
		INMEM,
		/** The production backend, and what the published numbers are about. */
		POSTGRES
	}

	/** Where the PostgreSQL server comes from. */
	public enum PostgresServer {
		/**
		 * A Testcontainers container, started once per JVM per image by the harness the TCK already
		 * uses. Zero setup, and reproducible in the sense that everyone gets the same image -- but it
		 * is a container running stock defaults (128MB of {@code shared_buffers}, untuned WAL) on
		 * whatever the host happens to be, so it is sound for comparing two runs on one machine and
		 * weak as a published capacity number.
		 */
		TESTCONTAINERS,
		/**
		 * A server configured outside the suite, reached through {@code db.properties}. This is what
		 * published numbers are measured against, because the settings that decide them are then
		 * deliberate rather than inherited.
		 */
		EXTERNAL
	}

	/**
	 * How much instrumentation the store carries. A dimension rather than a setting: the suite is
	 * expected to answer what the library's own meters cost, and that question needs the same
	 * workload run with and without them.
	 */
	public enum MetricsMode {
		/**
		 * No instrumentation. A store still needs a registry -- the constructor rejects null -- so this
		 * uses a composite with no children attached, whose meters are no-ops. As close to "off" as the
		 * API allows.
		 */
		OFF,
		/**
		 * A real registry with the default cap of 1000 distinct {@code purpose} tag values. What a
		 * sensible deployment looks like.
		 */
		CAPPED,
		/**
		 * A real registry with no cap. Interesting only against a {@code per-entity} stream design,
		 * where it is the configuration that registers a meter per entity -- the behaviour the cap
		 * exists to prevent, measured rather than asserted.
		 */
		UNLIMITED
	}

	/**
	 * What a store may do to the schema as it opens.
	 *
	 * <p>Deliberately narrower than {@code DatabaseInitMode}: there is no mode here that drops
	 * anything. A corpus costs minutes to build and is shared by every profile that names it, so a
	 * benchmark that could drop one by starting up is a benchmark that will eventually do it. The
	 * provisioner drops tables when it decides to rebuild, and nothing else does.
	 */
	public enum SchemaMode {
		/** Create whatever is missing, then validate. What provisioning uses. */
		ENSURE,
		/** Validate only. What a measurement run uses against a corpus that already exists. */
		VALIDATE,
		/** Touch nothing. For an external server whose schema a DBA owns. */
		NONE
	}

	/**
	 * How PostgreSQL is allowed to plan the DCB consistency check — a dimension rather than a setting,
	 * for the same reason {@link MetricsMode} is one: the suite exists to say what it costs, and that
	 * question needs the same workload run both ways.
	 *
	 * <p>The check is a re-used prepared statement, so the server holds a custom plan built from the
	 * actual values and a generic one built against default selectivity, and adopts the generic plan
	 * once its estimate looks no worse. That comparison is the thing under measurement: a DCB check
	 * expects <em>no rows</em> while a {@code NOT EXISTS} is priced by how soon a row turns up, so each
	 * added fact makes the generic plan look cheaper and the custom one dearer, and past the crossing
	 * every append scans the whole table for a row that is not there.
	 *
	 * <p><b>A third value pinning the generic plan was tried and is gone.</b> The large tier appeared to
	 * show the comparison failing the other way round, on a capture that had been read off the wrong
	 * workload; measured against the same corpus it was 20× worse than the default, because at ten
	 * million events the generic plan for one type plus one tag is a sequential scan of the whole table.
	 * That is what the third target bought — a plan reading turned into a measurement, and the
	 * measurement said no.
	 */
	public enum AppendPlanning {

		/** What the library does unless told otherwise: PostgreSQL chooses. */
		SERVER_DEFAULT,

		/** Every conditional append planned from its own values, at the cost of planning per append. */
		PER_APPEND
	}

	/**
	 * Which SQL shape the DCB check is stated in — a dimension for the same reason
	 * {@link AppendPlanning} is: the two shapes bill in different currencies (the entity's whole
	 * history against the stream's traffic since the cursor), and which is cheaper is a property of
	 * the boundary, not of the store. Running both against one corpus is what turns that argument
	 * into a measurement. See {@code PostgresEventStorageImpl.ConditionalAppendCheck}.
	 *
	 * <p>The prediction to hold the run against, written before it: {@code SCAN_FROM_CURSOR} should
	 * be drastically faster on the corpus's fat entities (whose last event sits near the stream head,
	 * so almost nothing follows the cursor) and drastically slower on its thin ones (whose last event
	 * is old, so proving absence walks a large tail of the stream) — with the expected walk length
	 * about {@code streamSize / entityHistory}. Over a Zipf entity walk that puts the mean in the
	 * tail's hands, so the headline number may well get <em>worse</em> while the fat-entity case
	 * collapses. If that is what comes back, neither shape is a default and the store should choose
	 * per append from cursor freshness.
	 */
	public enum AppendCheck {

		/** The library's default: {@code WHERE NOT EXISTS}, index choice left to the planner. */
		NOT_EXISTS,

		/** The check as an ordered probe walking the position index forward from the cursor. */
		SCAN_FROM_CURSOR
	}

	/** The default LISTEN/NOTIFY startup deadline: generous, because a cold pool is not a failure. */
	public static final Duration DEFAULT_NOTIFICATION_STARTUP_TIMEOUT = Duration.ofSeconds(30);

	public TargetSpec {
		if ( backend == null ) {
			throw new IllegalArgumentException("a target needs a backend");
		}
		if ( metrics == null ) {
			metrics = MetricsMode.OFF;
		}
		if ( schemaMode == null ) {
			schemaMode = SchemaMode.ENSURE;
		}
		if ( notificationStartupTimeout == null ) {
			notificationStartupTimeout = DEFAULT_NOTIFICATION_STARTUP_TIMEOUT;
		}
		if ( appendPlanning == null ) {
			appendPlanning = AppendPlanning.SERVER_DEFAULT;
		}
		if ( appendCheck == null ) {
			appendCheck = AppendCheck.NOT_EXISTS;
		}
		if ( backend == Backend.POSTGRES ) {
			if ( server == null ) {
				server = PostgresServer.TESTCONTAINERS;
			}
			if ( server == PostgresServer.TESTCONTAINERS && ( image == null || image.isBlank() ) ) {
				image = "postgres:18";
			}
		}
		if ( resultLimit != null && resultLimit <= 0 ) {
			throw new IllegalArgumentException("resultLimit must be positive, was " + resultLimit);
		}
	}

	/** The in-memory baseline, with no instrumentation and no shredding. */
	public static TargetSpec inmem ( ) {
		return new TargetSpec(Backend.INMEM, null, null, MetricsMode.OFF, false, null, SchemaMode.ENSURE,
				null, null, null);
	}

	/** A containerised PostgreSQL of the given image, with no instrumentation and no shredding. */
	public static TargetSpec postgres ( String image ) {
		return new TargetSpec(Backend.POSTGRES, PostgresServer.TESTCONTAINERS, image,
				MetricsMode.OFF, false, null, SchemaMode.ENSURE, null, null, null);
	}

	/** Whether measuring this target needs a Docker daemon. */
	public boolean requiresDocker ( ) {
		return backend == Backend.POSTGRES && server == PostgresServer.TESTCONTAINERS;
	}

	/** A short human-readable name, used in reports and in JMH parameter values. */
	public String describe ( ) {
		StringBuilder description = new StringBuilder();
		description.append(switch ( backend ) {
			case INMEM -> "inmem";
			case POSTGRES -> server == PostgresServer.EXTERNAL ? "postgres:external" : image;
		});
		description.append("/metrics=").append(metrics.name().toLowerCase());
		if ( shredding ) {
			description.append("/shredding");
		}
		if ( resultLimit != null ) {
			description.append("/limit=").append(resultLimit);
		}
		// Only when it is not the default, so every existing profile's target keeps the name its
		// committed baselines were recorded under -- and so a profile measuring the pair gets two
		// distinguishable targets rather than two rows the report would silently collapse into one.
		switch ( appendPlanning ) {
			case PER_APPEND -> description.append("/plan=per-append");
			case SERVER_DEFAULT -> { }
		}
		switch ( appendCheck ) {
			case SCAN_FROM_CURSOR -> description.append("/check=scan-from-cursor");
			case NOT_EXISTS -> { }
		}
		return description.toString();
	}
}
