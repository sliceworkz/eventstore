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

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.EventType;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.query.EventFilter;
import org.sliceworkz.eventstore.query.EventQuery.Direction;
import org.sliceworkz.eventstore.query.Limit;
import org.sliceworkz.eventstore.spi.EventStorage.EventToStore;
import org.sliceworkz.eventstore.spi.EventStorage.StoredEvent;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The load harness of the append-pipelining experiment ({@link AppendPipelining}); see
 * {@code docs/design/dcb-single-round-trip-append.md} for the scenarios and the results. Not a test: surefire's
 * name patterns do not match it, so no build runs it. It lives in the module's test tree, in the
 * storage's package, because it selects the mode and reads the per-phase timings through the
 * package-private hooks the experiment added rather than through public API.
 *
 * <p>One invocation is one phase against one external PostgreSQL:
 * <ul>
 *   <li>{@code phase=init} drops and recreates the tables and writes the corpus;</li>
 *   <li>{@code phase=run} runs deciders (and background writers) for a warm-up and a measured window,
 *       in one mode, and writes one CSV row per append attempt and per decision of the window;</li>
 *   <li>{@code phase=verify} checks the DCB invariant over everything the runs stored: every decision
 *       records how many events of its account it decided on, and must be the event at exactly that
 *       index of the account's history — a decision on a state another one had already changed would
 *       repeat or skip an index;</li>
 *   <li>{@code phase=explain} prints {@code EXPLAIN (ANALYZE, BUFFERS)} of the locked check's probe,
 *       custom and generic, from a stale reference and from the head.</li>
 * </ul>
 * Several {@code phase=run} processes against one database are the multi-JVM case.
 *
 * <p>What a decider does, per decision: take the reference {@code N} (the stream head, read before the
 * boundary and bounding it — the recommended decider — or, with {@code reference=last}, the last event
 * of its boundary, the naive one); read its boundary; spend {@code evalMicros} deciding, during which,
 * with probability {@code conflictPct}, another party appends a relevant event; and append
 * {@code batch} events with criteria {@code (F, N)}, re-deciding on a conflict.
 *
 * <p>Two shapes stress what a plan made from the bound values could get wrong: {@code accountHistory}
 * gives every account that many earlier events, spread through the corpus, so a plan answering from the
 * tag index collects a long history where the cursor walk from a fresh reference passes a handful of
 * rows; and {@code facts} OR-s that many items into the boundary (the account's own events, then typed
 * facts on the account that never occur), which is where planning a DCB check has had cliffs before.
 */
public final class DcbAppendBenchmark {

	static final EventStreamId STREAM = EventStreamId.forContext("bench").withPurpose("ledger");

	static final Pattern SEEN = Pattern.compile("\"seen\": ?(\\d+)");

	public static void main ( String[] args ) throws Exception {
		Map<String, String> a = new LinkedHashMap<>();
		for ( String arg : args ) {
			int eq = arg.indexOf('=');
			a.put(arg.substring(0, eq), arg.substring(eq + 1));
		}
		Config config = new Config(a);
		switch ( config.phase ) {
			case "init" -> init(config);
			case "run" -> run(config);
			case "verify" -> verify(config);
			case "explain" -> explain(config);
			default -> throw new IllegalArgumentException("phase " + config.phase);
		}
		System.exit(0);
	}

	static final class Config {
		final Map<String, String> args;
		final String phase;
		final String jdbcUrl;
		final String user;
		final String password;
		final String prefix;
		final AppendPipelining mode;
		final int writers;
		final int accountsPerWriter;
		final int instance;
		final int instances;
		final long evalMicros;
		final double conflictPct;
		final int batch;
		final int backgroundRate;
		final int backgroundBatch;
		final int corpus;
		final int accountHistory;
		final int facts;
		final String reference;
		final int warmupSec;
		final int durationSec;
		final long seed;
		final Path out;

		Config ( Map<String, String> a ) {
			this.args = a;
			phase = a.getOrDefault("phase", "run");
			jdbcUrl = a.getOrDefault("jdbcUrl", "jdbc:postgresql://localhost:55432/bench");
			user = a.getOrDefault("user", "bench");
			password = a.getOrDefault("password", "bench");
			prefix = a.getOrDefault("prefix", "dcbbench_");
			mode = AppendPipelining.parse(a.getOrDefault("mode", "off"));
			writers = Integer.parseInt(a.getOrDefault("writers", "8"));
			accountsPerWriter = Integer.parseInt(a.getOrDefault("accountsPerWriter", "50"));
			instance = Integer.parseInt(a.getOrDefault("instance", "0"));
			instances = Integer.parseInt(a.getOrDefault("instances", "1"));
			evalMicros = Long.parseLong(a.getOrDefault("evalMicros", "0"));
			conflictPct = Double.parseDouble(a.getOrDefault("conflictPct", "0"));
			batch = Integer.parseInt(a.getOrDefault("batch", "1"));
			backgroundRate = Integer.parseInt(a.getOrDefault("backgroundRate", "0"));
			backgroundBatch = Integer.parseInt(a.getOrDefault("backgroundBatch", "10"));
			corpus = Integer.parseInt(a.getOrDefault("corpus", "200000"));
			accountHistory = Integer.parseInt(a.getOrDefault("accountHistory", "0"));
			facts = Integer.parseInt(a.getOrDefault("facts", "1"));
			reference = a.getOrDefault("reference", "head");
			warmupSec = Integer.parseInt(a.getOrDefault("warmupSec", "5"));
			durationSec = Integer.parseInt(a.getOrDefault("durationSec", "15"));
			seed = Long.parseLong(a.getOrDefault("seed", "1"));
			out = Path.of(a.getOrDefault("out", "/tmp/dcbbench"));
		}

		String account ( int instance, int writer, int index ) {
			return "i%d-w%d-a%d".formatted(instance, writer, index);
		}

		/** the account's own events, OR-ed with {@code facts - 1} typed facts on the account that never occur */
		EventFilter boundary ( String account ) {
			EventFilter boundary = EventFilter.forTags("account", account);
			for ( int f = 1; f < facts; f++ ) {
				boundary = boundary.or(EventFilter.forTypes(EventType.named("Fact" + f)).tagged("account", account));
			}
			return boundary;
		}
	}

	static HikariDataSource pool ( Config config, int size ) {
		HikariConfig hikari = new HikariConfig();
		hikari.setJdbcUrl(config.jdbcUrl);
		hikari.setUsername(config.user);
		hikari.setPassword(config.password);
		hikari.setMaximumPoolSize(size);
		hikari.setMinimumIdle(size);
		return new HikariDataSource(hikari);
	}

	static PostgresEventStorageImpl storage ( Config config, HikariDataSource main, HikariDataSource monitoring, DatabaseInitMode initMode ) {
		return (PostgresEventStorageImpl) PostgresEventStorage.newBuilder()
				.name("dcbbench-" + config.instance)
				.prefix(config.prefix)
				.dataSource(main)
				.monitoringDataSource(monitoring)
				.databaseInitMode(initMode)
				.build();
	}

	// ------------------------------------------------------------------------------------------------
	// init
	// ------------------------------------------------------------------------------------------------

	/**
	 * The corpus: one opening event per account any instance's deciders will use, written first so a
	 * naive decider's reference starts {@code corpus} events behind the head, then {@code corpus} events
	 * on tags no decider reads, with {@code accountHistory} events per account spread evenly among them.
	 */
	static void init ( Config config ) throws Exception {
		try ( HikariDataSource main = pool(config, 4); HikariDataSource monitoring = pool(config, 2);
			  PostgresEventStorageImpl storage = storage(config, main, monitoring, DatabaseInitMode.RECREATE) ) {
			List<EventToStore> opened = new ArrayList<>();
			for ( int i = 0; i < config.instances; i++ ) {
				for ( int w = 0; w < config.writers; w++ ) {
					for ( int j = 0; j < config.accountsPerWriter; j++ ) {
						opened.add(new EventToStore(STREAM, EventType.named("AccountOpened"), "{}", Tags.of("account", config.account(i, w, j)), null));
					}
				}
			}
			for ( int from = 0; from < opened.size(); from += 1000 ) {
				storage.append(AppendCriteria.none(), STREAM, opened.subList(from, Math.min(opened.size(), from + 1000)));
			}
			List<EventToStore> chunk = new ArrayList<>(1000);
			long history = (long) opened.size() * config.accountHistory;
			long total = config.corpus + history;
			long historyWritten = 0;
			for ( long i = 0; i < total; i++ ) {
				if ( historyWritten < history && historyWritten * total <= history * i ) {
					EventToStore account = opened.get((int) (historyWritten % opened.size()));
					chunk.add(new EventToStore(STREAM, EventType.named("Historic"), "{}", account.tags(), null));
					historyWritten++;
				} else {
					chunk.add(background("corpus-" + (i % 1000)));
				}
				if ( chunk.size() == 1000 ) {
					storage.append(AppendCriteria.none(), STREAM, chunk);
					chunk.clear();
				}
			}
			if ( !chunk.isEmpty() ) {
				storage.append(AppendCriteria.none(), STREAM, chunk);
			}
			sql(main, "VACUUM ANALYZE " + config.prefix + "events");
			System.out.printf("init: %d accounts, %d corpus events, %d account history events%n", opened.size(), config.corpus, history);
		}
	}

	static EventToStore background ( String tag ) {
		return new EventToStore(STREAM, EventType.named("Noise"), "{\"n\": 1}", Tags.of("bg", tag), null);
	}

	// ------------------------------------------------------------------------------------------------
	// run
	// ------------------------------------------------------------------------------------------------

	/** the timing the storage reported for the append the current thread just made */
	static final ThreadLocal<AppendPipelining.Timing> LAST_TIMING = new ThreadLocal<>();

	/** with {@code auditConflicts=true}: where each conflict is looked up, on a connection of its own */
	static volatile HikariDataSource AUDIT;

	static void run ( Config config ) throws Exception {
		Files.createDirectories(config.out);
		int backgroundThreads = config.backgroundRate == 0 ? 0 : Math.max(1, config.backgroundRate / 4000);
		try ( HikariDataSource main = pool(config, config.writers + backgroundThreads + 4); HikariDataSource monitoring = pool(config, 2);
			  PostgresEventStorageImpl storage = storage(config, main, monitoring, DatabaseInitMode.NONE) ) {
			storage.appendPipelining(config.mode).appendTimingRecorder(LAST_TIMING::set);
			if ( Boolean.parseBoolean(config.args.getOrDefault("auditConflicts", "false")) ) {
				AUDIT = pool(config, 2);
			}

			AtomicBoolean measuring = new AtomicBoolean();
			AtomicBoolean stop = new AtomicBoolean();
			CountDownLatch done = new CountDownLatch(config.writers + backgroundThreads);
			List<Writer> writers = new ArrayList<>();
			for ( int w = 0; w < config.writers; w++ ) {
				Writer writer = new Writer(config, storage, w, measuring, stop, done);
				writers.add(writer);
				Thread.ofPlatform().name("decider-" + w).start(writer);
			}
			LongAdder backgroundEvents = new LongAdder();
			for ( int b = 0; b < backgroundThreads; b++ ) {
				int thread = b;
				Thread.ofPlatform().name("background-" + b).start(() -> {
					try {
						background(config, storage, thread, backgroundThreads, measuring, stop, backgroundEvents);
					} finally {
						done.countDown();
					}
				});
			}

			Thread.sleep(config.warmupSec * 1000L);
			String cpuContainer = config.instance == 0 ? config.args.get("cpuStatContainer") : null;
			long cpuBefore = containerCpuMicros(cpuContainer);
			DbStats before = DbStats.read(main, config);
			long backgroundBefore = backgroundEvents.sum();
			long windowStart = System.nanoTime();
			measuring.set(true);
			Thread.sleep(config.durationSec * 1000L);
			measuring.set(false);
			long windowNanos = System.nanoTime() - windowStart;
			DbStats after = DbStats.read(main, config);
			long dbCpuMicros = containerCpuMicros(cpuContainer) - cpuBefore;
			long background = backgroundEvents.sum() - backgroundBefore;
			stop.set(true);
			done.await();

			String run = "%s-i%d".formatted(config.args.getOrDefault("runId", "run"), config.instance);
			try ( PrintWriter appends = new PrintWriter(Files.newBufferedWriter(config.out.resolve(run + "-appends.csv")));
				  PrintWriter decisions = new PrintWriter(Files.newBufferedWriter(config.out.resolve(run + "-decisions.csv"))) ) {
				appends.println("append_ns,lock_wait_ns,lock_hold_ns,conflicted");
				decisions.println("decision_ns,attempts,read_ns");
				for ( Writer writer : writers ) {
					writer.appends.forEach(appends::println);
					writer.decisions.forEach(decisions::println);
				}
			}
			long committed = writers.stream().mapToLong(w -> w.committed).sum();
			long conflicts = writers.stream().mapToLong(w -> w.conflicts).sum();
			long allocated = writers.stream().mapToLong(w -> w.allocatedBytes).sum();
			try ( PrintWriter summary = new PrintWriter(Files.newBufferedWriter(config.out.resolve(run + "-summary.json"))) ) {
				summary.printf("{\"run\":\"%s\",\"mode\":\"%s\",\"window_ns\":%d,\"decisions\":%d,\"conflicts\":%d,"
						+ "\"background_events\":%d,\"allocated_bytes\":%d,\"db_cpu_us\":%d,\"db\":%s,\"args\":\"%s\"}%n",
						run, config.mode, windowNanos, committed, conflicts, background, allocated, cpuContainer == null ? -1 : dbCpuMicros,
						after.minus(before), config.args.toString().replace("\"", "'"));
			}
			System.out.printf("%s %s: %.1f decisions/s, %d conflicts, background %.0f ev/s%n", run, config.mode,
					committed / (windowNanos / 1e9), conflicts, background / (windowNanos / 1e9));
			if ( AUDIT != null ) {
				System.out.printf("%s %s audit (whole run): %d conflicts, %d committed-but-reported-conflicting, %d head behind own last write, %d other%n",
						run, config.mode, writers.stream().mapToLong(w -> w.audited).sum(), writers.stream().mapToLong(w -> w.phantoms).sum(),
						writers.stream().mapToLong(w -> w.headBehindOwnWrite).sum(), writers.stream().mapToLong(w -> w.otherConflicts).sum());
				AUDIT.close();
			}
		}
	}

	/** the database container's CPU time so far, from its cgroup; 0 when no container is named */
	static long containerCpuMicros ( String container ) throws IOException, InterruptedException {
		if ( container == null ) {
			return 0;
		}
		Process process = new ProcessBuilder("docker", "exec", container, "cat", "/sys/fs/cgroup/cpu.stat").redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		process.waitFor();
		for ( String line : output.split("\n") ) {
			if ( line.startsWith("usage_usec ") ) {
				return Long.parseLong(line.substring("usage_usec ".length()).strip());
			}
		}
		throw new IllegalStateException("no usage_usec in the cgroup of " + container + ": " + output);
	}

	static final class Writer implements Runnable {
		final Config config;
		final PostgresEventStorageImpl storage;
		final int index;
		final AtomicBoolean measuring;
		final AtomicBoolean stop;
		final CountDownLatch done;
		final List<String> appends = new ArrayList<>();
		final List<String> decisions = new ArrayList<>();
		long committed;
		long conflicts;
		EventReference lastCommitted;
		long audited, phantoms, headBehindOwnWrite, otherConflicts;
		long allocatedBytes;

		Writer ( Config config, PostgresEventStorageImpl storage, int index, AtomicBoolean measuring, AtomicBoolean stop, CountDownLatch done ) {
			this.config = config;
			this.storage = storage;
			this.index = index;
			this.measuring = measuring;
			this.stop = stop;
			this.done = done;
		}

		@Override
		public void run ( ) {
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			SplittableRandom random = new SplittableRandom(config.seed * 1_000_003L + config.instance * 1009L + index);
			boolean wasMeasuring = false;
			long allocatedAtStart = 0;
			try {
				while ( !stop.get() ) {
					boolean measure = measuring.get();
					if ( measure && !wasMeasuring ) {
						allocatedAtStart = threads.getCurrentThreadAllocatedBytes();
					} else if ( !measure && wasMeasuring ) {
						allocatedBytes += threads.getCurrentThreadAllocatedBytes() - allocatedAtStart;
					}
					wasMeasuring = measure;
					decide(random, measure);
				}
				if ( wasMeasuring ) {
					allocatedBytes += threads.getCurrentThreadAllocatedBytes() - allocatedAtStart;
				}
			} catch ( RuntimeException e ) {
				e.printStackTrace();
			} finally {
				done.countDown();
			}
		}

		void audit ( String key, EventReference n ) {
			audited++;
			try ( java.sql.Connection c = AUDIT.getConnection();
				  java.sql.PreparedStatement stmt = c.prepareStatement("SELECT count(*) FROM " + config.prefix + "events WHERE stream_context = ? AND stream_purpose = ? AND idempotency_key = ?") ) {
				stmt.setString(1, STREAM.context());
				stmt.setString(2, STREAM.purpose());
				stmt.setString(3, key);
				try ( java.sql.ResultSet rs = stmt.executeQuery() ) {
					rs.next();
					if ( rs.getLong(1) > 0 ) {
						phantoms++;
						System.out.printf("PHANTOM CONFLICT: writer %d, key %s committed but the append reported a conflict (reference %s)%n", index, key, n);
						return;
					}
				}
			} catch ( java.sql.SQLException e ) {
				throw new IllegalStateException(e);
			}
			if ( lastCommitted != null && (n == null || lastCommitted.happenedAfter(n)) ) {
				headBehindOwnWrite++;
			} else {
				otherConflicts++;
			}
		}

		void decide ( SplittableRandom random, boolean measure ) {
			String account = config.account(config.instance, index, random.nextInt(config.accountsPerWriter));
			EventFilter boundary = config.boundary(account);
			long started = System.nanoTime();
			long readNanos = 0;
			for ( int attempt = 1; ; attempt++ ) {
				long readStarted = System.nanoTime();
				EventReference n;
				int seen;
				if ( config.reference.equals("last") ) {
					List<StoredEvent> history = storage.query(boundary, STREAM, null, null, Limit.none(), Direction.FORWARD);
					n = history.isEmpty() ? null : history.getLast().reference();
					seen = history.size();
				} else {
					n = storage.head(STREAM).orElse(null);
					seen = storage.query(boundary, STREAM, null, n, Limit.none(), Direction.FORWARD).size();
				}
				readNanos += System.nanoTime() - readStarted;
				if ( config.evalMicros > 0 ) {
					LockSupport.parkNanos(config.evalMicros * 1000);
				}
				if ( config.conflictPct > 0 && random.nextDouble() * 100 < config.conflictPct ) {
					// another party changes the state this decision is being made on
					storage.append(AppendCriteria.none(), STREAM, List.of(new EventToStore(STREAM, EventType.named("Interfered"), "{}", Tags.of("account", account), null)));
				}
				List<EventToStore> events = new ArrayList<>(config.batch);
				for ( int k = 0; k < config.batch; k++ ) {
					events.add(new EventToStore(STREAM, EventType.named("Decided"), "{\"seen\": %d}".formatted(seen + k), Tags.of("account", account),
							AUDIT == null ? null : java.util.UUID.randomUUID().toString()));
				}
				LAST_TIMING.remove();
				long appendStarted = System.nanoTime();
				boolean conflicted;
				try {
					List<StoredEvent> stored = storage.append(AppendCriteria.of(boundary, n), STREAM, events);
					conflicted = false;
					lastCommitted = stored.getLast().reference();
				} catch ( OptimisticLockingException e ) {
					conflicted = true;
					if ( AUDIT != null ) {
						audit(events.getFirst().idempotencyKey(), n);
					}
				}
				long appendNanos = System.nanoTime() - appendStarted;
				AppendPipelining.Timing timing = LAST_TIMING.get();
				if ( measure && timing != null ) {
					appends.add("%d,%d,%d,%d".formatted(appendNanos, timing.lockWaitNanos(), timing.lockHoldNanos(), conflicted ? 1 : 0));
				}
				if ( !conflicted ) {
					if ( measure ) {
						committed++;
						decisions.add("%d,%d,%d".formatted(System.nanoTime() - started, attempt, readNanos));
					}
					return;
				}
				if ( measure ) {
					conflicts++;
				}
				if ( stop.get() ) {
					return;
				}
			}
		}
	}

	/** lock-free writers on tags no decider reads, paced to {@code backgroundRate} events a second in all */
	static void background ( Config config, PostgresEventStorageImpl storage, int thread, int threads,
			AtomicBoolean measuring, AtomicBoolean stop, LongAdder written ) {
		long intervalNanos = (long) (1e9 * config.backgroundBatch * threads / config.backgroundRate);
		long next = System.nanoTime();
		int counter = 0;
		while ( !stop.get() ) {
			List<EventToStore> events = new ArrayList<>(config.backgroundBatch);
			for ( int k = 0; k < config.backgroundBatch; k++ ) {
				events.add(background("live-" + thread + "-" + (counter++ % 1000)));
			}
			storage.append(AppendCriteria.none(), STREAM, events);
			written.add(events.size());
			next += intervalNanos;
			long wait = next - System.nanoTime();
			if ( wait > 0 ) {
				LockSupport.parkNanos(wait);
			} else if ( wait < -intervalNanos * 10 ) {
				next = System.nanoTime();   // far behind: do not burst to catch up
			}
		}
	}

	/** server-side counters for the measured window: WAL written, buffers, commits, rollbacks */
	record DbStats ( long walBytes, long blksHit, long blksRead, long commits, long rollbacks, long tupInserted ) {

		static DbStats read ( HikariDataSource dataSource, Config config ) throws SQLException {
			try ( Connection connection = dataSource.getConnection();
				  PreparedStatement stmt = connection.prepareStatement(
						  "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint, blks_hit, blks_read, xact_commit, xact_rollback, tup_inserted "
						  + "FROM pg_stat_database WHERE datname = current_database()");
				  ResultSet rs = stmt.executeQuery() ) {
				rs.next();
				return new DbStats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6));
			}
		}

		String minus ( DbStats before ) {
			return "{\"wal_bytes\":%d,\"blks_hit\":%d,\"blks_read\":%d,\"commits\":%d,\"rollbacks\":%d,\"tup_inserted\":%d}".formatted(
					walBytes - before.walBytes, blksHit - before.blksHit, blksRead - before.blksRead,
					commits - before.commits, rollbacks - before.rollbacks, tupInserted - before.tupInserted);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// verify and explain
	// ------------------------------------------------------------------------------------------------

	static void verify ( Config config ) throws Exception {
		try ( HikariDataSource main = pool(config, 4); HikariDataSource monitoring = pool(config, 2);
			  PostgresEventStorageImpl storage = storage(config, main, monitoring, DatabaseInitMode.NONE) ) {
			long decisions = 0;
			long violations = 0;
			for ( int i = 0; i < config.instances; i++ ) {
				for ( int w = 0; w < config.writers; w++ ) {
					for ( int j = 0; j < config.accountsPerWriter; j++ ) {
						String account = config.account(i, w, j);
						List<StoredEvent> history = storage.query(EventFilter.forTags("account", account), STREAM, null, null, Limit.none(), Direction.FORWARD);
						for ( int k = 0; k < history.size(); k++ ) {
							StoredEvent event = history.get(k);
							if ( !event.type().name().equals("Decided") ) {
								continue;
							}
							decisions++;
							Matcher seen = SEEN.matcher(event.payload());
							if ( !seen.find() || Integer.parseInt(seen.group(1)) != k ) {
								violations++;
								if ( violations <= 10 ) {
									System.out.printf("VIOLATION %s at index %d: %s%n", account, k, event.payload());
								}
							}
						}
					}
				}
			}
			System.out.printf("verify: %d decisions checked, %d violations%n", decisions, violations);
			Files.writeString(config.out.resolve(config.args.getOrDefault("runId", "verify") + "-verify.json"),
					"{\"decisions\":%d,\"violations\":%d}%n".formatted(decisions, violations));
			if ( violations > 0 ) {
				System.exit(2);
			}
		}
	}

	/**
	 * Plans of the locked check's probe, custom and generic, from the reference a naive decider would
	 * present (the account's last event, behind the corpus) and from the head, the documented decider's.
	 */
	static void explain ( Config config ) throws Exception {
		try ( HikariDataSource main = pool(config, 4); HikariDataSource monitoring = pool(config, 2);
			  PostgresEventStorageImpl storage = storage(config, main, monitoring, DatabaseInitMode.NONE) ) {
			String account = config.account(0, 0, 0);
			EventFilter boundary = config.boundary(account);
			List<StoredEvent> history = storage.query(boundary, STREAM, null, null, Limit.none(), Direction.FORWARD);
			EventReference n = history.getLast().reference();
			EventReference head = storage.head(STREAM).orElseThrow();
			System.out.printf("account %s (%d events), N = %s, head = %s%n", account, history.size(), n, head);
			for ( EventReference from : new EventReference[] { n, head } ) {
				StringBuilder check = new StringBuilder("SELECT 1 ");
				List<Object> parameters = new ArrayList<>();
				storage.addConsistencyCheck(check, parameters, AppendCriteria.of(boundary, from), STREAM);
				System.out.println(from == n
						? "== the probe from the account's last event (the naive decider)"
						: "== the probe from the head (the documented decider)");
				System.out.println(explain(main, check.toString(), parameters));
			}
		}
	}

	/**
	 * The plan PostgreSQL chooses from the bound values (custom) and the one a server-prepared statement
	 * settles into after its first executions (generic) — the store's append is server-prepared, so the
	 * generic plan is the one a steady stream of appends runs.
	 */
	static String explain ( HikariDataSource dataSource, String sql, List<Object> parameters ) throws SQLException {
		StringBuilder plans = new StringBuilder();
		for ( String mode : List.of("force_custom_plan", "force_generic_plan") ) {
			try ( Connection connection = dataSource.getConnection() ) {
				connection.setAutoCommit(false);
				try ( PreparedStatement set = connection.prepareStatement("SET LOCAL plan_cache_mode = " + mode) ) {
					set.execute();
				}
				try ( PreparedStatement stmt = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + sql) ) {
					for ( int i = 0; i < parameters.size(); i++ ) {
						stmt.setObject(i + 1, parameters.get(i));
					}
					plans.append("-- ").append(mode).append('\n');
					try ( ResultSet rs = stmt.executeQuery() ) {
						while ( rs.next() ) {
							plans.append(rs.getString(1)).append('\n');
						}
					}
				}
				connection.rollback();
			}
		}
		return plans.toString();
	}

	static void sql ( HikariDataSource dataSource, String sql ) throws SQLException {
		try ( Connection connection = dataSource.getConnection(); PreparedStatement stmt = connection.prepareStatement(sql) ) {
			stmt.execute();
		}
	}
}
