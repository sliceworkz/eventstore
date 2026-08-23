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
package org.sliceworkz.eventstore.benchmark.corpus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.LongConsumer;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sliceworkz.eventstore.benchmark.domain.InventoryEvent;
import org.sliceworkz.eventstore.benchmark.domain.LegacySalesEvent;
import org.sliceworkz.eventstore.benchmark.domain.SalesEvent;
import org.sliceworkz.eventstore.benchmark.env.BenchmarkTarget;
import org.sliceworkz.eventstore.benchmark.env.TargetFactory;
import org.sliceworkz.eventstore.benchmark.env.TargetSpec;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;

import tools.jackson.databind.json.JsonMapper;

/**
 * Decides whether a corpus already exists and can be reused, or has to be built -- and builds it.
 *
 * <p>Reuse is what makes the large tier practical: ten million events cost minutes to write and are
 * then measured against for days. But reuse is also the most dangerous thing the suite does, because
 * a wrongly reused corpus produces numbers that are entirely plausible and describe the wrong data.
 * Three checks stand between those outcomes, and a corpus is rebuilt unless all three pass:
 *
 * <ol>
 *   <li>the fingerprint is present, which it is by construction -- the fingerprint <em>is</em> the
 *       table prefix, so there is no way to look in the wrong place;</li>
 *   <li>the manifest was written by this generator version;</li>
 *   <li>the manifest's event count matches the store's actual row count, which is what catches a
 *       provisioning run that died half way.</li>
 * </ol>
 *
 * <p><b>Reuse only applies to SQL-backed targets.</b> An in-memory store starts empty in every
 * process, so there is nothing to find and provisioning always generates. That is fine at the small
 * and medium tiers and hopeless at the large one, which is why the large tier is a Postgres
 * proposition.
 */
public final class CorpusProvisioner {

	private static final Logger LOGGER = LoggerFactory.getLogger(CorpusProvisioner.class);

	/** Above this, generating into a store that cannot be reused is worth warning about. */
	private static final long IN_MEMORY_WARN_THRESHOLD = 200_000L;

	/** How many events to read back and deserialize as a shape check after provisioning. */
	private static final int VERIFY_SAMPLE_SIZE = 50;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CorpusSpec spec;
	private final String fingerprint;
	private final String prefix;

	public CorpusProvisioner ( CorpusSpec spec ) {
		this.spec = spec;
		this.fingerprint = CorpusFingerprint.of(spec);
		this.prefix = CorpusFingerprint.prefixFor(spec);
	}

	public String prefix ( ) {
		return prefix;
	}

	public String fingerprint ( ) {
		return fingerprint;
	}

	/** What happened, so a caller can report "reused" versus "built" honestly. */
	public record Outcome ( CorpusFacts facts, boolean rebuilt, long eventCount, Duration took, String reason ) { }

	/**
	 * An open store with its corpus in place, and what it took to get there.
	 *
	 * <p>The target is handed back <b>open</b>, and that is the whole point of this type. An in-memory
	 * store holds its corpus only for as long as it is open, so provisioning into one and then closing
	 * it generates several thousand events and throws them away -- and the next thing to open a store
	 * gets an empty one. That is not a hypothetical: it is what the workload dry run did on its first
	 * run, reporting "a query matching nothing" for every read.
	 *
	 * <p>For a SQL-backed target the data outlives the handle either way, so keeping it open costs
	 * nothing and the two cases stay one code path.
	 */
	public record Prepared ( BenchmarkTarget target, Outcome outcome ) implements AutoCloseable {

		@Override
		public void close ( ) {
			target.close();
		}
	}

	/**
	 * Opens a store, makes sure the corpus is in it, and hands both back.
	 *
	 * <p>The caller owns the returned target and must close it.
	 *
	 * @param targetSpec how to open the store; provisioning forces {@code ENSURE} regardless, since
	 *        creating the schema is exactly what it is for
	 * @param force rebuild even if a usable corpus is already there
	 */
	public Prepared open ( TargetSpec targetSpec, boolean force, LongConsumer progress ) {
		long started = System.nanoTime();

		TargetSpec provisioning = new TargetSpec(targetSpec.backend(), targetSpec.server(), targetSpec.image(),
				targetSpec.metrics(), spec.requiresShredding() || targetSpec.shredding(), targetSpec.resultLimit(),
				TargetSpec.SchemaMode.ENSURE, targetSpec.notificationStartupTimeout());

		BenchmarkTarget target = TargetFactory.open(provisioning, prefix);
		try {
			return new Prepared(target, provisionInto(target, force, started, progress));
		} catch ( RuntimeException e ) {
			target.close();
			throw e;
		}
	}

	private Outcome provisionInto ( BenchmarkTarget target, boolean force, long started, LongConsumer progress ) {
		Optional<DataSource> dataSource = target.dataSource();

		if ( dataSource.isEmpty() ) {
			return generateInMemory(target, started, progress);
		}

		ManifestStore manifests = new ManifestStore(dataSource.get());
		manifests.ensureTable();

		if ( !force ) {
			Optional<Outcome> reused = tryReuse(manifests, started);
			if ( reused.isPresent() ) {
				return reused.get();
			}
		}

		// forget first, then drop, then build: a manifest sitting beside a half-built store is the
		// one state that could let a later run reuse an incomplete corpus
		manifests.forget(fingerprint);
		dropCorpusTables(dataSource.get());
		return generateAndRecord(target, manifests, started, progress);
	}

	/**
	 * Provisions and closes, for callers that only want the corpus to exist afterwards.
	 *
	 * <p>Meaningful only for a SQL-backed target. Against an in-memory one this generates a corpus and
	 * immediately discards it, which {@link Outcome#reason()} says out loud rather than reporting a
	 * build that left nothing behind.
	 */
	public Outcome ensure ( TargetSpec targetSpec, boolean force, LongConsumer progress ) {
		try ( Prepared prepared = open(targetSpec, force, progress) ) {
			return prepared.outcome();
		}
	}

	private Optional<Outcome> tryReuse ( ManifestStore manifests, long started ) {
		Optional<CorpusManifest> manifest = manifests.find(fingerprint);
		if ( manifest.isEmpty() ) {
			return Optional.empty();
		}

		long actual = manifests.countEvents(prefix).orElse(-1L);
		CorpusManifest found = manifest.get();
		if ( !found.isUsable(actual) ) {
			LOGGER.info("rebuilding corpus {}: {}", fingerprint, found.reasonNotUsable(actual));
			return Optional.empty();
		}

		CorpusFacts facts = JSON.readValue(found.factsJson(), CorpusFacts.class);
		facts.requireUsable();
		LOGGER.info("reusing corpus {} ({} events, provisioned {})", fingerprint, actual, found.provisionedAt());
		return Optional.of(new Outcome(facts, false, actual,
				Duration.ofNanos(System.nanoTime() - started), "already provisioned"));
	}

	private Outcome generateInMemory ( BenchmarkTarget target, long started, LongConsumer progress ) {
		if ( spec.volume() > IN_MEMORY_WARN_THRESHOLD ) {
			LOGGER.warn("generating {} events into an in-memory store: nothing persists, so this is paid again "
					+ "on every run. The large tier belongs on PostgreSQL.", spec.volume());
		}
		CorpusFacts facts = new CorpusGenerator(spec).generateInto(target.storage(), progress);
		CorpusFacts completed = withRuntimeFacts(target, facts);
		completed.requireUsable();
		verifySample(target);
		return new Outcome(completed, true, completed.count(CorpusFacts.COUNT_TOTAL),
				Duration.ofNanos(System.nanoTime() - started),
				"generated in memory; it lives only as long as this store is open");
	}

	private Outcome generateAndRecord ( BenchmarkTarget target, ManifestStore manifests, long started,
			LongConsumer progress ) {
		CorpusFacts facts = new CorpusGenerator(spec).generateInto(target.storage(), progress);
		CorpusFacts completed = withRuntimeFacts(target, facts);
		completed.requireUsable();
		verifySample(target);

		long actual = manifests.countEvents(prefix).orElse(completed.count(CorpusFacts.COUNT_TOTAL));
		manifests.save(ManifestStore.manifestFor(fingerprint, JSON.writeValueAsString(spec), actual,
				JSON.writeValueAsString(completed)));

		// statistics for a table that was empty a moment ago are worse than none: the planner would
		// choose a sequential scan for every query and the first measurements would be nonsense
		analyze(target.dataSource().orElseThrow());

		return new Outcome(completed, true, actual, Duration.ofNanos(System.nanoTime() - started), "built");
	}

	/**
	 * Fills in the facts the generator cannot know, because they are assigned by the storage rather
	 * than by the generator: the position at the halfway point, which cursor-walk workloads start
	 * from.
	 */
	/** Ten pages, matching what the bounded replay workload claims to cover. */
	private static final int REPLAY_BOUND_EVENTS = 5_000;

	/**
	 * Fills in the facts the generator cannot know, because they are assigned by the storage rather
	 * than by the generator.
	 *
	 * <p>Both cursors are read off the <b>inventory</b> context rather than the whole store, because
	 * that is the context the read workloads query. A midpoint taken across every context lands
	 * wherever the noise happens to put it -- measured, a cursor walk from the store-wide midpoint
	 * covered 1121 events instead of the 2500 it was asking for, because it started near the end of
	 * inventory and ran out.
	 *
	 * <p>They are real references, not positions with a synthetic transaction id. Boundaries compare
	 * the whole {@code (tx, position)} tuple, so a fabricated {@code tx} of zero matches everything
	 * ahead of it and one of {@code Long.MAX_VALUE} bounds nothing -- which is exactly how the
	 * "ten batches" replay came to process the entire corpus.
	 */
	private CorpusFacts withRuntimeFacts ( BenchmarkTarget target, CorpusFacts facts ) {
		EventStream<InventoryEvent> inventory = target.store()
				.getEventStream(EventStreamId.forContext("inventory").anyPurpose(), InventoryEvent.class);

		long inventoryCount = inventory.query(EventQuery.matchAll()).count();

		String midCursor = referenceAt(inventory, Math.max(inventoryCount / 2, 1));
		String replayUntil = referenceAt(inventory, Math.min(REPLAY_BOUND_EVENTS, Math.max(inventoryCount, 1)));

		Double meanBytes = meanPayloadBytes(target, "sales", VERIFY_SAMPLE_SIZE).stream().boxed().findFirst()
				.orElse(null);

		return new CorpusFacts(facts.hotEntity(), facts.coldEntity(), facts.needleTagValue(), facts.swatheTagValue(),
				facts.matchCounts(), midCursor, replayUntil, facts.knownEventId(), facts.streamPurposes(), meanBytes);
	}

	/** The reference of the n-th event of a stream, rendered for storage in the manifest. */
	private static String referenceAt ( EventStream<InventoryEvent> stream, long ordinal ) {
		return stream.query(EventQuery.matchAll().limit(ordinal))
				.map(Event::reference)
				.reduce(( first, second ) -> second)
				.map(EventReference::toString)
				.orElse(null);
	}

	/**
	 * Reads a sample back through a <em>typed</em> stream.
	 *
	 * <p>The generator writes payload JSON with its own Jackson mapper, configured to match the one
	 * the store reads with. That is a coupling, and if it ever breaks the failure would otherwise
	 * surface as every read failing, hours later, in a benchmark rather than in provisioning. Fifty
	 * events cost nothing and turn that into an immediate, obvious error.
	 */
	private void verifySample ( BenchmarkTarget target ) {
		readSample(target, "inventory", InventoryEvent.class, null);

		// Sales too, and through the upcasters when the corpus is a legacy one.  Reading only inventory
		// would let a LEGACY corpus whose upcasters throw pass provisioning and fail hours later inside
		// a benchmark, where it would look like a store problem rather than a fixture one.
		if ( spec.payload() == CorpusSpec.PayloadProfile.LEGACY ) {
			readSample(target, "sales", SalesEvent.class, LegacySalesEvent.class);
		} else {
			readSample(target, "sales", SalesEvent.class, null);
		}
	}

	private <T> void readSample ( BenchmarkTarget target, String context, Class<T> root, Class<?> historicalRoot ) {
		EventStreamId id = EventStreamId.forContext(context).anyPurpose();
		EventStream<T> stream = historicalRoot == null
				? target.store().getEventStream(id, root)
				: target.store().getEventStream(id, root, historicalRoot);

		List<Event<T>> sample;
		try {
			sample = stream.query(EventQuery.matchAll().limit(VERIFY_SAMPLE_SIZE)).toList();
		} catch ( RuntimeException e ) {
			throw new IllegalStateException(
					"the corpus was written but its '%s' events cannot be read back: the generator's payload JSON does not match what the store's serde expects"
							.formatted(context),
					e);
		}
		if ( sample.isEmpty() ) {
			throw new IllegalStateException(
					"the corpus was written but a read of the '%s' context returned nothing".formatted(context));
		}
	}

	/**
	 * The mean serialized size of a sample of events, so the report can state what a payload profile
	 * actually costs rather than what its name suggests.
	 */
	public static OptionalDouble meanPayloadBytes ( BenchmarkTarget target, String context, int sampleSize ) {
		EventStream<Object> raw = target.store()
				.getEventStream(EventStreamId.forContext(context).anyPurpose());
		return raw.query(EventQuery.matchAll().limit(sampleSize))
				.mapToInt(event -> JSON.writeValueAsString(event.data()).length())
				.average();
	}

	/** Drops just this corpus's tables, leaving every other corpus in the database alone. */
	private void dropCorpusTables ( DataSource dataSource ) {
		List<String> tables = List.of("bookmarks", "lease_contenders", "leases", "shredding_keys", "events");
		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement() ) {
			for ( String table : tables ) {
				statement.execute("DROP TABLE IF EXISTS %s%s CASCADE".formatted(prefix, table));
			}
			LOGGER.info("dropped the tables of corpus {}", fingerprint);
		} catch ( SQLException e ) {
			throw new IllegalStateException("could not drop the tables of corpus " + fingerprint, e);
		}
	}

	private void analyze ( DataSource dataSource ) {
		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement() ) {
			statement.execute("ANALYZE %sevents".formatted(prefix));
		} catch ( SQLException e ) {
			// not fatal, but worth being loud about: without statistics the first numbers are nonsense
			LOGGER.warn("could not ANALYZE {}events; the planner has no statistics and early measurements "
					+ "will not be representative", prefix, e);
		}
	}

	/** Lists the corpora a database holds, for the operational "what is in here?" question. */
	public static List<CorpusManifest> inventory ( DataSource dataSource ) {
		ManifestStore manifests = new ManifestStore(dataSource);
		manifests.ensureTable();
		return manifests.findAll();
	}

	/** The size on disk of a corpus's events table, for the report. */
	public static Optional<String> tableSize ( DataSource dataSource, String prefix ) {
		try ( Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery(
						"SELECT pg_size_pretty(pg_total_relation_size('%sevents'))".formatted(prefix)) ) {
			return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
		} catch ( SQLException e ) {
			return Optional.empty();
		}
	}
}
