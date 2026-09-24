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
 * targets -- the same ten million events read in memory and on PostgreSQL, or with and without a
 * result limit -- and because a corpus is expensive to build while a target is free to open.
 *
 * <p>A target is measured unobserved: the store reports to {@code EventStoreObserver.NOOP}. What an
 * observer costs is the observer's own, and is measured with it, outside this suite.
 *
 * @param backend which storage implementation
 * @param server where PostgreSQL comes from; ignored for {@link Backend#INMEM}
 * @param image the container image for {@link PostgresServer#TESTCONTAINERS}, e.g. {@code postgres:18}
 * @param shredding whether a shredding codec is configured, which the {@code crm} context requires
 * @param resultLimit the storage-wide absolute result limit, or {@code null} for none
 * @param schemaMode what the store is allowed to do to the schema when it opens
 * @param notificationStartupTimeout how long {@code build()} waits for LISTEN/NOTIFY to register
 */
public record TargetSpec (
		Backend backend,
		PostgresServer server,
		String image,
		boolean shredding,
		Integer resultLimit,
		SchemaMode schemaMode,
		Duration notificationStartupTimeout ) {

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

	/** The default LISTEN/NOTIFY startup deadline: generous, because a cold pool is not a failure. */
	public static final Duration DEFAULT_NOTIFICATION_STARTUP_TIMEOUT = Duration.ofSeconds(30);

	public TargetSpec {
		if ( backend == null ) {
			throw new IllegalArgumentException("a target needs a backend");
		}
		if ( schemaMode == null ) {
			schemaMode = SchemaMode.ENSURE;
		}
		if ( notificationStartupTimeout == null ) {
			notificationStartupTimeout = DEFAULT_NOTIFICATION_STARTUP_TIMEOUT;
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

	/** The in-memory baseline, with no shredding. */
	public static TargetSpec inmem ( ) {
		return new TargetSpec(Backend.INMEM, null, null, false, null, SchemaMode.ENSURE, null);
	}

	/** A containerised PostgreSQL of the given image, with no shredding. */
	public static TargetSpec postgres ( String image ) {
		return new TargetSpec(Backend.POSTGRES, PostgresServer.TESTCONTAINERS, image,
				false, null, SchemaMode.ENSURE, null);
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
		if ( shredding ) {
			description.append("/shredding");
		}
		if ( resultLimit != null ) {
			description.append("/limit=").append(resultLimit);
		}
		return description.toString();
	}
}
