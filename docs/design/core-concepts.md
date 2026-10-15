# Core concepts

The rules in brief are in the root `CLAUDE.md`, under "Core Concepts". This file holds the full design: each rule, the reasons for it, and the alternatives that were rejected.

**EventStore:**
- Main entry point for interacting with the event storage system
- Built on an `EventStorage` with `EventStore.on(storage).build()`, or taken from a storage builder's
  `buildStore()`, which does the same and hands back one handle owning both
- **`EventStore.on(storage)` is the one entry point, and `EventStoreFactory` is the SPI behind it.**
  Everything a store can be given beyond its storage is a call on the builder, each defaulting to what
  the storage builders use: `.observer(o)` (default the storage's own observer, which a storage builder's
  `.observer(...)` put there and which is `EventStoreObserver.NOOP` otherwise) and `.shredding(codec)`
  (default the storage's own codec, which a storage builder's `.shredding(...)` put there). Every setter refuses `null`, since each has a default
  and a null could only be a mistake, and `build()` is where the `ServiceLoader` lookup happens, so a
  builder is configured before anything is resolved and fails at `build()` with the same
  `EventStorageException` the factory throws when no implementation is on the classpath. The built
  store does not own the storage (see "Lifecycle: closing a store"); `EventStore.owning` composes the
  two. The alternative — calling the factory, as
  `EventStoreFactory.get().eventStore(storage, observer, codec)` — loses because it puts a
  `ServiceLoader` chain and positional arguments, where a `null` means "the storage's own", at every
  call site that wants anything but the defaults. The factory stays public: it is what an implementation of this library provides through the `ServiceLoader`, what
  the builder and the storage builders' `buildStore()` call, and nothing application code needs to
  name. `EventStoreTest` in the api module pins the builder's argument checks and its failure with no
  implementation on the classpath; `EventStoreBuilderTest` in the tests module pins that each setting
  reaches the built store and that each default is the documented one
- Provides access to event streams via `getEventStream()`

**EventStream:**
- Identified by `EventStreamId` which consists of a context and optional purpose
- Purpose is optional: `EventStreamId.forContext("x")` defaults purpose to `"default"`, so a context that needs only one stream can ignore purpose entirely. Set a purpose only to distinguish multiple streams within a context (e.g. per-instance, or separating event kinds). Whether to make the purpose an *entity id* — a stream per SKU rather than a stream per context — is the one layout decision with measured consequences on both reads and write contention; see the Benchmarking digest below, and "Choosing a stream design" in `sliceworkz-eventstore-benchmark/CLAUDE.md` for the figures
- Supports both reading (via `query()`) and writing (via `append()`)
- Type-safe through generic parameter `<DOMAIN_EVENT_TYPE>`, which the single-class overloads of
  `getEventStream` fix from the root class: `getEventStream(id, CustomerEvent.class)` is an
  `EventStream<CustomerEvent>`, and assigning it to an `EventStream<OrderEvent>` — or widening it to an
  `EventStream<Object>`, which would let an append of a foreign event type compile — is a compile error
  rather than a runtime append failure. The legacy root class is not constrained, since legacy events
  upcast into current ones. The `Set<Class<?>>` overloads carry no such constraint and are the way to open
  a stream typed wider than its roots. `EventStoreTypeParameterTest` in the api module pins it by running
  javac against probe snippets
- Combines `EventSource` (reading) and `EventSink` (writing) interfaces
- **An append takes `List<? extends EphemeralEvent<? extends DOMAIN_EVENT_TYPE>>`, so an ordinary list
  of one event type fits.** A caller mapping its domain events into ephemeral ones holds a
  `List<EphemeralEvent<CustomerRegistered>>`, and `List` is invariant, so without the outer wildcard
  that list is not a `List<EphemeralEvent<? extends CustomerEvent>>`: every such call site has to name
  the parameter type it is producing — `.<EphemeralEvent<? extends CustomerEvent>>map(...)` — to say
  what the signature can say once, for a list the append only ever reads. The inner wildcard is what
  still lets a batch mix event types of the hierarchy, and what still refuses a batch of a foreign
  one; the outer one only removes the witness. Widening a parameter this way is source-compatible for
  callers and leaves the erasure alone, so a consumer compiled against the narrower signature keeps
  working — an *implementor* of `EventSink` has to widen its override with it, since the narrower one
  no longer overrides anything. `EventStoreTypeParameterTest` pins both the acceptance and the foreign
  batch's rejection by running javac
- **Raw mode is its own method, with its own type: `getRawEventStream(id)` returns an
  `EventSource<String>`.** No event root classes, so no type mapping: every stored event reads as the
  JSON document it is stored as — the same text `StoredEvent.payload()` carries, parsed by
  nothing on the way out, so on Postgres the `jsonb` rendering rather than the bytes appended — under
  its stored type, nothing upcast and nothing decrypted: a `Shreddable` comes back as its sealed
  envelope. A caller that wants to look inside parses it with whatever JSON library it uses; most
  never do. It is an `EventSource` rather than an `EventStream` because a raw stream cannot append (an
  append is admitted only for a type the stream maps, and a raw stream maps none), so the type says so
  instead of every append failing at runtime. What it is for: reading the event an
  `EventDeserializationException` names through `getEventById`, following every append in a store, and
  the presence check before an import. Two alternatives lose. An `EventSource<Object>` whose value is
  a parsed tree — a Jackson 3 `JsonNode` behind an `Object`, since the api carries no Jackson — names
  no type the api can describe, is usable only by a caller importing Jackson 3 (and not by one on
  Jackson 2 or another library), and pays a parse per event on the one read path that looks inside
  almost none of them. A `getEventStream(id)` overload with a free type parameter loses because
  `EventStream<CustomerEvent> s = store.getEventStream(id)` then compiles and hands back JSON
  documents under the domain type, a `ClassCastException` at the first `switch`. A stream typed wider
  than its roots that can still append is the `Set<Class<?>>` overload's job, not raw mode.
  `EventStoreTypeParameterTest` pins the acceptance and the rejections by running javac;
  `RawStreamTest` in the TCK pins per backend that the document is the storage's own text for the
  event, through `query` and `getEventById` alike
- **A wildcard stream is a source, not a sink.** An event is stored in exactly one stream, and a
  wildcard (`anyContext()`, or `anyPurpose()` within a context) names none, so a stream opened on
  one reads across every stream it matches and refuses `append` with `IllegalArgumentException`,
  nothing stored. To write to a stream it reads across, open that stream by its own id: a handle is
  cheap and shares its serde (see "Lifecycle: closing a stream" below), and a stream per operation
  is the intended usage. There is deliberately no append that names a target stream as a further
  argument, and `EventStreamId` carries no relation saying which stream may write to which — only
  `covers`, which says whether an id's scope contains a stream, and is what scopes every read and
  decides which subscribers a notification is relevant to. It is called that because it is a
  relation between two values, a scope containing a stream, and not a permission a stream holds:
  the alternative name — `canRead` — loses because it invites a `canWrite` beside it, the very
  relation this design refuses. The alternative — an `append(criteria, events,
  target)` through a wildcard stream bound to the target's context — loses because a stream is then
  a sink for some targets and not others, decided per call; because every append is observed as an
  append through the stream it went through, so a write landing in `customer#123` would be reported
  under the wildcard's id and never under its own; and because it buys nothing the shared serde does
  not already give. The refusal is at
  runtime, not at compile time, because whether an id is a wildcard is a property of its value: the
  same `EventStream` type reads a context or one of its streams depending only on the id it was
  opened with (the raw stream, whose read-only nature *is* static, is a separate case).
  `EventStreamTest.testAppendToNonSpecificStream` and `testAppendToWildcardPurposeStream` pin it per
  backend
- **`getEventById` answers in two levels: `Optional<List<Event<E>>>`.** The `Optional` says whether
  this stream holds a stored event with the id, the list what it reads as through the stream's
  mappings — one event, or through an upcaster several or none. A stored event that upcasts into
  nothing is therefore *present with an empty list*, and an id the storage does not hold, or holds
  in a stream this one does not read across, is *absent*. The alternative — a bare list, empty in
  both cases — loses because a presence check through a stream with upcasters then reports every
  legacy event that upcasts into nothing as missing, with nothing to say so; the two levels are what
  let `isPresent()` be the presence check on a typed stream and a raw one alike. `UpcastMultiTest`
  pins both answers per backend
- **`query()` returns a `List`, read in full.** Storage has finished reading by the time the list
  comes back, and every event in it is deserialized and upcast, so the type says what a query costs:
  a query with no limit against a storage with no `resultLimit` reads everything matching into heap,
  an OOM rather than a slow read, with no back-pressure to arrive at. Bound the read with
  `EventQuery.limit(n)`, which is the limit storage is given; nothing applied to the result bounds
  anything. The alternative — returning a `Stream` — loses because the type promises a laziness the
  storage does not deliver: `findFirst()` on it reads as though it bounded the read and does not, and
  a poison event fails from whichever terminal operation the caller wrote rather than from the query.
  `.stream()` is one call away for a caller who wants stream operations over events already read.
  Nothing needs closing — no database resource is held open behind the list (`EventSource.close()` is
  about subscriptions, not queries)
- **A full replay is a loop, not one unbounded query.** `Projector` already reads in batches of 500,
  carrying a cursor between them, and is the right tool for a stream of unknown size. By hand, read
  with `page(q.limit(n), cursor)`, which answers an `EventPage`: the events, the number of stored
  events read, and the reference of the last stored event read, which is the cursor of the next
  page. The last two are what paging needs and the events alone cannot say once upcasting is
  involved: a limit counts stored events, an upcaster may turn one into several or into none, so a
  page holding no events may sit in the middle of a stream and the reference to continue from may
  be on no event returned. A page shorter than its limit is the last one. `query(q, cursor)` is the
  same read without that account, read whole exactly as a page is (an `EventDeserializationException`
  comes from the call itself, and a page or a query holding a poison event hands out none of its
  events). `EventPageTest` pins it per backend. The unlimited path exists for
  callers who know their result set is small, or who genuinely want it all at once — it is not a
  way to process a large stream incrementally
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
    three reference columns off `idx_events_stream_position` — off `idx_events_context_order`
    for a context, off `idx_events_global_order` for a wildcard stream, decided by the scope and never
    by an estimate — and no payload (`PostgresHeadStatementTest`, `PostgresGlobalOrderIndexTest`,
    `PostgresOrderIndexAdmissionTest`)
  - **It names a stored event, whole**: `index` 0, and a boundary at it includes every event the stored
    event upcasts into — see the `until` note under EventFilter
  - The event at the head need not match the boundary's filter: the reference is a cursor for the check,
    and only matching events after it count. An absent head is an empty stream and must stay an absent
    reference — "I decided on an empty boundary" — never be substituted with some other reference.
    Reported as its own observation (`Observation.Head`), never as a read. `EventStoreImporter` bounds its
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
- **On the SPI records the payload is `payload`: one JSON document, as text.** `EventToStore`,
  `StoredEvent` and `EventToImport` name it the same, it is the text a raw stream's `data()` answers and
  what `EventToImport.withPayload` rewrites, and the in-memory backends refuse one that is not a JSON
  document. The file codec writes it under that key as the document it is, not as an escaped string, and
  reads a file carrying it under `immutableData` the same, so an events directory reloads whichever key
  its files carry

**EphemeralEvent:**
- Lightweight event representation before persistence (no stream, reference, or timestamp)
- Converted to full `Event` upon appending to a stream
- Created via `Event.of(data, tags)`

**EventType:**
- The stored name of an event: `EventType.of(Class)` is `Class.getSimpleName()`, unless the class is
  annotated `@EventName("...")`, in which case it is the annotation's value. That method is the one place
  a class becomes a stored name, so the annotation is honoured on append, in a stream's type mappings, in
  `EventTypesFilter.of(Class...)` and on a `@LegacyEvent`
- **`of(Class)` and `named(String)` are the two factories, and there is deliberately no `of(Object)`
  deriving the type from an event instance.** The alternative — an `of(Object)` overload beside
  `of(Class)`, calling `getClass()` itself — loses because an overload on `Object` accepts every
  argument the `Class` one does not: a stored name passed by mistake, `EventType.of("CustomerRegistered")`,
  compiles and is the type named `String`, which matches no stored event and fails nothing. With `Class`
  the only parameter type that call is a compile error, and the code holding an instance
  (`EphemeralEvent.of`, the serde, a filter matched in memory) writes `EventType.of(data.getClass())`
  itself. `named` is called that so the string factory reads as what it is.
  `EventStoreTypeParameterTest` pins the rejection by running javac against a probe
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
  any type carrying the tags, or as a chain: `EventFilter.forTypes(CustomerEvent.class).tagged("customer", id)`
- Used by `AppendCriteria` for optimistic locking (where direction/limit are irrelevant)
- **The fluent form and the two-halves form build the same filter.** `forTypes(classes...)` is
  `forEvents(EventTypesFilter.of(classes...), Tags.none())`, and `tagged(key, value)` narrows what is
  there: every item gets the tag on top of the tags it already requires, chained calls accumulate, and
  the `until` boundary is kept. Narrowing distributes over a union — `a.or(b).tagged(t)` is
  `a.tagged(t).or(b.tagged(t))` — which is what makes it well-defined on a filter of several items;
  a match-all narrowed is `forTags(t)`, a match-none stays match-none. `or` is the union: the items of
  both side by side, match-all on either side making the union match-all, and the same `until` on both
  or `IllegalArgumentException`. Both spellings resolve to the same `EventFilterItem`s, so a query
  built one way compares equal to the same query built the other way. `EventFilterTest` pins each rule, `EventQueryTest` the mirror on the query
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
- **So an `AppendCriteria`'s filter must carry no `until`.** One that does deems nothing after it
  relevant, so the check finds no new fact whatever lands: every append is admitted and nothing is
  raised — the check off, not merely narrowed. The head idiom puts both forms of one query in a
  caller's hands — `relevant.until(head)` for the read, `relevant` plus `head` for the criteria — and
  `AppendCriteria.of(relevant.until(head), head)`, which is what reusing "the query I read with"
  produces and reads as the more careful of the two, is the one that turns optimistic locking off. The
  head is a boundary as the criteria's *reference*, where the matching events after it are what
  conflict; in the filter it bounds the check away. Nothing catches it — the append reports success —
  so it is documented at all three places a caller meets it: `AppendCriteria` (both `of` factories),
  `EventFilter.until` and `EventSource.head()`
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
  any type carrying the tags — the usual shape of a consistency boundary — or as a chain,
  `EventQuery.forTypes(CustomerEvent.class).tagged("customer", id)`, where a sealed root names its
  whole hierarchy. `EventQuery` re-exposes the `EventFilter` builders and none of its readers:
  `forTypes`, `forTags`, `forEvents`, `tagged`, `or`, `until` and `untilIfEarlier` behave as they do
  on the filter (see EventFilter above), `tagged` keeping the query's direction and limit, `or`
  refusing two directions or a limit on either side, since a shared limit over a union does not mean
  "the last of each". Whether an event matches, whether the query matches everything or nothing, its
  items and its `until` boundary are the filter's to answer, through `filter()`:
  `query.filter().matches(event)`, `query.filter().isMatchNone()` — which is how `Projector` asks
  them. The readers of the query's own are the two it adds, `isBackwards()` and `limit()`. The
  alternative — the query delegating each filter reader through a method of its own — loses because it
  hands every caller two spellings of one question and makes the filter's read surface something to
  keep in step on the query. In the same spirit, the query names no `EventFilterItem`: its constructor
  takes an `EventFilter`, and `forEvents(types, tags)` builds one. `EventFilterItem` stays public
  because it is the component type of `EventFilter.items()`, which the impl (the legacy-type
  trace-back), the Postgres backend (the SQL it builds from each item) and the fixture read; it is a
  filter's item, and nothing application code needs to construct. `EventQueryTest` pins the surface
  reflectively
- **`.limit(n)` means "read n stored events", and it is pushed into the storage query** — a SQL
  `LIMIT` on Postgres, a short-circuiting `Stream.limit` in memory — not applied to the result. That
  is what makes it bound memory as well as output: a storage query materialises its whole result set
  before returning it, so an unbounded query over a large stream is a heap problem rather than a slow
  one. A cursor does not change this: `query(q.limit(500), cursor)` and `page(q.limit(500), cursor)`
  read 500, same as `query(q)` would with the limit on `q`. A query with no limit reads to the end of
  a stream, deliberately. The limit is a property of the query: `Limit` is the type of
  `EventQuery.limit()`, and of the SPI's `EventStorage.query`, and is not an argument of any read on
  `EventSource` — the alternative, a read taking a `Limit` beside the query's own, loses because two
  limits for one read is one more than a caller can keep straight, and the projector's page size is
  just `eventQuery().limit(n)`
- **The SPI is asked with an `EventFilter`, never with an `EventQuery`.** `EventStorage.query` takes
  the filter, the stream, the cursor, the limit and the direction as five parameters, so direction and
  limit reach a backend exactly once: the stream layer derives all three from the query it was given,
  the `Projector`'s page size included, since that is the limit of the query it pages with. The
  alternative — an `EventQuery` parameter beside a `Limit` and a `Direction` — loses because a
  backend is then handed two limits and two directions and nothing says which wins; the in-tree
  backends read the parameters and ignore the query's own, and a third-party one reading the query's
  own would be right by the signature and wrong by the store. The limit and the direction arrive as
  the query's own types, `Limit` and `EventQuery.Direction`: the SPI declares no direction enum of
  its own, since one with the same two values says one thing in two types and puts a translation
  between them on every path from a query to a backend, while the SPI already names `EventFilter`,
  `Limit` and `EventStreamId` from the api. The stream scope is an `EventStreamId`
  and never absent: a wildcard component reads across it, so `EventStreamId.anyContext()` is the whole
  storage, and a null is refused with `IllegalArgumentException` on `query`, `append` and `head` alike
  (`StreamScopeTest` in the TCK pins it per backend, with what each wildcard reads).
- **Without upcasting, n stored events are n events back. With it, they are not.** An `Upcaster`
  may turn one stored event into several or into none, and the limit is spent before it runs,
  so `.limit(1)` over an event upcasting into two returns two, and over one upcasting into none
  returns zero — having read exactly one stored event either way. Trimming the surplus would return a
  fragment of a stored event and leave a cursor pointing into its middle; `Projector` counts stored
  events for exactly this reason. Where a caller needs exactly n, take a `subList` of the returned
  list — cheap, since those events are already read. `UpcastMultiTest` pins this per backend

**AppendCriteria:**
- Controls optimistic locking when appending events
- Contains an `EventFilter` and an optional `EventReference` for the expected last event
- If new matching events are found after the reference, append fails with `OptimisticLockingException`
- Use `AppendCriteria.none()` for simple appends without locking, or the overloads that take no
  criteria — `append(events)` and `append(event)` on `EventSink` — which are the same append with
  `none()` and nothing else: no boundary checked, no `OptimisticLockingException` possible, and on
  Postgres no advisory lock taken. The two spellings are interchangeable. The alternative — making
  `none()` the only spelling, so that skipping the check is written out at every call — loses
  because a DCB append is opted into by presenting a boundary the caller holds from its read, not by
  the absence of an argument; an argument that is always `none()` where there is nothing to present
  marks nothing. `EventStreamTest.anAppendWithoutCriteriaIsAnAppendWithNoCriteria` pins it per backend
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
- **`EventHandler` is the one handler interface, and `when(Event<E>)` its one method.** The `Projector`
  hands every matching event to it, one call per event, and a handler that needs only the domain
  event switches on `event.data()`. The alternative — a second interface whose `when` takes the
  payload alone, with a default `when(Event)` unwrapping into it, and a `WithoutMetaData` twin of
  every interface built on the pair — loses because two methods of one name on one object, one to
  call and one to implement, is a split the compiler does not enforce (an `@Override` on the wrong
  one is found by the events that never arrive); because for a handler over `Object` the pair
  collapses into `when(Object)` and `when(Event<Object>)`, where an `Event` *is* an `Object`; and
  because it bought one `.data()` call per handler. Nor are there `when(List)`/`when(Stream)`
  batch defaults: the projector never calls them, so an override runs for nobody, and batch-level
  work has its seam in `BatchAwareProjection`. A payload-only convenience, if one is ever wanted,
  gets a distinct method name, never an overload of `when`. `ProjectionTest` in the api module pins
  the contract reflectively: one `when`, abstract, taking the `Event`, on both `EventHandler` and
  `Projection`

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
- **`readBookmark()` and a run never overlap.** Both take the projector's lock, so a manual bookmark read
  while a run is in progress — a subscribed projector runs on the storage's notification thread — waits
  for the run to finish and then resets the position, rather than moving a cursor the run is about to
  overwrite with its own progress. `ProjectorTest.testReadBookmarkWaitsForARunInProgress` pins it
- `ProjectorBatchDurabilityTest` in the TCK pins all of it per backend: the bookmark visible from inside
  the *second* batch already names the first, a failed commit is a `ProjectorException` and its events
  come round again, and a failing rollback keeps the cause

**Projector.Builder:**
- **One flat builder, starting from the source.** `Projector.from(stream).into(projection)` is the
  whole of a projector; `bookmarkAs("reader")` (or `bookmarkAs("reader", tags)`), `startingAfter`,
  `inBatchesOf`, `subscribe` are the optional settings, each one method on the same builder. A
  bookmarked projector reads its bookmark before every run; `readBookmarkOnce()` reads it before the
  first run only and `readBookmarkOnRequest()` only on `readBookmark()`. Those are the two readings
  that differ from the default, so they are the two that exist. The alternative — a sub-builder for
  the bookmark, entered and left around its own settings — loses because the sub-builder holds three
  settings and costs two calls to get to them, and because it puts the validation on the way out
  (`done()`) rather than on the setting itself. There is no other spelling: the source is given to
  `from`, so the builder has no public constructor and no `from` of its own.
  `idleBookmarkInterval(Duration)` paces the read-position moves of runs that handled nothing (see
  "Bookmarks" below)
- `Projector.from(null)` is refused with `IllegalArgumentException` at the call; `build()` refuses a
  missing projection, and a bookmark read setting without a reader, with `IllegalStateException`
  naming the call to make, rather than leaving it to fail as a `NullPointerException` from inside the
  first batch — after the bookmark has been read and, for a subscribed projector, after the source has
  been registered with the storage. `inBatchesOf(n)` refuses a batch size below 1
- **`ProjectorException.getEventReference()` is the last event handed to `when`, which is the failing
  event only when `when` threw.** For a page that could not be read or a batch hook that threw, it is
  the last event of an earlier batch, or null when there was none; an
  `EventDeserializationException`'s own reference names the poison event. Its javadoc lists the cases
- `accumulatedMetrics()` is the read of a subscribed projector's position from another thread: the
  field it answers is `volatile` and published by every run, where `run()`'s return value is only
  seen by the thread that called it, which for a subscribed projector is the storage's notification
  thread
- **`Projection.eventQuery()` is read once per run.** Storage is asked with that query and every event
  of the run is matched against it, so a projection that computes its query cannot be asked twice and
  answer differently in one run, and is not asked once per event. `ProjectorTest` pins both per backend

**Projection initQuery (Savepoint Pattern):**
- Projections can define an optional `initQuery()` (default returns `EventQuery.matchNone()`; a `null` is tolerated and means the same) that runs before the main `eventQuery()`
- Enables the savepoint pattern: a backward query with limit 1 finds the most recent savepoint event that summarizes prior state
- The `Projector` executes `initQuery()` first, passes results to `when()`, then uses the last event's reference as the cursor for `eventQuery()`
- Savepoint events are pure domain events — no special framework support needed
- When no savepoint exists, the main `eventQuery()` replays from the beginning (graceful degradation)
- When bookmarking is enabled on the `Projector`, `initQuery()` is ignored (a warning is logged at build time)
- A savepoint handler that throws fails the run as a `ProjectorException` naming the savepoint event, exactly
  as a failing batch does, and the cursor goes back to where the run started, so the next run re-runs
  `initQuery()` rather than skipping it and starting the main query from a read model that was never
  initialised. `ProjectorTest.testProjectorWrapsAFailingSavepointHandlerInAProjectorException` pins it per backend
- The `initQuery()` and `eventQuery()` should query different event types to avoid double-processing and to allow recovery from buggy savepoints

