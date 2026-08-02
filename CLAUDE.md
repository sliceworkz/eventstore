# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java-based EventStore library implementing the Dynamic Consistency Boundary (DCB) specification. The codebase is organized as a Maven multi-module project providing event storage abstractions with multiple backend implementations.

**Core Modules:**
- `sliceworkz-eventstore-api`: Core API interfaces and contracts
- `sliceworkz-eventstore-impl`: Implementation of the EventStore
- `sliceworkz-eventstore-infra-inmem`: In-memory storage backend (for development/testing)
- `sliceworkz-eventstore-infra-postgres`: PostgreSQL storage backend (production-ready)
- `sliceworkz-eventstore-tests`: Shared test scenarios
- `sliceworkz-eventstore-examples`: Example usage code

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

**EventStore:**
- Main entry point for interacting with the event storage system
- Obtained via `EventStoreFactory.get().eventStore(eventStorage)`
- Provides access to event streams via `getEventStream()`

**EventStream:**
- Identified by `EventStreamId` which consists of a context and optional purpose
- Purpose is optional: `EventStreamId.forContext("x")` defaults purpose to `"default"`, so a context that needs only one stream can ignore purpose entirely. Set a purpose only to distinguish multiple streams within a context (e.g. per-instance, or separating event kinds)
- Supports both reading (via `query()`) and writing (via `append()`)
- Type-safe through generic parameter `<DOMAIN_EVENT_TYPE>`
- Combines `EventSource` (reading) and `EventSink` (writing) interfaces

**Event:**
- Record type containing: `stream`, `type`, `reference`, `data`, `tags`, `timestamp`
- Data is the actual domain event (typically a sealed interface with record implementations)
- Tags enable dynamic querying and consistency boundaries
- Created via `Event.of(data, tags)` for ephemeral events or full constructor for persisted events

**EphemeralEvent:**
- Lightweight event representation before persistence (no stream, reference, or timestamp)
- Converted to full `Event` upon appending to a stream
- Created via `Event.of(data, tags)`

**Tags:**
- Key-value pairs attached to events for dynamic retrieval
- Enable querying events across different event types
- Core to the Dynamic Consistency Boundary pattern
- Created via `Tags.of("key", "value")` or `Tags.of(Tag.of("key", "value"))`

**EventFilter:**
- Pure matching criteria: event types, tags, and an optional "until" temporal boundary
- Does not carry traversal semantics (direction, limit) — those belong to `EventQuery`
- Can match all (`EventFilter.matchAll()`), none (`EventFilter.matchNone()`), or specific criteria
- Created via `EventFilter.forEvents(eventTypesFilter, tags)`
- Used by `AppendCriteria` for optimistic locking (where direction/limit are irrelevant)
- **`until` is an inclusive upper bound over the total `(tx, position, index)` order and is
  direction-independent**: `.backwards()` returns the same events as forward, newest first. It is part of
  the filter, so it also bounds a consistency boundary — an event past it is not a new relevant fact and
  raises no `OptimisticLockingException`. Backends must compare it as the tuple, exactly as they compare
  the cursor; comparing positions alone drops events whose transaction and position were assigned in
  different orders. `EventQueryUntilBoundaryTest` pins all of this down per backend

**EventQuery:**
- Wraps an `EventFilter` together with traversal semantics (direction and limit)
- Use `EventQuery.filter()` to extract the pure matching criteria
- Can match all (`EventQuery.matchAll()`), none (`EventQuery.matchNone()`), or specific criteria
- Supports backward direction (`.backwards()`) and result limits (`.limit(n)`)
- Created via `EventQuery.forEvents(eventTypesFilter, tags)`

**AppendCriteria:**
- Controls optimistic locking when appending events
- Contains an `EventFilter` and an optional `EventReference` for the expected last event
- If new matching events are found after the reference, append fails with `OptimisticLockingException`
- Use `AppendCriteria.none()` for simple appends without locking
- Use `AppendCriteria.of(eventQuery, reference)` or `AppendCriteria.of(eventFilter, reference)` for conditional appends

**Projection:**
- Combines an `EventQuery` with an `EventHandler`
- Processes all events matching the query criteria
- Used for building read models from event streams
- Optionally defines an `initQuery()` for the savepoint pattern (see below)

**Projection initQuery (Savepoint Pattern):**
- Projections can define an optional `initQuery()` (default returns `null`) that runs before the main `eventQuery()`
- Enables the savepoint pattern: a backward query with limit 1 finds the most recent savepoint event that summarizes prior state
- The `Projector` executes `initQuery()` first, passes results to `when()`, then uses the last event's reference as the cursor for `eventQuery()`
- Savepoint events are pure domain events — no special framework support needed
- When no savepoint exists, the main `eventQuery()` replays from the beginning (graceful degradation)
- When bookmarking is enabled on the `Projector`, `initQuery()` is ignored (a warning is logged at build time)
- The `initQuery()` and `eventQuery()` should query different event types to avoid double-processing and to allow recovery from buggy savepoints

### Storage Implementations

**In-Memory (Development/Testing):**
```java
EventStorage storage = InMemoryEventStorage.newBuilder().build();
EventStore store = EventStoreFactory.get().eventStore(storage);

// Or use convenience method to get EventStore directly
EventStore store = InMemoryEventStorage.newBuilder().buildStore();
```

**PostgreSQL (Production):**
```java
// Basic setup with defaults
EventStorage storage = PostgresEventStorage.newBuilder()
    .build();
EventStore store = EventStoreFactory.get().eventStore(storage);

// With custom configuration
EventStorage storage = PostgresEventStorage.newBuilder()
    .name("mystore")
    .prefix("PREFIX_")
    .initializeDatabase()
    .build();

// With custom DataSource
EventStorage storage = PostgresEventStorage.newBuilder()
    .dataSource(myDataSource)
    .monitoringDataSource(myMonitoringDataSource)
    .prefix("PREFIX_")
    .build();
```

PostgreSQL requires a `db.properties` file with connection settings. The DataSourceFactory searches for this file in the current directory and up to 2 parent directories.

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

`PostgresEventStorageImpl.stop()` still exists, deprecated, and delegates to `close()`. Prefer `close()`:
it is on the interface, so no downcast, and it works for framework integration (Spring infers `close` as
the destroy method for a `@Bean`; CDI `@Disposes`; try-with-resources).

### Lifecycle: closing a stream

`EventSource` — so `EventStream` — is `AutoCloseable` too, but at a much smaller scale: the only thing a
stream owns is its subscriptions.

- **A stream you only query and append through owns nothing.** `getEventStream()` registers nothing with
  the storage; the registration happens on the *first* `subscribe(...)`, because a stream with no
  subscribers has nothing to do with a notification anyway. Most streams are in this category, are handed
  out per operation, and need no lifecycle handling at all.
- **A stream you subscribe to is held by the storage, strongly, until closed.** This is what makes live
  updates survive the caller dropping the variable:
  ```java
  // this keeps working -- the storage holds the stream, so the subscription cannot be collected
  eventStore.getEventStream(streamId, CustomerEvent.class)
            .subscribe(reference -> { ...; return reference; });
  ```
  The cost of that guarantee is that nothing releases it on your behalf. A subscribed stream that is
  never closed is retained for the lifetime of the storage — deliberately a leak you can find, rather
  than a subscription that dies at an unpredictable GC with no error and no log, which is what holding
  listeners weakly used to give.
- **Close what you subscribe to**, or close the store, which closes them all:
  ```java
  try ( EventStream<CustomerEvent> stream = eventStore.getEventStream(streamId, CustomerEvent.class) ) {
      Projector.from(stream).towards(projection).subscribe().build();
      ...
  }   // subscriptions ended, registration released
  ```
- **Closing a stream is not terminal**, unlike closing a store or a storage. It ends the subscriptions and
  clears the listeners; the handle stays usable for query, append and bookmark, and subscribing again
  re-registers it. A stream is a cheap per-operation handle, not a connection — there is nothing to
  protect by poisoning it. Idempotent, and closing a never-subscribed stream is a no-op.
- **Consistent append listeners need no registration.** `EventStreamConsistentAppendListener` is called
  inline by `append()` on the same object, never through a storage notification, so subscribing one
  registers nothing. `close()` still discards it.

For backends: `EventStorage.unsubscribe(EventStoreListener)` is the SPI counterpart, and `subscribe` must
hold listeners **strongly** and be idempotent per listener. `unsubscribe` has a no-op `default` so a
backend written before it still compiles — and `EventStreamSubscriptionLifecycleTest` in the TCK catches
one that relies on that default, by asserting a closed stream becomes unreachable. No count of delivered
notifications can catch it: a closed stream has already discarded its listeners, so it stays quiet whether
or not the storage let go of it.

### Typical Usage Pattern

```java
// 1. Create storage and event store
EventStore eventstore = InMemoryEventStorage.newBuilder().buildStore();

// 2. Get an event stream
EventStreamId streamId = EventStreamId.forContext("customer").withPurpose("123");
EventStream<CustomerEvent> stream = eventstore.getEventStream(streamId, CustomerEvent.class);

// 3. Append events (simple append)
stream.append(AppendCriteria.none(), Event.of(new CustomerRegistered("John"), Tags.none()));

// 4. Query all events
Stream<Event<CustomerEvent>> events = stream.query(EventQuery.matchAll());

// 5. Query with filters
Stream<Event<CustomerEvent>> filtered = stream.query(
    EventQuery.forEvents(EventTypesFilter.of(CustomerRegistered.class), Tags.of("region", "EU"))
);

// 6. Conditional append with optimistic locking
EventQuery customerQuery = EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123"));
List<Event<CustomerEvent>> existingEvents = stream.query(customerQuery).toList();
EventReference lastRef = existingEvents.getLast().reference();

stream.append(
    AppendCriteria.of(customerQuery, lastRef),
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
Projector.from(stream).towards(projection).build().run();
// initQuery finds the last StockCounted, then eventQuery processes only subsequent movements
```

### Importing Events Between Stores

`EventStoreImporter` (in `org.sliceworkz.eventstore.migration`, api module) copies events from one
`EventStorage` into another via the SPI method `EventStorage.importEvents(List<EventToImport>, ImportMode)`.

```java
ImportReport report = EventStoreImporter.from(sourceStorage).to(targetStorage)
    .mode(ImportMode.SKIP_EXISTING_ID)                       // default is FAIL_ON_EXISTING_ID
    .after(previousReport.sourceTo())                        // optional: catch-up run
    .transform(src -> Optional.of(EventToImport.from(src)    // optional: remap / rewrite / drop
                        .withStream(archiveStream)))
    .batchSize(1000)
    .onProgress(r -> LOGGER.info("{}", r))
    .run();
```

**What survives, what does not:**
- **Preserved**: `EventId`, timestamp, idempotency key, event type, tags, immutable and erasable payloads
- **Reassigned by the target**: `position` and `tx`. An import reproduces the source *order*, never its
  ordering numbers. `index` is a read-time upcasting artifact and is always 0 at rest.

**Why it lives at the SPI level.** `EventToImport`/`StoredEvent` carry opaque JSON plus a type name, so an
import needs no domain classes on the classpath, does no serde round-trip, does not upcast, and does not
re-split `@Erasable` fields against annotations that may have changed. Going through `EventStream` instead
would rewrite legacy events into current ones and lose the idempotency key, which the public `Event` record
does not carry.

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
- **One importer at a time per target** — the conflict check and the insert are not under a common lock.
- **Listeners are notified** exactly as for appends, so a merge into a live store wakes its projections.
  Imported events arrive at new (high) positions carrying old timestamps, so "later position implies later
  timestamp" no longer holds in that store.
- **Checking a target up front** must be done in **raw mode**
  (`eventStore.getEventStream(EventStreamId.anyContext())`, no event root classes). With domain classes
  registered, `getEventById` upcasts, and a legacy event whose upcast yields zero current events comes back
  as an empty list even though it exists — a false negative.

`EventToImport`'s canonical constructor is public, so it also writes synthetic events with a chosen id and
timestamp directly into a store — useful for fixtures, but it bypasses `append()` and everything that path
guarantees.

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
`EventStoreBackend`, reported under its own name (`testQueryOneEvent [postgres:18]`). This replaces
the hand-written `@Nested OnInMem / OnPostgres17 / OnPostgres18` triples that used to be copy-pasted
into every scenario class.

Backends are discovered with the `ServiceLoader`. In this repository the set is declared in
`sliceworkz-eventstore-tests/src/test/resources/META-INF/services/org.sliceworkz.eventstore.testing.EventStoreBackend`
and covers **all four in-tree storages**: `inmem`, `inmem-fs`, `postgres:17` and `postgres:18`. Adding
a storage to the compliance run is one line in that file.

- Narrow a local run with `-Deventstore.testing.backends=inmem` to skip the containers entirely.
- Scenarios needing an optional part of the contract declare it —
  `@ForEachBackend(requires = Capability.IMPORT)` — and are *skipped*, not failed, on backends that do
  not support it. Capabilities: `IMPORT`, `TABLE_PREFIX`, `RESULT_LIMIT`, `RAW_STORAGE_ACCESS`.
- `@ForEachBackend(excludingBackends = "inmem-fs")` opts a backend out **for cost, not capability** —
  `EventStorePerformanceTest` uses it because 10.000 appends against a file-backed store dominates CI
  time. Also reported as skipped, so the gap stays visible. Not allowed inside the TCK: a compliance
  scenario that skips a backend proves nothing about it, so use `requires` there instead.
- `TckBackendCoverageTest` fails the build if a TCK scenario is annotated `@Test` (so it would run
  against one backend only), if one opts a backend out with `excludingBackends`, or if a backend goes
  missing from the service file. All three are silent failures otherwise — that is exactly how three
  scenario classes came to run in-memory only.

Backends run one after another in a single JVM, and in-JVM parallelism
(`junit.jupiter.execution.parallel.enabled`) is not an option without changing how isolation works
first: per-test isolation on Postgres is `initializeDatabase()` dropping and recreating the tables
for the store's prefix, so two scenarios sharing a backend concurrently would drop each other's
tables mid-test. To split a run anyway, `-Deventstore.testing.backends=...` partitions it across
separate JVMs.

The Postgres backends are `Postgres17Backend` and `Postgres18Backend`; the shared base
`AbstractPostgresBackend` is abstract on purpose, so no class name can be read as "PostgreSQL,
unspecified version". `AbstractPostgresBackend.forImage("postgres:15")` covers a version with no
dedicated class (the image tag becomes the backend name, so the version still shows in reports).

**Test Structure:**
`sliceworkz-eventstore-tests` runs the TCK against every in-tree backend — via surefire's
`dependenciesToScan`, which is the same one line a third-party `EventStorage` adds — plus the few
tests that are repo-internal rather than part of the storage contract (`EventStorePerformanceTest`,
`EventImportRoundTripTest`, `TckBackendCoverageTest`). Postgres containers are managed by
`PostgresContainer`, started once per JVM per image; per-test isolation comes from
`initializeDatabase()` dropping and recreating the schema, not from a fresh container.

**Testing application code:**
`EventStoreFixture` gives application authors a `given/when/then` over an in-memory store — seed
history, run a decider, assert what it appended, assert an `OptimisticLockingException` fires when it
should, drive a projection to a known point. `whenConcurrently(...)` appends into the window between
a decider's query and its own append, which is the only deterministic way to provoke a DCB conflict.
See `EventStoreFixtureTest` for a worked example.

**Timestamps are not assertable.** The in-memory store stamps events from the JVM clock; Postgres does
not bind `event_timestamp` on append at all and lets the DDL default (`CURRENT_TIMESTAMP`, server
clock) apply. There is no `Clock` seam anywhere. Assert on timestamps only with a tolerance window, as
`EventTimestampUtcTest` does. The one path that writes a chosen timestamp is `importEvents`, which
bypasses `append()`.

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

## Key Design Principles

1. **Sealed Event Hierarchies**: Use sealed interfaces with record implementations for type safety
2. **Tag-Based Queries**: Use tags for dynamic event retrieval across event types
3. **Immutable Events**: All events are records and immutable
4. **Optimistic Locking via DCB**: Use `AppendCriteria` for conditional appends based on relevant facts
5. **Storage Abstraction**: Code against `EventStorage` interface for backend independence
6. **Service Loader Pattern**: `EventStoreFactory` uses Java ServiceLoader for implementation discovery
7. **Builder Pattern**: Storage implementations use fluent builders for configuration

## DCB Compliance

This implementation is fully compliant with the [DCB Specification](https://dcb.events/specification/):

- **Dynamic Event Tagging**: Events can be tagged with arbitrary key-value pairs for retrieval
- **Dynamic Consistency Boundaries**: Optimistic locking via `AppendCriteria` ensures consistency by checking for new relevant facts before appending
- **Event Queries**: Allow dynamic selection of relevant events based on types and tags
- **Optimistic Concurrency**: `OptimisticLockingException` is thrown when conflicting events are detected

The key insight of DCB is that business decisions are based on querying relevant historical events, and new events should only be stored if no new relevant facts have emerged since the decision was made. This is achieved through:
1. Query events with an `EventQuery` to make a decision
2. Note the reference of the last relevant event
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

- Table schema can be prefixed (useful for multi-tenancy or isolation)
- Database initialization performed via `.initializeDatabase()` on builder
- Uses HikariCP for connection pooling
- Separate DataSource for monitoring queries (optional, defaults to main DataSource)
- Tests use Testcontainers for isolated PostgreSQL instances
- Requires the `btree_gin` extension (a standard contrib extension, available on the major managed Postgres offerings). Schema initialization runs `CREATE EXTENSION IF NOT EXISTS btree_gin` and schema validation requires `idx_events_stream_tags`, a combined stream+tags GIN index that serves DCB reads scoping by stream *and* filtering by tags in one index. The B-tree indexes are retained for ordered stream replay (GIN cannot serve `ORDER BY`)
- **Ordering is the `(event_tx, event_position)` tuple everywhere — reads, the `until` boundary, and the
  optimistic-locking check.** `event_position` is a `bigserial` taken at insert time and `event_tx` is
  `pg_current_xact_id()`, assigned independently, so the two orders genuinely disagree: a transaction can
  carry a lower position and a higher tx than one that committed before it. The read path's
  `pg_snapshot_xmin` barrier makes that ordinary rather than exotic — it withholds an event whose
  transaction is still in flight, so a reader takes a reference and the event surfaces afterwards, sorting
  later on a lower position. Any predicate over the log's order goes through `addCursorBoundary` or
  `addUntilBoundary`; comparing `event_position` alone is a different order and silently drops events.
  Writers that do not hold the append lock (unconditional appends, `importEvents`, raw SQL) are where the
  inversion actually comes from, which is why the advisory lock below does not subsume this
- **Conditional appends are serialized per stream by a `pg_advisory_xact_lock`.** The optimistic-locking
  check is an `INSERT … WHERE NOT EXISTS (…)`, and under PostgreSQL's default READ COMMITTED isolation each
  statement fixes its snapshot when it starts, so two concurrent appends at the same consistency boundary
  both find it empty, both insert, and both commit — a silent DCB violation. The conflicting row is a
  *phantom* at the moment of the check, so no row lock can cover it. `append` therefore takes a
  transaction-scoped advisory lock, keyed on a SHA-256 of the table prefix plus `(stream_context,
  stream_purpose)`, before running the INSERT. Consequences worth knowing:
  - **The lock is taken as its own statement, before the INSERT** — this is load-bearing, not stylistic.
    Folded into the INSERT's `WHERE`, the statement would block with its stale snapshot already taken and
    the check would still miss the other appender's row: the same race with a lock in front of it.
  - **Only conditional appends take it.** `AppendCriteria.none()` reads nothing and so cannot observe a
    stale boundary; a conditional append that misses one is still equivalent to the two having run in the
    order conditional-then-unconditional, which is a legitimate history. Bulk ingestion stays fully parallel.
  - **The key is the stream, not the filter.** Hashing the filter would be finer grained and unsound: two
    overlapping-but-unequal filters (tag `A` vs tags `A + B`) hash differently and would not exclude each
    other. An append not confined to one fully specified stream falls back to a storage-wide key.
  - **Cost**: measured at ~5% against 8 concurrent writers spread over 1000 streams. Conditional appends to
    *one* stream serialize for the duration of a single INSERT, so a hot stream (remember a stream is
    usually a bounded context, not one aggregate) is the ceiling to watch. Key collisions only make two
    unrelated streams take turns; they can never let a real conflict through.
  - **Why not `SERIALIZABLE`**: it is correct, but a poor fit. A DCB boundary check is always
    `event_position > <recent reference>`, i.e. a scan of the log's tail, which is exactly where every
    writer writes — so SSI predicate locks collide constantly. Measured on the same workload: 86%
    serialization failures and a third of the throughput, with disjoint boundaries falsely conflicting
    because the planner's choice (seq scan → relation-level `SIRead` lock) decides the granularity.
  - No DDL change, so no migration: the lock is entirely in the write path.
- **Oldest supported PostgreSQL is 16** (`Builder.OLDEST_SUPPORTED_MAJOR_VERSION`). The schema itself only
  needs 13 — `xid8`, `pg_current_xact_id()` — but 16 is both the oldest version with a support life worth
  committing to (13 went end-of-life in November 2025, 14 follows in November 2026, 15 in November 2027)
  and the oldest this library has ever actually worked on. The docs previously promised 13+ while the
  compliance run covered 17 and 18 only, and adding a floor backend showed the claim had never held: the
  conditional append's `SELECT * FROM ( VALUES … )` carried no alias, which PostgreSQL only made optional
  for FROM-clause subqueries in 16, so **every** conditional append — every DCB consistency check — failed
  on 15 and older with `VALUES in FROM must have an alias`. The alias (`AS new_events`) is there now and is
  kept even though 16 does not need it. An older server is **warned about, not rejected**: a hard failure
  would turn a library upgrade into an outage, and the warning names the version. `Postgres16Backend` is in
  the TCK service file so the floor is actually exercised — that is what an untested support claim is worth,
  and why the floor backend earns its CI minute
- **`ENSURE` brings functions and triggers up to date; tables, columns and indexes are only ever created.**
  The functions are `CREATE OR REPLACE`d and each trigger is compared against the shape this release wants
  (`tgtype` plus target function, in a `DO $$` block) and recreated only when it differs — so wrong timing,
  wrong orientation or a trigger pointing at the wrong function self-heal, while the ordinary startup, where
  the trigger is already correct, is a catalog read that takes no lock on the events table. `CREATE OR
  REPLACE TRIGGER` would be simpler but is PG14+ *and* rewrites unconditionally, taking `ACCESS EXCLUSIVE`
  on every start of every instance. `drop-schema.sql` drops the two functions as well as the tables, which
  is what makes `INITIALIZE` mean what it says: the triggers go with the tables via `CASCADE`, the functions
  do not, so before this a stale body survived the "drop and recreate from scratch" mode and the freshly
  created trigger was wired straight back to it — the store then reported a validated schema with its
  notifications dead
- **Schema scripts run as one transaction under a per-prefix advisory lock** (`executeSqlScripts`, keyed on
  a SHA-256 of the prefix and a scope no stream can produce, sharing `advisoryLockKey` with the append lock).
  `CREATE TABLE / INDEX / EXTENSION IF NOT EXISTS` is not atomic against a concurrent creator, so before this
  several instances starting together on a database without the schema raced on the system catalogs and 64 of
  80 failed to start, on PG17 and PG18 alike. One transaction across *all* scripts also makes `INITIALIZE`'s
  drop-then-ensure indivisible, so a second instance cannot drop what the first has just recreated
- **What is still missing: a version marker, and validation of an object's shape.** `checkDatabase()` checks
  that named tables, columns (type + nullability), functions and indexes *exist*, and that each trigger
  exists with the expected `action_orientation`; it does not check
  an index's method, columns or uniqueness, a column default, or a function body. So an index rebuilt as the
  wrong kind, or the idempotency index silently made non-unique, passes validation — and a `VALIDATE`/`NONE`
  deployment, where a DBA applies DDL, never gets the function repair either. A change needing `ALTER TABLE`
  still has to be applied by hand (see the manual migrations below). `PostgresSchemaDriftTest` pins down both
  halves per backend: what `ENSURE` now repairs, and what it still does not. See
  `sliceworkz-eventstore-infra-postgres/SCHEMA-MIGRATION.md` for the measurements and the recommended
  version-table design
- **Append notifications are emitted once per stream per statement, not once per row.** The trigger on
  `<prefix>events` is `AFTER INSERT ... REFERENCING NEW TABLE AS inserted FOR EACH STATEMENT`, and the
  function emits one `pg_notify` per distinct `(stream_context, stream_purpose)` in the transition table,
  carrying that stream's maximum over the total `(event_tx, event_position)` order. It was `FOR EACH ROW`,
  which meant a 1000-event append queued 1000 notifications and an import chunk queued 5000 — all but one
  per stream discarded by `OptimizingApendListenerDecorator` after being built as JSON, written to the
  cluster-wide async queue, sent over the wire, parsed by Jackson and fanned out to every listener.
  Measured on the PG16 floor, a 100k-row insert: the notification count falls from 100.000 to exactly 1,
  and trigger time roughly halves — 1230ms to 460ms on one run, 808ms to 369ms on another (the absolute
  numbers move a lot between runs; the ratio is the stable part). The remaining cost is the transition
  table, which is materialised as a tuplestore and then sorted, so this is not free — just far cheaper
  than a plpgsql invocation and a queued notification per row. This also matches the in-memory backends,
  which have always notified once per stream per append. Things to keep in mind when touching this:
  - **The aggregation is `DISTINCT ON (stream_context, stream_purpose) ... ORDER BY event_tx DESC,
    event_position DESC`, not `max(event_position)`.** The two orders genuinely disagree (see the
    `(tx, position)` note above), and `DISTINCT ON` returns the whole winning row, so `event_id` belongs
    to the reference being reported instead of being aggregated independently of it. A notification naming
    a reference the reader has already passed is dropped by the optimizing decorator, so getting this
    wrong strands subscriptions silently rather than loudly.
  - **One notification per *distinct stream*, never a single collapsed "something happened".**
    `AppendsToEventStoreNotification.isRelevantFor` matches through `EventStreamId.canRead`, so the
    notification has to name a concrete stream or no concrete subscriber matches it.
  - The payload shape is unchanged — `eventTx` is still rendered as a JSON *string* by
    `jsonb_build_object` — so the Java side needed no change.
  - **The trigger's expected `tgtype` is 4** (`INSERT` with the `ROW` bit clear), where the row-level
    version was 5. That is what makes an un-migrated database fail the shape compare and get repaired by
    `ENSURE`. `tgnewtable = 'inserted'` is compared too: the function reads the transition table, so a
    statement-level trigger declared without `REFERENCING` would fail at runtime rather than at startup.
  - The bookmark trigger is deliberately still `FOR EACH ROW`: `bookmark()` is a single-row upsert, so
    per-row and per-statement are the same count there.
  - `EventAppendNotificationGranularityTest` in the TCK pins the granularity and the reference down for
    every backend.
- **`checkTrigger` validates `action_orientation`, not just the trigger's name.** A `VALIDATE`/`NONE`
  deployment never gets the `ENSURE` repair, so without this an un-migrated database would start and
  misbehave. The failure it prevents is not loud: a statement-level trigger bound to a stale row-level
  function body does *not* raise in PostgreSQL — `NEW` is unassigned, so it emits a notification with
  every field null, which becomes a wildcard stream with a zero reference that every concrete
  subscriber's `canRead` rejects. Live updates would stop with nothing thrown and nothing logged.
- **The async notification queue was never the binding constraint.** Measured on PG16, 100.000 pending
  notifications occupy 0.217% of it, so it holds ~46 million and a single transaction would need that many
  events to hit `NOTIFY queue is full` (SQLSTATE 53200) — far past where the in-memory `List<EventToImport>`
  would OOM first. `EventStoreImporter` also commits one transaction per `batchSize` (default 1000), so a
  million-event migration is a thousand commits, not one. The queue is cluster-wide and only recycled once
  every listener has consumed, so a stalled listener does make usage accumulate monotonically across
  transactions — but from a base low enough that the amplification was a throughput and latency problem,
  not a correctness-of-operation one.
- `stream_purpose` defaults to `'default'` in the DDL, matching `EventStreamId.DEFAULT_PURPOSE`. On a database created before this alignment (default was `''`), operators doing raw SQL inserts should run `ALTER TABLE <prefix>events ALTER COLUMN stream_purpose SET DEFAULT 'default';` — no data migration is needed since all events written through the library bind the purpose explicitly
- **Idempotency keys are scoped per event stream (context + purpose), not per storage/table.** Uniqueness is enforced by the partial unique index `idx_events_stream_idempotency` on `(stream_context, stream_purpose, idempotency_key) WHERE idempotency_key IS NOT NULL` (schema validation requires it), so the same key used on two unrelated streams does not collide and dedup behaviour does not depend on how storage instances / prefixes are wired at runtime. The `idempotency_key` is persisted and surfaced on `StoredEvent` when reading (it is not exposed on the public `Event` record). A duplicate append is still silently ignored (returns an empty result). On a database created before this change (when `idempotency_key` had a table-wide `UNIQUE`), migrate with: `ALTER TABLE <prefix>events DROP CONSTRAINT <prefix>events_idempotency_key_key; CREATE UNIQUE INDEX <prefix>idx_events_stream_idempotency ON <prefix>events (stream_context, stream_purpose, idempotency_key) WHERE idempotency_key IS NOT NULL;` — no data migration is needed
- **Importing needed no DDL change**: `event_id` is already a plain `UUID NOT NULL UNIQUE` and `event_timestamp` is nullable with a `CURRENT_TIMESTAMP` default, so both can be supplied explicitly. `importEvents` binds them per row, chunks statements at 5000 rows (9 params/row against the 65535-parameter wire ceiling) inside a single transaction, and matches `RETURNING` rows **by event_id** rather than by row order — with `ON CONFLICT` the returned rows are a subset of the input, so position in the result set means nothing. Conflicts are routed by constraint name from `PSQLException.getServerErrorMessage().getConstraint()`, not by matching message text
- **Imported event ids must be UUIDs** (the `::uuid` cast); `importEvents` validates this up front to give a clear error rather than an opaque cast failure
- **`timestamptz` keeps microseconds and rounds anything finer**, so a nanosecond-precision timestamp (as an in-memory store produces) lands up to half a microsecond away from where it started. This is the only lossy part of an inmem → Postgres → inmem round trip; `EventImportRoundTripTest` pins it down
