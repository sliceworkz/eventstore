# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java-based EventStore library implementing the Dynamic Consistency Boundary (DCB) specification. The codebase is organized as a Maven multi-module project providing event storage abstractions with multiple backend implementations.

**Core Modules:**
- `sliceworkz-eventstore-api`: Core API interfaces and contracts, plus the SPI (`EventStorage`) a backend implements
- `sliceworkz-eventstore-impl`: Implementation of the EventStore (streams, serde, upcasting, shredding, meters)
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

- **`sliceworkz-eventstore-api` carries Micrometer and SLF4J, and no Jackson proper.** `MeterRegistry`
  is in the signature of `EventStoreFactory.eventStore` and `Metrics.globalRegistry` is the default of
  every builder, so Micrometer is a compile dependency and deliberately never `<optional>`: marking it
  optional would not make a metrics-free consumer possible, only move the failure from dependency
  resolution to a `NoClassDefFoundError` on the first store built. The alternative — an internal meter
  facade with a no-op binding, Micrometer loaded only when present — is what a metrics-free api would
  take, and is not on offer. `Projector` logs through SLF4J. The one Jackson artifact the api names is
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
- **`Metrics.globalRegistry` is the default wherever a registry is not given** — the one-argument
  `EventStoreFactory.eventStore(storage)` and every storage builder's `buildStore()`. Micrometer's
  global registry is a composite with no children until something adds one, so meters registered
  there cost a map entry and record nothing; an application that binds its real registry to it
  gets the store's meters in its own series without configuring anything. The testing module never registers there: `AbstractEventStoreTest` and
  `EventStoreFixture` give every store a `SimpleMeterRegistry` of its own, so a fixture in an
  application's test suite leaves nothing behind in the application's registry.

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
- Purpose is optional: `EventStreamId.forContext("x")` defaults purpose to `"default"`, so a context that needs only one stream can ignore purpose entirely. Set a purpose only to distinguish multiple streams within a context (e.g. per-instance, or separating event kinds). Whether to make the purpose an *entity id* — a stream per SKU rather than a stream per context — is the one layout decision with measured consequences on both reads and write contention; see the Benchmarking digest below, and "Choosing a stream design" in `sliceworkz-eventstore-benchmark/CLAUDE.md` for the figures
- Supports both reading (via `query()`) and writing (via `append()`)
- Type-safe through generic parameter `<DOMAIN_EVENT_TYPE>`, which the single-class overloads of
  `getEventStream` fix from the root class: `getEventStream(id, CustomerEvent.class)` is an
  `EventStream<CustomerEvent>`, and assigning it to an `EventStream<OrderEvent>` — or widening it to an
  `EventStream<Object>`, which would let an append of a foreign event type compile — is a compile error
  rather than a runtime append failure. The historical root class is not constrained, since legacy events
  upcast into current ones. The `Set<Class<?>>` overloads carry no such constraint and are the way to open
  a stream typed wider than its roots. `EventStoreTypeParameterTest` in the api module pins it by running
  javac against probe snippets
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
- **`head()` is the reference of the newest stored event of the stream, answered without reading it.**
  It exists to pin a consistency boundary *before* a decision is read: take the head, bound every read
  with `until(head)` or `Projector.runUntil(head)`, and hand the same reference to `AppendCriteria`.
  Several reads then share one boundary, so nothing lands between them unseen, and the lock check gets
  a cursor at the stream head instead of at the boundary's own newest event — which on Postgres turns
  a walk over every stream event since that event into a walk over the few appended during the
  decision (see the stale-cursor note under PostgreSQL). Three properties carry it, pinned per backend
  by `HeadTest`:
  - **It is what a query would see**, not what has been committed: on Postgres it sits behind the same
    `pg_snapshot_xmin` barrier as every read, so during a visibility stall it lags exactly as reads do.
    A head that ran ahead of the reads it bounds would let the reads and the check disagree
    (`PostgresVisibilityStallTest.testTheHeadSitsBehindTheSameBarrierAsReads`)
  - **It never deserializes, upcasts or decrypts.** The stream's mappings are irrelevant, so a head this
    stream cannot map, one that upcasts into nothing, or one holding a `Shreddable` under a key store
    that is down cannot make it fail or lie. The natural alternative — the typed
    `query(matchAll().backwards().limit(1))` idiom — loses on all three: a poison event at the head
    fails every command, an upcast-to-nothing head reads as an empty stream, and a sealed value costs a
    key-store round trip. That is why it is a method on `EventSource` and an SPI method on
    `EventStorage`, whose `default` is that query for a backend written before it; Postgres reads the
    three reference columns off `idx_events_stream_position` — off `idx_events_context_tx_position`
    for a context, off `idx_events_tx_position` for a wildcard stream — and no payload
    (`PostgresHeadStatementTest`, `PostgresGlobalOrderIndexTest`)
  - **It names a stored event, whole**: `index` 0, and a boundary at it includes every event the stored
    event upcasts into — see the `until` note under EventFilter
  - The event at the head need not match the boundary's filter: the reference is a cursor for the check,
    and only matching events after it count. An absent head is an empty stream and must stay an absent
    reference — "I decided on an empty boundary" — never be substituted with some other reference.
    Counted on `sliceworkz.eventstore.head`, not on the query meters. `EventStoreImporter` bounds its
    reads at the source head through the same method

**Event:**
- Record type containing: `stream`, `type`, `storedType`, `reference`, `data`, `tags`, `timestamp`
- `type` is the type of `data`; `storedType` is the name under which the event sits in storage. They differ only
  for an upcast event, where `storedType` names the legacy type it was read from
- Data is the actual domain event (typically a sealed interface with record implementations)
- Tags enable dynamic querying and consistency boundaries
- Created via `Event.of(data, tags)` for ephemeral events or full constructor for persisted events
- **`timestamp` is an `Instant`**: the moment the store persisted the event, on the storage's clock — the
  JVM's in memory, the server's on Postgres, where the column is a `timestamptz`. It is the same kind of
  value as `Bookmark.updatedAt` and the clocks on `Lease`, so the three compare without conversion, and it
  is what `StoredEvent`, `EventToStore.positionAt` and `EventToImport` carry too. An instant has no zone:
  the day or wall-clock time it falls on is the reader's rendering, made with the zone the reader means
  (`event.timestamp().atZone(zone)`). The alternative — a `LocalDateTime` documented as "always UTC" —
  loses because the type does not carry the convention: nothing stops a reader comparing it with a
  wall-clock reading in the JVM's zone, and every correct use starts by re-attaching the offset the type
  dropped. The file codec writes it as an ISO-8601 instant at UTC and reads a value carrying no offset as
  UTC, so an events directory holds one meaning of the field whichever shape a file carries.
  `EventTimestampTest` pins per backend that the stamp is the instant of the append

**EphemeralEvent:**
- Lightweight event representation before persistence (no stream, reference, or timestamp)
- Converted to full `Event` upon appending to a stream
- Created via `Event.of(data, tags)`

**EventType:**
- The stored name of an event: `EventType.of(Class)` is `Class.getSimpleName()`, unless the class is
  annotated `@EventName("...")`, in which case it is the annotation's value. That method is the one place
  a class becomes a stored name, so the annotation is honoured on append, in a stream's type mappings, in
  `EventTypesFilter.of(Class...)` and on a `@LegacyEvent`
- **The plain class name is the intended setup; `@EventName` is for the class whose stored name cannot
  be its own name** (a renamed class, a simple name another context already stores). Annotating every
  event up front is not a best practice and buys nothing: a string literal is as permanent a commitment
  as a class name, so it does not avoid the rename problem, only adds a second name to keep in step
  with the first
- Deliberately the *simple* name, not the fully qualified one, so moving a class between packages —
  the refactor people actually do — costs nothing
- **The stored name is therefore wire format**, and it is global to a storage rather than scoped to a
  stream. See "Event type names are wire format" under Naming Conventions before renaming an event class
  or introducing a second class with an existing simple name — `@EventName` is the answer to both, and
  `EventNameTest` in the TCK pins it per backend

**Tags:**
- Key-value pairs attached to events for dynamic retrieval
- Enable querying events across different event types
- Core to the Dynamic Consistency Boundary pattern
- Created via `Tags.of("key", "value")`, `Tags.of("k1", "v1", "k2", "v2", ...)` (alternating keys and
  values; an odd count is rejected rather than read as a trailing flag) or `Tags.of(Tag.of("key", "value"))`
- **`Tags` is a set, and its factories behave like one.** `Tags.of(Tag...)` eliminates a repeated tag
  rather than rejecting it: tags gathered from several sources (a domain tag list plus what a
  decorator adds) legitimately overlap, and building with `Set.of` would turn that overlap into an
  append failure. A `null` element is an `IllegalArgumentException`. Several tags under one *key* are
  an ordinary shape — a transfer tagged `customer:alice` and `customer:bob` — so `tags.tag(key)` answers
  only a key holding a single tag and throws `IllegalStateException` when more than one carries it,
  rather than returning whichever hash order put first; `tags.tags(key)` is the read for a key that may
  hold several. `TagsTest` pins both
- **`Tag.toString()` is the wire format, not a debugging rendering.** A tag is flattened to
  `"key:value"` to be persisted and to be matched: the Postgres backend stores `Tags.toStrings()` in a
  `text[]` column and answers a tag query with `event_tags @> ARRAY[...]` built from the *same*
  rendering, then hands tags back through `Tags.parse(String[])`. The string is unescaped, and
  `Tag.parse` splits on the **first** `':'`, strips both halves and maps an empty half to `null` —
  so `toString`/`parse` is only a round trip for tags whose key has no colon and whose halves are
  neither empty nor padded. The in-memory backends flatten nothing and match on the `Tag` record,
  so they cannot fail this way — a tag that does not survive the rendering behaves in a test against
  the in-memory store and diverges on Postgres, which is why the rejection below lives in `Tag` itself
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
  constructible tag. Two distinct tags on one event cannot be stored as one array element,
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
- Created via `EventFilter.forEvents(eventTypesFilter, tags)`, or `EventFilter.forTags(tags)` for events of
  any type carrying the tags
- Used by `AppendCriteria` for optimistic locking (where direction/limit are irrelevant)
- **A sealed interface in a type filter stands for every event type under it.** An event is stored
  under the simple name of its record, never under an interface it implements, so
  `EventTypesFilter.of(Class...)` resolves a sealed interface into the event types it permits,
  recursively, when the filter is built: the root of a hierarchy names all of it
  (`EventTypesFilter.of(CustomerEvent.class)`), a nested interface names its own branch, and the
  filter then holds those names only. Resolved at construction rather than where the filter is
  matched, because a filter is matched in several places — the storage query, the store's re-check
  of the events it upcasts, a `Projector`'s check of the events it is handed, the lock check of an
  append — and only some of them have the stream's registrations at hand; resolving once keeps them
  in agreement. The alternative — resolving an interface by name inside the typed serde — loses
  because the filter then holds a name no stored event carries, honoured by whichever path consults
  the serde and by none of the others; a lock check built on it admits every append, silently. A
  non-sealed interface is refused with `IllegalArgumentException`, as `getEventStream` refuses it as
  a root. A filter built from `EventType`s is literal: `EventType.of(SomeInterface.class)` names a
  stored type no record has. `EventTypesFilterTest` pins the resolution,
  `EventTypesFilterHierarchyTest` in the TCK pins the four paths per backend, legacy upcasts included
- **`until` is an inclusive upper bound over *stored* events, in the `(tx, position)` order, and is
  direction-independent**: `.backwards()` returns the same events as forward, newest first. It is part of
  the filter, so it also bounds a consistency boundary — an event past it is not a new relevant fact and
  raises no `OptimisticLockingException`. Backends must compare it as the tuple, exactly as they compare
  the cursor; comparing positions alone drops events whose transaction and position were assigned in
  different orders. `EventQueryUntilBoundaryTest` pins all of this down per backend
- **A boundary names a stored event, whole — never a fragment of one.** The `index` on a reference
  distinguishes the events one stored event upcasts into, and a storage never sees it: it compares
  stored events, whose index is always 0. So the read side compares the same way
  (`EventReference.storedEventHappenedAfter`, used by `EventFilter.matches` and `Projector.runUntil`),
  and every event the stored event at the boundary upcasts into is at or before it, whatever its index.
  This is what lets a reference obtained without upcasting — `head()`, a bookmark read back from
  Postgres, which stores no index — bound a typed read without cutting the newest stored event in
  pieces. The alternative — comparing the full `(tx, position, index)` on the read side — loses
  because such a reference would then include the first upcast event of the newest stored event and
  drop the rest, and a decision made on that fragment is admitted by the lock check, which compares
  stored events and sees nothing after the boundary. `EventFilterTest` pins the comparison,
  `HeadTest.theHeadNamesTheWholeStoredEventWhenItUpcastsIntoSeveral` the read and the projector

**EventQuery:**
- Wraps an `EventFilter` together with traversal semantics (direction and limit)
- Use `EventQuery.filter()` to extract the pure matching criteria
- Can match all (`EventQuery.matchAll()`), none (`EventQuery.matchNone()`), or specific criteria
- Supports backward direction (`.backwards()`) and result limits (`.limit(n)`)
- Created via `EventQuery.forEvents(eventTypesFilter, tags)`, or `EventQuery.forTags(tags)` for events of
  any type carrying the tags — the usual shape of a consistency boundary
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
  compact constructor normalises a null to `Optional.empty()`, so a backend can call `.isPresent()` on it
  without a null guard of its own
- **"No criteria" and "an empty expected reference" are different things, and only `isNone()` distinguishes
  them.** `isNone()` is derived from the filter being `matchNone`, independently of the reference. An empty
  reference under a *real* filter means "I decided on an empty stream", which is still a consistency boundary:
  any matching event in the stream is a new relevant fact and must raise `OptimisticLockingException` (see
  `OptimisticLockingTest.testOptimisticLockingSucceedsWhenExpectingEmptyStreamAndStreamIsNotEmpty`). A backend
  skipping the check when the reference is absent is a silent loss of optimistic locking; `AppendCriteriaTest`
  in the TCK pins both halves down
- **A boundary over a current type counts the legacy events that upcast into it**, exactly as a query for
  that type returns them. Storage checks stored type names, so `EventStreamImpl.append` traces the
  criteria's types back to their legacy names before handing it over, the same trace-back the query path
  applies (`determineLegacyTypes`). Without it the two paths disagree on one filter: a decision read
  through the query sees the legacy event and the lock check admits the append over it. The exception
  names the boundary the caller decided on, never the stored names it was checked with — a caller
  comparing `getFilter()` to its own criteria, as the fixture's `OptimisticLockingFailure` does, finds no
  legacy names it never wrote; storage's exception is the cause. On a stream without legacy types the
  trace-back changes nothing and storage's exception passes through untouched.
  `UpcastTest.aBoundaryOverACurrentTypeCountsTheLegacyEventsUpcastIntoIt` pins it per backend

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
- **The bookmark is placed after every batch, not once per run.** The re-projection window after a crash
  is therefore one batch (500 events by default), not the whole catch-up. Bookmarking once at the end of
  `run()` would be cheaper — one upsert instead of one per batch — but a catch-up that commits 2000 batches
  and then dies would replay all of them; one upsert per batch during a replay is what the bookmark is for
- **A batch that fails takes the projector's cursor back with it.** `afterBatch` is called inside the
  try, not from a `finally`: a commit that throws rolls `lastEventReference` back to where the batch
  started and is reported as a `ProjectorException` like any other projection failure. Both halves
  matter. A cursor left advanced past a rolled-back batch skips its events for good, and a later
  successful batch bookmarks over the hole. An untyped throwable misses the `catch ( ProjectorException )`
  that stops a processor and lands in a catch-all that loops, so a projection whose commits keep
  failing would re-query and re-project at full thread speed
- **A batch is ended exactly once.** `cancelBatch` is not called after an `afterBatch` that threw — that
  projection has already released what it held — and a `cancelBatch` that throws is logged and attached
  to the original failure as a **suppressed** exception rather than replacing it, so a poison event whose
  rollback also fails is still reported as a poison event, not as a rollback problem in the wrong store
- **Where a re-projection would duplicate rather than merely repeat, the projection should hold its own
  position.** `afterBatch` is handed the batch's last `EventReference` for exactly this: write it into the
  same store, in the same transaction, and resume from it. Two stores that cannot share a transaction
  cannot be made exactly-once any other way. `sliceworkz-eventmodeling`'s `SqlReadModelProjector` is the
  worked example
- `ProjectorBatchDurabilityTest` in the TCK pins all of it per backend: the bookmark visible from inside
  the *second* batch already names the first, a failed commit is a `ProjectorException` and its events
  come round again, and a failing rollback keeps the cause

**Projection initQuery (Savepoint Pattern):**
- Projections can define an optional `initQuery()` (default returns `EventQuery.matchNone()`; a `null` is tolerated and means the same) that runs before the main `eventQuery()`
- Enables the savepoint pattern: a backward query with limit 1 finds the most recent savepoint event that summarizes prior state
- The `Projector` executes `initQuery()` first, passes results to `when()`, then uses the last event's reference as the cursor for `eventQuery()`
- Savepoint events are pure domain events — no special framework support needed
- When no savepoint exists, the main `eventQuery()` replays from the beginning (graceful degradation)
- When bookmarking is enabled on the `Projector`, `initQuery()` is ignored (a warning is logged at build time)
- The `initQuery()` and `eventQuery()` should query different event types to avoid double-processing and to allow recovery from buggy savepoints

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
  forever as a quiet channel with the gauge reading 1. Every monitoring connection runs under a 5s
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
- **Observability.** `sliceworkz.eventstore.notifications.up` is a gauge, 1/0, tagged `storage` and
  `channel` (`event_appended` / `bookmark_placed`). It is registered by the constructor, so the series
  exists reading 0 from the moment the storage does — a gauge that only appears once notifications work
  is no use for alerting on notifications not working. It also drops back to 0 when a *running* store
  loses its monitoring connection, which is the same silence as never having had one.
  `PostgresEventStorageImpl.isNotificationsAvailable()` is the same state for a health endpoint, at the
  cost of a downcast from `EventStorage`.
- **What arrives on the channel cannot take a monitor down, and a monitor that does go down cannot
  leave the gauge reading 1.** A `NOTIFY` channel is a database-wide name: any session in the database
  can publish on it, and a trigger left behind by another release may not agree with this one on the
  payload. A payload that does not parse, or parses into something `EventReference` refuses (a null
  id, a position of 0), is logged at ERROR and dropped, and the monitor reads on — the catch is on
  `RuntimeException`, not on the parser's own exception type, because the conversion into a reference
  throws `IllegalArgumentException`. Anything a listener throws, an `Error` included, is contained the
  same way. And the `listening` flag behind the gauge is cleared in a `finally`, so no exit from the
  monitor's loop, an uncaught one included, leaves `notifications.up` claiming a channel that nobody is
  listening on. The alternative — catching only the parser's exception — loses because a single
  malformed payload then ends the monitor's virtual thread silently, and nothing wakes a subscriber
  again for the life of the storage while the gauge and `isNotificationsAvailable()` both say
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
  started, `start()` checks that no stream head carries a transaction id the cluster has not assigned yet —
  the signature of a `pg_dump`/`pg_restore` into a fresh cluster, where the restored history sits
  above the visibility barrier and reads as absent while every new append sorts before it. Fatal in
  the same way, with the storage closed and the remedies named. See "Backup and restore" in the
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
  never closed is retained for the lifetime of the storage — deliberately a leak you can find. The
  alternative — holding listeners weakly — loses because a subscription then dies at an unpredictable
  GC with no error and no log.
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
- **`sliceworkz.eventstore.append.position`** is a gauge of the highest position appended, tagged like
  the other stream meters (`context`, `purpose`, `typed`, `storage`), and reads `NaN` until something is
  appended. Its state is held **per tag set on the store**, not per stream, and registered once — because
  a gauge cannot be re-registered (Micrometer keeps the first registration and ignores the rest) and
  because Micrometer holds gauge state *weakly*. Held per stream instead, only the first stream ever
  created for a tag set would be wired to the series, and the series would go permanently `NaN` as soon
  as that stream was collected — which, in the per-operation usage recommended above, is almost
  immediately, with nothing failing to say so. `AppendPositionGaugeTest` covers it. The tag set it is
  held per is bounded — see the metrics section below for why that matters and what it costs when it is
  not.

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

- **`placeBookmark` rejects a reference this storage never stored** — `EventStorageException`, nothing
  written, and a previously placed bookmark for that reader stays. The realistic mistake it catches is a
  reference from a *different* store or prefix in a miswired multi-store setup, which would otherwise
  poison the reader's cursor silently. Postgres enforces it with the `fk_bookmarks_event_id` foreign key
  (recognised by the constraint name the server reports, like the idempotency index — never by message
  text); the in-memory store checks its log under the same monitor that guards `append`. The contract is
  documented on `EventStorage.bookmark`, and `BookmarksTest` in the TCK pins it per backend, so a backend
  that accepts any reference fails compliance rather than diverging quietly
- **A bookmark stores the event id and nothing else about the event; its position and transaction
  are answered from the event.** On Postgres the bookmarks table has no ordering columns, and
  `getBookmark`/`getBookmarks` join the events row on the unique `event_id` index (one probe; the
  bookmark trigger does the same for its notification payload). The in-memory store resolves the
  reference from its log at placement and again when a persisted bookmark is loaded. So the reference
  read back — and the one a `BookmarkPlacedNotification` carries — is always the store's own for that
  event, whatever the caller passed: a bookmark cannot carry a cursor that disagrees with the event it
  names, and a bookmarks table carried between stores by id (an import preserves ids and reassigns
  both ordering columns) is valid as it stands. The alternative — storing the caller's `(tx, position)`
  beside the id — loses because the foreign key never checks that copy, so a bookmark could pass
  validation with a stored id and a wrong cursor, and because it makes every bookmark meaningless
  outside the store it was taken from. `BookmarksTest.bookmarkIsResolvedToTheStoresOwnCoordinates`
  and `bookmarkNotificationCarriesTheStoresOwnCoordinates` pin it per backend. A Postgres database
  created while the columns existed needs a hand-applied migration — `ALTER TABLE <prefix>bookmarks
  DROP COLUMN event_position, DROP COLUMN event_tx;` — which `checkDatabase()` reports under `VALIDATE`
  and `ENSURE`, and the first placement names under `NONE`; see "Migrating the bookmarks table" in the
  postgres module README
- **The foreign key deliberately does not cascade.** An absent bookmark means "replay from the
  beginning" — for a dispatcher in the eventmodeling framework, duplicate publishing to an external
  system, the worst outcome it documents. `ON DELETE CASCADE` handed exactly that to the readers least
  able to afford it: an event deletion (retention pruning, surgically removing a poison event) cascades
  away the bookmarks of readers still pointing into the deleted range — the *lagging* ones — silently,
  with no notification, since the bookmark trigger fires on INSERT/UPDATE only. With the default NO
  ACTION, deleting events out from under an outstanding bookmark fails loudly, and whoever prunes
  decides explicitly what happens to the reader. The constraint carries the reads too: a bookmark
  resolves to its event on every read (next bullet), so an event deleted from under one would leave
  the reader with no position at all — the same "replay from the beginning"
- **Migration for a database created while the cascade existed**: `ENSURE` only ever creates tables, so
  an existing database keeps its constraint until migrated by hand —
  `ALTER TABLE <prefix>bookmarks DROP CONSTRAINT fk_bookmarks_event_id; ALTER TABLE <prefix>bookmarks
  ADD CONSTRAINT fk_bookmarks_event_id FOREIGN KEY (event_id) REFERENCES <prefix>events(event_id);` —
  no data migration is needed. `checkDatabase()` validates the constraint by name only, not its delete
  rule, so an un-migrated database still starts, with the old cascade behaviour

### Idempotent appends: a key per event, and the batch as the unit of de-duplication

An `EphemeralEvent.withIdempotencyKey(key)` makes an append safe to retry: a key already stored on
the same stream is swallowed — nothing written, an empty list returned, counted on
`sliceworkz.eventstore.append.deduplicated`. The key is scoped to the stream (context and purpose),
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
  of scrape body. Uncapped, 10.000 purposes is 150.000 meters, 53 MB and a 23 MB scrape; 100.000
  extrapolates to ~550 MB and 1.8M series. Nothing fails — the numbers stay correct and the process just
  gets heavier for as long as it runs, so the growth looks like an ordinary leak rather than a metrics
  problem.
- **So the `purpose` tag is capped.** A store tags the first `MeterOptions.maxPurposeTagValues()`
  distinct purposes it sees (**default 1000**) and reports every purpose after that as `_other`, logging
  one WARN naming the purpose that tripped it. Below the cap nothing changes — that is exactly the case
  where a per-purpose breakdown is worth having — and above it the meters stay flat while the events are
  still counted, pooled under `_other`. Measured at 10.000 purposes: 15.015 meters instead of 150.000,
  and a 2.3 MB scrape instead of 23 MB.
- **The cost is heap and scrape size, not speed — and that half is measured.** The
  `metrics-cost` profile runs one corpus (100.000 events, `PER_ENTITY`, 2000 entities, so twice the
  default cap) against three stores that differ only in this setting: no meters, capped, uncapped.
  On PG18 all three land within about 1% of each other on unconditional appends, the canonical DCB
  check, an entity read and the savepoint probe — and capped against unlimited flips sign between
  runs, which is what no effect looks like. So a store past the cap is not paying for it in
  throughput, and neither is an instrumented store against an uninstrumented one; what an uncapped
  store spends is the memory and the series above, for as long as the process runs. (One caveat on
  reading that profile: the corpus is generated inside the first fork of the first target, so whichever
  target runs first is measured against a colder server. The figures above are the ones that survive
  running the targets in both orders; a cross-target percentage that does not is measuring the harness.)
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
  `MeterOptions.withUnlimitedPurposeTagValues()` removes the cap, which is only safe where purpose is
  low-cardinality by construction.
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
- **`sliceworkz.eventstore.head`** (and `head.duration`) counts head lookups, tagged like the other
  stream meters and deliberately not folded into `sliceworkz.eventstore.query`: a head lookup is the pin
  of a consistency boundary, and a dashboard should tell pins from reads — once a framework pins every
  command at the head, this series is its command rate. Pinned per backend by
  `HeadTest.headLookupsAreCountedOnTheirOwnMeter`.
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

// 6. Conditional append with optimistic locking: pin the boundary at the head BEFORE reading.
//    An absent head is an empty stream, and a valid boundary, so a customer with no history yet
//    needs no special case -- there is no getLast() to throw on an empty result
EventQuery customerQuery = EventQuery.forTags(Tags.of("customer", "123"));
EventReference head = stream.head().orElse(null);
List<Event<CustomerEvent>> existingEvents = stream.query(customerQuery.until(head)).toList();

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
Projector.from(stream).towards(projection).build().run();
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
- **Checking a target up front** must be done in **raw mode**
  (`eventStore.getEventStream(EventStreamId.anyContext())`, no event root classes). With domain classes
  registered, `getEventById` upcasts, and a legacy event whose upcast yields zero current events comes back
  as an empty list even though it exists — a false negative.

`EventToImport`'s canonical constructor is public, so it also writes synthetic events with a chosen id and
timestamp directly into a store — useful for fixtures, but it bypasses `append()` and everything that path
guarantees.

### Upcasting: a chain of versions, and what `targetTypes()` commits to

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
- **What an upcaster produces is checked on the read.** An event whose class is not among its
  declared targets fails as `EventDeserializationException` naming the upcaster, the class produced
  and the declared set, carrying the stored event's reference like any other read failure. An
  upcaster declaring `Set.of()` and producing an event is the shape this catches: a query for the
  produced type would never have fetched the event it came from
- **A failure on a later hop names the stored event and the upcaster that threw.** The exception's
  `getEventType()` is the stored type — the event a caller can dead-letter — and the message names
  the upcaster of the hop that failed, which is the code to fix
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
  produces no new tuple to VACUUM, leaves the heap in insertion order, and reaches
  the ciphertext already sitting in WAL, on replicas and in every backup. The alternative — nulling a
  separate erasable column with an `UPDATE` — reaches none of those copies and makes the log no longer
  append-only.
- **A shredded value is never null**, which is what keeps erasure from creating poison events: a record
  whose compact constructor rejects nulls still builds after its data is gone. Nor can "erased" be
  confused with "never held any", and a `Shreddable<Integer>` reads as shredded rather than as `0`.
- **A `Shreddable` anywhere works** — nested records, `List` elements, `Map` values — because it is one
  Jackson serializer on one document. The alternative — annotating personal components and splitting them
  into a second document — loses because reconciling the two on read takes a deep merge, and a merge that
  replaces JSON arrays wholesale silently drops the non-personal fields of partly-personal collection
  elements on every ordinary read, erasure or not.
- **Two subjects in one event each get their own key**, which no per-field annotation or per-event key
  can express. Keys are scoped to `(type, id, category)`, so "erase marketing, retain financial" is a
  category away.
- **`erase(DataSubject, reason)` erases one category; `eraseAllCategories(type, id, reason)` erases the
  person.** A `DataSubject` always names a category — `DataSubject.of("customer", id)` is the `default`
  one — and `erase` destroys the keys of that category only, reporting success because the erasure it
  names was performed. So `erase(DataSubject.of("customer", id))` on a subject that also holds
  `marketing` data leaves the marketing data readable, which is right for a per-category request and
  wrong for an art.17 request. The whole-person erasure takes the type and id and no category, so it
  cannot be narrowed by accident, and answers a `SubjectErasureReport` with one `ErasureReport` per
  category that held live keys. It is a separate SPI method on `ShreddingKeyStore` and `ShreddingCodec`
  (`shredAllCategories`), whose defaults throw `UnsupportedOperationException` so a key store written
  before it is told rather than made to erase one category and report success; a restricted codec
  passes it through whole. The alternative — having `erase` of the default category mean "every
  category" — loses because it makes erasing only the default category inexpressible, and because a
  category is what a subject's data is *written* under, so which one a caller happens to name is not a
  statement about the others.
- **The subject id must not itself be personal data.** It is stored in the clear in the envelope and
  survives erasure by construction — use a customer number, never an email address.
- **`KeyId` values are random and land on the event as `dek:` tags**, so "every event holding data under
  this key" is an ordinary tag query on the existing index. The tags stay after the key is destroyed, as
  a tombstone that says an erasure touched the event without saying what it took.
- **Erasure notifies nothing.** Read models, caches, search indexes and downstream systems keep their
  copies, and projections hold bookmarks so they never re-read. Re-projecting is the application's job.
- **Without a codec configured, registering an event type that declares a `Shreddable` fails** at
  `getEventStream` — before anything is read or written — rather than storing personal data in the clear.
  That check reads declarations (record components, type arguments, array elements) and cannot see a
  `Shreddable` held behind a component declared as an interface or a non-record class, so the
  codec-less mapper carries a `Shreddable` serializer of its own that throws: such an append fails as
  `EventSerializationException`, nothing stored, on the typed and the raw serde alike, instead of
  Jackson writing the `Present` record — value and subject in the clear — as it otherwise would.
  `CodecLessShreddableSerdeTest` in the impl module pins both routes and that a codec seals the same
  value; `ShreddableEventDataTest.registeringAProtectedEventTypeWithoutACodecFails` pins the
  registration check per backend.

**Two seams, and a shipped default.** `AesGcmShreddingCodec` (AES-256-GCM, random 96-bit IV per value,
envelope metadata bound as AAD) over a `ShreddingKeyStore`:

```java
InMemoryEventStorage.newBuilder().shredding(new InMemoryShreddingKeyStore()).buildStore();
InMemoryFsEventStorage.newBuilder().directory(dir).shredding(new InMemoryFsShreddingKeyStore(dir)).buildStore();
PostgresEventStorage.newBuilder().shredding().buildStore();          // keys in <prefix>shredding_keys
PostgresEventStorage.newBuilder().shredding(myKmsCodec).buildStore(); // take over encryption entirely
```

- **The codec travels with the storage, so `build()` honours `.shredding(...)` as `buildStore()` does.**
  `EventStorage.shreddingCodec()` answers the codec a builder was given (empty by default, so a backend
  written before it keeps working), and `EventStoreFactory.eventStore(storage)` — every overload not
  handed a codec of its own — uses it; a codec passed to the four-argument overload wins. The storage
  never seals or unseals anything itself, which is what keeps raw mode, exports and imports seeing the
  envelope as stored. The alternative — a codec living on the store alone, wired only by `buildStore()`
  — loses because a caller taking the storage from `build()` then gets a store that refuses the very
  event types the builder was configured for, and on Postgres cannot construct the key store the
  no-arg `shredding()` stands for at all: it needs the `DataSource` the builder resolves, and one
  loaded from `db.properties` is never handed out. `StorageShreddingCodecTest` pins the two in-memory
  backends and the precedence rule; `PostgresShreddingBuilderTest` pins the no-arg case per Postgres
  version. `MeterOptions` remains the one builder setting `build()` ignores, since it is a property of
  the store's meters and nothing about the storage.

- **`ShreddingKeyStore`** is the narrow seam: keep the shipped encryption, hold keys in Vault/KMS/an HSM.
- **`ShreddingCodec`** is the outer seam: take over encryption too, so key material never enters the JVM.
- **`unseal`/`resolve` returning empty means *erased*; anything else must throw `ShreddingException`.**
  This is the contract that matters most. Reported as empty, a key-store outage renders every protected
  value as erased — and projections, being at-least-once and bookmarked, write those gaps into read
  models permanently and never revisit them. `TypedEventPayloadSerializerDeserializer` rethrows a
  `ShreddingException` unwrapped (and unwraps one Jackson wrapped) precisely so that "retry later" does
  not arrive as `EventDeserializationException`, which means "never retry".
- **A key id the store has never held is not erased either; it throws.** A shredded key keeps its row,
  so every shipped key store can tell "destroyed" from "never seen", and the second means the store is
  not the one the events were sealed against: the fs store pointed at the wrong directory, the Postgres
  one at the wrong prefix or database, events imported without their keys. Reported as erased, that is
  the outage failure above applied to the *whole* store at once. The alternative — a fourth
  `KeyResolution` answer — loses because a reader could do nothing with it but throw: the value is not
  erased, and not withheld from this reader in particular. The cost is that shredded rows must stay
  (which the audit already requires): pruning one turns that subject's events from "erased" into
  unreadable, with an error naming the key. `ShreddableEventDataTest.aKeyThisStoreNeverHeldThrowsRatherThanReadingAsErased`
  pins it per backend, at the seam and through a projector.
- **The shipped codec measures the key it seals under, and keeps the label it binds unambiguous.**
  The JCE encrypts under a 128-, 192- or 256-bit AES key alike, so a key store minting the wrong
  length would otherwise seal without complaint under an envelope recording `A256GCM` for a value
  not sealed that way. `AesGcmShreddingCodec.seal` refuses a key that is not 256-bit AES material —
  `ShreddingException` naming the key and its length, nothing sealed — and refuses a key whose
  material it cannot see (`getEncoded()` null, an HSM-resident key), since the key-store seam hands
  material into the JVM and a key that never leaves its hardware belongs behind a `ShreddingCodec` of
  its own. `open` deliberately does not measure: what is sealed is sealed, and refusing to read it
  would strand the data while protecting nothing. The metadata GCM authenticates is the algorithm,
  key id, subject type, id and category joined with `|`, nothing escaped, so `seal` also refuses a
  `|` in any of those — two labels differing only in where the `|` falls would otherwise authenticate
  as one, and a sealed value could be relabelled between them with decryption still succeeding. The
  alternative — escaping the fields — loses because the authenticated string is recomputed on both
  sides and stored nowhere, so an escaped form stops authenticating every value already sealed whose
  fields hold the escape character, with nothing on the envelope to say which form it was sealed
  under; an envelope already carrying a `|` stays readable, ambiguous as it was written. The subject
  rule applies to this codec only — a codec of your own binds what it likes — and a subject is refused
  before the key store is asked, so it is not given a key row it will never use.
  `AesGcmShreddingCodecTest` pins all of it, the layout of the authenticated bytes included.
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
audit.categories();                                             // which categories exist, and how much under each
audit.keys(KeyAuditQuery.forKeys(keysOnAnEvent));               // are the keys this event carries still live?
```

- **`KeyRecord` carries no key material, and no method here returns any.** That separation is the whole
  reason this is a second interface rather than another method on the key store: a dashboard credential
  granted it can see *that* data is protected and *when* it was erased, and never *what* it was. The
  Postgres implementation does not merely refrain from reading `key_material` — the column is absent
  from every statement the audit issues, predicates included, so key bytes cannot reach a log or a heap
  dump through this path. That absence is also what keeps the audit working for the reporting role the
  postgres README recommends, which is granted every column *but* that one: PostgreSQL checks `SELECT`
  privilege on every column a statement references, a `WHERE` or `FILTER` clause as much as the select
  list, so "shredded" is judged by `shredded_at` — stamped by the same statement that nulls the material
  — never by `key_material IS NULL`. `PostgresShreddingReportingRoleTest` pins every audit statement
  under that role. The same privilege rule hides the column from `information_schema`, so such a role
  starts its store with `DatabaseInitMode.NONE`; `VALIDATE` would report `key_material` as missing.
- **Bounded, with no cursor.** `KeyAuditQuery` always carries a limit (default 500). A store running for
  years holds one row per subject per category and never prunes the shredded ones, and unlike an event
  query there is nothing to resume from — so an accidental full enumeration is not offered.
- **`categories()` is the inventory: which categories of personal data the store holds, and how much
  under each** (`CategoryTotals`: live subjects, live keys, shredded keys per category, most live
  subjects first). A category is the unit of erasure *and* of access, and only the key store knows
  which exist — writers choose them, and events carry them only inside sealed envelopes — so this is
  what an operator reads before deciding which categories a service is `restrictedTo`. Deriving it
  from `keys()` is wrong on any store holding more keys than the query's limit, which is why it is a
  method rather than a recipe. An erased category stays listed with zero live keys and its erasures
  counted. The default throws `UnsupportedOperationException`, like the optional SPI methods on
  `EventStorage`, so an audit written before it compiles and a caller is told rather than shown an empty
  inventory; the three shipped key stores all answer it.
- **`KeyAuditQuery.forKeys(Set<KeyId>)` is the join back from an event.** An event carries its keys as
  `dek:` tags and in each envelope, and nothing else; whether those keys still exist is the key store's
  to say. A reader holding an event — a dashboard rendering it, a support tool — asks for exactly those
  keys and can tell "protected" from "erased on … because …" without holding a key of its own. A
  primary-key lookup on Postgres, however many keys a page of events carries; the limit defaults to the
  number of keys asked for. It narrows the other parts rather than replacing them, an empty set is
  refused (it could only mean "nothing"), and a key the store never held answers nothing rather than
  failing — an envelope from another store is a miswiring the caller can see from the gap. The
  five-argument constructor stays, meaning "any key".
- **Which *events* hold data under a key is not answered here** — the key store has never seen an event.
  That is an ordinary tag query, since each event carries its keys as `dek:` tags:
  `EventQuery.forEvents(EventTypesFilter.any(), Tags.of(KeyId.TAG_KEY, record.id().value()))`.
- **Optional, like leases.** A key store fronting a KMS that does not enumerate returns empty and
  callers do without; all three shipped key stores implement it.

**The Postgres key store caches resolved keys with a TTL, default one hour**
(`PostgresShreddingKeyStore.DEFAULT_CACHE_TTL`). Without a cache, replaying a stream costs a query per
protected value; with one that never expires, an erasure performed by *another* instance would never be
noticed. An erasure performed by *this* instance drops its entries immediately, so the ttl bounds only
the cross-instance case — which makes it the outer edge of "erased" for a multi-instance deployment, and
a number worth stating in a data protection notice rather than discovering. `Duration.ZERO` disables the
cache and makes an erasure effective everywhere at once, at a query per protected value. A key that was
never seen is deliberately not cached as absent, so a shredded key still costs one query per read rather
than reporting stale data as readable.

**The cache is bounded in size too, at `DEFAULT_MAX_CACHED_KEYS` (10.000) entries, least recently used
evicted first.** The ttl bounds how stale an entry can be, not how many there are: a lapsed entry is
dropped when it is next asked for, and one never asked for again is dropped by nothing else, so a
process that resolves a key per subject over its lifetime — a projection replaying a stream of a
million subjects — would otherwise hold every one of them for good, at a few hundred bytes each. Below
the bound nothing changes; above it a key outside the working set costs one query when it comes round
again, and the four-argument constructor sets the bound for a working set that is genuinely larger.
`KeyCacheTest` pins the bound, the ttl and the eviction order without a database;
`PostgresShreddingKeyStoreCacheTest` pins that the store honours them, by counting the connections it
takes.

**A key is committed before the event sealed under it, never with it.** `keyFor` runs while the
payload is sealed, before the storage is handed anything to append, and the Postgres key store takes a
connection of its own for it even though it writes to the same `DataSource` as the events. Sharing the
`DataSource` buys the schema machinery, one set of credentials and a backup carrying both; it does not
put the two in one transaction, on this or any key store, and the order is the guarantee: mint first,
durable before it is returned, append second. A rolled-back append leaves a key row with no event under
it, which the subject's next append seals under; an event whose key was never persisted cannot happen.

**Raw mode does not decrypt**, deliberately: a wildcard stream, an export or an import sees the sealed
envelope as stored, which is what lets `EventStoreImporter` copy events with no keys and no domain
classes.

**Not every reader may read everything, and a reader that may not gets a third state: `Withheld`.**
Access to a protected value is the ability to resolve its key, so who may read what is decided on the
key seams and never in the read path. `Shreddable` is `Present`, `Shredded` or `Withheld`; a withheld
value exists, may well still have its key, and this reader does not get it. It carries the subject and
key id like `Shredded`, cannot be appended (this process never held the plaintext), and says nothing
about erasure — a reader that may not decrypt a value cannot tell whether it was erased, and is not
told; `ShreddingAudit` stays the account of erasures.

- **Why a third state rather than either existing answer.** Reported as `Shredded`, a projection renders
  "erased" for data that is not and writes that into its read model for good. Reported as a
  `ShreddingException`, it means "retry later", so a `Projector` that is merely not entitled fails the
  batch and never advances. Withheld is neither: the read completes, the projection decides how to
  render the gap, and everything the reader *is* entitled to still gets projected. Adding a case to the
  sealed interface breaks every exhaustive `switch` at compile time, which is the point — each renderer
  has to decide what a withheld value looks like. `orElse("[erased]")` call sites are the ones to review,
  since the fallback now covers both cases.
- **The seams carry it as sealed results, not exceptions.** `ShreddingKeyStore.resolveKey` answers
  `KeyResolution.Resolved | Erased | Denied`, and `ShreddingCodec.open` answers
  `Unsealed.Plaintext | Erased | Withheld`; both have defaults deriving the first two answers from the
  older two-answer methods, so a key store or codec written before them keeps working and never denies.
  A sealed type rather than a `ShreddingException` subtype because the difference between "erased",
  "denied" and "down" is the most important contract in this subsystem, and a `catch (ShreddingException)`
  retry loop would swallow a refusal by accident. The two-answer methods on the shipped
  implementations *throw* for a denial rather than report it as erased — nothing in the library calls
  them any more.
- **Three ways a reader is limited, from cheap to hard:**
  ```java
  // a reporting service: typed events, none of the personal data. Without a codec it could not open
  // the stream at all -- registering a type that declares a Shreddable fails on a store with none
  PostgresEventStorage.newBuilder().shredding(ShreddingCodec.withholdingAll()).buildStore();

  // a service that reads names and never addresses: an in-process policy on the category
  PostgresEventStorage.newBuilder().shredding(AesGcmShreddingCodec.over(keyStore).restrictedTo(Set.of("identity"))).buildStore();

  // the hard boundary: a key store that refuses keys this role is not granted
  KeyResolution resolveKey ( KeyId key ) { ... return new KeyResolution.Denied("vault: 403"); }
  ```
  `restrictedTo` decides on the category the envelope carries in the clear, before any key lookup, so a
  denied category costs no key-store traffic. It is symmetric — the codec seals nothing outside its
  categories either, and such an append fails as an `EventSerializationException` with nothing stored —
  and it passes erasure and audit through whole, because an erasure that silently left another category
  readable while reporting success is the worst outcome an erasure can have. It is a data-minimisation
  boundary a deployment declares for itself, not a security boundary: the process still holds the codec.
  The security boundary is the key store's refusal — a KMS policy per service role, or on Postgres a
  role granted every column of `shredding_keys` *except* `key_material`, which `PostgresShreddingKeyStore`
  recognises by SQLSTATE 42501 and reports as `Denied` (cached for the key ttl). Row-level security on
  that table does **not** produce a denial: a hidden row reads as erased. The two compose.
- **The unit of access is the unit of encryption: the `Shreddable` value, partitioned by `category`.**
  "Name but not address" is two wrapped values under two categories, chosen when the event is written,
  not one `Shreddable<ContactDetails>` holding both. Nothing inside one sealed value can be handed out on
  its own — the alternative, decrypting and blanking fields, leaves the plaintext in the reader's memory
  and needs the annotation-plus-split design already rejected above. A category is forward-only: a
  value sealed under `default` cannot be re-categorised without re-sealing, which means rewriting the
  event. Each category is one more key row per subject and one more `keyFor` query per append that
  carries it, so a handful per subject is the intended scale, not one per field.
- **A withheld reader still sees the pseudonymous subject id, the category, and the `dek:` tags.** That
  is unchanged and by design, and it is why the rule that subject ids and tags must not themselves be
  personal data is load-bearing for the PII-less reader.

**A component that was a plain field when its events were written cannot be read as a `Shreddable`.**
The stored value is bare, so nothing can say whose data it is, and the read fails with a message saying
to migrate the events via `EventStoreImporter.transform` or to read the old shape through a
`@LegacyEvent` upcaster. Guessing a subject would leave old personal data unprotected and unerasable.

`ShreddableEventDataTest` in the TCK pins all of this per backend, against *that backend's* key store:
the two-subject erasure, the collection case, a record whose constructor rejects nulls surviving erasure,
category independence and the whole-person erasure across every category (through a restricted codec
too), idempotent erasure and a fresh key afterwards, the `dek:` tags, the audit view
(including, reflectively, that `KeyRecord` cannot carry key material), that an unreachable key store
throws instead of reporting the data as erased and so does one asked for a key it never held — and, for entitlement, that a withholding codec reads the
typed events with every value withheld, that a restricted codec reads its categories and withholds the
rest without a key lookup, seals nothing outside them and erases everything, that a withheld value says
nothing about erasure, that a key store's `Denied` reads as withheld and a projector advances over it,
and that a withheld value cannot be appended again. `ReaderEntitlementTest` in the api module pins the
seams below the store.

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
first: per-test isolation on Postgres is `initializeDatabase()` dropping and recreating the tables
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
`EventImportRoundTripTest`, and the store-level tests of the impl module's meters and serde sharing
(`MeterPurposeCardinalityTest`, `AppendPositionGaugeTest`, `QueryTimerTest`,
`EventStreamSerdeSharingTest`). Postgres containers are managed by `PostgresContainer`, started once
per JVM per image; per-test isolation comes from `initializeDatabase()` dropping and recreating the
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
  performance one. They lose selective tag queries by 30–90× (exactly the case the GIN index exists
  for) and win only where a limit fills before the scan gets far; prototyping tag-query cost against
  them points backwards.
- **Stream design** (`stream-design-*` pair): **`PER_ENTITY` wins or ties everything except reading a
  context in order** (13–15× worse in the committed run, which was measured without
  `idx_events_context_tx_position`, the index that serves exactly that read). The canonical DCB
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
- **The library's own meters cost nothing measurable in throughput** — capped, uncapped and absent
  land within ~1% — so their cost is the heap and scrape size described in the metrics section above.
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
   it `@LegacyEvent(upcast = ...)`, and upcast it to the renamed class — see the upcasting sections. The
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
6. **Service Loader Pattern**: `EventStoreFactory` uses Java ServiceLoader for implementation discovery
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
  append sorts before all of history. `build()` refuses to start such a store (a bounded index walk
  over the stream heads, under every init mode). Physical backups keep the counter and need nothing;
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
  order**: `idx_events_tx_position`, the global order, for a read that binds no stream column (a
  wildcard stream paged by a store-wide projection or an export, `head()` of the whole store, an
  unscoped `EventStoreImporter` run), and `idx_events_context_tx_position` for one that binds the
  context and leaves the purpose open (a whole-context replay over a per-entity layout). The stream
  indexes all lead with `(stream_context, stream_purpose)` and offer such reads neither a start
  condition nor an order, so without these every page is a scan plus a sort, whatever its limit.
  A database created before they existed needs them applied — `ENSURE` does that on the next
  start, a `VALIDATE`/`NONE` deployment by hand with `CREATE INDEX CONCURRENTLY` — see "Migrating a
  database created before the order indexes existed" in the postgres module README.
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
