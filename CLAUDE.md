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
- `sliceworkz-eventstore-testing`: Backend harness, the shared TCK, and the application-author fixture
- `sliceworkz-eventstore-examples`: Example usage code
- `sliceworkz-eventstore-benchmark`: Capacity-characterisation suite (nothing runs during a build)

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
- **`query()` returns a `Stream`, but it is already in memory.** Storage has finished reading by the
  time the stream comes back — the whole result set is fetched and the stream iterates a list. So
  `findFirst()`, `.limit(10)` and `takeWhile` on the returned stream discard work already done, and a
  query with no limit against a storage with no `resultLimit` reads everything matching into heap: an
  OOM rather than a slow stream, with no back-pressure to arrive at. Bound the read with
  `EventQuery.limit(n)`, which is the limit storage is given. Nothing needs closing — no database
  resource is held open behind the stream, so it is safe to abandon half-consumed (`EventSource.close()`
  is about subscriptions, not queries)
- **A full replay is a loop, not one unbounded query.** `Projector` already reads in batches of 500,
  carrying a cursor between them, and is the right tool for a stream of unknown size. By hand, page with
  `query(q.limit(n), cursor)`, advancing `cursor` to the last reference of each page. The unlimited path
  exists for callers who know their result set is small, or who genuinely want it all at once — it is
  not a way to process a large stream incrementally

**Event:**
- Record type containing: `stream`, `type`, `reference`, `data`, `tags`, `timestamp`
- Data is the actual domain event (typically a sealed interface with record implementations)
- Tags enable dynamic querying and consistency boundaries
- Created via `Event.of(data, tags)` for ephemeral events or full constructor for persisted events

**EphemeralEvent:**
- Lightweight event representation before persistence (no stream, reference, or timestamp)
- Converted to full `Event` upon appending to a stream
- Created via `Event.of(data, tags)`

**EventType:**
- The stored name of an event: `EventType.of(Class)` is `Class.getSimpleName()`, with no override
- Deliberately the *simple* name, not the fully qualified one, so moving a class between packages —
  the refactor people actually do — costs nothing
- **The simple name is therefore wire format**, and it is global to a storage rather than scoped to a
  stream. See "Event type names are wire format" under Naming Conventions before renaming an event class
  or introducing a second class with an existing simple name

**Tags:**
- Key-value pairs attached to events for dynamic retrieval
- Enable querying events across different event types
- Core to the Dynamic Consistency Boundary pattern
- Created via `Tags.of("key", "value")` or `Tags.of(Tag.of("key", "value"))`
- **`Tag.toString()` is the wire format, not a debugging rendering.** A tag is flattened to
  `"key:value"` to be persisted and to be matched: the Postgres backend stores `Tags.toStrings()` in a
  `text[]` column and answers a tag query with `event_tags @> ARRAY[...]` built from the *same*
  rendering, then hands tags back through `Tags.parse(String[])`. The string is unescaped, and
  `Tag.parse` splits on the **first** `':'`, strips both halves and maps an empty half to `null` —
  so `toString`/`parse` is only a round trip for tags whose key has no colon and whose halves are
  neither empty nor padded. The in-memory backends flatten nothing and match on the `Tag` record,
  so they cannot fail this way, which is exactly why it stayed invisible: a store that behaves in
  tests and diverges in production
- **Construction therefore rejects the shapes that do not survive it**, rather than escaping the
  stored form — existing rows cannot be rewritten, so the encoding has to stay as it is.
  `IllegalArgumentException` for: a `':'` in the **key** (`Tag.of("a:b","c")` rendered as `"a:b:c"`,
  which is also what `Tag.of("a","b:c")` renders to — two logical tags, one stored string); leading
  or trailing whitespace on either half (`parse` strips it); an empty key or value (`parse` nulls
  it); and a tag with neither key nor value (renders as `""`, reads back as nothing). Values may
  contain `':'` freely, and whitespace *inside* a key or value is fine. Whitespace is rejected
  rather than silently stripped so the mistake surfaces where it is made — a caller handling
  untrusted input should `strip()` first. `Tag.of(null, "v")` stays legal, because `Tag.parse(":v")`
  still produces it from history
- **What that buys: `toString` is injective, so `parse(tag.toString())` is the identity** for every
  constructible tag. Two distinct tags on one event can no longer be stored as one array element,
  and a tag read off an `Event` is the tag that was appended — which matters because re-tagging a
  new event with a tag read back from an old one is an ordinary pattern
- **`Tag.parse` and `Tags.parse` stay lenient, deliberately.** They are the read path for tags
  written before any of this was enforced, and they normalise instead of rejecting: a stored
  `"k: v "` comes back as `Tag.of("k","v")`, a stored `"k:"` as `Tag.of("k")`. Everything they
  return is constructible, so reading legacy data never throws. Two consequences for legacy rows
  only: the tag read off such an event is not the tag that was appended, and because `Tags.parse`
  builds a `Set`, two stored strings normalising to the same tag **collapse into one**
- **Matching is exact containment, never key-prefix.** `Tag.of("customer")` and
  `Tag.of("customer","123")` are two different tags on every backend, and a query for the first does
  **not** return events carrying the second. Users reasonably expect the bare key to act as "any
  customer"; it does not, and there is no wildcard form. Tag every event with
  `Tag.of("customer", id)` and query for that; a bare key is a flag, for when the presence of the
  tag is itself the fact
- `TagTest`/`TagsTest` pin the round trip and the rejections; `TagRoundTripTest` in the TCK proves
  per backend that a written tag is found by a query for itself and comes back unchanged, over the
  full legal character set (colons in values, unicode, newlines, `{`/`,`/quotes that are `text[]`
  syntax, 1000-character values)

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
- **`.limit(n)` means "read n stored events", and it is pushed into the storage query** — a SQL
  `LIMIT` on Postgres, a short-circuiting `Stream.limit` in memory — not applied to the result. That
  is what makes it bound memory as well as output: a storage query materialises its whole result set
  before returning it, so an unbounded query over a large stream is a heap problem rather than a slow
  one. A cursor does not change this: `query(q.limit(500), cursor)` reads 500, same as `query(q)`
  would with the limit on `q`. Pass `Limit.none()` to the three-argument overload to read to the end
  of a stream deliberately
- **Without upcasting, n stored events are n events back. With it, they are not.** An `@Upcast`
  method may turn one stored event into several or into none, and the limit is spent before it runs,
  so `.limit(1)` over an event upcasting into two returns two, and over one upcasting into none
  returns zero — having read exactly one stored event either way. Trimming the surplus would return a
  fragment of a stored event and leave a cursor pointing into its middle; `Projector` counts stored
  events for exactly this reason. Where a caller needs exactly n, `.limit(n)` the returned `Stream` —
  cheap, since those events are already read. `UpcastMultiTest` pins this per backend

**AppendCriteria:**
- Controls optimistic locking when appending events
- Contains an `EventFilter` and an optional `EventReference` for the expected last event
- If new matching events are found after the reference, append fails with `OptimisticLockingException`
- Use `AppendCriteria.none()` for simple appends without locking
- Use `AppendCriteria.of(eventQuery, reference)` or `AppendCriteria.of(eventFilter, reference)` for conditional appends
- **`expectedLastEventReference()` is never null**, whichever factory or constructor produced the criteria — the
  compact constructor normalises a null to `Optional.empty()`. `none()` used to put a literal null there, so the
  most common criteria in the library threw an NPE on `.isPresent()` and both in-tree backends carried their own
  null guard; a third-party `EventStorage` had to guess the same one
- **"No criteria" and "an empty expected reference" are different things, and only `isNone()` distinguishes
  them.** `isNone()` is derived from the filter being `matchNone`, independently of the reference. An empty
  reference under a *real* filter means "I decided on an empty stream", which is still a consistency boundary:
  any matching event in the stream is a new relevant fact and must raise `OptimisticLockingException` (see
  `OptimisticLockingTest.testOptimisticLockingSucceedsWhenExpectingEmptyStreamAndStreamIsNotEmpty`). A backend
  skipping the check when the reference is absent is a silent loss of optimistic locking; `AppendCriteriaTest`
  in the TCK pins both halves down

**Projection:**
- Combines an `EventQuery` with an `EventHandler`
- Processes all events matching the query criteria
- Used for building read models from event streams
- Optionally defines an `initQuery()` for the savepoint pattern (see below)

**BatchAwareProjection — the seam between a projection's own store and the bookmark:**
- A `BatchAwareProjection` commits its own work in `afterBatch`, and the bookmark saying how far it has
  come lives in the event store. There is no transaction across the two, so the ordering is the whole
  guarantee: **the batch is committed first and bookmarked second**, which makes a crash in that window
  cost a re-projection and never a silent skip. At-least-once, deliberately, in that direction
- **The bookmark is placed after every batch, not once per run.** It used to be written after the whole
  `run()` loop, so a catch-up that committed 2000 batches and then died replayed all of them — the window
  was the entire run rather than the 500 events the batch boundary suggests. The cost of the change is one
  bookmark upsert per batch during a replay, which is what the bookmark is for
- **A batch that fails takes the projector's cursor back with it.** `afterBatch` is called inside the
  try, not from a `finally`: a commit that throws now rolls `lastEventReference` back to where the batch
  started and is reported as a `ProjectorException` like any other projection failure. Before, it escaped
  the projector as a bare `RuntimeException` **past** the bookmark placement while the in-memory cursor
  stayed advanced — so the rolled-back batch's events were skipped for good, and a later successful batch
  bookmarked over the hole. Nothing threw where anyone would see it, and downstream the untyped throwable
  missed the `catch ( ProjectorException )` that would have stopped the processor, landing in a catch-all
  that immediately looped: a projection whose commits kept failing re-queried and re-projected at full
  thread speed
- **A batch is ended exactly once.** `cancelBatch` is not called after an `afterBatch` that threw — that
  projection has already released what it held — and a `cancelBatch` that throws is logged and attached
  to the original failure as a **suppressed** exception rather than replacing it. It used to replace it,
  so a poison event whose rollback also failed was reported as a rollback problem, sending whoever read
  the log to the wrong store. `BatchAwareProjection.cancelBatch` had always documented the containment;
  only now does it happen
- **Where a re-projection would duplicate rather than merely repeat, the projection should hold its own
  position.** `afterBatch` is handed the batch's last `EventReference` for exactly this: write it into the
  same store, in the same transaction, and resume from it. Two stores that cannot share a transaction
  cannot be made exactly-once any other way. `sliceworkz-eventmodeling`'s `SqlReadModelProjector` is the
  worked example
- `ProjectorBatchDurabilityTest` in the TCK pins all of it per backend: the bookmark visible from inside
  the *second* batch already names the first, a failed commit is a `ProjectorException` and its events
  come round again, and a failing rollback keeps the cause

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

### Lifecycle: starting a store when the database is not there

`build()` finishes by starting the two LISTEN/NOTIFY monitors and waiting for them to register. That wait
is **bounded** (10s by default) because the monitors have no failure mode: on a `SQLException` they log,
back off and retry for as long as the storage lives. Waiting on them without a deadline is waiting on
something that may never happen — which is exactly what an unreachable database used to do to
`build()`: hang forever, with no exception, no timeout and nothing logged above DEBUG.

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
  store down when its connection drops.
- **Which configurations can reach this.** With `ENSURE` or `VALIDATE` the schema work runs first and
  fails with a clear error, so a dead *main* DataSource never reaches the wait. The exposed paths are
  `DatabaseInitMode.NONE` (recommended for production, where nothing touches the database before the
  monitors do) and — the realistic one — a **reachable main DataSource with an unreachable monitoring
  one**. Those two are configured separately precisely because LISTEN/NOTIFY does not survive a
  transaction pooler, so "pooled works, direct is firewalled" is an ordinary misconfiguration.
  Note that version detection (`detectsNativeUuidv7Support`) does *not* fail the build — it logs a WARN
  and falls back to the legacy implementation — so it is the schema work, not the version probe, that
  provides the fail-fast.
- **Observability.** `sliceworkz.eventstore.notifications.up` is a gauge, 1/0, tagged `storage` and
  `channel` (`event_appended` / `bookmark_placed`). It is registered by the constructor, so the series
  exists reading 0 from the moment the storage does — a gauge that only appears once notifications work
  is no use for alerting on notifications not working. It also drops back to 0 when a *running* store
  loses its monitoring connection, which is the same silence as never having had one.
  `PostgresEventStorageImpl.isNotificationsAvailable()` is the same state for a health endpoint, at the
  cost of a downcast from `EventStorage`.
- **An interrupt during startup throws** `EventStorageException` and closes the storage, rather than
  returning quietly. The alternative — restore the flag and return — hands back a storage nobody can tell
  is unstarted, with two monitor threads still retrying behind it.
- **`close()` releases a caller still inside `start()`.** It counts the readiness latches down itself,
  because the monitors it stopped never will. Without that, closing a storage whose `start()` was still
  waiting left that thread parked forever.

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
- **There is only one kind of append listener, and it is eventually consistent.** To react to your own
  append on the appending thread, nothing is subscribed: the typed events, with their assigned
  references, are the return value of `append()`.
- **What makes it cheap is that the expensive part is shared, not rebuilt.** `getEventStream` allocates a
  stream object and resolves ~10 Micrometer meters (a map lookup each, since Micrometer dedups by name +
  tags) — about **2µs and 1KB**. The payload serde is *not* rebuilt: `EventStoreImpl` caches one per
  distinct pair of event root class sets and hands the same instance to every stream opened with that
  mapping. Building one costs ~20µs and ~40KB, but the construction is the smaller half of the story —
  Jackson caches its per-type serializers **inside the mapper**, so a serde per call gives every stream a
  cold type cache and re-runs bean introspection on the first serialize of each record type. Measured on
  a 24-record sealed hierarchy, a serde per call made a query through a freshly obtained stream
  **~175µs / 139KB against ~36µs / 69KB** through a stream that was kept — four times the work, for a
  call the documentation calls cheap. With the serde shared the two are the same.
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
- **`sliceworkz.eventstore.append.position`** is a gauge of the highest position appended, tagged like
  the other stream meters (`context`, `purpose`, `typed`, `storage`), and reads `NaN` until something is
  appended. Its state is held **per tag set on the store**, not per stream, and registered once — because
  a gauge cannot be re-registered (Micrometer keeps the first registration and ignores the rest) and
  because Micrometer holds gauge state *weakly*. With a per-stream holder only the first stream ever
  created for a tag set was wired to the series, and the series went permanently `NaN` as soon as that
  stream was collected — which, in the per-operation usage recommended above, is almost immediately.
  Nothing failed; the metric was just stuck. `AppendPositionGaugeTest` covers it. The tag set it is held
  per is bounded — see the metrics section below for why that matters and what it costs when it is not.

For backends: `EventStorage.unsubscribe(EventStoreListener)` is the SPI counterpart, and `subscribe` must
hold listeners **strongly** and be idempotent per listener. `unsubscribe` has a no-op `default` so a
backend written before it still compiles — and `EventStreamSubscriptionLifecycleTest` in the TCK catches
one that relies on that default, by asserting a closed stream becomes unreachable. No count of delivered
notifications can catch it: a closed stream has already discarded its listeners, so it stays quiet whether
or not the storage let go of it.

### Listeners: one kind, eventually consistent, and what a failure costs

**`append()` returns the events it wrote** — typed, with their assigned references, the same list the
caller would otherwise have had to query back. That is the whole of this store's read-your-own-writes
story, and it is why there is exactly one listener interface:
`EventStreamEventuallyConsistentAppendListener`. To react to your own append on the appending thread,
write the code after the call.

**No listener runs in a transaction, and none can veto an append.** `EventStorage.append` commits before
it returns — on Postgres by issuing the `COMMIT` inside it, in memory by having the events in the log — so
by the time anything is notified, the events are durable and every other reader can see them. A
notification is an announcement, never a vote.

**A listener failure is never anybody else's failure, and never silent.** Each subscriber's exception is
contained, logged at ERROR, and the next subscriber still gets the notification.

- **The bystanders are the other subscribers.** An escaping throwable used to end the whole notification
  task, so every subscriber after the failing one missed that append too.
- **It used to be invisible as well.** The throwable surfaced on the virtual thread's uncaught-exception
  handler: a bare stack trace on `System.err`, at no level, under no logger name, attributed to nothing.
  `Projector.eventsAppended` calls `run()`, which rethrows a `ProjectorException`, so the ordinary case — a
  projection that throws — was a read model that stopped advancing with nothing in the application's own
  logs to say why. Bookmark listeners get the same containment.
- This is what the storage backends have always done with their own listeners (`notifyQuietly` in
  `InMemoryEventStorageImpl`, and the Postgres LISTEN/NOTIFY monitors), after the same bug there.
- **Nothing replays what a failing listener missed.** It is notified again on the next append; the
  notification it failed on is gone. A listener that must not lose progress belongs behind a `Projector`
  reading from a bookmark.
- `AppendListenerFailureTest` in the TCK pins it per backend: a throwing subscriber does not starve the one
  behind it, and notifications keep arriving for both afterwards.

**A listener that returns null is caught up, not asking to be told again.** `OptimizingAppendListenerDecorator`
keeps delivering until the listener has reached the target it was notified about, and it used to learn that
only from a non-null return — so a null left it with nothing to compare against and nothing to reach, and it
re-delivered the same target without pausing: ~700.000 deliveries a second on one pinned virtual thread.

- **The ordinary listener hits this, not an exotic one.** `Projector.eventsAppended` returns
  `run().lastEventReference()`, which is null whenever the query matched no events — so *any* subscribed
  projector whose event type had not occurred yet burned a core from the first unrelated append to its
  stream until the first matching one. Nothing threw, nothing was logged, and it cleared itself the moment
  one matching event arrived, which is why it survived: it looks like load, not like a bug.
- Null and a reference *behind* the target now mean the same thing — caught up to the target. Nothing is
  lost by that: the next append carries a later reference, which is after this one and so still delivered.
- The interface has always documented the return as "never null"; `Projector` has always violated it, so
  null is given a defined meaning rather than left to whoever reads the contract more carefully.
- `AppendListenerFailureTest.testListenerReportingNoProgressIsNotRedeliveredTo` pins it per backend.

### Bookmarks: a cursor that must name a stored event, and a foreign key that never cascades

- **`placeBookmark` rejects a reference this storage never stored** — `EventStorageException`, nothing
  written, and a previously placed bookmark for that reader stays. The realistic mistake it catches is a
  reference from a *different* store or prefix in a miswired multi-store setup, which would otherwise
  poison the reader's cursor silently. Postgres enforces it with the `fk_bookmarks_event_id` foreign key
  (recognised by the constraint name the server reports, like the idempotency index — never by message
  text); the in-memory store checks its log under the same monitor that guards `append`. The contract is
  documented on `EventStorage.bookmark`, and `BookmarksTest` in the TCK pins it per backend — before
  this, the backends genuinely diverged: Postgres rejected, in-memory accepted anything, and no TCK
  scenario said which was intended
- **The check is on the event id alone, matching the foreign key.** The `(tx, position)` pair that
  cursor comparisons actually order by is not cross-validated — a bookmark carrying a stored id with a
  wrong position still passes. Keep that in mind before reading the constraint as "the bookmark is
  valid": it says the event exists, not that the cursor is coherent
- **The foreign key deliberately does not cascade.** An absent bookmark means "replay from the
  beginning" — for a dispatcher in the eventmodeling framework, duplicate publishing to an external
  system, the worst outcome it documents. `ON DELETE CASCADE` handed exactly that to the readers least
  able to afford it: an event deletion (retention pruning, surgically removing a poison event) cascades
  away the bookmarks of readers still pointing into the deleted range — the *lagging* ones — silently,
  with no notification, since the bookmark trigger fires on INSERT/UPDATE only. A dangling cursor is
  harmless by contrast: reads compare the stored `(event_tx, event_position)` and never join back to the
  events row. With the default NO ACTION, deleting events out from under an outstanding bookmark fails
  loudly, and whoever prunes decides explicitly what happens to the reader
- **Migration for a database created while the cascade existed**: `ENSURE` only ever creates tables, so
  an existing database keeps its constraint until migrated by hand —
  `ALTER TABLE <prefix>bookmarks DROP CONSTRAINT fk_bookmarks_event_id; ALTER TABLE <prefix>bookmarks
  ADD CONSTRAINT fk_bookmarks_event_id FOREIGN KEY (event_id) REFERENCES <prefix>events(event_id);` —
  no data migration is needed. `checkDatabase()` validates the constraint by name only, not its delete
  rule, so an un-migrated database still starts, with the old cascade behaviour

### Leases: electing one processor among several instances

**The storage can hold named leases, which is what a framework builds leader election on** — one
instance of a deployment holds a lease and processes; the others stand by and take over when it
expires or is released. Three optional SPI methods on `EventStorage` (`UnsupportedOperationException`
defaults, the `importEvents` precedent; `Capability.LEASE` gates the TCK scenarios, and its
`supports()` default answers **false**, so a backend written before leases existed skips them —
which is also why `supports()` is now an exhaustive switch rather than "true for anything new"):

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
- **The fencing token strictly increases on every ownership change and never resets** — a release
  *backdates the heartbeat* rather than deleting the row, precisely so the token survives (the TCK
  caught the delete-based version minting token 1 twice). Renewals keep the token
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
  notice an un-migrated database; the README's privilege table carries the grants (the leases table
  needs no `DELETE` — releases update; contender rows are pruned, so that table does)
- `LeaseTest` in the TCK pins the state machine per backend: acquire/renew/expire/release, the
  step-down protocol and its lapse with a dead contender, fencing monotonicity across takeovers and
  releases, independence of distinct leases, post-close behaviour — and, load-bearing above all,
  that exactly one of N concurrent contenders wins an acquirable lease

### Metrics: what the stream meters cost, and the cap on `purpose`

Every meter the store registers is tagged `context`, `purpose`, `typed`, `storage`, and two of them
(`query.event`, `append.event`) add `eventtype` on top. `context` is a code-level concept, so its
cardinality is a property of the application. **`purpose` is not**: it is documented as "an optional
secondary identifier … (e.g. customer ID, order number)", and half the examples in this repository are
`forContext("customer").withPurpose("123")`. Used that way it takes one value per entity.

- **Nothing evicts a meter.** A Micrometer registry keeps every meter it has ever registered, so the cost
  follows the number of distinct purposes the process has *ever seen*, not how many streams are alive.
  Dropping the stream handle — the per-operation usage this document recommends — releases none of it.
- **Measured, per distinct purpose** (in-memory store, two event types, `SimpleMeterRegistry`):
  **15 meters** (+2 per further event type), **~5.5 KB of heap**, **18 Prometheus series** and ~2.4 KB
  of scrape body. At 10.000 purposes that was 150.000 meters, 53 MB and a 23 MB scrape; at 100.000 it
  extrapolates to ~550 MB and 1.8M series. Nothing fails — the numbers stay correct and the process just
  gets heavier for as long as it runs, which is why this survived so long.
- **So the `purpose` tag is capped.** A store tags the first `MeterOptions.maxPurposeTagValues()`
  distinct purposes it sees (**default 1000**) and reports every purpose after that as `_other`, logging
  one WARN naming the purpose that tripped it. Below the cap nothing changes — that is exactly the case
  where a per-purpose breakdown is worth having — and above it the meters stay flat while the events are
  still counted, pooled under `_other`. Re-measured at 10.000 purposes: 15.015 meters instead of 150.000,
  and a 2.3 MB scrape instead of 23 MB.
- **Admission is first-come-first-served and permanent.** A purpose that got its own tag value keeps it
  for the life of the store, so a dashboard built on that series does not lose it when traffic widens.
  The flip side is that *which* purposes get through is arrival order and not stable across restarts —
  the accepted cost of a bound that needs no configuration. Past the cap a per-purpose breakdown was not
  going to be readable anyway.
- **Configuring it** — the two-argument factory calls and every existing caller keep working unchanged
  and get the default cap:
  ```java
  // purpose is an entity id here: never break down by it
  EventStoreFactory.get().eventStore(storage, registry, MeterOptions.withoutPurposeBreakdown());

  // a broad but genuinely bounded set of purposes
  EventStoreFactory.get().eventStore(storage, registry, MeterOptions.withMaxPurposeTagValues(5000));

  // same thing through the storage builders' buildStore()
  InMemoryEventStorage.newBuilder().meterOptions(MeterOptions.withoutPurposeBreakdown()).buildStore();
  ```
  `MeterOptions.withUnlimitedPurposeTagValues()` restores the old unbounded behaviour, which is only safe
  where purpose is low-cardinality by construction.
- **A Micrometer `MeterFilter` is not a substitute**, which is why this lives in the library. A filter
  runs at registration, and the store keys its `append.position` gauge state on the tags it *asked* for —
  so with `MeterFilter.denyNameStartsWith("sliceworkz")`, a registry holding **zero** meters still leaves
  the store growing by ~730 bytes per distinct purpose. The cap is applied where the tag value is chosen,
  so it bounds the meters, the `eventtype` cross product and that map in one place.
- **`context` is deliberately not capped.** It names a bounded context and comes from the code, not from
  the traffic. A store whose *context* is per-entity has the same problem with none of the protection —
  don't do that.
- **`sliceworkz.eventstore.append.deduplicated`** counts events an append submitted and storage silently
  swallowed as idempotency-key duplicates (`submitted − stored`, incremented in `EventStreamImpl.append`).
  It exists because the de-duplication is otherwise invisible in the meters: `append` counts calls,
  `append.event` counts submitted events, and one call can carry several events, so no subtraction
  recovers it. A clean run reads 0. Tagged like the other stream meters, and pinned per backend by
  `EventStreamIdempotencyTest.aSwallowedDuplicateIsCountedOnTheDeduplicatedMeter`.
- `MeterPurposeCardinalityTest` pins the cap, the pooling, the permanence of an admitted purpose, that
  the default applies to a store nobody configured, and that the cap holds exactly under concurrent first
  use of distinct purposes.

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
decrypt. Going through `EventStream` instead would rewrite legacy events into current ones and lose the
idempotency key, which the public `Event` record does not carry.

**Sealed values move as ciphertext, and the keys do not move with them.** A `Shreddable`'s envelope is
opaque JSON like any other payload, so an import copies it verbatim without keys, domain classes, or the
right to read the personal data. The consequence is the obvious one: a store imported into a deployment
whose key store does not hold those keys reads every protected value as erased. Migrate the keys
alongside the events, or accept the erasure.

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

### When a payload cannot be converted

The serde layer throws two named types, both unchecked, both in the **api** module
(`org.sliceworkz.eventstore.events`) so a caller never imports from `...impl.serde` to catch one. It used to
throw a bare `RuntimeException` in fourteen places, several with no message of their own and two with no
cause, which left "the event cannot be read" and "the database is down" distinguishable only by matching on
message text.

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
  (`FAIL_ON_UNKNOWN_PROPERTIES` is enabled deliberately), a renamed event class, or an `@Upcast` throwing on
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
  reached the projection — `EventDeserializationException.getReference()` is the one that names it.
- **Deserialization is lazy, so it surfaces from the caller's terminal operation**, not from `query()`.
  `getEventById` is eager and throws directly. `append` deserializes the events it just wrote in order to
  return them, so a payload that serializes but cannot be read back fails *there*, as a deserialization
  failure, with the event already stored.

**Misconfiguration is `IllegalArgumentException`, not a serde type.** A `@LegacyEvent` on a class registered
as current, a current class registered as legacy, and an upcaster that cannot be instantiated are all
properties of the `Class` handed to `getEventStream`; they fail at stream creation, before anything is read
or written, and there is no recovery but to fix the code. Two checks in the same method were already typed
that way (duplicate event name, non-sealed interface), so this is consistency rather than new surface. The
messages now name the upcaster *and* the event class and keep the reflective cause — a bare
`RuntimeException(NoSuchMethodException)` said neither.

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
an upcaster rather than as a parse failure, and the exception is wrapped exactly once — the typed serde used
to catch its own exception and re-wrap it, so the message naming the missing type only ever reached a user as
the cause of a second, vaguer one.

### Every exception here survives a process boundary, and names the event it failed on

A `Throwable` is `Serializable`, so a field on one that is not makes the whole exception unserializable —
and the symptom is uniquely unhelpful, because whatever was carrying it across a process boundary reports
a `NotSerializableException` **instead of** the failure. The real error is not logged, not wrapped, not
chained: it is replaced. That is how this survived unnoticed in `OptimisticLockingException`, the single
most commonly thrown type in the library, which held an `Optional` and an `EventFilter` and could not be
serialized at all. A forked JMH benchmark hitting a genuine DCB conflict died with a serialization
complaint and exit code 1, naming nothing about the conflict.

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
  is held as a nullable `EventReference` and wrapped in the getter. A serialized `Optional` field would
  have arrived as null and turned a conflict report into an NPE at the point of reading it.
- `ExceptionSerializationTest` in the api module pins all four down. It is a cheap test for a failure mode
  otherwise only ever discovered inside a harness nobody suspects.

### Erasing personal data: `Shreddable` values and crypto-shredding

**Personal data is wrapped, not annotated, and erasure destroys a key rather than rewriting an event.**
A record component declared `Shreddable<T>` is bound to a `DataSubject`, encrypted on append under the
key held for that subject, and stored as a sealed envelope inside the ordinary payload.
`EventStore.erase(subject, reason)` destroys the keys; nothing in the events table is written.

```java
record TransferMade(
        String transferId, Money amount,
        String fromCustomerId,                  // pseudonymous — survives erasure
        Shreddable<PartyDetails> from,          // Alice's data, Alice's key
        Shreddable<PartyDetails> to             // Bob's data, Bob's key
) implements PaymentEvent { }

DataSubject alice = DataSubject.of("customer", "alice-42");
payments.append(AppendCriteria.none(), Event.of(new TransferMade(..., Shreddable.of(details, alice), ...), tags));

eventStore.erase(alice, ErasureReason.of("GDPR art.17 request #4711"));

transfer.from();   // Shredded[customer/alice-42/default, k-7f2a91c4]
transfer.to();     // Present[PartyDetails[Bob Jansen, ...]]   -- unaffected
transfer.from().map(PartyDetails::name).orElse("[erased]");
```

- **The stored event never changes.** Its bytes stay identical forever, so an erasure needs no UPDATE,
  produces no new tuple to VACUUM, does not decorrelate the BRIN index on `event_position`, and reaches
  the ciphertext already sitting in WAL, on replicas and in every backup — all of which the previous
  `UPDATE ... SET event_erasable_data = null` did not. The log stays genuinely append-only.
- **A shredded value is never null**, which is what keeps erasure from creating poison events: a record
  whose compact constructor rejects nulls still builds after its data is gone. Nor can "erased" be
  confused with "never held any", and a `Shreddable<Integer>` reads as shredded rather than as `0`.
- **A `Shreddable` anywhere works** — nested records, `List` elements, `Map` values — because it is one
  Jackson serializer on one document. The old `@Erasable`/`@PartlyErasable` split reconciled two
  documents with a deep merge that replaced JSON arrays wholesale, so a collection of partly-personal
  elements silently lost its non-personal fields on every ordinary read, erasure or not.
- **Two subjects in one event each get their own key**, which no per-field annotation or per-event key
  can express. Keys are scoped to `(type, id, category)`, so "erase marketing, retain financial" is a
  category away.
- **The subject id must not itself be personal data.** It is stored in the clear in the envelope and
  survives erasure by construction — use a customer number, never an email address.
- **`KeyId` values are random and land on the event as `dek:` tags**, so "every event holding data under
  this key" is an ordinary tag query on the existing index. The tags stay after the key is destroyed, as
  a tombstone that says an erasure touched the event without saying what it took.
- **Erasure notifies nothing.** Read models, caches, search indexes and downstream systems keep their
  copies, and projections hold bookmarks so they never re-read. Re-projecting is the application's job.
  This is the one part of the old design's problems that shredding does not fix.
- **Without a codec configured, registering an event type that declares a `Shreddable` fails** at
  `getEventStream` — before anything is read or written — rather than storing personal data in the clear.

**Two seams, and a shipped default.** `AesGcmShreddingCodec` (AES-256-GCM, random 96-bit IV per value,
envelope metadata bound as AAD) over a `ShreddingKeyStore`:

```java
InMemoryEventStorage.newBuilder().shredding(new InMemoryShreddingKeyStore()).buildStore();
InMemoryFsEventStorage.newBuilder().directory(dir).shredding(new InMemoryFsShreddingKeyStore(dir)).buildStore();
PostgresEventStorage.newBuilder().shredding().buildStore();          // keys in <prefix>shredding_keys
PostgresEventStorage.newBuilder().shredding(myKmsCodec).buildStore(); // take over encryption entirely
```

- **`ShreddingKeyStore`** is the narrow seam: keep the shipped encryption, hold keys in Vault/KMS/an HSM.
- **`ShreddingCodec`** is the outer seam: take over encryption too, so key material never enters the JVM.
- **`unseal`/`resolve` returning empty means *erased*; anything else must throw `ShreddingException`.**
  This is the contract that matters most. Reported as empty, a key-store outage renders every protected
  value as erased — and projections, being at-least-once and bookmarked, write those gaps into read
  models permanently and never revisit them. `TypedEventPayloadSerializerDeserializer` rethrows a
  `ShreddingException` unwrapped (and unwraps one Jackson wrapped) precisely so that "retry later" does
  not arrive as `EventDeserializationException`, which means "never retry".
- **Nothing here needs post-quantum work.** The design uses no asymmetric cryptography, so Shor has no
  target; Grover leaves AES-256 at ~128 bits of effective security. Shredding is in fact a stronger
  position than encryption at rest generally is — the threat model is ciphertext recovered from a backup
  with the key destroyed, and no computation recovers a key that does not exist. Post-quantum only
  becomes a question inside an implementor's own codec that wraps data keys under a KEK with RSA-OAEP or
  ECIES, which is exactly the decision the seam leaves to them.
- **`alg` is recorded per sealed value**, so a store can hold several algorithms at once and change
  algorithm without rewriting history. That agility, not the choice of cipher, is the real defence
  against harvest-now-decrypt-later.

**Rotation only ever applies forward, and that is the design rather than an omission.** There is no way
to rotate a live key and re-seal what it protects, because re-sealing means rewriting stored events —
the one thing this design exists to avoid. Events staying byte-identical is what makes destroying a key
reach every copy of them (WAL, replicas, backups) with nothing to chase; a re-seal would have to reach
all of those too, and would not. So:

- **A subject whose keys are shredded gets a fresh key** for anything appended afterwards, and
  everything sealed under the old key stays sealed under it for as long as that ciphertext exists.
  Erasing twice therefore destroys two keys, not one — which is why `shred` matches on *every* key a
  subject has ever held, not just the active one.
- **What can change without rewriting anything is the algorithm**, recorded per sealed value. New
  appends can use a new one while old events keep decrypting under the one they were written with. For
  a long-lived log that agility is what rotation is usually reached for anyway.
- **A key-encrypting key can be rotated freely**, since that lives inside an implementor's own codec or
  key store and never touches the events. That is where a KMS's rotation story belongs.

**A key store can report on itself, without being able to decrypt anything.** `ShreddingAudit` —
`EventStore.shreddingAudit()`, or `ShreddingKeyStore.audit()` — answers which subjects hold protected
data and which erasures have happened, and is the *only* way to read that: the events record nothing
about an erasure, since they are never rewritten, so the key store is the whole account of it.

```java
ShreddingAudit audit = eventStore.shreddingAudit().orElseThrow();

audit.totals();                                          // subjects with live keys, live keys, shredded keys
audit.keys(KeyAuditQuery.forSubject("customer", "alice-42"));   // one person, every category
audit.keys(KeyAuditQuery.all().onlyShredded());                 // the erasure log: what, when, on whose authority
```

- **`KeyRecord` carries no key material, and no method here returns any.** That separation is the whole
  reason this is a second interface rather than another method on the key store: a dashboard credential
  granted it can see *that* data is protected and *when* it was erased, and never *what* it was. The
  Postgres implementation does not merely refrain from reading `key_material` — the column is absent
  from every statement it issues, so key bytes cannot reach a log or a heap dump through this path.
- **Bounded, with no cursor.** `KeyAuditQuery` always carries a limit (default 500). A store running for
  years holds one row per subject per category and never prunes the shredded ones, and unlike an event
  query there is nothing to resume from — so an accidental full enumeration is not offered.
- **Which *events* hold data under a key is not answered here** — the key store has never seen an event.
  That is an ordinary tag query, since each event carries its keys as `dek:` tags:
  `EventQuery.forEvents(EventTypesFilter.any(), Tags.of(KeyId.TAG_KEY, record.id().value()))`.
- **Optional, like leases.** A key store fronting a KMS that does not enumerate returns empty and
  callers do without; all three shipped key stores implement it.

**The Postgres key store caches resolved keys with a TTL, default one hour**
(`PostgresShreddingKeyStore.DEFAULT_CACHE_TTL`). Without a cache, replaying a stream costs a query per
protected value; with an unbounded one, an erasure performed by *another* instance would never be
noticed. An erasure performed by *this* instance drops its entries immediately, so the ttl bounds only
the cross-instance case — which makes it the outer edge of "erased" for a multi-instance deployment, and
a number worth stating in a data protection notice rather than discovering. `Duration.ZERO` disables the
cache and makes an erasure effective everywhere at once, at a query per protected value. A key that was
never seen is deliberately not cached as absent, so a shredded key still costs one query per read rather
than reporting stale data as readable.

**Raw mode does not decrypt**, deliberately: a wildcard stream, an export or an import sees the sealed
envelope as stored, which is what lets `EventStoreImporter` copy events with no keys and no domain
classes.

**Legacy `@Erasable` events are still readable.** The annotations, the two Jackson mappers, the view
introspector and the erasable *write* path are gone; `event_erasable_data` is still read, and a stored
event carrying one is still deep-merged exactly as before. Nothing writes a second document any more.
A component that used to be `@Erasable` and is now `Shreddable` cannot be read off old events — the
stored value is bare, so nothing can say whose data it is — and fails with a message saying to migrate
via `EventStoreImporter.transform` or to read the old shape through a `@LegacyEvent` upcaster.

`ShreddableEventDataTest` in the TCK pins all of this per backend, against *that backend's* key store:
the two-subject erasure, the collection case, the validating record that used to become a poison event,
category independence, idempotent erasure and a fresh key afterwards, the `dek:` tags, the audit view
(including, reflectively, that `KeyRecord` cannot carry key material), and — load-bearing — that an
unreachable key store throws instead of reporting the data as erased.

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
and covers **all five in-tree storages**: `inmem`, `inmem-fs`, `postgres:16`, `postgres:17` and
`postgres:18`. Adding a storage to the compliance run is one line in that file.

- Narrow a local run with `-Deventstore.testing.backends=inmem` to skip the containers entirely.
- Scenarios needing an optional part of the contract declare it —
  `@ForEachBackend(requires = Capability.IMPORT)` — and are *skipped*, not failed, on backends that do
  not support it. Capabilities: `IMPORT`, `TABLE_PREFIX`, `RESULT_LIMIT`, `RAW_STORAGE_ACCESS`.
- `@ForEachBackend(excludingBackends = "inmem-fs")` opts a backend out **for cost, not capability** —
  reported as skipped, so the gap stays visible. Not allowed inside the TCK: a compliance scenario
  that skips a backend proves nothing about it, so use `requires` there instead. **Nothing in the
  repository uses it**: its only user was `EventStorePerformanceTest`, which excluded `inmem-fs`
  because 10.000 appends against a file-backed store dominated CI time, and that test is gone — a
  benchmark pretending to be a test, printing an unread number on every build and asserting only what
  the TCK already asserts. Measurement lives in `sliceworkz-eventstore-benchmark` now.
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
tests that are repo-internal rather than part of the storage contract (`EventImportRoundTripTest`,
`TckBackendCoverageTest`). Postgres containers are managed by
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

## Benchmarking

Measurement lives in **`sliceworkz-eventstore-benchmark`** and never runs during a build: the module
is `src/main` only, so `mvn package` compiles and shades it and runs nothing. It is a
*capacity-characterisation* harness — JMH for operation-level numbers, a separate runner for
sustained load and live latency, both driving one shared `Workload` catalogue over corpora that are
provisioned once and reused. See that module's README for the full picture; what matters from here:

- **Every number is published with a manifest**, and the comparators refuse rather than guess. A
  percentage between two runs on different machines is not a statement about the store, and nothing
  about the two numbers says so. `report --baseline` diffs the same configuration over time and
  refuses when the corpus, targets or environment differ; `compare --a --b` diffs two configurations
  measured here and refuses when the *environment* differs.
- **Curated runs are committed** to `sliceworkz-eventstore-benchmark/results/<version>/<profile>/`,
  so a figure quoted here or in a README has something behind it that a pull request can review.
  Publishing refuses a Testcontainers run, a run whose store drifted over 2%, and — under any flag —
  a run that failed a correctness check.
- **The profiles are mostly pairs.** `stream-design-tagged` against `stream-design-per-entity`,
  `read-shapes` against `crowded-store` and `crowded-database`, the three `write-contention-*`
  collision modes. Each pair differs in one property, which is what makes the difference between them
  attributable.

**The figures quoted in this document are not yet reproduced by that suite.** They were measured ad
hoc while the behaviour they describe was being fixed, and the code that produced them is gone — which
is the reason the suite exists. Treat each as a recorded observation rather than a current
measurement, and where one matters, run the profile that would replace it:

| figure in this document | profile that would re-derive it |
|---|---|
| `~5%` for the append advisory lock | `write-contention-spread` against `write-contention-one-stream` |
| `~175µs / 139KB` vs `~36µs / 69KB` for a fresh vs shared serde | not covered — the suite always shares, since that is what the store does now |
| `15 meters / ~5.5 KB` per distinct purpose | `metrics-cost` (throughput only; the heap figure has no workload) |
| `1230ms → 460ms` for the statement-level append trigger | `ingest-saturation`, and only as a total — the trigger is not timed separately |

Two of those four have no profile behind them, deliberately: a per-meter heap figure and a per-trigger
time are properties of a snapshot rather than of a throughput, and inventing a workload to produce a
number that shape would produce a worse one. They stay as recorded observations, and are marked as
such rather than quietly dropped.

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

**An event class's simple name is stored data.** `EventType.of(Class)` is `Class.getSimpleName()`
(`EventType.java:83`) — there is no annotation, registry or builder hook to override it. That one string is
what goes into the `event_type` column, what `EventTypesFilter` matches on, and what keys the deserializer
(`TypedEventPayloadSerializerDeserializer.deserializers`, a `Map<String, EventDeserializer>`).

Using the *simple* name rather than the fully qualified one is deliberate and worth keeping in mind: moving a
class to another package, splitting a hierarchy across packages, or reorganising modules changes nothing on
disk. The package is not a wire commitment. **The class name is.**

**Renaming an event class breaks reads of its history.** Stored events are immutable, so every event already
written keeps the old name while the renamed class claims a new one. Reads then fail with:

```
No mapping found for event type 'CustomerRegistered'
```

Every IDE offers that rename as an ordinary refactor, and nothing at compile time objects. Three ways out,
in the order you would normally reach for them:

1. **Don't rename.** Pick the stored name deliberately when the event is created, and treat it afterwards
   the way you would a database column name.
2. **Keep the old name alive in code.** Move a class carrying the old name into a legacy hierarchy, annotate
   it `@LegacyEvent(upcast = ...)`, and upcast it to the renamed class — see the upcasting sections. This
   leaves storage untouched and is the only option that needs no access to the database, but it costs a
   permanent extra class plus an upcaster for what was only a rename.
3. **Rewrite the stored names.** `UPDATE <prefix>events SET event_type = 'New' WHERE event_type = 'Old';`
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
  `IllegalArgumentException: duplicate event name Created`
  (`TypedEventPayloadSerializerDeserializer.java:95`). The message names the string only, not the two
  classes, so grep for the name to find them.
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

**Practical rule: keep event class simple names unique across an entire storage, not just per stream.** Two
bounded contexts sharing a store cannot both have a `Created`, a `StatusChanged` or an `Updated`. Prefix
them (`OrderCreated`, `VacancyCreated`) or give each context its own storage. If two contexts must share a
name, keep every read scoped to one stream — no wildcard streams, no store-wide projections — and know that
nothing enforces that from here on.

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
- Requires the `btree_gin` extension (a standard contrib extension, available on the major managed Postgres offerings). Schema initialization installs it and schema validation requires `idx_events_stream_tags`, a combined stream+tags GIN index that serves DCB reads scoping by stream *and* filtering by tags in one index. The B-tree indexes are retained for ordered stream replay (GIN cannot serve `ORDER BY`)
- **Installing that extension needs `CREATE` on the *database*, which is not `CREATE` on the schema.**
  `btree_gin` is *trusted* (PG13+), so no superuser is involved — but a role granted `CREATE` on its
  schema and nothing on the database, the ordinary locked-down deployment, creates every table, index,
  function and trigger here and then cannot create the extension. Because the schema scripts are one
  transaction, that is not a missing index: the whole schema rolls back and the store does not start.
  See "Database privileges" in the postgres module's README for the table of what each
  `DatabaseInitMode` needs.
  - **The DBA-installs-it-once split is the recommended answer, and it costs the application role
    nothing afterwards.** `ensure-schema.sql` pre-checks `pg_extension` and skips the statement
    entirely when the extension is present, so an unprivileged role starts against it indefinitely —
    not even a `NOTICE`. (A bare `CREATE EXTENSION IF NOT EXISTS` would also have worked, since
    PostgreSQL's `IF NOT EXISTS` short-circuit precedes its privilege check, but it puts a `NOTICE` in
    every startup log and issues DDL a `VALIDATE`-style deployment has no business issuing.)
  - **The remaining failure names its remedies.** A `RAISE` in the `insufficient_privilege` handler
    reports the extension, the privilege distinction, and both ways out (`CREATE EXTENSION btree_gin`
    once, or `GRANT CREATE ON DATABASE`), instead of a bare `permission denied to create extension`
    wrapped in `Failed to execute database script`.
  - **The schema advisory lock does not cover this statement**, because it is keyed on the table prefix
    while an extension is database-scoped. Two stores with *different* prefixes starting together on a
    database without `btree_gin` therefore raced on `pg_extension_name_index` exactly as the tables
    used to race on `pg_type_typname_nsp_index` — the loser rolling its whole schema back. The block
    swallows `duplicate_object` and `unique_violation`: by the time either surfaces the winner has
    committed (a conflicting catalog insert blocks until it does), so the opclasses the index needs are
    visible to the loser's transaction, and it goes on to create its index.
  - **Where the extension lives is not a constraint.** `CREATE EXTENSION btree_gin SCHEMA extensions`,
    the convention on several managed offerings, serves the index with no `search_path` change and no
    `USAGE` grant on that schema — default operator class resolution is not `search_path`-filtered.
  - `PostgresBtreeGinPrivilegeTest` pins all four down per backend (16, 17, 18).
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
- **That tuple comparison is written as a SQL row constructor — `(event_tx, event_position) > (?, ?)` —
  and the lexicographic expansion it is equivalent to is several times slower.** SQL defines the two as
  the same predicate, so nothing about what a boundary *means* depends on the spelling. What depends on
  it is whether PostgreSQL can use it: a boundary is nearly always conjoined with `stream_context = ?
  AND stream_purpose = ?`, and `idx_events_stream_position` is `(stream_context, stream_purpose,
  event_tx, event_position)`, so with the leading two pinned by equality a row comparison over the
  trailing two becomes an index **start condition** — descend once to the cursor, walk the leaves in
  order — and a page costs what the page returns. A disjunction is not a start condition and lands in a
  `Filter`.
  - **Measured** (PG18, 100.000-event `TAGGED` corpus, cursor at the midpoint, one page of 500). The
    expansion still used the index, but only for the stream columns, and then discarded every event
    before the cursor to reach page one: `Rows Removed by Filter: 27500`, 1295 buffers, 3.344ms. The row
    comparison starts the scan at the cursor: nothing filtered, 27 buffers, 0.165ms. End to end that was
    **2.9× on a five-page cursor walk and 2.0× on a projector run**, with `query-stream-page` and
    `append-none` — the shapes carrying no boundary — unmoved, which is what says the difference is the
    boundary and not the machine.
  - **The cost of the expansion grows with how deep the cursor already sits**, not with the page size.
    That is the wrong shape for the way `Projector` pages, and it grows silently: every result is
    correct, so only a plan shows it.
  - **It also improves the plans the server *caches* for the DCB check**, which was not what it was for.
    A conditional append's `NOT EXISTS` carries the same boundary, and under the expansion the *generic*
    plan for the canonical `type + tag` check was a whole-table sequential scan (19.5ms, 100.008 rows
    discarded for a row that is not there); with the row comparison it is an index scan (0.23ms). The
    or-groups cliff is unaffected — that one is the tag disjunction, not the cursor.
  - **There is no setting for this, deliberately.** The two spellings are semantically identical, so no
    deployment can want the slower one, and a knob whose right value is the same everywhere is an
    unmade decision left in the code. `PostgresCursorBoundaryTest` guards it per backend (16, 17, 18):
    the boundary must reach the `Index Cond`, with no `BitmapOr` and no `Sort` above the scan. That test
    exists because the expansion is what anyone rewriting this would naturally reach for, and nothing
    else would notice.
  - **A related trap in the same statement**: the read path's `ORDER BY event_tx::xid8, event_position`
    looks redundantly cast and is not. The select list projects `event_tx::text`, which PostgreSQL names
    `event_tx`, and SQL resolves a bare name in `ORDER BY` to an **output** column before it looks at the
    table — so `ORDER BY event_tx` sorts by the *text* rendering of a transaction id (`'9'` after `'10'`)
    on an expression no index can supply, putting a `Sort` above every ordered read. Written as an
    expression the name cannot be captured. Do not "simplify" that cast away
- **A long-running *writing* transaction anywhere in the cluster freezes what this store can read.** The
  barrier above is `event_tx < pg_snapshot_xmin(pg_current_snapshot())`, and `pg_snapshot_xmin` is the
  oldest transaction id still running — a property of the whole PostgreSQL cluster, not of this store.
  Every event appended since the oldest open transaction took its id is invisible here until that
  transaction ends. **Nothing fails and nothing is logged**: reads just stop advancing, projections go
  quiet, bookmarks stop moving, `SELECT count(*)` in psql shows the events are there, and when the
  blocker finally ends everything appears at once. `PostgresVisibilityStallTest` demonstrates it end to
  end.
  - **Only transactions that have *written* count** — this is what makes the hazard narrow rather than
    severe, and it is worth being precise about. PostgreSQL assigns a transaction id lazily, at the
    first write, and only assigned ids enter a snapshot's xmin. A read-only transaction pins nothing,
    however long it runs, at **any** isolation level including SERIALIZABLE, and so does an `idle in
    transaction` connection that only ever read. So `pg_dump`, reporting queries, analytics reads and a
    replica feed are all harmless. What is not harmless: a batch job, an ETL run, a migration, or an
    `idle in transaction` connection that wrote before going idle. `SELECT … FOR UPDATE` and an explicit
    `pg_current_xact_id()` also assign an id without writing a row.
  - **The blocker does not have to touch the events table, or even this database.** Transaction ids are
    cluster-wide, so a writer in a *different database of the same cluster* pins this store's barrier
    just as effectively. Verified on PG17 and PG18. The operational rule is therefore "do not share a
    cluster with long-running write transactions", not "do not share a table".
  - **Read-your-own-writes does not hold** while a blocker is open: a caller can append successfully and
    not read the event back. Under DCB that surfaces as an optimistic-locking conflict a retry loop
    **cannot clear**. The append-side `NOT EXISTS` check deliberately carries no `xmin` filter, so it
    sees committed events the reader cannot; a decider re-reads its boundary, gets the same stale
    reference, appends, and conflicts again — for as long as the stall lasts. Not spurious, exactly:
    there really is a new relevant fact, it is just being withheld from the one party that needs it.
  - **The append advisory lock does not compound this.** A transaction that has only taken
    `pg_advisory_xact_lock` holds no transaction id, so neither the lock holder before its INSERT nor
    any appender blocked behind it pins the barrier. Only the INSERT itself does, for its own duration.
  - **Diagnosing it.** `backend_xid IS NOT NULL` is the whole predicate — filtering on `state <> 'idle'`
    or on `xact_start` age reports harmless read-only sessions as suspects:
    ```sql
    SELECT pg_snapshot_xmin(pg_current_snapshot());          -- the barrier
    SELECT pid, datname, usename, application_name, state,
           now() - xact_start AS held_for, backend_xid, query
    FROM   pg_stat_activity
    WHERE  backend_xid IS NOT NULL                            -- only these can stall the store
    ORDER  BY xact_start;                                     -- the oldest is the culprit
    ```
    Deliberately unfiltered by `datname`: the culprit may be in another database of the cluster. Run it
    as a superuser or as a member of `pg_read_all_stats` — `xact_start`, `query` and `state` come back
    NULL for other roles' sessions otherwise, and you get a row identifying the blocker with no way to
    tell how old it is or what it is doing. (`backend_xid` is readable regardless, so the `WHERE` clause
    still selects the right rows for any role.)
  - **The library does not meter this**, and there is nothing in the `sliceworkz.eventstore.*` meters
    that reveals it — they count and time the calls the store makes, all of which keep succeeding
    throughout a stall. Detection is therefore external, on the database, using the query above.
    Two notes for whoever wires that up:
    - **Watch `pg_snapshot_xmin` standing still *while* something holds a transaction id**, not either
      alone. xmin also stops moving on a completely idle database, so "xmin has not advanced" on its own
      fires on every quiet store; "a transaction holds an xid" on its own fires on every append in
      flight. It is the combination that means events are being withheld.
    - **`now() - xact_start` reads zero for an ordinary application role.** `pg_stat_activity` blanks
      the columns describing another role's session for anyone who is not a superuser or a member of
      `pg_read_all_stats`, and blanks them to NULL rather than refusing — so the natural "age of the
      oldest blocking transaction" check reports a confident all-clear right through a stall another
      role is causing. `backend_xid` is *not* blanked, so a count of blocking transactions does survive
      on ordinary privileges; the age does not. Either grant `pg_read_all_stats`, or time the staleness
      of `pg_snapshot_xmin` from outside instead of asking the server how old the blocker is.
    - Do **not** measure the effect by counting withheld events: `count(*) … WHERE event_tx >=
      pg_snapshot_xmin(…)` has no index to use (there is none on `event_tx`), so it is a sequential scan
      of the whole events table every time it is sampled.
  - **The store is a mild instance of its own hazard**, which is why this is normal rather than exotic:
    an append in flight holds a transaction id, so a second append that starts later and commits first
    cannot read its own event back until the first one finishes. That window is one INSERT long and
    self-clearing, and `ConcurrentAppendVisibilityTest` is written to tolerate it (it re-polls). The
    same mechanism, with a blocker that lasts minutes instead of milliseconds, is the hazard above.
  - **A consequence for anything that would hold the store's *own* connections in a transaction.**
    Reads currently run on autocommit, which is what keeps the store out of its own blast radius. Moving
    `query()` to a cursor-based, autocommit-off streaming read would make a long-running read a
    long-running transaction — and while a purely read-only one still assigns no transaction id and so
    still pins nothing, that safety rests entirely on the streaming connection never writing. Any design
    that opens a transaction and reads for minutes needs to hold that property deliberately, or the
    store becomes its own worst blocker.
  - **Do not "fix" this by bounding the barrier** — falling back to reading everything committed once
    xmin has been pinned for a while trades a visible, self-healing stall for silent event loss in
    exactly the scenario the barrier exists to prevent. `ConcurrentAppendVisibilityTest` is what fails
    when you try.
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
- **The DCB check is a re-used prepared statement, and PostgreSQL's choice of plan for it can fall off a
  cliff.** The server holds two plans for such a statement — a *custom* one re-planned from the actual
  parameter values, and a *generic* one planned once against default selectivity — and from the tenth
  execution it adopts the generic plan if its **estimated** cost looks no worse. A DCB check is the shape
  that misleads that comparison: its expected result is *no rows*, while a `NOT EXISTS` is priced by how
  soon a row is expected to turn up. Every OR-ed fact a decision rests on makes the generic plan expect a
  match sooner, so its estimate *falls* while the custom plan's — built from real tag statistics — rises.
  - **Observed on a 100.000-event `TAGGED` corpus** (`auto_explain` capture, PG18): the estimates cross
    between two and three OR-ed facts. At two the server keeps a `BitmapOr` over `idx_events_stream_tags`
    and the append costs ~1.5ms; at three it adopts a generic plan that sequential-scans the whole events
    table — `Rows Removed by Filter: 100044`, for a row that is not there — and the append costs ~17ms,
    with four, five and ten costing the same. Nothing is logged and no meter moves.
  - **Three shapes go the other way and keep their custom plan**, for the same reason: `append-type-and-tag`
    (the canonical check), `append-multi-tag` and `decide-then-append` all measure around 1ms/op against
    generic plans that take 20–28ms. The canonical check is therefore one bad estimate away from the
    same fate, and nothing in the store would say if it flipped.
  - **The row-comparison cursor boundary took most of that risk away, after the fact.** Those 20–28ms
    generic plans were whole-table sequential scans measured while the boundary was still written as a
    disjunction. With the row comparison the generic plan for `append-type-and-tag` is an index scan at
    **0.23ms** rather than a seq scan at 19.5ms, and `decide-then-append` likewise — so for the
    single-item shapes a flip to the generic plan is no longer a cliff at all. What is unchanged is the
    or-groups curve below: that cliff is the tag disjunction, and the boundary was never its cause.
  - **`conditionalAppendPlanning(PER_APPEND)` takes the choice away**, planning every conditional append
    from its own values. Implemented as `setPrepareThreshold(0)` on that one statement through pgjdbc —
    no extra round trip, no effect on any other statement sharing the connection, and unlike
    `SET plan_cache_mode` it cannot leak. It is best effort against a `DataSource` that will not unwrap
    to pgjdbc, which is a single WARN rather than a failed append.
  - **The default stays `SERVER_DEFAULT`, and that is now a measurement rather than a reservation.**
    `sliceworkz-eventstore-benchmark`'s `dcb-plan-cache` profile runs both modes over one corpus in one
    run, with `append-none` as the control that must not move; `dcb-plan-cache-small` is the same
    profile against a 5.000-event corpus, so the pair isolates the one property that decides whether
    planning per append pays for itself — the size of the table being planned against. What the two say:
    - **`PER_APPEND` only ever buys a plan the server got wrong**, and where it does not change the plan
      it costs about a quarter to a third of a conditional append's throughput (measured on the small
      corpus: 0.70–0.74× on the or-groups shapes, 0.88–0.95× on `decide-then-append`, the canonical
      `type-and-tag` check and the empty-boundary case). That is the price of parse+analyze per
      execution, and it is charged on every conditional append whether or not there is anything to fix.
    - **Where the generic plan is bad, it is very bad**, which is why the setting exists. A types-only
      filter has a catastrophic generic plan at any store size (small corpus: 1.49× in `PER_APPEND`'s
      favour, against a generic plan measuring 0.51 ops/ms to the custom plan's 12.60), and three or
      more OR-ed facts flip to a whole-table scan on the 100.000-event corpus for a ~10× loss.
    - **At the widest shape it cannot help at all.** `append-or-groups-10` on 100.000 events reports the
      *same* sequential scan under `force_custom_plan` — real tag statistics do not rescue a ten-way
      `BitmapOr` there — so `PER_APPEND` pays the planning and gets the plan it was trying to avoid.
    - **Small stores never need it.** At 5.000 events the server keeps the custom plan on every shape
      unaided, and the captured plan is identical at one, two, three, five and ten facts (a `BitmapOr`
      over `idx_events_stream_position`, cost 12.62 → 12.65). The 42% decline across that width is
      statement handling — 17 bound parameters against 62 — and not data access, so nothing about it is
      a planning problem.

    So `PER_APPEND` is a remedy for a store observed to have flipped, not a setting to turn on
    generally: reach for it when appends on one shape jump by an order of magnitude with no change in
    the data, and leave it alone otherwise. These are Testcontainers runs on a developer machine, which
    is enough to establish the direction and the rough magnitude and is deliberately not published under
    `results/` — the publisher refuses a Testcontainers run for exactly this reason.

    `PostgresConditionalAppendPlanningTest` pins the mechanism per backend — that the statement does reach
    the plan cache by default and does not under `PER_APPEND` — and that neither mode changes what a
    consistency boundary means.
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
  per stream discarded by `OptimizingAppendListenerDecorator` after being built as JSON, written to the
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
- `stream_purpose` defaults to `'default'` in the DDL, matching `EventStreamId.DEFAULT_PURPOSE` — a public
  constant, so an interop layer can bind the same value the library does rather than copy the literal out
  of this file. On a database created before this alignment (default was `''`), operators doing raw SQL inserts should run `ALTER TABLE <prefix>events ALTER COLUMN stream_purpose SET DEFAULT 'default';` — no data migration is needed since all events written through the library bind the purpose explicitly
- **Idempotency keys are scoped per event stream (context + purpose), not per storage/table.** Uniqueness is enforced by the partial unique index `idx_events_stream_idempotency` on `(stream_context, stream_purpose, idempotency_key) WHERE idempotency_key IS NOT NULL` (schema validation requires it), so the same key used on two unrelated streams does not collide and dedup behaviour does not depend on how storage instances / prefixes are wired at runtime. The `idempotency_key` is persisted and surfaced on `StoredEvent` when reading (it is not exposed on the public `Event` record). A duplicate append is still silently ignored (returns an empty result). On a database created before this change (when `idempotency_key` had a table-wide `UNIQUE`), migrate with: `ALTER TABLE <prefix>events DROP CONSTRAINT <prefix>events_idempotency_key_key; CREATE UNIQUE INDEX <prefix>idx_events_stream_idempotency ON <prefix>events (stream_context, stream_purpose, idempotency_key) WHERE idempotency_key IS NOT NULL;` — no data migration is needed
  - **The duplicate is recognised by the index the server names, never by the message text.** Both the
    append and the import path go through `isIdempotencyKeyViolation`, which pairs SQLSTATE 23505 with
    `PSQLException.getServerErrorMessage().getConstraint()` — populated with the *index* name for a bare
    `CREATE UNIQUE INDEX`, and compared case-insensitively against `<prefix>idx_events_stream_idempotency`
    because PostgreSQL folds the unquoted identifier in the DDL. Two things make the message text unusable
    here, and only one of them is obvious. The subtle one is the table prefix: it is caller-supplied and
    validated only as `[a-zA-Z0-9_]+_`, so a prefix containing the word "idempotency" puts that word into
    `<prefix>events_pkey` and `<prefix>events_event_id_key` as well, and a substring match then swallows
    *every* unique violation the table can raise — reporting a successful de-duplication for an append that
    wrote nothing. (The other, message translation under a non-English `lc_messages`, turns out **not** to
    bite: an index name is an identifier, so it appears verbatim in the French and Japanese messages too.
    Verified, not assumed.) `EventStreamIdempotencyTest` builds its store with exactly such a prefix and
    pins a generated `event_id` with a `BEFORE INSERT` trigger, so the misrouting fails the build rather
    than passing silently
  - **Identifier length is a coupling to keep in mind.** PostgreSQL truncates identifiers at 63 bytes, which
    would break an exact-name comparison; `MAX_PREFIX_LENGTH` (32) keeps the longest generated index name at
    61 characters, so it cannot happen. Raising that cap needs this comparison revisited — and, more
    urgently, would let two of the schema's index names truncate to the same string, at which point
    `CREATE UNIQUE INDEX IF NOT EXISTS` silently does nothing and idempotency uniqueness stops existing
- **Importing needed no DDL change**: `event_id` is already a plain `UUID NOT NULL UNIQUE` and `event_timestamp` is nullable with a `CURRENT_TIMESTAMP` default, so both can be supplied explicitly. `importEvents` binds them per row, chunks statements at 5000 rows (9 params/row against the 65535-parameter wire ceiling) inside a single transaction, and matches `RETURNING` rows **by event_id** rather than by row order — with `ON CONFLICT` the returned rows are a subset of the input, so position in the result set means nothing. Conflicts are routed by constraint name from `PSQLException.getServerErrorMessage().getConstraint()`, not by matching message text
- **Imported event ids must be UUIDs** (the `::uuid` cast); `importEvents` validates this up front to give a clear error rather than an opaque cast failure
- **`timestamptz` keeps microseconds and rounds anything finer**, so a nanosecond-precision timestamp (as an in-memory store produces) lands up to half a microsecond away from where it started. This is the only lossy part of an inmem → Postgres → inmem round trip; `EventImportRoundTripTest` pins it down
- **A `db.properties` *value* never reaches an error message or a log line — only the key does.** Every
  non-`datasource.` key goes through `HikariConfigurationUtil.setHikariProperty`, and
  `db.<name>.password` is one of them, so a value interpolated into a message is a database password in
  every log, stack trace and error reporter downstream. The failure message names the property and the
  type the setter expected (`Error setting property 'maximumPoolSize' (expected int)`) and stops there;
  the detail is left to the cause, which for the realistic throwers — a numeric property fed a
  non-numeric value, a Hikari setter rejecting one — concerns a property that is never a secret. An
  empty property name (a stray `db.pooled.=x` line) is rejected with that explanation rather than
  reaching `charAt(0)`. `HikariConfigurationUtilTest` asserts the value is absent from the whole
  exception chain, not just its top frame
