[![ci build - mvn package](https://github.com/sliceworkz/eventstore/actions/workflows/ci.yaml/badge.svg)](https://github.com/sliceworkz/eventstore/actions/workflows/ci.yaml)
[![Status: Beta](https://img.shields.io/badge/status-beta-orange)](#)
[![Maven Central](https://img.shields.io/maven-central/v/org.sliceworkz/sliceworkz-eventstore-bom)](https://central.sonatype.com/search?q=g:org.sliceworkz)
[![Quickstart](https://img.shields.io/badge/Quickstart%20Guide-blue)](https://sliceworkz.github.io/posts/eventstore-quickstart/)
[![Docs](https://img.shields.io/badge/Documentation-purple)](https://sliceworkz.github.io/categories/eventstore-documentation/)

# About Eventstore

A DCB (Dynamic Consistency Boundary) compliant EventStore implementation in Java.

Persistence options: PostgreSQL for production, and two in-memory stores (plain, and persisted to
JSON files) for development, demos and tests.

Supports all features described by the [DCB Specification](https://dcb.events/specification/):
- Tagging of events for dynamic retrieval
- Optimistic locking / conditional append via `AppendCriteria`


# Getting started

## Requirements

- **Java 21** or later (the library is compiled with `--release 21` and uses records and sealed
  interfaces in its public API)
- **Maven 3.6.3** or later to build from source
- For the PostgreSQL backend: **PostgreSQL 16** or later, with the `btree_gin` extension available
  (creating it needs `CREATE` on the database — see the
  [postgres module README](sliceworkz-eventstore-infra-postgres/README.md))

## Coordinates

Every artifact is published to Maven Central under the group `org.sliceworkz`. Import the BOM once,
then add the modules you need without versions:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.sliceworkz</groupId>
            <artifactId>sliceworkz-eventstore-bom</artifactId>
            <version>0.10.2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- the API you code against -->
    <dependency>
        <groupId>org.sliceworkz</groupId>
        <artifactId>sliceworkz-eventstore-api</artifactId>
    </dependency>
    <!-- one storage backend; for PostgreSQL use sliceworkz-eventstore-infra-postgres instead -->
    <dependency>
        <groupId>org.sliceworkz</groupId>
        <artifactId>sliceworkz-eventstore-infra-inmem</artifactId>
    </dependency>
</dependencies>
```

A backend pulls in `sliceworkz-eventstore-impl` at runtime, so `EventStore.on(storage).build()` finds an
implementation without you naming one. The PostgreSQL backend declares the JDBC driver as `provided`:
add `org.postgresql:postgresql` yourself, at the version your platform ships.

## Modules

| artifact | module name | what it is | when you need it |
|---|---|---|---|
| `sliceworkz-eventstore-api` | `org.sliceworkz.eventstore` | The interfaces you code against (`EventStore`, `EventStream`, `Event`, `Tags`, `AppendCriteria`, `Projector`, …) and the `EventStorage` SPI a backend implements | always |
| `sliceworkz-eventstore-impl` | `org.sliceworkz.eventstore.impl` | The `EventStore` implementation: streams, serialization, upcasting, crypto-shredding, meters | pulled in at runtime by every backend |
| `sliceworkz-eventstore-infra-inmem` | `org.sliceworkz.eventstore.infra.inmem` | In-memory storage | development, demos and tests |
| `sliceworkz-eventstore-infra-inmem-fs` | `org.sliceworkz.eventstore.infra.inmem.fs` | The in-memory storage persisted to JSON files | local development that must survive a restart |
| `sliceworkz-eventstore-infra-postgres` | `org.sliceworkz.eventstore.infra.postgres` | PostgreSQL storage | production |
| `sliceworkz-eventstore-serialization-json` | `org.sliceworkz.eventstore.serialization.json` | JSON codecs for stored events and bookmarks | pulled in by the file-backed store; only needed directly to build your own backend on it |
| `sliceworkz-eventstore-testing` | `org.sliceworkz.eventstore.testing` | `EventStoreFixture` for testing your application, plus the compliance suite for third-party backends | in `test` scope, see [Testing](#testing) |
| `sliceworkz-eventstore-bom` | — | Bill of materials pinning all of the above to one version | imported once, as shown above |

The examples, benchmark and TCK-runner modules in this repository are not published.

The module name is the `Automatic-Module-Name` in each jar's manifest, so `requires
org.sliceworkz.eventstore.infra.postgres;` works on the module path and keeps working whatever the
jar file is called. The jars carry no `module-info.java`: they are automatic modules, which read every
other module and export every package, so nothing in them is encapsulated.

## Five concepts

- **Event.** A record from a sealed interface of domain events, past tense (`CustomerRegistered`).
  Its simple class name is the stored type name, so treat it like a column name: renaming the class
  breaks reads of its history.
- **Tags.** Key-value pairs on an event (`Tags.of("customer", "123")`). They are how events are found
  across types, and they are what a consistency boundary is drawn around.
- **Stream.** `EventStreamId.forContext("customer")` names a stream; an optional purpose
  (`.withPurpose("123")`) splits a context into several. A stream scopes reads and writes; it is not a
  type namespace, so keep event class names unique across the whole store.
- **Query.** `EventQuery.forTypes(CustomerEvent.class).tagged("customer", id)` selects events by type
  and tag, a sealed root standing for its whole hierarchy; `EventQuery.forEvents(types, tags)` builds the
  same query from its two halves at once, and `.or(other)` unites two. `query()` returns a
  `Stream`, but the whole result is already in memory, so bound a read over a large stream with
  `.limit(n)` and page with `page(query, cursor)`, or let a `Projector` do that for you.
- **Conditional append.** `AppendCriteria.of(query, lastReference)` makes an append fail with
  `OptimisticLockingException` when a new event matching the query has landed after the reference.
  That is the DCB idea in one line: decide on the relevant facts, then append only if no new relevant
  fact appeared in the meantime.

## Hello world

Two customers, one stream, tags to tell them apart, and a conditional append that only succeeds while
nothing new is known about the customer it concerns:

```java
import java.util.List;

import org.sliceworkz.eventstore.EventStore;
import org.sliceworkz.eventstore.events.Event;
import org.sliceworkz.eventstore.events.EventReference;
import org.sliceworkz.eventstore.events.Tags;
import org.sliceworkz.eventstore.infra.inmem.InMemoryEventStorage;
import org.sliceworkz.eventstore.query.EventQuery;
import org.sliceworkz.eventstore.stream.AppendCriteria;
import org.sliceworkz.eventstore.stream.EventStream;
import org.sliceworkz.eventstore.stream.EventStreamId;
import org.sliceworkz.eventstore.stream.OptimisticLockingException;

public class HelloEventstore {

    sealed interface CustomerEvent {
        record CustomerRegistered ( String id, String name ) implements CustomerEvent { }
        record CustomerNameChanged ( String id, String name ) implements CustomerEvent { }
    }

    public static void main ( String[] args ) {

        // 1. a store; swap in PostgresEventStorage.newBuilder().buildStore() for production
        try ( EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore() ) {

            // 2. a stream, typed to the sealed interface
            EventStream<CustomerEvent> customers =
                eventStore.getEventStream(EventStreamId.forContext("customer"), CustomerEvent.class);

            // 3. the first append, unconditional: nothing was read, so there is no boundary to check.
            //    The returned events carry the references storage assigned
            customers.append(List.of(
                Event.of(new CustomerEvent.CustomerRegistered("123", "John"), Tags.of("customer", "123")),
                Event.of(new CustomerEvent.CustomerRegistered("124", "Jane"), Tags.of("customer", "124"))));

            // 4. read one customer's history by tag
            EventQuery customer123 = EventQuery.forTypes(CustomerEvent.class).tagged("customer", "123");
            List<Event<CustomerEvent>> history = customers.query(customer123);
            EventReference lastKnown = history.getLast().reference();

            // 5. decide, then append only if nothing about customer 123 has landed since
            customers.append(AppendCriteria.of(customer123, lastKnown),
                Event.of(new CustomerEvent.CustomerNameChanged("123", "Jon"), Tags.of("customer", "123")));

            // 6. the same reference is now stale: a new fact about 123 exists, so this is refused
            try {
                customers.append(AppendCriteria.of(customer123, lastKnown),
                    Event.of(new CustomerEvent.CustomerNameChanged("123", "Jonathan"), Tags.of("customer", "123")));
            } catch ( OptimisticLockingException expected ) {
                System.out.println("refused: " + expected.getMessage());
            }

            customers.query(EventQuery.matchAll()).forEach(System.out::println);
        }
    }
}
```

Two things the snippet leaves out:

- **A boundary at the stream head.** When a decision takes several reads, take `customers.head()`
  first, bound each read with it, and hand the same reference to `AppendCriteria`. It is cheaper to
  check on PostgreSQL and the only sound way to pin more than one read to one moment.
- **Read models.** `Projector.from(stream).into(projection).build().run()` replays a query into a
  `Projection` in batches; `.subscribe()` keeps it running as events arrive, and a bookmark lets it
  resume where it left off.

For PostgreSQL, `PostgresEventStorage.newBuilder().buildStore()` reads a `db.properties` describing a
pooled connection for reads and appends and a direct one for LISTEN/NOTIFY; the
[postgres module README](sliceworkz-eventstore-infra-postgres/README.md) has the template, the
database privileges per init mode, and the backup and migration notes.

## Where to go next

- The [quickstart guide](https://sliceworkz.github.io/posts/eventstore-quickstart/) walks through a
  complete application step by step; the
  [documentation](https://sliceworkz.github.io/categories/eventstore-documentation/) covers the rest.
- [`sliceworkz-eventstore-examples`](sliceworkz-eventstore-examples/src/main/java/org/sliceworkz/eventstore/examples)
  holds runnable examples: append and query, optimistic locking, aggregates, projections and
  savepoints, subscriptions, upcasting.
- [`CLAUDE.md`](CLAUDE.md) is the design record: why each contract is what it is, which alternatives
  were rejected and what was measured. Read it when the javadoc says *what* and you want *why*.

What lands on your classpath: `sliceworkz-eventstore-api` brings Micrometer (the store's meters, with
`Metrics.globalRegistry` as the default registry) and SLF4J, and no Jackson beyond the optional
`jackson-annotations` artifact that Jackson 2 and 3 share. Jackson 3 (`tools.jackson.*`) comes with the
impl and the backends; it is a different groupId and package from Jackson 2, so an application on
Jackson 2 runs both side by side with no conflict.


# Shutting a store down

`EventStore` and `EventStorage` are `AutoCloseable`. A store that lives as long as the process needs
nothing; one created per tenant, per test or per hot reload should be closed, because the Postgres
backend runs two LISTEN/NOTIFY threads holding JDBC connections — and those threads keep the storage
alive, so dropping the reference does not help.

```java
try ( EventStore eventStore = PostgresEventStorage.newBuilder().buildStore() ) {
    ...
}   // monitors stopped, and pools the builder created are closed
```

Closing blocks until the background threads have really stopped, is idempotent and terminal, and never
closes a `DataSource` you supplied yourself. Afterwards every operation throws
`EventStorageClosedException` rather than half-working with dead notifications. The full contract is on
`EventStorage.close()`; `PostgresEventStorageImpl.stop()` is deprecated and delegates to it.

Closing an `EventStore` shuts down that store, not the storage under it — a storage can back several
stores and usually outlives them, so you close it yourself once the stores built on it are closed. The
store from `buildStore()` is the exception: it created the storage and hands you nothing else, so it
closes both. When you build the pair yourself and want one handle, compose it the same way:

```java
EventStorage storage = PostgresEventStorage.newBuilder().build();
try ( EventStore eventStore = EventStore.owning(EventStore.on(storage).build(), storage) ) {
    ...
}
```


# Moving events between stores

`EventStoreImporter` copies events from one storage backend into another, keeping each event's id,
timestamp and idempotency key. Position and transaction are always reassigned by the target, so an
import reproduces the source *order* but not its ordering numbers.

```java
ImportReport report = EventStoreImporter.from(sourceStorage).to(targetStorage).run();

// or only part of it: one logical stream, or every event carrying a tag, selected by the storage
// query rather than read and discarded -- which makes it the way to archive a closed period
ImportReport archived = EventStoreImporter.from(live).to(cold)
    .stream(EventStreamId.forContext("ledger").withPurpose("2024Q1"))
    .run();
```

It works below the serialization layer, so no domain classes are needed and legacy event types are not
upcasted on the way through. A transformation can remap the stream, retag, or rewrite the payload —
which also makes it a stream-cloning and schema-migration tool, with no fidelity guarantee beyond what
the transformation asks for. See the javadoc on `org.sliceworkz.eventstore.migration` for the caveats
that matter: an import is atomic per batch only, nothing is verified afterwards, and one importer
should run at a time per target.


# Testing

`sliceworkz-eventstore-testing` is published for two audiences.

**Testing your application.** `EventStoreFixture` covers the shape every DCB application has — read
the relevant facts, decide, append conditionally:

```java
EventStoreFixture<LearningEvent> fixture =
    EventStoreFixture.inMemory(EventStreamId.forContext("learning"), LearningEvent.class);

fixture.given(event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"))
       .when(stream -> new Registrations(stream).subscribe("123", "abc001"))
       .expectResult(true)
       .expectAppended(event(new StudentSubscribed("123", "abc001"))
                           .tagged("student", "123").tagged("course", "abc001"));
```

The decider gets a real `EventStream`, so the code under test is unmodified production code. Only the
payload and tags are compared — stream, reference and timestamp are assigned by the store.
`whenConcurrently(...)` appends into the window between the decider's query and its own append, which
is the only deterministic way to provoke the conflict a consistency boundary exists to catch:

```java
fixture.given(event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"))
       .whenConcurrently(
           stream -> new Registrations(stream).subscribe("123", "abc001"),
           event(new StudentSubscribed("123", "abc001")).tagged("course", "abc001"))
       .expectOptimisticLockingFailure()
       .matchingTags("course", "abc001");
```

**Implementing your own `EventStorage`.** The same module carries the compliance suite. Implement
`EventStoreBackend`, register it in
`META-INF/services/org.sliceworkz.eventstore.testing.EventStoreBackend`, and point surefire at the
artifact:

```xml
<dependenciesToScan>
    <dependency>org.sliceworkz:sliceworkz-eventstore-testing</dependency>
</dependenciesToScan>
```

Every scenario then runs against your storage. Optional parts of the contract (`importEvents`, table
prefixes, result limits, direct database access) are declared as capabilities and skipped rather than
failed where you do not support them.


# Other

## Contributing

Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.


## License

This project is licensed under the LGPL-3.0 License - see the [LICENSE](LICENSE) file for details.
External components on which this project depends are listed in the [NOTICE](NOTICE) file.

