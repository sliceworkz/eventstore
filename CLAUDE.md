# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java-based EventStore library implementing the Dynamic Consistency Boundary (DCB) specification. The codebase is organized as a Maven multi-module project providing event storage abstractions with multiple backend implementations.

**Core Modules:**
- `sliceworkz-eventstore-api`: Core API interfaces and contracts, plus the SPI (`EventStorage`) a backend implements
- `sliceworkz-eventstore-impl`: Implementation of the EventStore (streams, serde, upcasting, shredding, observations)
- `sliceworkz-eventstore-serialization-json`: JSON codecs for stored events and bookmarks, used by the file-backed store
- `sliceworkz-eventstore-infra-inmem`: In-memory storage backend (for development/testing)
- `sliceworkz-eventstore-infra-inmem-fs`: The in-memory backend persisted to JSON files, for local development that must survive a restart
- `sliceworkz-eventstore-infra-postgres`: PostgreSQL storage backend (production-ready)
- `sliceworkz-eventstore-testing`: Backend harness, the shared TCK, and the application-author fixture (published, for third-party backends too)
- `sliceworkz-eventstore-tests`: Runs the TCK against every in-tree backend, plus the repo-internal tests
- `sliceworkz-eventstore-examples`: Example usage code
- `sliceworkz-eventstore-benchmark`: Capacity-characterisation suite (nothing runs during a build)
- `sliceworkz-eventstore-parent-pom` / `sliceworkz-eventstore-bom`: Build parent and the bill of materials consumers import

**Every jar declares its name on the module path.** The parent pom writes an `Automatic-Module-Name`
into each jar's manifest from the module's `automatic.module.name` property, which is its root
package (`org.sliceworkz.eventstore` for the api, `org.sliceworkz.eventstore.infra.postgres` for
the Postgres backend, and so on — the README's module table lists them). Without it the JDK derives
a name from the file name, which changes on a rename or relocation and breaks every consumer's
`requires`; the declared name is a commitment, and the one a `module-info.java` would have to keep.
An enforcer rule in the parent pom fails the build for a jar module without the property or with a
name outside `org.sliceworkz.eventstore`, since the alternative — the literal `${automatic.module.name}`
shipped in a manifest — is an invalid module name that fails nothing until a consumer puts the jar
on the module path. There is deliberately no `module-info.java` yet: an automatic module reads
everything and exports everything, so nothing in these jars is encapsulated, and a real module
descriptor is a separate decision about what to encapsulate, taken for all the jars at once (the api
would have to `uses EventStoreFactory` and the impl `provides` it, for a start, and every dependency
would have to resolve on the module path).

**What the published artifacts put on a consumer's classpath.** The parent pom declares no
compile-scoped dependency: one there is inherited by every module and ships in every published POM,
whether the module uses it or not. Each module declares what it imports, so the transitive set of an
artifact is what its code needs and nothing more:

- **`sliceworkz-eventstore-api` carries SLF4J, and no metrics library and no Jackson proper.** The
  store reports what it does through its own SPI, `EventStoreObserver` (see "Observability" below), so
  no Micrometer, OpenTelemetry or other type appears in any signature, and a consumer binds the one it
  uses through an observer of its own. An enforcer rule in the parent pom fails the build if Micrometer
  reaches any module, in any scope.
- `Projector` logs through SLF4J. The one Jackson artifact the api names is
  `jackson-annotations`, optional: `EventQuery` and `EventFilter` mark their derived getters
  `@JsonIgnore` so a mapper renders a query by its components only, and that is the 2.x annotations
  artifact Jackson 2 and Jackson 3 share (Jackson 3's databind depends on it). A consumer serializing
  anything already has it; one with no Jackson resolves nothing and loses nothing, because a JVM
  ignores an annotation whose type is absent at runtime. An enforcer rule in the api pom fails the
  build if Jackson proper reaches its compile or runtime classpath.
- **Jackson 3 arrives with the impl, the JSON codecs and the backends** — the payload serde, the
  file codecs, the in-memory store's payload validation, the Postgres notification payloads. It is
  `tools.jackson.*`, a different groupId and package from Jackson 2, so an application on Jackson 2
  runs both side by side: two Jacksons on the classpath, no conflict, and its own mapper untouched. That is the cost of building the serde on Jackson 3 and it is not hidden.
- **Observation is opt-in: `EventStoreObserver.NOOP` is the default wherever no observer is given** —
  `EventStore.on(storage)` without `.observer(...)` on a storage built without one, the one-argument
  `EventStoreFactory.eventStore(storage)` and every storage builder's `build()` and `buildStore()`. The
  no-op observer allocates nothing and records nothing. The testing module observes nothing unless a
  test asks: `AbstractEventStoreTest` and `EventStoreFixture` build unobserved stores, so a fixture in an
  application's test suite leaves nothing behind in the application's metrics or traces.

## Build Commands

**Build entire project:**
```bash
mvn clean install
```

**Build specific module:**
```bash
cd sliceworkz-eventstore-infra-postgres
mvn clean install
```

**Run tests:**
```bash
mvn test
```

**Run specific test:**
```bash
mvn test -Dtest=EventStoreBasicTest
```

**Skip tests during build:**
```bash
mvn clean install -DskipTests
```

## Architecture

### Core Concepts

**Read `docs/design/core-concepts.md` before you change any of the types below.** It holds the reasons
and the rejected alternatives. The rules:

**EventStore:**
- `EventStore.on(storage).build()` is the one entry point; a storage builder's `buildStore()` does the
  same and owns both. Builder settings: `.observer(o)` and `.shredding(codec)`, each defaulting to the
  storage's own; every setter refuses `null`. The `ServiceLoader` lookup happens in `build()`, which
  throws `EventStorageException` when no implementation is on the classpath.
- `EventStoreFactory` is the SPI behind the builder; application code does not name it.
  Tests: `EventStoreTest` (api), `EventStoreBuilderTest` (tests module).

**EventStream:**
- Identified by `EventStreamId` (context + purpose; `forContext("x")` uses purpose `"default"`).
  Whether the purpose is an entity id is a layout decision with measured costs — see Benchmarking.
- `getEventStream(id, Root.class)` returns `EventStream<Root>`; a wider type is a compile error. The
  `Set<Class<?>>` overloads carry no such constraint. The legacy root class is not constrained.
- `append` takes `List<? extends EphemeralEvent<? extends E>>`. An implementor of `EventSink` must
  override with that signature.
- **Raw mode:** `getRawEventStream(id)` returns `EventSource<String>`: each event is its stored JSON text
  (`StoredEvent.payload()`), under its stored type, not upcast, not decrypted. It cannot append.
  Do not add an `EventSource<Object>` of parsed trees or a `getEventStream(id)` with a free type
  parameter. Tests: `EventStoreTypeParameterTest` (javac probes), `RawStreamTest`.
- **A wildcard stream (`anyContext()`, `anyPurpose()`) reads and never appends** —
  `IllegalArgumentException`, nothing stored. To write, open the target stream by its own id. There is
  no append naming a target stream, and `EventStreamId` has no write relation — only `covers`. Do not
  add `canRead`/`canWrite`. Tests: `EventStreamTest.testAppendToNonSpecificStream`,
  `testAppendToWildcardPurposeStream`.
- **`getEventById` returns `Optional<List<Event<E>>>`**: absent = not held by this stream; present with
  an empty list = held, upcasts into nothing. Test: `UpcastMultiTest`.
- **`query()` returns a `List`, read in full** (deserialized and upcast). Not a `Stream`. A query with no
  limit reads everything into heap; bound it with `EventQuery.limit(n)`.
- **A full replay is a loop**: `Projector` (batches of 500), or by hand `page(q.limit(n), cursor)`,
  which returns an `EventPage` (events, stored events read, reference of the last stored event read).
  A page shorter than its limit is the last one; an empty page can be mid-stream. A poison event fails
  the whole page or query. Test: `EventPageTest`.
- **`head()`** is the reference of the newest stored event, read without deserializing, upcasting or
  decrypting. It sits behind the same visibility barrier as reads (Postgres `pg_snapshot_xmin`). It is
  an SPI method on `EventStorage` (default: a backwards limit-1 query); Postgres reads reference
  columns from the stream, context or global order index only, chosen by scope. It has `index` 0 and
  names the whole stored event. An absent head is an empty stream — keep it absent. Reported as
  `Observation.Head`. Use: take the head, bound reads with `until(head)`, pass the head to
  `AppendCriteria`. Tests: `HeadTest`, `PostgresVisibilityStallTest`, `PostgresHeadStatementTest`,
  `PostgresGlobalOrderIndexTest`, `PostgresOrderIndexAdmissionTest`.

**Event / EphemeralEvent:**
- `Event` record: `stream`, `type`, `storedType`, `reference`, `data`, `tags`, `timestamp`. `storedType`
  differs from `type` only for an upcast event.
- `timestamp` is an `Instant` on the storage's clock (JVM in memory, server on Postgres). The file codec
  writes ISO-8601 UTC and reads a value with no offset as UTC. Do not use `LocalDateTime`.
  Test: `EventTimestampTest`.
- On SPI records (`EventToStore`, `StoredEvent`, `EventToImport`) the payload field is `payload`: one
  JSON document as text. The file codec writes it as a document, and also reads `immutableData`.
- `EphemeralEvent` (no stream, reference, timestamp) is made by `Event.of(data, tags)`.

**EventType:**
- `EventType.of(Class)` is the simple name, or the `@EventName` value. It is the one place a class
  becomes a stored name. `EventType.named(String)` is the string factory. Do not add `of(Object)`.
- Do not annotate every event with `@EventName`; use it only for a class whose stored name cannot be
  its own name. The stored name is wire format and global to a storage — see "Event type names are
  wire format". Test: `EventNameTest`.

**Tags:**
- `Tags.of("k","v")`, `Tags.of("k1","v1","k2","v2",...)` (odd count rejected), `Tags.of(Tag...)`.
- `Tags` is a set: a repeated tag is removed, a `null` is `IllegalArgumentException`. `tags.tag(key)`
  throws `IllegalStateException` when several tags have the key; use `tags.tags(key)`.
- **`Tag.toString()` (`"key:value"`) is the wire format** — Postgres stores and matches it in a
  `text[]`. `Tag.parse` splits on the first `':'`, strips, maps empty to `null`.
- **Construction rejects** (`IllegalArgumentException`): `':'` in the key, leading/trailing whitespace,
  an empty key or value, a tag with neither. Values may contain `':'`. `Tag.of(null, "v")` stays legal.
  So `parse(tag.toString())` is the identity. Do not escape the stored form.
- `Tag.parse`/`Tags.parse` stay lenient for legacy rows (normalise, never throw).
- **Matching is exact containment, never key-prefix.** `Tag.of("customer")` does not match
  `Tag.of("customer","123")`. There is no wildcard.
  Tests: `TagTest`, `TagsTest`, `TagRoundTripTest`.

**EventFilter:**
- Matching only: event types, tags, optional `until`. No direction or limit.
- Built by `matchAll()`, `matchNone()`, `forEvents(types, tags)`, `forTags(tags)`,
  `forTypes(classes...)`, `.tagged(key, value)` (adds the tag to every item, keeps `until`, distributes
  over `or`), `.or(...)` (union; both sides need the same `until`). Both spellings make equal filters.
  Test: `EventFilterTest`.
- **A sealed interface in a type filter resolves to every type under it at construction.** A
  non-sealed interface is `IllegalArgumentException`. A filter built from `EventType`s is literal.
  Tests: `EventTypesFilterTest`, `EventTypesFilterHierarchyTest`.
- **`until` is an inclusive bound over stored events in the `(tx, position)` order**, the same forwards
  and backwards, and it also bounds a consistency boundary. Compare it as the tuple.
  Test: `EventQueryUntilBoundaryTest`.
- **An `AppendCriteria` filter must carry no `until`.** `AppendCriteria.of(relevant.until(head), head)`
  turns optimistic locking off with no error. Use `relevant.until(head)` for the read and
  `AppendCriteria.of(relevant, head)` for the append. This is documented on `AppendCriteria.of`,
  `EventFilter.until` and `EventSource.head()`.
- **A boundary names a whole stored event.** The read side compares with
  `EventReference.storedEventHappenedAfter` (ignores `index`). Do not compare `(tx, position, index)`
  on the read side. Test: `HeadTest.theHeadNamesTheWholeStoredEventWhenItUpcastsIntoSeveral`.

**EventQuery:**
- An `EventFilter` plus direction (`.backwards()`) and limit (`.limit(n)`). It re-exposes the filter
  builders (`forTypes`, `forTags`, `forEvents`, `tagged`, `or`, `until`, `untilIfEarlier`) but none of
  the filter readers: ask `query.filter()`. Its own readers are `isBackwards()` and `limit()`. `or`
  refuses two directions or a limit on either side. The query names no `EventFilterItem`.
  Test: `EventQueryTest`.
- **`.limit(n)` means "read n stored events" and is pushed into storage** (SQL `LIMIT`, `Stream.limit`).
  A cursor does not change it. `Limit` is a property of the query, never a separate read argument.
- **The SPI is asked with an `EventFilter`, never an `EventQuery`:** `EventStorage.query(filter, stream,
  cursor, limit, direction)`, with `Limit` and `EventQuery.Direction`. Do not add an SPI direction enum.
  The stream scope is never null (`IllegalArgumentException` on `query`, `append`, `head`); `anyContext()`
  is the whole storage. Test: `StreamScopeTest`.
- With upcasting, n stored events can read as more or fewer events. Do not trim; take a `subList`.
  Test: `UpcastMultiTest`.

**AppendCriteria:**
- An `EventFilter` plus an optional expected last `EventReference`. New matching events after the
  reference raise `OptimisticLockingException`.
- `AppendCriteria.none()` and `append(events)`/`append(event)` are the same: no check, no exception, no
  advisory lock on Postgres. Test: `EventStreamTest.anAppendWithoutCriteriaIsAnAppendWithNoCriteria`.
- `AppendCriteria.of(query or filter, reference)`. `expectedLastEventReference()` is never null.
- **Only `isNone()` (filter is `matchNone`) means "no criteria".** An empty reference under a real filter
  means "I decided on an empty stream" and must still be checked. Tests: `AppendCriteriaTest`,
  `OptimisticLockingTest.testOptimisticLockingSucceedsWhenExpectingEmptyStreamAndStreamIsNotEmpty`.
- **A boundary over a current type counts the legacy events that upcast into it**:
  `EventStreamImpl.append` traces types back with `determineLegacyTypes`. The exception names the
  caller's boundary; storage's exception is the cause.
  Test: `UpcastTest.aBoundaryOverACurrentTypeCountsTheLegacyEventsUpcastIntoIt`.

**Projection and EventHandler:**
- A `Projection` is an `EventQuery` plus an `EventHandler`, with an optional `initQuery()`.
- **`EventHandler.when(Event<E>)` is the one handler method.** Do not add a payload-only overload of
  `when`, a `WithoutMetaData` twin, or `when(List)`/`when(Stream)` defaults. Batch work goes in
  `BatchAwareProjection`. Test: `ProjectionTest` (api).

**BatchAwareProjection:**
- `afterBatch` commits the projection's own work first; the bookmark is placed second, after every
  batch. At-least-once.
- `afterBatch` runs inside the try: a commit that throws rolls the cursor back and is a
  `ProjectorException`.
- A batch ends once: no `cancelBatch` after a failed `afterBatch`; a throwing `cancelBatch` is logged
  and added as a suppressed exception.
- For exactly-once, store the `EventReference` given to `afterBatch` in the projection's own store.
- `readBookmark()` and a run take the same lock. Tests: `ProjectorBatchDurabilityTest`,
  `ProjectorTest.testReadBookmarkWaitsForARunInProgress`.

**Projector.Builder:**
- `Projector.from(stream).into(projection)`, then optional `bookmarkAs("reader"[, tags])`,
  `readBookmarkOnce()`, `readBookmarkOnRequest()`, `startingAfter`, `inBatchesOf(n)` (n ≥ 1),
  `subscribe()`, `idleBookmarkInterval(Duration)`, `named(String)`. One flat builder; no sub-builder,
  no public constructor.
- `from(null)` is `IllegalArgumentException`; `build()` with no projection, or a bookmark setting with
  no reader, is `IllegalStateException`.
- `ProjectorException.getEventReference()` is the last event given to `when`, not always the failing
  one; `EventDeserializationException.getReference()` names a poison event.
- `accumulatedMetrics()` is the thread-safe read of a subscribed projector's position.
- `Projection.eventQuery()` is read once per run. Test: `ProjectorTest`.

**initQuery (savepoint pattern):**
- `initQuery()` defaults to `matchNone()` (`null` means the same). The `Projector` runs it first, gives
  its events to `when()`, then starts `eventQuery()` after the last one. No savepoint: replay from the
  beginning.
- With bookmarking, `initQuery()` is ignored (a warning at build time).
- A failing savepoint handler is a `ProjectorException`, and the cursor goes back so the next run runs
  `initQuery()` again. Test: `ProjectorTest.testProjectorWrapsAFailingSavepointHandlerInAProjectorException`.
- `initQuery()` and `eventQuery()` should select different event types. Example: "Savepoint Pattern
  with initQuery" below.

### Storage Implementations

**In-Memory (Development/Testing):**

Correctness-equivalent to Postgres and *not* performance-equivalent: it is an unindexed linear scan,
so a selective tag query costs it a walk of the whole log where Postgres does an index lookup. Do not
size an application from it — see the Benchmarking digest below, and "What a read costs" in
`sliceworkz-eventstore-benchmark/CLAUDE.md`.

Correctness-equivalent includes what a storage *refuses*: an `append` or `importEvents` whose payload is
not a JSON document — text that does not parse, a blank string, a null — fails with
`EventStorageException` and stores nothing of the batch, exactly as the `::jsonb` cast makes Postgres
fail. The stream layer never produces such a payload, so this only shows on the raw SPI path (an
import, a fixture, a third-party caller writing `EventToStore` directly); it is checked here so that
path cannot pass a test against the in-memory store and fail in production. `AppendPayloadTest` and
`EventImportTest.testInvalidJsonPayloadIsRejected` in the TCK pin it per backend.

Correctness-equivalent also includes the order: the log is kept in `(tx, position)` order, and the
cursor a query starts after is a boundary in that order, found by binary search and compared over
stored events (`storedEventHappenedAfter`, so the index a reference carries plays no part) — exactly
the row comparison Postgres runs. The alternative — skipping `position` elements — loses because it
reads a position as a list index, which holds only while positions are dense and assigned in
transaction order: a reference from another store, a reloaded log with a gap, or a transaction and
position assigned in different orders then skip the wrong events or throw. Positions come from a
counter seeded from the highest loaded position, never from the size of the log, so a file-backed
log reloaded with a gap (a crash between two writes that landed out of order) never reissues a
position to the next append. `InMemoryEventStorageImplTest` pins the cursor, the gap and a
preloaded log put in order; `InMemoryFsEventStorageImplTest` the reload with a gap.

```java
EventStorage storage = InMemoryEventStorage.newBuilder().build();
EventStore store = EventStore.on(storage).build();

// Or use convenience method to get EventStore directly
EventStore store = InMemoryEventStorage.newBuilder().buildStore();
```

**PostgreSQL (Production):**
```java
// Basic setup with defaults
EventStorage storage = PostgresEventStorage.newBuilder()
    .build();
EventStore store = EventStore.on(storage).build();

// With custom configuration
EventStorage storage = PostgresEventStorage.newBuilder()
    .name("mystore")
    .prefix("tenant1_")
    .validateDatabase()
    .build();

// A test store: drop and recreate the tables on every start. RECREATE is the one destructive
// mode, and named for it -- ENSURE, the default, is the mode that initializes a database
EventStorage storage = PostgresEventStorage.newBuilder()
    .recreateDatabase()
    .build();

// With custom DataSource
EventStorage storage = PostgresEventStorage.newBuilder()
    .dataSource(myDataSource)
    .monitoringDataSource(myMonitoringDataSource)
    .prefix("tenant1_")
    .build();
```

Without a `DataSource`, the builder needs a `db.properties` file with connection settings. It takes
`.configuration(Properties | Path)` first; otherwise `DataSourceFactory` reads the first of: the system
property `eventstore.db.config`, the environment variable `EVENTSTORE_DB_CONFIG`, `./db.properties` in
the working directory, `db.properties` on the classpath. It never walks into parent directories, and
`build()` throws `EventStorageException` naming every location it tried when nothing is found. See
"Finding `db.properties`" in `sliceworkz-eventstore-infra-postgres/CLAUDE.md` for the reasoning.

### Lifecycle: starting a store when the database is not there

`build()` finishes by starting the two LISTEN/NOTIFY monitors and waiting for them to register. That wait
is **bounded** (10s by default) because the monitors have no failure mode: on a `SQLException` they log,
back off and retry for as long as the storage lives. Waiting on them without a deadline is waiting on
something that may never happen: against an unreachable database, `build()` would hang forever, with no
exception and nothing logged above DEBUG.

**Expiry is fatal, and there is deliberately no mode that starts anyway.** An event-sourced application
that is not told when events are appended has read models that quietly stop advancing: nothing wakes a
subscriber, so projections only move when something happens to run them. It serves stale data with
nothing in its own logs to say so, which is worse than not starting. `build()` therefore closes the
storage and throws `EventStorageException` — closing, not just throwing, because the two monitor threads
would otherwise keep retrying behind a storage the caller never received.

```java
// default: fail after 10s if LISTEN/NOTIFY is not established
EventStorage storage = PostgresEventStorage.newBuilder().build();

// where startup legitimately races the database coming up
EventStorage storage = PostgresEventStorage.newBuilder()
    .notificationStartupTimeout(Duration.ofSeconds(30))
    .build();
```

- **The deadline is generous on purpose.** A database that is up answers in milliseconds; the cost of
  being too impatient with one that is merely slow — a cold pool, a simultaneous restart — is an
  application that refuses to boot. Within the deadline the monitors' retry loop does the waiting, so a
  store racing its database up succeeds rather than failing (`PostgresNotificationStartupTest`).
- **A *running* store still repairs itself.** The same retry loop brings notifications back after an
  outage, with nothing to restart — the fail-fast is about not *starting* blind, not about tearing a live
  store down when its connection drops. That includes a connection that drops *silently*: the monitors
  wait for notifications with a bare socket read and send nothing meanwhile, so a peer that vanished
  without closing (a dropped NAT or firewall state, a partition, a crashed host) would otherwise be read
  forever as a quiet channel reported up. Every monitoring connection runs under a 5s
  network timeout, and a monitor silent for `notificationProbeInterval` (30s by default) sends one
  round trip and replaces a connection that does not answer. `PostgresMonitorLivenessTest` pins it
  through a TCP proxy that swallows bytes without closing either side.
- **Which configurations can reach this.** With `ENSURE` or `VALIDATE` the schema work runs first and
  fails with a clear error, so a dead *main* DataSource never reaches the wait; under
  `DatabaseInitMode.NONE` (recommended for production) the restored-history check below is the first
  thing `start()` asks the database, so a dead main DataSource fails there instead, within the pool's
  connection timeout and naming the database. The exposed path is therefore the realistic one — a
  **reachable main DataSource with an unreachable monitoring one**. Those two are configured separately
  precisely because LISTEN/NOTIFY does not survive a transaction pooler, so "pooled works, direct is
  firewalled" is an ordinary misconfiguration.
  Note that version detection (`detectsNativeUuidv7Support`) does *not* fail the build — it logs a WARN
  and falls back to the legacy implementation — so it is the schema work, not the version probe, that
  provides the fail-fast.
- **Observability.** The health of each channel (`EVENT_APPENDED` / `BOOKMARK_PLACED`) is reported to
  the storage's observer through `EventStoreObserver.notificationChannelChanged`: down from the
  constructor, so an observer knows of the channel from the moment the storage exists — a signal that
  only appears once notifications work is no use for alerting on notifications not working — up once
  the monitor listens, down again when a *running* store loses its monitoring connection, which is the
  same silence as never having had one, and down on close, before `storageClosed`. Every transition is
  reported exactly once, whichever path in the monitor made it.
  `PostgresEventStorage.isNotificationsAvailable()` is the same state for a health endpoint, on the
  type the builder's `build()` returns, so no downcast: keep that handle rather than widening it to
  `EventStorage` where a health check needs it.
- **What arrives on the channel cannot take a monitor down, and a monitor that does go down cannot
  leave the channel reported up.** A `NOTIFY` channel is a database-wide name: any session in the database
  can publish on it, and a trigger left behind by another release may not agree with this one on the
  payload. A payload that does not parse, or parses into something `EventReference` refuses (a null
  id, a position of 0), is logged at ERROR and dropped, and the monitor reads on — the catch is on
  `RuntimeException`, not on the parser's own exception type, because the conversion into a reference
  throws `IllegalArgumentException`. Anything a listener throws, an `Error` included, is contained the
  same way. And the `listening` flag behind the channel's state is cleared in a `finally`, so no exit from the
  monitor's loop, an uncaught one included, leaves the channel reported up while nobody is
  listening on. The alternative — catching only the parser's exception — loses because a single
  malformed payload then ends the monitor's virtual thread silently, and nothing wakes a subscriber
  again for the life of the storage while the channel's reported state and `isNotificationsAvailable()` both say
  otherwise. `PostgresNotificationMonitorTest` pins the delivery step without a database;
  `PostgresNotificationStartupTest` pushes junk down both channels of a live store and checks a real
  append and bookmark still get through behind it.
- **An interrupt during startup throws** `EventStorageException` and closes the storage, rather than
  returning quietly. The alternative — restore the flag and return — hands back a storage nobody can tell
  is unstarted, with two monitor threads still retrying behind it.
- **`close()` releases a caller still inside `start()`.** It counts the readiness latches down itself,
  because the monitors it stopped never will; otherwise a thread still waiting in `start()` would stay
  parked forever.

- **A store restored logically into a younger cluster is refused too.** Before the monitors are
  started, `start()` checks that the newest stored event in the `(tx, position)` order does not carry
  a transaction id the cluster has not assigned yet — the signature of a `pg_dump`/`pg_restore` into
  a fresh cluster, where the restored history sits above the visibility barrier and reads as absent
  while every new append sorts before it. One probe off the global order index, whatever the store
  holds. Fatal in the same way, with the storage closed and the remedies named. See "Backup and restore" in the
  postgres module README, and the PostgreSQL notes below. It runs *before* the monitors, on a
  connection returned before they take theirs: the other order deadlocks several stores starting on one
  shared pool, since each monitor holds its connection for the life of the storage. While a caller is in
  that check, `close()` does not release it; the pool's connection timeout bounds it instead.

### Lifecycle: closing a store

Both `EventStorage` and `EventStore` extend `AutoCloseable`. A store that lives as long as the process
needs no explicit close; one created per tenant, per test or per hot reload does — the Postgres backend
runs two LISTEN/NOTIFY monitor threads, each holding a JDBC connection, and those threads keep the whole
storage reachable, so a dropped-but-unclosed storage is not reclaimed by GC.

```java
try ( EventStore eventStore = PostgresEventStorage.newBuilder().buildStore() ) {
    ...
}   // stops the monitors and closes the pools the builder created
```

The contract every backend implements (documented on `EventStorage.close()`):

- **Idempotent** — later calls do nothing and never throw.
- **Blocks, bounded** — when `close()` returns, the background threads have finished and released their
  connections. On Postgres the monitors poll for notifications in 100ms slices, so they notice the stop
  within that, UNLISTEN, and hand their connections back to the pool healthy: closing takes ~100ms and
  logs nothing. A monitor that fails to stop on its own within 2s is interrupted instead (hard bound 5s);
  that path breaks its connection under the driver, so the pool logs "connection marked as broken" — an
  interrupted shutdown, not a normal one.
- **Ownership** — a `DataSource` you passed to `.dataSource(...)` is never closed; one the builder
  created from `db.properties` is. If you supply the pool, close the storage *before* closing the pool —
  the other order leaves the monitors retrying against a dead pool, which they cannot distinguish from a
  database outage.
- **Terminal** — no reopening; `start()` on a closed storage throws.
- **Operations throw afterwards** — every read and write throws `EventStorageClosedException`; `name()`
  keeps working. A closed storage does not keep serving reads while its notifications are dead, which
  would strand projections silently.
- **Closing an `EventStore` does *not* close a storage you gave it.** A storage can back several stores
  and usually outlives them, so closing it is the caller's job — after closing the stores built on it.
  The exception is the store from `buildStore()`: it created the storage and hands back nothing else, so
  it closes both (via `EventStore.owning(store, storage)`, which you can use for the same purpose when
  you build the pair yourself). Closing a store is safe for its siblings: they keep working.
- **A closed `EventStore`'s streams throw too**, for the same reason a closed storage's operations do —
  its notifications have stopped, so letting it keep reading would strand its subscribers silently.

`close()` is the only way to stop a storage. It is on the interface, so no downcast, and it works for
framework integration (Spring infers `close` as the destroy method for a `@Bean`; CDI `@Disposes`;
try-with-resources).

### The storage classes are not public

`PostgresEventStorageImpl`, `PostgresLegacyEventStorageImpl`, `InMemoryEventStorageImpl` and
`InMemoryFsEventStorageImpl` are package-private. A builder is the only way to obtain a storage, and
what it hands back is typed by what a caller may do with it: `InMemoryEventStorage.Builder.build()` and
the fs builder return `EventStorage`, since nothing those backends do is beyond the contract, and
`PostgresEventStorage.Builder.build()` returns `PostgresEventStorage`, an `EventStorage` that also
answers `isNotificationsAvailable()` — the one thing the Postgres backend has to say that the contract
does not cover, and what a health endpoint asks. Everything else the Postgres class does beyond the
contract is the builder's to call, in the builder's order — the schema mode, `start(timeout)`, the two
timeout setters — and the defaults a caller may want to name are constants on
`PostgresEventStorage.Builder` (`DEFAULT_NOTIFICATION_STARTUP_TIMEOUT`, `DEFAULT_LOCK_TIMEOUT`,
`DEFAULT_NOTIFICATION_PROBE_INTERVAL`). The alternative — public implementation classes with public
constructors, `start()`, the schema methods and the setters on the instance — loses because it offers
a second way to build a storage that skips the builder's version detection, its bounded wait for the
monitors and its pool ownership rules, and because every public member is one a consumer can come to
depend on, a `@Deprecated` removal cycle away from being changed. The tests of a backend live in its
package and reach what they need without any of it being public.
`PostgresLifecycleTest.testTheHandleTheBuilderReturnsAnswersWhetherNotificationsAreUp` pins the
public health path.

### Lifecycle: closing a stream

`EventSource` — so `EventStream` — is `AutoCloseable` too, but at a much smaller scale: the only thing a
stream owns is its subscriptions.

- **A stream you only query and append through owns nothing.** `getEventStream()` registers nothing with
  the storage; the registration happens on the *first* `subscribe(...)`, because a stream with no
  subscribers has nothing to do with a notification anyway, and is released by whatever ends the
  *last* subscription. Most streams are in this category, are handed out per operation, and need no
  lifecycle handling at all.
- **A stream you subscribe to is held by the storage, strongly, until closed.** This is what makes live
  updates survive the caller dropping the variable:
  ```java
  // this keeps working -- the storage holds the stream, so the subscription cannot be collected
  eventStore.getEventStream(streamId, CustomerEvent.class)
            .subscribe(reference -> { ...; return reference; });
  ```
  The cost of that guarantee is that nothing releases it on your behalf. A subscribed stream that is
  never closed is retained for the lifetime of the storage — deliberately a leak you can find. The
  alternative — holding listeners weakly — loses because a subscription then dies at an unpredictable
  GC with no error and no log.
- **Close what you subscribe to**, or close the store, which closes them all:
  ```java
  try ( EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class) ) {
      Projector.from(stream).into(projection).subscribe().build();
      ...
  }   // subscriptions ended, registration released
  ```
- **Closing a stream is not terminal**, unlike closing a store or a storage. It ends the subscriptions and
  clears the listeners; the handle stays usable for query, append and bookmark, and subscribing again
  re-registers it. A stream is a cheap per-operation handle, not a connection — there is nothing to
  protect by poisoning it. Idempotent, and closing a never-subscribed stream is a no-op.
- **`subscribe(...)` returns a `Subscription`, which ends that one listener.** Closing a stream ends
  every subscription on it; closing the handle ends one and leaves the others going, and the two
  compose because the registration follows the subscriptions: **a stream is registered with the
  storage exactly while it has a live subscription**, so closing the last handle releases the stream
  as closing the stream does, and subscribing again after that re-registers it. A handle is identified
  by itself, not by its listener (the same listener subscribed twice has two subscriptions), closing
  it is idempotent, and a handle the stream or the store already ended reads `isActive() == false`
  and closes as a no-op. A notification already being dispatched when a handle closes may still reach
  its listener, the same promise the SPI makes for an unsubscribed listener. The alternative — a
  stream registered from its first `subscribe` until its own `close()`, whatever its handles do —
  loses because a caller holding only the handle then has no way to release the stream, and a stream
  whose every subscription was closed stays held by the storage for the life of the storage, the exact
  leak the handle exists to avoid. Inside `EventStreamImpl` every change to the subscription lists and
  to the registration flag is made under one lock, so a last handle closing and a new subscription
  arriving cannot interleave into a listener the storage never notifies. `Projector.Builder.subscribe()`
  keeps no handle — the projector subscribes itself and ends with its source — so a projector that
  must be unsubscribed on its own is built without that setting and subscribed by hand, since a
  `Projector` is an `AppendListener`. `EventStreamSubscriptionLifecycleTest` pins it per backend,
  reachability of a stream whose last handle closed included.
- **There is only one kind of append listener, `AppendListener`, and it is eventually consistent** — and
  one kind of bookmark listener, `BookmarkListener`. That is why the names carry no qualifier: every
  listener is told after the commit, on a notification thread, so "eventually consistent" in a name
  would distinguish it from nothing. To react to your own append on the appending thread, nothing is
  subscribed: the typed events, with their assigned references, are the return value of `append()`.
- **What makes it cheap is that the expensive part is shared, not rebuilt.** `getEventStream` allocates a
  stream object and reports it to the observer — about **2µs and 1KB**. The payload serde is *not* rebuilt: `EventStoreImpl` caches one per
  distinct pair of event root class sets and hands the same instance to every stream opened with that
  mapping. Building one costs ~20µs and ~40KB, but the construction is the smaller half of the story —
  Jackson caches its per-type serializers **inside the mapper**, so a serde per call gives every stream a
  cold type cache and re-runs bean introspection on the first serialize of each record type. Measured on
  a 24-record sealed hierarchy, a serde per call puts a query through a freshly obtained stream at
  **~175µs / 139KB against ~36µs / 69KB** through a stream that is kept — four times the work, for a
  call this document calls cheap. With the serde shared the two are the same.
  - **The cache key is the root class sets, never the `EventStreamId`.** The same stream can legitimately
    be opened with different type mappings, and two streams with the same mapping can share a serde
    whatever their ids.
  - **Only the serde is shared, never the `EventStreamImpl`.** A serde is written once at registration
    and read-only afterwards (the two Jackson mappers are immutable and thread-safe), so sharing it is
    invisible. A stream is stateful — subscriber lists, a subscribed flag — so sharing *it* would make
    one caller's `close()` end another caller's subscriptions. That is why the cheap-handle contract
    above survives the optimisation: you still get your own stream.
  - The cache lives on the store, not statically: its key holds `Class` objects, and a static cache would
    pin their class loader for the life of the JVM. `EventStreamSerdeSharingTest` pins all of this down.

For backends: `EventStorage.unsubscribe(EventStoreListener)` is the SPI counterpart, and `subscribe` must
hold listeners **strongly** and be idempotent per listener. `unsubscribe` has a no-op `default` so a
backend written before it still compiles — and `EventStreamSubscriptionLifecycleTest` in the TCK catches
one that relies on that default, by asserting a closed stream becomes unreachable. No count of delivered
notifications can catch it: a closed stream has already discarded its listeners, so it stays quiet whether
or not the storage let go of it.

### Listeners: one kind, eventually consistent, and what a failure costs

**`append()` returns the events it wrote** — typed, with their assigned references, the same list the
caller would otherwise have had to query back. That is the whole of this store's read-your-own-writes
story, and it is why there is exactly one append listener interface: `AppendListener`. To react to
your own append on the appending thread, write the code after the call.

**No listener runs in a transaction, and none can veto an append.** `EventStorage.append` commits before
it returns — on Postgres by issuing the `COMMIT` inside it, in memory by having the events in the log — so
by the time anything is notified, the events are durable and every other reader can see them. A
notification is an announcement, never a vote.

**A storage notifies holding none of its own locks.** The in-memory backend does its work under its
monitor and delivers the notifications after releasing it; Postgres delivers on its monitor threads. A
listener notified under the storage's monitor that waits for a lock deadlocks the process on Java 21 once
the other carriers are pinned by threads waiting to enter that monitor. Read `docs/design/listeners.md`
before you move a notification back under a lock. Test: `ListenerMayUseTheStorageTest` (TCK, per backend).

**What a notification announces, a query can see — and a backend whose reads lag its commits has to
hold the notification back until they can.** The store treats a listener that reads nothing as caught
up (`OptimizingAppendListenerDecorator`, below), so a notification delivered before its events are
readable would leave the subscriber behind with nothing to wake it until the next append to its
stream. Postgres is such a backend — committed events sit behind the `pg_snapshot_xmin` barrier while
an older writing transaction is open — and its append monitor parks a notification until the event it
names is below the barrier. Documented on `EventStorage.subscribe`; pinned by
`PostgresVisibilityStallTest`.

**A listener failure is never anybody else's failure, and never silent.** Each subscriber's exception is
contained, logged at ERROR, and the next subscriber still gets the notification. Bookmark listeners get the
same containment, and the storage backends do the same with their own listeners (`notifyQuietly` in
`InMemoryEventStorageImpl`, and the Postgres LISTEN/NOTIFY monitors).

- **Why contained:** a throwable escaping the notification task would end it, so every subscriber after the
  failing one would miss that append too.
- **Why logged, at ERROR, by the library:** an exception left to a virtual thread's uncaught-exception
  handler is a bare stack trace on `System.err`, at no level, under no logger name, attributed to nothing.
  The ordinary failure is a projection that throws — `Projector.eventsAppended` calls `run()`, which
  rethrows a `ProjectorException` — and left unlogged that is a read model that stops advancing with
  nothing in the application's own logs to say why.
- **Nothing replays what a failing listener missed.** It is notified again on the next append; the
  notification it failed on is gone. A listener that must not lose progress belongs behind a `Projector`
  reading from a bookmark.
- `AppendListenerFailureTest` in the TCK pins it per backend: a throwing subscriber does not starve the one
  behind it, and notifications keep arriving for both afterwards.

**A listener that returns null is caught up, not asking to be told again.** `OptimizingAppendListenerDecorator`
keeps delivering until the listener has reached the target it was notified about, and a null return counts as
reached, exactly like a reference *behind* the target. Nothing is lost by that: the next append carries a
later reference, which is after this one and so still delivered.

- **The ordinary listener returns null, not an exotic one.** `Projector.eventsAppended` returns
  `run().lastEventReference()`, which is null whenever the query matched no events — so *any* subscribed
  projector whose event type has not occurred yet answers null to every unrelated append on its stream.
  Treating null as "not caught up" would leave the decorator with nothing to compare against and re-deliver
  the same target without pausing: ~700.000 deliveries a second on one pinned virtual thread, with nothing
  thrown or logged, indistinguishable from load until the first matching event clears it.
- The interface documents the return as "never null"; `Projector` does not honour that, so null is given a
  defined meaning rather than left to whoever reads the contract more carefully.
- `AppendListenerFailureTest.testListenerReportingNoProgressIsNotRedeliveredTo` pins it per backend.

### Bookmarks: a cursor that must name a stored event, and a foreign key that never cascades

**Read `docs/design/bookmarks.md` before you change bookmarks, the bookmarks table or how a `Projector`
places them.** It holds the reasons, the rejected alternatives and the migrations. The rules:

- **`placeBookmark` rejects a reference this storage never stored** — `EventStorageException`, nothing
  written, the earlier bookmark stays. Postgres enforces it with `fk_bookmarks_event_id` (recognised by
  constraint name, never by message text); in memory it is checked under the `append` monitor.
- **A bookmark stores only the event id.** Position and transaction are read from the event (Postgres
  joins on `event_id`; in memory it resolves from the log). So a bookmark read back, and a
  `BookmarkPlacedNotification`, always carry the store's own coordinates. Do not store the caller's
  `(tx, position)`. Tests: `BookmarksTest.bookmarkIsResolvedToTheStoresOwnCoordinates`,
  `bookmarkNotificationCarriesTheStoresOwnCoordinates`.
- **A bookmark has two positions:** `reference` (last event handled) and `readUpTo` (read up to,
  relevant or not). Lag is counted from `Bookmark.readUpToOrReference()`; `EventSource.findBookmark`
  answers the whole bookmark. **`readUpTo` is never a resume point**; a projector resumes from
  `reference`. The one exception is opt-in: `Projector.Builder.resumeAfterReadPosition()`, for a
  bookmark with no handled event. Do not use `startingAfter(readPosition)` for that.
  Test: `ProjectorReadPositionTest`.
- `readUpTo` is optional and follows the same rules as `reference` (unknown event rejected, id only,
  store's own coordinates). Postgres: nullable `read_up_to_event_id` with
  `fk_bookmarks_read_up_to_event_id`, LEFT JOIN. The SPI `bookmark(reader, reference, readUpTo, tags)`
  defaults to dropping `readUpTo`.
- **`Bookmark.reference()` is an `Optional`**: empty for a reader that has read but handled nothing. A
  bookmark names at least one of the two positions. Place a read position alone with
  `EventSource.placeReadPosition(reader, readUpTo, tags)` (SPI: `EventStorage.bookmarkReadPosition`,
  default records nothing). Such a reader resumes from the beginning (`getBookmark` answers empty); a
  read position placed alone never clears a handled reference (Postgres: `COALESCE` in the upsert);
  `removeBookmark` removes it; a `BookmarkListener` is not told of it. Postgres: `event_id` nullable,
  check `ck_bookmarks_handled_or_read_position`.
- **A projector keeps `readUpTo` by four rules** (on `Projector`'s class javadoc): take `head()` at the
  start of every run; after each committed batch record the last handled event as both positions; after
  a run that read to the end, record the later of the head and the last handled event (`runUntil` only
  within its boundary); a run that handled nothing moves only `readUpTo`, at most once per
  `idleBookmarkInterval` (default 2s; `Duration.ZERO` for every run; `deferredReadUpToDueIn()` says when
  a held-back move is due). Test: `ProjectorReadPositionTest`.
- **The foreign key never cascades** (default NO ACTION). Deleting events under a bookmark must fail
  loudly; a cascade would silently delete a lagging reader's bookmark and replay it from the beginning.
- **Schema changes:** `ENSURE` adds the `read_up_to_event_id` column and relaxes `event_id` to nullable;
  `checkDatabase()` reports a table that is not migrated under `VALIDATE`; under `NONE` the first use
  names the migration. Dropping the old `event_position`/`event_tx` columns and removing an old
  `ON DELETE CASCADE` are hand migrations — see `docs/design/bookmarks.md` and the postgres module
  README.

Test for the whole contract, per backend: `BookmarksTest`.

### Idempotent appends: a key per event, and the batch as the unit of de-duplication

An `EphemeralEvent.withIdempotencyKey(key)` makes an append safe to retry: a key already stored on
the same stream is swallowed — nothing written, an empty list returned, and the append observed as
completed `Outcome.Duplicated` (see "Observability"). The key is scoped to the stream (context and purpose),
persisted on the row and surfaced on `StoredEvent`, never on the public `Event`.

- **A command producing several events gets a key per event, derived from the command's id**
  (`cmd-4711/1`, `cmd-4711/2`). Keys are per event because that is what storage holds — one column,
  one stream-scoped unique index — and the events of one batch must carry *distinct* keys.
- **The batch is swallowed whole only as a retry: when every key in it was stored before.** A batch
  is stored atomically, so a retry finds every key or none, and an event with no key rides along
  with the keyed ones. **A batch mixing stored and new keys is refused with
  `IdempotencyKeyConflictException`, nothing stored.** It cannot be a retry: one of its events
  collides with a different event holding its key, and the rest are unknown to the store. Both
  silent answers lie — storing the unknown events leaves the caller believing the colliding fact
  landed too, and swallowing the batch loses the unknown events with nothing to say so — and the
  second is what an all-or-nothing rule reaches for by default, which is why the exception exists.
  It extends `RuntimeException` directly, not `EventStorageException`, because it is never worth
  retrying. Partial storage is also not an answer every backend can give: Postgres writes a batch as
  a single multi-row insert and pairs the rows it returns with the input by position, so it inserts
  all or none; the server reports the first violating row only, so after the rollback the backend
  runs one lookup of the batch's keys on the stream to tell a retry from a conflict (no key present
  at all means the writer that held it rolled back, a transient `EventStorageException`). The one
  blind spot: a batch reusing a key with *different* unkeyed events cannot be told from a retry, so
  a command should key every event it emits.
- **A batch repeating a key is refused with `IllegalArgumentException`, storing nothing.** Left to
  the server, the unique index rejects the second row and the append path reads that as "appended
  before", so the first ever attempt at such a batch would store nothing and report a successful
  de-duplication. Both `EventStreamImpl.append` and every backend's SPI `append` check it before
  anything is written, since the SPI is a public path too.
- **The lock check runs first.** A conditional append that conflicts raises
  `OptimisticLockingException` whether or not its keys are duplicates; the de-duplication is only
  seen by an append that was admitted.
- `EventStreamIdempotencyTest` pins the stream-level contract per backend and `AppendIdempotencyTest`
  the SPI one: a retried batch stores nothing and notifies nobody, a mixed batch throws and stores
  nothing, and a refused batch spends no key.

### Leases: electing one processor among several instances

**The storage can hold named leases, which is what a framework builds leader election on** — one
instance of a deployment holds a lease and processes; the others stand by and take over when it
expires or is released. Three optional SPI methods on `EventStorage` (`UnsupportedOperationException`
defaults, the `importEvents` precedent; `Capability.LEASE` gates the TCK scenarios, and its
`supports()` default answers **false**, so a backend written before leases existed skips them.
`supports()` is an exhaustive switch rather than "true for anything unknown" so that a new capability
whose SPI methods default to throwing cannot be claimed by accident):

- **`requestLease(LeaseRequest)` is acquisition, renewal and contender registration in one call**,
  made periodically by every contender (a third of the ttl is a sensible interval). It answers
  `LEADER`, `STANDBY`, or `LEADER_STEP_DOWN_REQUESTED` — still leader, but a live contender with a
  **strictly higher** priority is waiting, so finish the current work and `releaseLease`. The storage
  never revokes a live lease itself; a step-down is always the holder's own act. Equal priority never
  triggers a step-down
- **Expiry is judged on the storage's clock, never a contender's.** A lease whose heartbeat is older
  than the ttl it was requested with is expired and acquirable. Contenders only ever measure
  durations on their own clocks (their polling interval, the time since their last *confirmed*
  renewal) — the single-writer guarantee is "storage-clock expiry plus self-demotion on the caller
  clock before the ttl", and it holds up to a caller paused beyond its ttl, which no lease can
  prevent and the fencing token exists to expose
- **The fencing token strictly increases on every acquisition and never resets** — a release
  *backdates the heartbeat* rather than deleting the row, precisely so the token survives: deleting the
  row would let the next acquisition mint token 1 again. Renewals keep the token; a renewal is a
  request by the owner of a lease that is *still live*. The same owner re-acquiring its own expired or
  released lease is an acquisition and gets a new token, because that is the pause the token exists
  to expose: a holder paused beyond its ttl — or a restarted process reusing its predecessor's owner
  id — comes back under a token its earlier self never held, so anything the earlier self still
  stamps is recognisably stale. Deciding renewal-versus-acquisition on the owner name alone loses
  exactly that case, on both backends identically, with nothing failing to say so.
  `LeaseTest.testTheSameOwnerReacquiresItsExpiredLeaseUnderANewToken` and
  `...AfterReleasingIt` pin it per backend
- **In-memory backends contend for real within one storage instance** (the same `synchronized` that
  gives them DCB atomicity), so a single process trivially wins everything while a test can genuinely
  elect between two contenders on any backend. The fs decorator forwards explicitly and deliberately
  does not persist leases: a lease held by a process that no longer runs must expire, not be
  resurrected on reload
- **On Postgres, leases are two tables outside the event log** (`<prefix>leases`,
  `<prefix>lease_contenders`), written in one short transaction on the ordinary pool, serialized per
  lease by a `pg_advisory_xact_lock` on a NUL-prefixed scope (`leaseLockKey`, sharing
  `advisoryLockKey` with the append and schema locks, colliding with neither). Consequences worth
  spelling out: election traffic takes no lock any event query or append takes; a waiting contender
  holds no transaction id, and the lease writes are milliseconds — so leases neither pin
  `pg_snapshot_xmin` nor are subject to it (which is also why a lease is deliberately **not**
  modelled as events: event reads sit behind the xmin barrier, and one long writing transaction
  anywhere in the cluster would make every lease look expired at once). All timestamps compare via
  `now()` in SQL only. `checkDatabase()` validates both tables, so `VALIDATE`/`NONE` deployments
  notice an un-migrated database; the postgres module README's privilege table carries the grants (the
  leases table needs no `DELETE` — releases update; contender rows are pruned, so that table does)
- `LeaseTest` in the TCK pins the state machine per backend: acquire/renew/expire/release, the
  step-down protocol and its lapse with a dead contender, fencing monotonicity across takeovers and
  releases, independence of distinct leases, post-close behaviour — and, load-bearing above all,
  that exactly one of N concurrent contenders wins an acquirable lease

### Observability: an SPI of the library's own, and no metrics library

**The store reports what it does to an `EventStoreObserver`, in its own terms, and names no metrics or
tracing library.** An application that wants meters, spans or anything else provides an observer that
turns observations into them; a Micrometer binding lives outside this repository, in the Sliceworkz
plugins. The alternative — a meter facade naming counters, timers and gauges — loses because it
rebuilds a weaker version of the library it binds to and can express only meters: a tracer needs to
know where an operation starts and ends and what it answered, which is what an observation is. Micrometer's
own Observation API would carry that too, and loses only because the api would still depend on
Micrometer.

- **An operation is a scope.** `start(Observation)` is called on the caller's thread before the
  operation does anything, and returns an `Observation.Scope` the store completes or fails exactly once
  and closes in a `finally`, all on that thread — so an observer may make a span current between
  `start` and `close`, and the JDBC calls, key-store lookups and a projector batch's page query nest
  beneath it with nothing propagated. The operations: `Append`, `Query` (a query and a page alike, a
  projector's pages among them), `GetEvent`, `Head`, `PlaceBookmark`, `GetBookmark`, `ListBookmarks`,
  `ProjectorBatch` (phase `INIT` for the savepoint read, `BATCH` for every page) and `Erase`. The
  records carry the caller's own arguments and the stream as `StreamInfo` — the purpose uncapped.
  `Observation` is sealed: an observer switching with a `default` branch keeps compiling when an
  operation is added.
- **The outcome is typed by the observation**: a scope started for an `Append` completes with an
  `Outcome.AppendResult`. The outcomes of stream operations carry `storageTime`, the share spent inside
  the `EventStorage`; the scope spans the whole call, so the rest is serde, upcasting and unsealing — on
  an ordinary page most of the wait (`StorageTimeTest`).
- **A completion is an answer, not only a success.** An admitted append answers `Appended`,
  `Conflicted` (a DCB conflict: nothing stored, the caller receives the `OptimisticLockingException` and
  re-decides) or `Duplicated` (every idempotency key stored before: a retry, swallowed whole). A batch
  is stored whole or not at all, so there is no partly de-duplicated answer, and the lock check runs
  first, so a stale retry answers `Conflicted`. `failed` is for an operation that could not answer: a
  storage error, a poison event, a key store that is down, an `IdempotencyKeyConflictException`, a
  projection that threw — the throwable the caller receives. Argument refusals (a wildcard append, a
  legacy type in a filter, a repeated key) happen before an observation starts and are not reported.
- **Lifecycle, reported for what is held.** `storageStarted`/`storageClosed` (a backend reports them at
  the end of `build()` and on close, its channels reported down first), `subscriptionOpened`/
  `subscriptionClosed` (once per subscription, so opened minus closed is the live count and one that only
  rises is a subscribed stream nobody closes), `notificationChannelChanged` (see the Postgres lifecycle
  notes) and `streamOpened`. There is deliberately no `streamClosed`: a handle used only to query and
  append holds nothing and is never closed, so a counterpart would climb forever; what a stream holds is
  a subscription. Nor is there a store opened/closed: a store has no identity an observation is keyed by
  — several stores share a storage and its name — and closing one releases nothing an observer tracks
  that the subscription and storage signals do not already report.
- **The observer travels with the storage**, as the shredding codec does: a storage builder's
  `.observer(o)` is answered by `EventStorage.observer()`, and a store built on the storage reports to it
  unless `EventStore.on(storage).observer(...)` gives it its own. A `Projector` finds it through
  `EventSource.observation()`, so a projector is observed exactly when its source is, with nothing to
  configure on the projector. What its batches are reported under (`ProjectorBatch.projection`) is the
  projection's class simple name — the full class name for an anonymous one — unless the builder's
  `.named(String)` says otherwise. That is for a caller wrapping its own components in one adapter
  class, which would otherwise report every one of them under the adapter's name; the name takes no
  part in bookmarking. `ObservationTest.aNamedProjectorReportsItsBatchesUnderItsName` pins it.
- **An observer never fails an operation.** The library wraps every observer with
  `EventStoreObserver.contained(...)`: what it throws is caught (a `RuntimeException` or a
  `LinkageError`, the second being a binding whose library is missing) and logged, at ERROR the first
  time and at DEBUG after, so a broken observer is reported without a stack trace per operation. The
  observer is still on the caller's thread, so a slow one is a slow store.
- **Cardinality is the observer's concern.** Every observation carries the stream id as it is, and
  `purpose` is often an entity id. An observer turning it into a metrics tag must bound the values it
  admits — a registry never evicts a meter, so an uncapped per-entity tag is a leak that fails nothing —
  while a tracer or a log line wants the purpose uncapped. That is why the cap lives in the Micrometer
  binding, where the tag value is chosen, and not in the store.
- **Pool metrics are HikariCP's.** `PostgresEventStorage.Builder.poolMetrics(MetricsTrackerFactory)`
  hands the pools the builder uses a tracker factory — HikariCP's own seam, which ships one for
  Micrometer — so the Postgres module names no metrics library either.
- `ObservationTest` in the TCK pins every operation's observation per backend — what each reports, the
  conflict and the retry as answers, the nesting of a projector's reads under its batch, the
  subscription count, the erasure — and, through `RecordingObserver`'s contract checks, that every scope
  is completed or failed exactly once and closed in order. `RecordingObserver` is published in the
  testing module for application tests too; `ContainedObserverTest` in the api module pins containment.

### Typical Usage Pattern

```java
// 1. Create storage and event store
EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();

// 2. Get an event stream
EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
EventStream<CustomerEvent> stream = eventstore.getEventStream(streamId, CustomerEvent.class);

// 3. Append events unconditionally: no decision was read, so there is no boundary to check.
//    The same append as stream.append(AppendCriteria.none(), ...), which stays valid
stream.append(Event.of(new CustomerRegistered("John"), Tags.none()));

// 4. Query all events
List<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll());

// 5. Query with filters
List<Event<CustomerEvent>> filtered = stream.query(
    EventQuery.forEvents(EventTypesFilter.of(CustomerRegistered.class), Tags.of("region", "EU"))
);

// 6. Conditional append with optimistic locking: pin the boundary at the head BEFORE reading.
//    An absent head is an empty stream, and a valid boundary, so a customer with no history yet
//    needs no special case -- there is no getLast() to throw on an empty result
EventQuery customerQuery = EventQuery.forTags(Tags.of("customer", "123"));
EventReference head = stream.head().orElse(null);
List<Event<CustomerEvent>> existingEvents = stream.query(customerQuery.until(head));

// the criteria gets the query UNBOUNDED, with the head as the expected last event. Handing it
// customerQuery.until(head) instead -- reusing the query the read used -- deems nothing after the
// head relevant, so every append is admitted and no OptimisticLockingException is ever raised
stream.append(
    AppendCriteria.of(customerQuery, head),
    Event.of(new CustomerNameChanged("Jane"), Tags.of("customer", "123"))
);
```

### Savepoint Pattern with initQuery

```java
// Stock keeping with savepoint optimization
sealed interface StockEvent {
    record StockAdded(String product, int quantity) implements StockEvent {}
    record StockPicked(String product, int quantity) implements StockEvent {}
    record StockCounted(String product, int counted) implements StockEvent {} // savepoint
}

class StockLevelProjection implements Projection<StockEvent> {
    private final String product;
    private int level = 0;

    @Override
    public EventQuery initQuery() {
        // Find the last stock count (savepoint) — backwards, limit 1
        return EventQuery.forEvents(
            EventTypesFilter.of(StockCounted.class),
            Tags.of("product", product)
        ).backwards().limit(1);
    }

    @Override
    public EventQuery eventQuery() {
        // Only process movements — savepoints are handled exclusively by initQuery
        return EventQuery.forEvents(
            EventTypesFilter.of(StockAdded.class, StockPicked.class),
            Tags.of("product", product)
        );
    }

    @Override
    public void when(Event<StockEvent> event) {
        switch (event.data()) {
            case StockCounted c  -> level = c.counted();
            case StockAdded a    -> level += a.quantity();
            case StockPicked p   -> level -= p.quantity();
        }
    }

    public int level() { return level; }
}

// Usage
StockLevelProjection projection = new StockLevelProjection("WIDGET-42");
Projector.from(stream).into(projection).build().run();
// initQuery finds the last StockCounted, then eventQuery processes only subsequent movements
```

### Importing Events Between Stores

`EventStoreImporter` (in `org.sliceworkz.eventstore.migration`, api module) copies events from one
`EventStorage` into another via the SPI method `EventStorage.importEvents(List<EventToImport>, ImportMode)`.

```java
ImportReport report = EventStoreImporter.from(sourceStorage).to(targetStorage)
    .mode(ImportMode.SKIP_EXISTING_ID)                       // default is FAIL_ON_EXISTING_ID
    .stream(EventStreamId.forContext("ledger").withPurpose("2024Q1"))   // optional: one stream only
    .matching(EventFilter.forEvents(EventTypesFilter.any(), Tags.of("period", "2024Q1")))  // optional: types/tags
    .after(previousReport.sourceTo())                        // optional: catch-up run
    .transform(src -> Optional.of(EventToImport.from(src)    // optional: remap / rewrite / drop
                        .withStream(archiveStream)))
    .batchSize(1000)
    .onProgress(r -> LOGGER.info("{}", r))
    .run();
```

**Selection is pushed into the storage query, which is what makes the importer an archiving tool.**
`.stream(...)` (a concrete or wildcard `EventStreamId`) and `.matching(EventFilter)` become the
stream scope and the types/tags of the query the source is paged with, so on Postgres a run over one
closed period costs what that period's events cost, answered from the stream and tag indexes, not a
walk over the table. The two compose, and the transformation only sees what the selection read. The
alternative — dropping unwanted events from `transform` — reads the whole source to discard most of it,
which looks fine against the in-memory store and is a full pass over the table on Postgres. A filter
carrying its own `until` bounds the run there when it is earlier than the source head, and
`ImportReport.sourceTo()` then names that boundary, so `.after(report.sourceTo())` continues correctly.
Types are matched by stored name (a legacy type by its legacy name), since nothing upcasts on this path.
What a selection does *not* do is touch the source: an archive is a copy, and removing the copied range
from the live store is a separate, deliberate operator act — the bookmarks foreign key is there to make
it fail loudly for a reader still pointing into it (see Bookmarks above).

**What survives, what does not:**
- **Preserved**: `EventId`, timestamp, idempotency key, event type, tags and payload
- **Reassigned by the target**: `position` and `tx`. An import reproduces the source *order*, never its
  ordering numbers. `index` is a read-time upcasting artifact and is always 0 at rest.

**Why it lives at the SPI level.** `EventToImport`/`StoredEvent` carry opaque JSON plus a type name, so an
import needs no domain classes on the classpath, does no serde round-trip, does not upcast, and does not
decrypt. Going through `EventStream` instead would rewrite legacy events into current ones and lose the
idempotency key, which the public `Event` record does not carry.

**Sealed values move as ciphertext, and the keys do not move with them.** A `Shreddable`'s envelope is
opaque JSON like any other payload, so an import copies it verbatim without keys, domain classes, or the
right to read the personal data. The consequence is the obvious one: a store imported into a deployment
whose key store does not hold those keys cannot read any protected value — every read throws
`ShreddingException` naming a key the store never held, since an unknown key is deliberately not
reported as erased (see below). Migrate the keys alongside the events. To accept the erasure
deliberately, carry the key rows across shredded — material gone, reason stamped — so the values read
as erased and the audit says why.

**Import modes** (`EventStorage.ImportMode`):
- `FAIL_ON_EXISTING_ID` (default) — an already-present event id aborts the batch with `EventImportConflictException`
- `SKIP_EXISTING_ID` — an already-present event id is skipped, matching **on id alone**; no payload is read
  back or compared. This is the resume mode.

An idempotency key already used by a *different* event on the same stream is fatal in **both** modes — the
Postgres implementation infers `ON CONFLICT (event_id)` specifically so the stream-scoped idempotency index
still raises.

**Caveats that matter:**
- **Atomic per batch only.** A failure part-way leaves earlier batches committed. Re-run with
  `SKIP_EXISTING_ID` to continue. There is no dry-run mode.
- **Nothing is verified.** Matching is on id; faithfulness of a migration is the caller's problem.
- **The transform can rewrite anything** — stream, tags, payload, type, id, timestamp. That makes it a
  stream-cloning and schema-migration tool, and means it offers no fidelity guarantee of its own.
  Rewriting ids makes `SKIP_EXISTING_ID` meaningless (nothing stable left to match on).
- **Reads are always bounded at the source head**, captured before the first write. This is what makes
  `from(x).to(x)` (cloning inside one store) terminate instead of re-reading its own writes forever. Events
  appended to the source during the run are excluded; `ImportReport.sourceTo()` fed into a later run's
  `.after(...)` picks them up in O(new events).
- **This is also how a store moves to another cluster.** A logical dump keeps the source's
  transaction ids and cannot be read on a younger cluster (see the PostgreSQL notes); an import lets
  the target assign its own. Bookmarks are not part of an import, but since a bookmark stores only the
  event id and the import preserves ids, the bookmarks table can be copied across as it stands and
  resolves to the target's coordinates. Carry the shredding keys across too.
- **One importer at a time per target** — the conflict check and the insert are not under a common lock.
- **Listeners are notified** exactly as for appends, so a merge into a live store wakes its projections.
  Imported events arrive at new (high) positions carrying old timestamps, so "later position implies later
  timestamp" no longer holds in that store.
- **Checking a target up front** is `getEventById(id).isPresent()`, best on a **raw** stream
  (`eventStore.getRawEventStream(EventStreamId.anyContext())`, no event root classes): nothing is
  upcast there and nothing can fail to deserialize. A typed stream answers presence correctly too —
  a legacy event whose upcast yields zero current events is present with an empty list, not absent —
  but needs the domain classes and throws on an event its mappings cannot read.

`EventToImport`'s canonical constructor is public, so it also writes synthetic events with a chosen id and
timestamp directly into a store — useful for fixtures, but it bypasses `append()` and everything that path
guarantees.

### Upcasting: a chain of versions, and what `targetTypes()` commits to

**The names: a `@LegacyEvent(upcaster = X.class)` names an `Upcaster`, whose one abstract method is
`upcast`, and everything on the read side of an upcast is *legacy* — the annotation, the root class
parameters of `getEventStream` (`legacyEventRootClasses`), the serde's `registerLegacyEventTypes`, the
type parameter `LEGACY_EVENT`, the trace-back `determineLegacyTypes`.** The interface is named for
what it is, an upcaster, not for what it does; `@LegacyEvent(upcaster = ...)` reads as "the upcaster
for this legacy event" where a member called `upcast` named the class after the verb. And one word,
not two: "historical" for the same thing in the api and "legacy" in the annotation would leave a
reader wondering whether a historical root class is a legacy one. The other type parameter is
`TARGET_EVENT`, since what an upcaster produces may be the next legacy version rather than a current
type (below).

**An upcaster's target may itself be a `@LegacyEvent`, and the chain is followed until it reaches a
current type.** A history written as `V1`, then `V2`, then `V3` reads through a `V1 → V2` upcaster
and a `V2 → V3` one, each written when its version arrived and neither rewritten when the next one
came. The alternative — one hop, with a legacy target handed back as though it were current — loses
twice over: the stream's type parameter then lies (the value is a legacy class, and the caller's
exhaustive `switch` over the current hierarchy fails with a `ClassCastException`), and the query
path has no way to know that a stored `V1` is a `V3`, so a query or a consistency boundary over `V3`
silently skips the `V1` events. `TypedEventPayloadSerializerDeserializer` applies the hops in turn
on the read and traces `determineLegacyTypes` back through all of them, so a query for `V3`
fetches `V2` and `V1`, and a boundary over `V3` counts an event two hops behind it.

- **What `targetTypes()` declares is checked at stream creation.** Every class it names must be a
  type registered on the stream — a current type, or a further legacy type — and the chains must
  end in a current type. A target the stream does not register (an event class of a hierarchy the
  caller forgot to pass to `getEventStream`, or another class under a registered stored name) and a
  cycle are `IllegalArgumentException`, like the other registration checks, with the upcaster and
  the target named. The alternative — accepting whatever `targetTypes()` names — loses because such
  a target is never a runtime failure: the trace-back finds no current type behind the legacy one,
  so every query for the produced type skips its events with nothing to say so. A sealed interface
  among the targets stands for every type under it, as in a filter. The check runs over the complete set of roots — `serdeFor` calls `validate()` once every
  root is registered — because the roots arrive as sets, in no order, and a target may sit in a
  root registered after the upcaster's own. A serde read before `validate()` runs the same check
  itself, so the call is not load-bearing for correctness, only for failing early
- **`targetTypes()` defaults to the `TARGET_EVENT` type argument, for an upcaster declared over one
  event class.** `class V1ToV2 implements Upcaster<V1, V2>` produces `V2` and nothing else, and its
  declaration already says so; the default reads the type argument off the class — through a
  generic superclass or superinterface too, where the argument is bound there — and answers that
  one class, checked at stream creation and on the read exactly as a written declaration is, so
  the ordinary one-to-one upcaster is the `upcast` method alone. Where the declaration does not fix
  one event class — a sealed interface as the argument, which is how an upcaster that splits or
  drops its event is typed, a raw `Upcaster`, a type variable the class leaves open — the default
  throws, and the serde reports it as `IllegalArgumentException` at stream creation naming the
  upcaster, the legacy event and the fix: override `targetTypes()`. The alternative — reading a
  sealed interface as "every type under it", which is what the same class *written* in
  `targetTypes()` means — loses because a splitting or dropping upcaster then declares the whole
  hierarchy by omission: every query for any type in it fetches the legacy events only to discard
  what they upcast into, correct and silently wasteful, with nothing to say the declaration was
  never made. `UpcasterTest` in the api module pins what the default reads and refuses;
  `UpcastChainSerdeTest` how the refusal is reported; `UpcastChainTest` in the TCK reads a chain
  through derived defaults per backend
- **What an upcaster produces is checked on the read.** An event whose class is not among its
  declared targets fails as `EventDeserializationException` naming the upcaster, the class produced
  and the declared set, carrying the stored event's reference like any other read failure. An
  upcaster declaring `Set.of()` and producing an event is the shape this catches: a query for the
  produced type would never have fetched the event it came from
- **A failure on a later hop names the stored event and the upcaster that threw.** The exception's
  `getEventType()` is the stored type — the event a caller can dead-letter — and the message names
  the upcaster of the hop that failed, which is the code to fix
- **A filter names current types, and one naming a legacy type is refused.**
  `EventTypesFilter.of(V1.class)` on a stream registering `V1` as a `@LegacyEvent` is an
  `IllegalArgumentException` naming the legacy type and the current type it is read as, in a query
  and in an `AppendCriteria` alike, with nothing read or stored. Neither could be answered: storage
  would fetch the `V1` rows, the read would upcast them into `V3`, and the filter re-applied to what
  was read would drop every one for not being `V1` — a query returning nothing, while the same
  filter as a boundary, checked over stored names, counts the very events the query cannot return.
  The alternative — mapping the name forward to `V3` — loses because it answers a question the
  caller did not ask: every `V3`, the ones never stored as `V1` included. The check sits in the one
  place the query path and the lock check both pass through (`includeLegacyEventTypes`), which is
  what keeps the two from disagreeing; it is judged on the stream's registrations
  (`legacyTypesAmong` on the serde), so a raw stream, which registers no legacy types, reads the
  name as stored, and so does a typed stream on which the name is a current type. A name the stream
  registers under neither is not refused: it passes to storage unchanged, as before.
  `UpcastTest.aQueryOrABoundaryNamingALegacyTypeIsRefused` pins it per backend; `UpcastChainTest`
  that a type mid-chain is named with the current type its chain ends in, `UpcastMultiTest` the
  type that upcasts into nothing
- `UpcastChainTest` in the TCK pins it per backend: the two-hop read, the trace-back forwards,
  backwards and under a limit, the boundary, both registration rejections and the read-time one.
  `UpcastChainSerdeTest` in the impl module pins the messages below the store

### When a payload cannot be converted

The serde layer throws two named types, both unchecked, both in the **api** module
(`org.sliceworkz.eventstore.events`) so a caller never imports from `...impl.serde` to catch one. Named
types are what let a caller tell "the event cannot be read" from "the database is down" without matching on
message text; a bare `RuntimeException` from the serde would leave only the message to go on.

- **`EventSerializationException`** — from `append`, for a payload that cannot be written. Nothing is stored.
- **`EventDeserializationException`** — for a stored event this stream's type mappings cannot read. Carries
  `getEventType()` (the name in *storage*, which is not necessarily a type any current class claims) and
  `getReference()`.

**Neither is ever worth retrying, and that is the whole point of the split.** A failure to convert a payload
is a property of the payload and the mappings, identical on the next attempt and on every other instance; an
`EventStorageException` from the same call may be a dropped connection. A retry loop that cannot tell them
apart either retries forever on a poison event or gives up on a blip.

- **A deserialization failure is a poison event, not a broken store.** The storage read *succeeded*. The
  realistic causes are configuration and history rather than bugs: a stream opened without the root class
  covering a stored type, a record that has since lost a component the stored JSON still carries
  (`FAIL_ON_UNKNOWN_PROPERTIES` is enabled deliberately), a renamed event class, or an `Upcaster` throwing on
  legacy data that does not satisfy a current validation rule.
- **`getReference()` is what makes the type useful rather than merely tidy.** The serde is handed a type name
  and two JSON strings and cannot say *which* stored event failed, so `EventStreamImpl.enrich` attaches the
  reference on the way out (`withReference`, which carries message, cause and stack trace over). Its `id()`
  goes to `getEventById` on a **raw** stream — one with no mappings has nothing to fail on — so the stored
  JSON can be read even though the typed stream chokes on it.
- **Through a `Projector` the type is the *only* signal.** `run()` wraps everything it catches in
  `ProjectorException`, so a dropped connection and an unreadable event arrive identically; `getCause()`
  being an `EventDeserializationException` is what separates them. Careful:
  `ProjectorException.getEventReference()` is the last event *handled*, and the offending event never
  reached the projection — nor did any event of its batch, since the projector reads a batch as one
  `EventPage`, deserialized whole before the first of its events is handed over, and a batch that
  fails takes the cursor back to where it started. `EventDeserializationException.getReference()`
  is the one that names the poison event.
- **It is thrown by `query()` and `page()` themselves.** Both read their result in full before
  returning it, so a poison event fails the call that reads it and nothing is returned; `getEventById`
  throws the same way. `append` deserializes the events it just wrote in order to return them, so a
  payload that serializes but cannot be read back fails *there*, as a deserialization failure, with the
  event already stored.

**Misconfiguration is `IllegalArgumentException`, not a serde type.** A `@LegacyEvent` on a class registered
as current, a current class registered as legacy, an upcaster that cannot be instantiated, an upcaster
naming a target the stream does not register and upcasters forming a cycle are all properties of the
`Class`es handed to `getEventStream`; they fail at stream creation, before anything is read or written,
and there is no recovery but to fix the code — the same type the duplicate-event-name and
non-sealed-interface checks in that method throw. The messages name the upcaster *and* the event class and
keep the reflective cause, since a bare `NoSuchMethodException` says neither.

**Why there is no common root for everything the library throws.** A root only pays for itself if catching
"anything from this library" is useful, and it is not: the failures need opposite responses
(`OptimisticLockingException` → retry immediately; `EventStorageException` → retry with backoff; serde →
never), so a root would mostly encourage the broad catch this split exists to avoid. It would also be
incomplete — the registration failures are `IllegalArgumentException` and would sit outside it — and
reparenting `OptimisticLockingException` and `ProjectorException`, which callers already catch by name, is a
change to load-bearing public API for no demonstrated caller. The two types here extend `RuntimeException`
directly and nothing depends on that, so a root stays cheap to add if a caller ever turns up who needs one.

`SerdeFailureTest` in the TCK pins all of this down per backend: the reference that comes back really does
identify the offending stored event (it is fetched again in raw mode), an upcaster that throws is reported as
an upcaster rather than as a parse failure, and the exception is wrapped exactly once — so the message naming
the missing type is the one the caller sees, not the cause of a second, vaguer one.

### Every exception here survives a process boundary, and names the event it failed on

A `Throwable` is `Serializable`, so a field on one that is not makes the whole exception unserializable —
and the symptom is uniquely unhelpful, because whatever was carrying it across a process boundary (a forked
JMH benchmark, a remote test runner, a job scheduler) reports a `NotSerializableException` **instead of**
the failure. The real error is not logged, not wrapped, not chained: it is replaced. An
`OptimisticLockingException` that could not be serialized would turn a genuine DCB conflict into a
serialization complaint naming nothing about the conflict, so every exception here is kept serializable.

- **`EventReference`, `EventId` and `EventType` are `Serializable`**, so the exceptions that exist to name
  a failing event — `ProjectorException`, `EventDeserializationException` — arrive with that name intact.
  These are records, which deserialize **through their canonical constructor** rather than by field
  injection, so the validation is re-applied on the way in and no stream can conjure a reference with a
  null id or a non-positive position. That is what makes committing them to a serialized form cheap; a
  classic class with the same invariants would not be.
- **`EventFilter` is deliberately not**, and stays `transient` on `OptimisticLockingException`. It is a
  query shape over six further types, wanted by nobody across a boundary, and the exception's message
  already names it in text. So `getFilter()` reads null on a deserialized instance — the one documented
  exception to its "never null".
- **`getExpectedLastEventReference()` keeps its "never null" contract on the far side**, because the field
  is held as a nullable `EventReference` and wrapped in the getter. An `Optional` field is not serializable,
  and would arrive as null and turn a conflict report into an NPE at the point of reading it.
- `ExceptionSerializationTest` in the api module pins all four down. It is a cheap test for a failure mode
  otherwise only ever discovered inside a harness nobody suspects.

### Erasing personal data: `Shreddable` values and crypto-shredding

**Read `docs/design/shredding.md` before you change anything in shredding, a key store, a codec or the
audit.** It holds the reasons and the rejected alternatives. The rules:

- **A record component declared `Shreddable<T>` is sealed on append** under a key for its `DataSubject`
  `(type, id, category)`, and stored as an envelope inside the ordinary payload. Erasure destroys keys;
  **the stored event never changes** — no UPDATE, no re-seal, no rotation of a live key.
- **A `Shreddable` is `Present`, `Shredded` or `Withheld`, never null.** `Withheld` means "this reader
  may not read it" and says nothing about erasure. A `Shreddable` works anywhere in a payload (nested
  records, `List` elements, `Map` values); two subjects in one event get two keys.
- **`erase(type, id, reason)` erases the whole person, every category** (`shredAllCategories`, answers a
  `SubjectErasureReport`); `eraseCategory(subject, reason)` erases one category. Keep the two separate.
  Erasure notifies nothing: read models keep their copies.
- **The subject id and tags must not be personal data** — they stay in the clear. Key ids land on the
  event as `dek:` tags; "which events use this key" is a tag query.
- **Without a codec, registering an event type that declares a `Shreddable` fails** at `getEventStream`,
  and a `Shreddable` behind an interface-typed component fails the append as `EventSerializationException`.
- **The codec travels with the storage** (`EventStorage.shreddingCodec()`), like the observer; a codec
  given to `EventStore.on(storage).shredding(codec)` wins.
- **Seams:** `ShreddingKeyStore` (keys only; `resolveKey` answers `KeyResolution.Resolved | Erased |
  Withheld`) and `ShreddingCodec` (encryption too; `open` answers `Unsealed.Plaintext | Erased |
  Withheld`). Shipped: `AesGcmShreddingCodec` (AES-256-GCM, refuses a non-256-bit key, a key with no
  visible material, and a `|` in the authenticated labels).
- **Only a real erasure may answer `Erased`. An outage, and a key id the store never held, throw
  `ShreddingException`** — never report them as erased. The serde rethrows `ShreddingException`
  unwrapped, so it is never an `EventDeserializationException`. Shredded key rows are never pruned.
- **A key is committed before the event sealed under it**, on its own connection, never in one
  transaction with the append.
- **Access limits:** `ShreddingCodec.withholdingAll()`, `restrictedTo(categories)` (symmetric: seals
  nothing outside its categories, but passes erasure and audit through whole), and a key store that
  refuses keys (Postgres: SQLSTATE 42501 on `key_material` reads as `Withheld`; row-level security does
  not work — a hidden row reads as erased).
- **`ShreddingAudit` reports without key material.** No audit statement on Postgres names
  `key_material`, not even in a predicate; "shredded" is judged by `shredded_at`. `KeyAuditQuery` is
  always limited (default 500). `categories()` and `forKeys(...)` are part of it.
- **Postgres key cache:** ttl `DEFAULT_CACHE_TTL` (1 hour; the cross-instance bound on "erased"),
  size bound `DEFAULT_MAX_CACHED_KEYS` (10.000, LRU). A never-seen key is not cached as absent.
- **Raw mode does not decrypt**, so raw streams, exports and imports see the envelope. An import moves
  ciphertext only: move the keys too.
- A component that was a plain field when its events were written cannot be read as a `Shreddable`;
  migrate with `EventStoreImporter.transform` or a `@LegacyEvent` upcaster.

Tests: `ShreddableEventDataTest` (TCK, per backend), `ReaderEntitlementTest`, `AesGcmShreddingCodecTest`,
`CodecLessShreddableSerdeTest`, `KeyCacheTest`, `PostgresShreddingKeyStoreCacheTest`,
`PostgresShreddingReportingRoleTest`, `StorageShreddingCodecTest`, `PostgresShreddingBuilderTest`.

## Testing

Testing support lives in **`sliceworkz-eventstore-testing`**, a published module (compile scope; add
it in `test` scope). It holds three things:

| package | for whom |
|---|---|
| `org.sliceworkz.eventstore.testing` | the backend harness: `AbstractEventStoreTest`, `EventStoreBackend`, `@ForEachBackend` |
| `org.sliceworkz.eventstore.testing.tck` | the shared compliance scenarios every `EventStorage` must satisfy |
| `org.sliceworkz.eventstore.testing.fixture` | the `given/when/then` fixture for application authors |

Everything is in `src/main/java`, not a test-jar: a test-jar is not transitively resolved and gets no
sources or javadoc, which makes it a poor way to ship a TCK.

**Base Test Class:**
Tests extend `AbstractEventStoreTest`, which provides:
- `eventStore()` / `eventStorage()`: the store under test, fresh and empty per test method
- `createEventStorage()`: override to supply a storage directly instead of using a backend
- `storageOptions()`: override to ask the backend for a store with a result limit or a table prefix
- `waitBecauseOfEventualConsistency(BooleanSupplier)`: Awaitility helper for async listener assertions
- `dataSource()`: direct database access, where the backend is SQL-backed
- automatic setup/teardown via JUnit 5 lifecycle

Teardown goes through `EventStoreBackend.destroyEventStorage`, which defaults to `storage.close()` —
the SPI contract already requires that to release everything the storage created and to block until it
has, so a backend only overrides it to release something *it* handed the storage, such as a pool the
storage deliberately will not close.

**Running against every backend:**
Annotate scenarios `@ForEachBackend` instead of `@Test`. Each runs once per registered
`EventStoreBackend`, reported under its own name (`testQueryOneEvent [postgres:18]`). The alternative —
a hand-written `@Nested` class per backend in every scenario — loses because adding a backend then
means touching every scenario, and a scenario that forgets one runs against fewer backends without
anything saying so.

Backends are discovered with the `ServiceLoader`. In this repository the set is declared in
`sliceworkz-eventstore-tests/src/test/resources/META-INF/services/org.sliceworkz.eventstore.testing.EventStoreBackend`
and covers **all five in-tree storages**: `inmem`, `inmem-fs`, `postgres:16`, `postgres:17` and
`postgres:18`. Adding a storage to the compliance run is one line in that file.

- Narrow a local run with `-Deventstore.testing.backends=inmem` to skip the containers entirely.
- Scenarios needing an optional part of the contract declare it —
  `@ForEachBackend(requires = Capability.IMPORT)` — and are *skipped*, not failed, on backends that do
  not support it. Capabilities: `IMPORT`, `TABLE_PREFIX`, `RESULT_LIMIT`, `RAW_STORAGE_ACCESS`, `LEASE`.
  The `supports()` default answers true for the first three and false for the last two.
- `@ForEachBackend(excludingBackends = "inmem-fs")` opts a backend out **for cost, not capability** —
  reported as skipped, so the gap stays visible. Not allowed inside the TCK: a compliance scenario
  that skips a backend proves nothing about it, so use `requires` there instead. **Nothing in the
  repository uses it.** The one thing that would want it — a test doing thousands of appends against
  the file-backed store to print a throughput number — is a benchmark, and belongs in
  `sliceworkz-eventstore-benchmark`, not in a build.
- `TckBackendCoverageTest` fails the build if a TCK scenario is annotated `@Test` (so it would run
  against one backend only), if one opts a backend out with `excludingBackends`, or if a backend goes
  missing from the service file. All three are silent otherwise: a scenario quietly running against
  fewer backends than intended fails nothing.

Backends run one after another in a single JVM, and in-JVM parallelism
(`junit.jupiter.execution.parallel.enabled`) is not an option without changing how isolation works
first: per-test isolation on Postgres is `recreateDatabase()` dropping and recreating the tables
for the store's prefix, so two scenarios sharing a backend concurrently would drop each other's
tables mid-test. To split a run anyway, `-Deventstore.testing.backends=...` partitions it across
separate JVMs.

The Postgres backends are `Postgres16Backend`, `Postgres17Backend` and `Postgres18Backend`; the shared
base `AbstractPostgresBackend` is abstract on purpose, so no class name can be read as "PostgreSQL,
unspecified version". `AbstractPostgresBackend.forImage("postgres:19")` covers a version with no
dedicated class (the image tag becomes the backend name, so the version still shows in reports).

**Test Structure:**
`sliceworkz-eventstore-tests` runs the TCK against every in-tree backend — via surefire's
`dependenciesToScan`, which is the same one line a third-party `EventStorage` adds — plus the few
tests that are repo-internal rather than part of the storage contract: `TckBackendCoverageTest`,
`EventImportRoundTripTest`, and the store-level tests of the impl module's observations, builder and
serde sharing (`StorageTimeTest`, `EventStoreBuilderTest`, `EventStreamSerdeSharingTest`). Postgres containers are managed by `PostgresContainer`, started once
per JVM per image; per-test isolation comes from `recreateDatabase()` dropping and recreating the
schema, not from a fresh container.

**Testing application code:**
`EventStoreFixture` gives application authors a `given/when/then` over an in-memory store — seed
history, run a decider, assert what it appended, assert an `OptimisticLockingException` fires when it
should, drive a projection to a known point. `whenConcurrently(...)` appends into the window between
a decider's query and its own append, which is the only deterministic way to provoke a DCB conflict.
See `EventStoreFixtureTest` for a worked example.

**Timestamps are not assertable.** The in-memory store stamps events from the JVM clock; Postgres does
not bind `event_timestamp` on append at all and lets the DDL default (`CURRENT_TIMESTAMP`, server
clock) apply. There is no `Clock` seam anywhere. Assert on timestamps only with a tolerance window, as
`EventTimestampTest` does. The one path that writes a chosen timestamp is `importEvents`, which
bypasses `append()`.

## Benchmarking

Measurement lives in **`sliceworkz-eventstore-benchmark`** and never runs during a build: JMH for
operation-level numbers and a load runner for sustained load and live latency, both driving one
shared workload catalogue over content-addressed corpora. Every number is published with a manifest,
the comparators refuse cross-environment diffs, and curated runs are committed under
`results/<version>/<profile>/`. **The mechanics, the profiles, and every measured figure with its
caveats live in `sliceworkz-eventstore-benchmark/CLAUDE.md`** (loaded when working in that module)
and in that module's README. The digest of the conclusions that matter outside the module — treat
each figure as Testcontainers-on-a-developer-machine unless the module file says otherwise:

- **Deserialization is ~2µs per event, and on an ordinary page it is most of the wait**: a 500-event
  page spends 60–83% of its time in JDBC and the serde rather than in PostgreSQL. Bounding a read
  with `EventQuery.limit(n)` is worth more than it looks, and tuning the database is the wrong first
  move for a read returning thousands of events.
- **The in-memory backends are unindexed linear scans** — a correctness substitute, never a
  performance one. They lose selective tag queries by 24–59× (exactly the case the GIN index exists
  for) and win only where a limit fills before the scan gets far; prototyping tag-query cost against
  them points backwards.
- **Stream design** (`stream-design-*` pair): **`PER_ENTITY` wins or ties everything except reading a
  context in order** (13–15× worse in the committed run, which was measured without
  `idx_events_context_order`, the index that serves exactly that read). The canonical DCB
  check is 4.2× better single-threaded and 16.8× at eight writers, because distinct purposes take
  distinct advisory locks. **But read an entity through its own stream, or the design buys nothing**:
  addressing a per-entity corpus by tag through a wildcard purpose costs 23–29× over its own stream.
- **A shared append lock stops throughput scaling flat** (~1.4 ops/ms at any writer count, against
  5.5 → 24 for writers spread over entities), and **a shared boundary makes added writers strictly
  worse**: useful appends fall 8.15 → 0.22 ops/ms from one to sixteen writers at 82% conflicts.
  Widen hot boundaries; the lock is bought off by stream layout.
- **Sharing a table with other domains costs nothing a stream-scoped index prunes** (ten of twelve
  read shapes unmoved at 6× table volume), but a tag's selectivity is a property of the *table*,
  which flips OR-of-facts reads to full materialisation (5.4×). Sharing only a *database* with idle
  neighbour stores costs nothing measurable; a busy neighbour is the `pg_snapshot_xmin` hazard in
  the postgres notes.
- **Every target is measured unobserved** (`EventStoreObserver.NOOP`). What an observer costs is the
  observer's own, measured with it, outside this suite.
- The DCB check's criteria-derived shape — the probe for cursor-bearing criteria, the
  custom-planned tag path for cursorless ones — is summarised under PostgreSQL below;
  `large-tier-writes` and `dcb-boundary-staleness` are the profiles that characterise it. The
  rejected alternative — one uniform `NOT EXISTS` check left to the plan cache — keeps its measured
  baselines under `results/` as the reasoning (the `-not-exists`-suffixed directories,
  `dcb-cost-curve-ext-not-exists` showing the fully cliffed plan cache).

## Naming Conventions

**Domain Events:**
- Use sealed interfaces for type-safe event hierarchies
- Implement as records (immutable)
- Named as past-tense business facts (e.g., `CustomerRegistered`, `OrderPlaced`)

**Example:**
```java
sealed interface CustomerEvent {
    record CustomerRegistered(String id, String name) implements CustomerEvent { }
    record CustomerNameChanged(String id, String name) implements CustomerEvent { }
    record CustomerChurned(String id) implements CustomerEvent { }
}
```

### Event type names are wire format

**An event class's simple name is stored data, unless the class declares another.** `EventType.of(Class)`
is `Class.getSimpleName()`, or the value of an `@EventName` annotation on the class — the one place a class
is turned into a stored name, so nothing else needs to know about the annotation. The simple name is the
intended case: most event classes carry no annotation, and should not. That one string is
what goes into the `event_type` column, what `EventTypesFilter` matches on, and what keys the deserializer
(`TypedEventPayloadSerializerDeserializer.deserializers`, a `Map<String, EventDeserializer>`). The
annotation's value is used exactly as given (non-blank, no leading or trailing whitespace, or
`IllegalArgumentException` at stream creation), is not inherited, and combines with `@LegacyEvent`.
`EventNameTest` in the TCK pins per backend that the declared name goes through every path the class
name goes through: the append, the typed read, a filter built from the class, the raw view, the lock
check and a legacy class's registration.

Using the *simple* name rather than the fully qualified one is deliberate and worth keeping in mind: moving a
class to another package, splitting a hierarchy across packages, or reorganising modules changes nothing on
disk. The package is not a wire commitment. **The class name is** — for every class that does not carry
`@EventName`, which is most of them.

**Renaming an event class breaks reads of its history.** Stored events are immutable, so every event already
written keeps the old name while the renamed class claims a new one. Reads then fail with:

```
No mapping found for event type 'CustomerRegistered'
```

Every IDE offers that rename as an ordinary refactor, and nothing at compile time objects. Four ways out,
in the order you would normally reach for them:

1. **Don't rename.** Name the class deliberately when the event is created, and treat that name
   afterwards the way you would a database column name. This is the intended setup and needs no
   annotation — an `@EventName` on a class that is already called what it is stored as adds a second
   copy of the same commitment and nothing else.
2. **Rename the class, keep the stored name.** Annotate the renamed class with the name its history was
   written under:
   ```java
   @EventName("CustomerRegistered")
   record CustomerSignedUp ( String id, String name ) implements CustomerEvent { }
   ```
   Storage is untouched, nothing is upcast, and no database access is needed. The cost is that the
   class and its stored name now differ, permanently, which is what the annotation makes visible at the
   declaration rather than in a migration script. This is the fix for a rename; when the *shape* changed
   too, it is not enough on its own.
3. **Keep the old name alive in code.** Move a class carrying the old name into a legacy hierarchy, annotate
   it `@LegacyEvent(upcaster = ...)`, and upcast it to the renamed class — see the upcasting sections. The
   legacy class can carry the stored name in an `@EventName` too, so it need not be called what the
   history is called. This is the option when the event's shape changed as well as its name; for a bare
   rename it costs a permanent extra class plus an upcaster that option 2 does not.
4. **Rewrite the stored names.** `UPDATE <prefix>events SET event_type = 'New' WHERE event_type = 'Old';`
   is a valid migration: no foreign key, check constraint or unique index is keyed on `event_type`, so
   nothing else in the schema has to change. `idx_events_stream_type_position` includes the column, and
   Postgres maintains it transparently — on a large table budget for the row and index rewrite, and scope
   the statement by `stream_context` when the rename applies to one context only.
   `EventStoreImporter`'s `.transform(src -> Optional.of(EventToImport.from(src).withType(...)))` does the
   same during a copy, if you would rather rebuild the store than mutate it. Either way history no longer
   reads exactly as it was written, and the change has to reach every environment, replica and restored
   backup, plus anything outside this library reading the same table.

**Names are global to a storage, not scoped to a stream.** A stream scopes *reads*; it is not part of a
type's identity. Two classes with the same simple name in different contexts write indistinguishable
`event_type` values into one table:

- **On one stream this fails loudly.** Registering both throws
  `IllegalArgumentException: duplicate event name Created` (from
  `TypedEventPayloadSerializerDeserializer`). The message names the string only, not the two classes,
  so grep for the name to find them.
- **Across streams nothing catches it.** No exception, no warning, at registration or at write time.

**And a read spanning both contexts does not fail cleanly.** A wildcard stream
(`EventStreamId.anyContext()`), the raw/import path or a store-wide projection resolves the payload by name
alone. `FAIL_ON_UNKNOWN_PROPERTIES` is enabled, so it looks like a mismatch would be rejected — it usually
is not. Reading one context's `Created` with the other context's class:

| reader record vs. stored payload | outcome |
|---|---|
| more components (`Created(id, amount, dept)` reads `{id, amount}`) | **succeeds**, `dept` defaulted to null |
| same component names, different types (`int` → `String`, `int` → `short`) | **succeeds**, coerced |
| same shape, different meaning | **succeeds**, wrong class |
| fewer components (`Created(id)` reads `{id, amount}`) | throws `UnrecognizedPropertyException` |

Only the *narrower* reader is protected. The usual outcome is the wrong class silently populated with
another context's data, which surfaces as bad numbers in a projection rather than as an error.

**Practical rule: keep stored event names unique across an entire storage, not just per stream.** Two
bounded contexts sharing a store cannot both *store* a `Created`, a `StatusChanged` or an `Updated`. They
can both have a class called that: give one of them a distinct stored name with
`@EventName("OrderCreated")`, prefix the class names themselves (`OrderCreated`, `VacancyCreated`), or
give each context its own storage. With the stored names distinct, both hierarchies register on one
wildcard stream and a store-wide read resolves each context's events with its own class
(`EventNameTest.twoContextsShareASimpleNameWhenOneDeclaresItsStoredName`). If two contexts must share a
stored name, keep every read scoped to one stream — no wildcard streams, no store-wide projections — and
know that nothing enforces that from here on. **Nothing can**: streams are opened independently and a
storage never sees every type mapping at once, so uniqueness across contexts stays a convention; the
annotation is the tool for keeping it where a class name cannot, not an enforcement of it.

## Key Design Principles

1. **Sealed Event Hierarchies**: Use sealed interfaces with record implementations for type safety
2. **Tag-Based Queries**: Use tags for dynamic event retrieval across event types
3. **Immutable Events**: All events are records and immutable
4. **Optimistic Locking via DCB**: Use `AppendCriteria` for conditional appends based on relevant facts
5. **Storage Abstraction**: Code against `EventStorage` interface for backend independence
6. **Service Loader Pattern**: `EventStoreFactory` is the SPI Java's ServiceLoader discovers; `EventStore.on(storage)` is the entry point that resolves it
7. **Builder Pattern**: Storage implementations use fluent builders for configuration

## Documentation Conventions

Documentation and javadoc describe the design as it is, not how it got here. Two rules:

- **Never narrate fixed bugs or earlier implementations.** "This used to X" and "before this change"
  are commit-message material; once merged they cost every future reader a detour through code that
  no longer exists. The version history is the record of what changed and when.
- **Do document rejected alternatives — as alternatives, with the reasoning.** Where a plausible
  design was considered and turned down, say what it was and why it loses (measured, where the
  benchmark suite can), so the next person reaching for the same idea finds the reasoning instead of
  re-treading it. Phrase it as a standing design choice ("the alternative — X — loses because Y"),
  never as a chronology ("we replaced X"). A rejected alternative's measured record may stay
  committed (e.g. under the benchmark module's `results/`) as the evidence behind such a note.
- **Write the rule in `CLAUDE.md` and the reasons in `docs/design/`.** Claude Code loads this file in
  full at every start, so it holds what a change must respect: the rule, the names, the tests that pin
  it. The reasoning, the rejected alternatives and the measurements go in the topic's file under
  `docs/design/`, and the section here names that file as plain text. Do not import it with `@`: an
  imported file loads at start too, and the size comes back. A module's own rules go in that module's
  `CLAUDE.md`, which loads only when work touches the module.

## DCB Compliance

This implementation is fully compliant with the [DCB Specification](https://dcb.events/specification/):

- **Dynamic Event Tagging**: Events can be tagged with arbitrary key-value pairs for retrieval
- **Dynamic Consistency Boundaries**: Optimistic locking via `AppendCriteria` ensures consistency by checking for new relevant facts before appending
- **Event Queries**: Allow dynamic selection of relevant events based on types and tags
- **Optimistic Concurrency**: `OptimisticLockingException` is thrown when conflicting events are detected

The key insight of DCB is that business decisions are based on querying relevant historical events, and new events should only be stored if no new relevant facts have emerged since the decision was made. This is achieved through:
1. Query events with an `EventQuery` to make a decision
2. Note the reference of the last relevant event — or take the stream's `head()` *before* the query and
   bound the query with it, which is the same boundary with a cursor at the stream head (see `head()`
   under EventStream: cheaper to check on Postgres, and the only sound way when the decision takes more
   than one read)
3. Append new events with `AppendCriteria` containing the query's `EventFilter` and last reference
4. If new events matching the filter exist after the reference, the append fails

**"After the reference" in step 4 means the total `(tx, position, index)` order** — the one
`EventReference.happenedAfter` defines and reads are ordered by — not position alone. The two are not
interchangeable: a backend assigning position and transaction independently can produce an event holding a
lower position and a higher transaction than one that committed before it, and such an event is after the
reference for every reader. A check comparing positions alone does not see it and admits an append against
a history the store no longer agrees with, silently. `PostgresLockCheckOrderingTest` pins this down for the
Postgres backend, where the two are a `bigserial` and a `xid8`; see the PostgreSQL notes below.

**The guarantee holds under concurrency, and every backend has to earn it.** The check in step 4 and the
insert must be one indivisible step. If they are not, two appends racing at the same boundary each find it
empty, both are admitted, and the invariant is gone with nothing raised — the worst kind of failure, since
the store reports success to both callers. The in-memory backends get this by construction (`append` is
`synchronized`); the Postgres backend takes a per-stream advisory lock, because its check is a phantom
predicate that READ COMMITTED does not protect (see the PostgreSQL notes below).

`ConcurrentOptimisticLockingTest` in the TCK is what holds every backend to it: several threads append at
one boundary from a common start signal, and exactly one must win while the rest get an
`OptimisticLockingException`. Note that the rest of `OptimisticLockingTest` is single-threaded, so it
proves the check *reads* correctly and says nothing about whether it is atomic — which is why a backend
can pass all of it and still violate the boundary in production.

## PostgreSQL Specific Notes

The deep operational notes — schema and trigger repair, migrations, advisory-lock keying, the
LISTEN/NOTIFY machinery, diagnosis SQL, measured plan behaviour — live in
**`sliceworkz-eventstore-infra-postgres/CLAUDE.md`**, loaded when working in that module. The facts
that bind everywhere:

- **Ordering is the `(event_tx, event_position)` tuple everywhere** — reads, the `until` boundary,
  and the optimistic-locking check. The two columns are assigned independently and genuinely
  disagree, so comparing positions alone is a different order that silently drops events. The cursor
  boundary is written as a SQL **row constructor** and the read path's `ORDER BY event_tx::xid8`
  cast is load-bearing (a bare name resolves to the text output column) — do not "simplify" either;
  `PostgresCursorBoundaryTest` guards it, and the expansion costs 2–3× on cursor walks.
- **Conditional appends serialize per stream via `pg_advisory_xact_lock`** keyed on the prefix and
  `(stream_context, stream_purpose)`; unconditional appends take no lock. A hot stream is therefore
  a ceiling, and stream layout the fix — see the write-contention findings under Benchmarking.
  **The wait for the lock is bounded** — the builder's `lockTimeout`, 10s by default, sent as
  `SET LOCAL lock_timeout` in the lock's own round trip and scoped to that transaction — so a holder
  that has stalled fails the appends queued behind it one at a time (`EventStorageException`, SQLSTATE
  `55P03`, nothing written) instead of parking each in a pool connection until the pool is empty and
  every operation of the store fails with it. The same bound covers the per-lease lock; the schema
  lock is deliberately outside it. `PostgresLockTimeoutTest` pins it.
- **A long-running *writing* transaction anywhere in the cluster freezes what this store can
  read** (the `pg_snapshot_xmin` barrier): reads stop advancing, projections go quiet, nothing
  fails, and read-your-own-writes breaks in a way a DCB retry loop cannot clear. Only
  transactions holding a transaction id count — read-only ones never do, at any isolation level.
  Append notifications are held back until the events they announce are readable, so a subscribed
  projection catches up by itself when the blocker ends rather than staying behind until the next
  append to its stream — and a notification withheld for more than 10s is the one WARN the library
  logs about a stall. The diagnosis query and monitoring guidance are in the module file; do not
  "fix" this by bounding the barrier.
- **Back the cluster up physically; a logical dump restored into a fresh cluster does not work.**
  `pg_dump` copies `event_tx` as data, so the restored history carries ids above the new cluster's
  counter: every read sits behind the visibility barrier and sees an empty store, and the first
  append sorts before all of history. `build()` refuses to start such a store (one probe off the
  global order index, under every init mode — never a walk of the streams, which on a per-entity
  layout is a probe per entity on every boot). Physical backups keep the counter and need nothing;
  moving a store between clusters is `EventStoreImporter`'s job, which reassigns both ordering
  columns — bookmarks copy across by id, keys and the `btree_gin` extension travel separately. The
  runbook, with the measured breakage and the `pg_resetwal` escape hatch, is "Backup and restore" in
  the postgres module README.
- **The DCB check's SQL shape is derived from the criteria, not configured.** A criteria carrying an
  expected reference runs as an ordered probe (`ORDER BY event_tx, event_position LIMIT 1`) that
  walks the position index forward *from the cursor* and stops at the first match — its cached
  generic plan is that walk, so the plan is stable, there is no or-groups cliff (2.6× at ten OR-ed
  facts), and the canonical one-type-one-tag check under a traffic-faithful entity mix measures
  ~26 ms/op (~83× an unconditional append) at ten million events. A criteria *without* a
  reference — the uniqueness pattern, "I decided on an empty boundary" — runs as `NOT EXISTS` with
  server preparation disabled for that statement, so it is planned from its bound values and
  answered by the tag index (~2.3 ms/op at ten million events).
  The probe's one cost is a stale cursor — linear in the stream events since it, ~0.2 µs each, so
  the *average* check on a tagged stream prices at ~0.2 µs × the count of entities active in it,
  whatever the traffic skew. Re-reading the boundary refreshes the cursor only for an entity that
  has been moving; for a long-idle one the fix is presenting the freshest reference the read
  observed — `EventSource.head()` taken *before* the boundary read, which bounds the read and feeds
  the check — and that collapses the walk: a whole decision done that way — two bounded reads plus
  the checked append — measures ~4 ms/op against the unbounded naive decider's ~500, and is the one
  conditional write that scales with writers on a tagged stream.
  The benchmark module's notes carry the reasoning and the measured curve. The natural alternative — one uniform
  `NOT EXISTS` statement for every criteria, left to the plan cache — was measured and rejected: a
  `NOT EXISTS` is priced by how soon a row turns up while a DCB check expects no row, so the plan
  cache settles on plans built for the wrong question (~190× with 50–150% error bars on the
  canonical check, a 14× cliff at two OR-ed facts, and a steady-state 1.16 s whole-table scan on
  the empty boundary). The measurements behind that rejection are recorded in the benchmark
  module's `CLAUDE.md`.
- **A read that does not bind both stream columns walks an index on the `(event_tx, event_position)`
  order**: `idx_events_global_order`, the global order, for a read that binds no stream column (a
  wildcard stream paged by a store-wide projection or an export, `head()` of the whole store, an
  unscoped `EventStoreImporter` run), and `idx_events_context_order` for one that binds the
  context and leaves the purpose open (a whole-context replay over a per-entity layout). The stream
  indexes all lead with `(stream_context, stream_purpose)` and offer such reads neither a start
  condition nor an order, so without these every page is a scan plus a sort, whatever its limit.
- **And a read that *does* bind them must never be served by one of those two.** A wider order index
  supplies the same `ORDER BY`, so the planner will walk it and filter on the stream columns it does
  not lead with whenever the stream is a large share of the table — pricing the filter as though the
  stream's events were spread evenly through the order, which for a stream that has been quiet while
  its neighbours wrote they are not. Correct, unlogged, and linear in everything written since: a
  quiet stream's `head()` 24 ms against 0.08 ms with 300.000 foreign events behind it, and its DCB
  check 56 ms against 0.02 ms, that one worst because the probe is server-prepared and the cached
  generic plan *is* the walk. So each of the two is **partial on an admission predicate** — a
  tautology (`event_position > 0`, `event_tx > '0'::xid8`) the planner cannot prove, spelled out by
  exactly the statements whose scope binds no more than that index leads with, and by no others. A
  partial index is closed to a statement that does not imply its predicate whatever the estimate, so
  this holds for a cached generic plan too, which is what a cost-based fix cannot promise. The
  rejected alternative — expression-keyed order indexes — and the two dead ends before it are in the
  postgres module file. Migration: `ENSURE` creates the two and drops the ones they replaced on the
  next start; a `VALIDATE`/`NONE` deployment applies it by hand with `CREATE INDEX CONCURRENTLY`, and
  `checkDatabase()` reports a missing replacement, one created without its predicate, and a superseded
  index still present — see "Migrating a database created before the order indexes carried their
  admission predicates" in the postgres module README.
- **Oldest supported PostgreSQL is 16**, and the `btree_gin` extension is required — creating it
  needs `CREATE` on the *database*, not the schema; a DBA installing it once is the recommended
  split, and an unprivileged role then starts against it silently.
- **Idempotency keys are scoped per stream** (partial unique index `idx_events_stream_idempotency`);
  a duplicate is recognised by the constraint name the server reports, never by message text, and a
  swallowed duplicate returns an empty result. A batch is one multi-row insert, so a duplicate in it
  rejects the whole batch, and one lookup of the batch's keys after the rollback tells a retry (every
  key stored, swallowed) from a conflict (some stored, `IdempotencyKeyConflictException`) — the rule
  under "Idempotent appends" above. A batch repeating a key is refused in Java before the insert,
  since the server would report it as the same violation.
- Append notifications are emitted once per stream per statement, not per row; `timestamptz` keeps
  microseconds (the one lossy step of an inmem → Postgres → inmem round trip); and a `db.properties`
  *value* never reaches an error message or log line — only the key does.
