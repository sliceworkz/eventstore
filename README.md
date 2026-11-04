[![ci build - mvn package](https://github.com/sliceworkz/eventstore/actions/workflows/ci.yaml/badge.svg)](https://github.com/sliceworkz/eventstore/actions/workflows/ci.yaml)
[![Status: Beta](https://img.shields.io/badge/status-beta-orange)](#project-status)
[![Maven Central](https://img.shields.io/maven-central/v/org.sliceworkz/sliceworkz-eventstore-bom)](https://central.sonatype.com/search?q=g:org.sliceworkz)
[![Quickstart](https://img.shields.io/badge/Quickstart%20Guide-blue)](https://sliceworkz.github.io/posts/eventstore-quickstart/)
[![Docs](https://img.shields.io/badge/Documentation-purple)](https://sliceworkz.github.io/categories/eventstore-documentation/)
[![License: LGPL-3.0](https://img.shields.io/badge/license-LGPL--3.0-green)](LICENSE)

# Sliceworkz EventStore

**An event store for Java that implements the [Dynamic Consistency Boundary (DCB)](https://dcb.events/specification/)
specification — on PostgreSQL in production, in memory for development and tests.**

Classic event stores make you choose your consistency boundaries up front: one stream per aggregate, and
every decision has to fit inside one of them. DCB drops that constraint. Events are tagged with whatever
they are about, a decision reads exactly the facts it needs — across types, across entities — and the
append succeeds only if none of those facts changed in the meantime. The consistency boundary is drawn per
decision, not per class.

```java
EventQuery customer = EventQuery.forTypes(CustomerEvent.class).tagged("customer", "123");

EventReference head = stream.head().orElse(null);               // 1. pin the moment you decide on
var history = stream.query(customer.until(head));               // 2. read the relevant facts
// ... decide ...
stream.append(AppendCriteria.of(customer, head),                // 3. append, unless a new fact about
    Event.of(new CustomerNameChanged("123", "Jon"), Tags.of("customer", "123")));   //    customer 123 landed since
```

That is the whole idea. The rest of this library is what it takes to run it in production.

## What you get

| | |
|---|---|
| **DCB, fully compliant** | Tag-based queries, conditional appends with `AppendCriteria`, `OptimisticLockingException` on a conflict — atomic under concurrency on every backend, held to it by a shared compliance suite |
| **Type-safe streams** | Events are records under a sealed interface; `EventStream<CustomerEvent>` is checked by the compiler, and a sealed root in a query stands for its whole hierarchy |
| **Three backends** | PostgreSQL 16+ for production, in-memory for tests, and in-memory persisted to JSON files for local development that survives a restart |
| **Projections** | `Projector` replays a query into a read model in batches, resumes from a bookmark, keeps running on new appends, and reports its lag |
| **Schema evolution** | `@LegacyEvent` upcasters, chained version by version; `@EventName` to rename a class without touching history |
| **Idempotent appends** | Per-event idempotency keys, so a retried command stores nothing twice |
| **Personal data** | Crypto-shredding: a `Shreddable<T>` field is encrypted per data subject, and erasing the subject destroys the key — the event log is never rewritten |
| **Operations** | Health reporting, an observability SPI you bind to Micrometer, OpenTelemetry or anything else, leases for leader election, and an importer for moving or archiving events between stores |
| **Testing** | A `given/when/then` fixture for your own code, and a run-it-yourself compliance kit for third-party backends |

The api module depends on SLF4J and nothing else you would notice: no metrics library, no Jackson 2.

The [Sliceworkz Event Modeling framework](https://github.com/sliceworkz/eventmodeling) builds commands,
read models, automations and feature slices on top of this store. Use this library directly when you want
the store; use the framework when you want an application structure around it.


# Getting started

## Requirements

- **Java 21** or later at runtime
- For PostgreSQL: **PostgreSQL 16** or later, with the `btree_gin` extension available
- To build from source: JDK 25 and Maven 3.6.3+ (the build still targets release 21)

## Add the dependency

Every artifact is on Maven Central under `org.sliceworkz`. Import the BOM, then pick a backend:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.sliceworkz</groupId>
            <artifactId>sliceworkz-eventstore-bom</artifactId>
            <version>0.12.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.sliceworkz</groupId>
        <artifactId>sliceworkz-eventstore-api</artifactId>
    </dependency>
    <!-- for development and tests; use sliceworkz-eventstore-infra-postgres in production -->
    <dependency>
        <groupId>org.sliceworkz</groupId>
        <artifactId>sliceworkz-eventstore-infra-inmem</artifactId>
    </dependency>
</dependencies>
```

A backend brings the implementation along, so `EventStore.on(storage).build()` finds it on its own. The
PostgreSQL backend declares the JDBC driver `provided`: add `org.postgresql:postgresql` yourself.

## Hello world

Two customers in one stream, told apart by their tags, and a conditional append that succeeds only while
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

        // a store; PostgresEventStorage.newBuilder().buildStore() in production
        try ( EventStore eventStore = InMemoryEventStorage.newBuilder().buildStore() ) {

            EventStream<CustomerEvent> customers =
                eventStore.getEventStream(EventStreamId.forContext("customer"), CustomerEvent.class);

            // an unconditional append: nothing was read, so there is nothing to check
            customers.append(List.of(
                Event.of(new CustomerEvent.CustomerRegistered("123", "John"), Tags.of("customer", "123")),
                Event.of(new CustomerEvent.CustomerRegistered("124", "Jane"), Tags.of("customer", "124"))));

            // decide on what is known about customer 123, as of the stream head
            EventQuery customer123 = EventQuery.forTypes(CustomerEvent.class).tagged("customer", "123");
            EventReference head = customers.head().orElse(null);
            List<Event<CustomerEvent>> history = customers.query(customer123.until(head));

            // append, unless a new fact about 123 landed after the head
            customers.append(AppendCriteria.of(customer123, head),
                Event.of(new CustomerEvent.CustomerNameChanged("123", "Jon"), Tags.of("customer", "123")));

            // the same decision again is now stale: refused
            try {
                customers.append(AppendCriteria.of(customer123, head),
                    Event.of(new CustomerEvent.CustomerNameChanged("123", "Jonathan"), Tags.of("customer", "123")));
            } catch ( OptimisticLockingException expected ) {
                System.out.println("refused: " + expected.getMessage());
            }

            // a change to customer 124 would not have conflicted: it is outside the boundary
            customers.query(EventQuery.matchAll()).forEach(System.out::println);
        }
    }
}
```

One rule to keep from this: **bound the read with `until(head)`, but hand `AppendCriteria` the query
unbounded.** `AppendCriteria.of(customer123.until(head), head)` deems nothing after the head relevant, so it
would admit every append and never raise a conflict.

The [quickstart guide](https://sliceworkz.github.io/posts/eventstore-quickstart/) builds a complete
application step by step.


# Core concepts

- **Event** — a record under a sealed interface, named in the past tense (`CustomerRegistered`). Its simple
  class name is what is stored, so treat it like a column name; `@EventName` keeps the stored name when you
  rename the class.
- **Tags** — key-value pairs on an event (`Tags.of("customer", "123")`). How events are found across types,
  and what consistency boundaries are drawn around.
- **Stream** — `EventStreamId.forContext("customer")`, optionally split with `.withPurpose("123")`. A stream
  scopes reads and writes; keep event names unique across the whole store all the same.
- **Query** — `EventQuery.forTypes(...).tagged(...)`, combined with `.or(...)`, bounded with `.until(ref)`
  and `.limit(n)`. `query()` returns a `List`, read in full: bound a large read, page it with
  `page(query, cursor)`, or let a `Projector` do it.
- **Conditional append** — `AppendCriteria.of(query, reference)`: refused with `OptimisticLockingException`
  when an event matching the query landed after the reference.


# Choosing a backend

| backend | artifact | use it for | notes |
|---|---|---|---|
| PostgreSQL | `sliceworkz-eventstore-infra-postgres` | production | LISTEN/NOTIFY for live projections, advisory locks for the DCB check, indexes for tag queries. Configure with a `DataSource` or a `db.properties`; see the [module README](sliceworkz-eventstore-infra-postgres/README.md) for privileges, init modes and backup/restore |
| In-memory | `sliceworkz-eventstore-infra-inmem` | tests, demos | Behaves exactly like PostgreSQL, but is an unindexed scan: never size an application from it |
| In-memory, file-backed | `sliceworkz-eventstore-infra-inmem-fs` | local development | The in-memory store, persisted to JSON files so it survives a restart |

```java
EventStore store = PostgresEventStorage.newBuilder()
    .dataSource(pool)                      // or a db.properties on the classpath
    .monitoringDataSource(directPool)      // LISTEN/NOTIFY needs a direct connection
    .prefix("tenant1_")
    .buildStore();
```


# A tour of the rest

## Read models

```java
Projector.from(stream).into(new CustomerListProjection())
    .bookmarkAs("customer-list")   // resume where it left off
    .subscribe()                   // keep running as events arrive
    .build()
    .run();
```

A `Projection` is a query plus a `when(Event<E>)` handler. The projector reads in batches of 500, places a
bookmark after each, and records how far it has *read* past events its query skips, so its lag is measured
to the stream head. `BatchAwareProjection` lets a projection commit its own work per batch; a savepoint
query (`initQuery()`) starts a replay from the last snapshot-like event instead of the beginning.

## Evolving events

```java
@LegacyEvent(upcaster = CustomerRegisteredV1ToV2.class)
record CustomerRegisteredV1 ( String name ) implements LegacyCustomerEvent { }

@EventName("CustomerRegistered")   // renamed class, same stored name
record CustomerSignedUp ( String id, String name ) implements CustomerEvent { }
```

Upcasters chain (`V1 → V2 → V3`), queries and consistency boundaries over the current type include the
legacy events behind it, and a misdeclared chain fails when the stream is opened rather than on a read.

## Idempotent appends

```java
stream.append(AppendCriteria.of(query, head),
    Event.of(new PaymentReceived(...), tags).withIdempotencyKey("payment-4711"));
```

A retry whose keys are all stored is swallowed: nothing written, an empty result returned. A batch mixing
stored and new keys is refused with `IdempotencyKeyConflictException`, so a half-retried decision never
lands silently.

## Personal data and erasure

```java
record CustomerRegistered ( String id, Shreddable<ContactDetails> contact ) implements CustomerEvent { }

EventStore store = InMemoryEventStorage.newBuilder()
    .shredding(new InMemoryShreddingKeyStore())    // PostgresShreddingKeyStore in production
    .buildStore();

store.erase("customer", "123", ErasureReason.of("GDPR art.17 request #4711"));
```

A `Shreddable` value is encrypted (AES-256-GCM) under a key per data subject and category. Erasure destroys
the keys; the stored events never change, and reads afterwards see the value as `Shredded`. An audit view
reports what was erased without ever exposing key material. Read models keep their own copies, so rebuild
them or keep personal data out of them.

## Moving and archiving events

```java
EventStoreImporter.from(live).to(cold)
    .stream(EventStreamId.forContext("ledger").withPurpose("2024Q1"))
    .run();
```

Copies events between storages below the serialization layer — ids, timestamps and idempotency keys
preserved, order reproduced — with an optional transformation for remapping or migrating. It is also the
way to move a store to another PostgreSQL cluster.

## Operating it

- **Health** — `storage.health()` answers per aspect (database round trip, notification channels, a
  visibility stall, pool waits), never throws, and returns within its timeout.
- **Observability** — `EventStoreObserver` sees every operation as a scope with its outcome (an append
  answers `Appended`, `Conflicted` or `Duplicated`). Bind it to the metrics or tracing library you use.
- **Leases** — `requestLease`/`releaseLease` with fencing tokens, for electing one processor among several
  instances.
- **Closing** — `EventStore` and `EventStorage` are `AutoCloseable`. Close a store you create per tenant or
  test; closing a store leaves a storage you supplied alone, and `buildStore()` closes both.


# Testing

`sliceworkz-eventstore-testing` (in `test` scope) serves two audiences.

**Your application.** `EventStoreFixture` runs your unmodified code against a real stream:

```java
fixture.given(event(new CourseDefined("abc001", "Java basics", 12)).tagged("course", "abc001"))
       .when(stream -> new Registrations(stream).subscribe("123", "abc001"))
       .expectAppended(event(new StudentSubscribed("123", "abc001"))
                           .tagged("student", "123").tagged("course", "abc001"));
```

`whenConcurrently(...)` appends into the window between your read and your append — the one deterministic
way to prove a consistency boundary catches the conflict it exists for.

**Your own backend.** Implement `EventStoreBackend`, register it as a service, and point surefire's
`dependenciesToScan` at the artifact: every compliance scenario then runs against your storage, with
optional capabilities skipped rather than failed.


# Modules

| artifact | what it is |
|---|---|
| `sliceworkz-eventstore-api` | The interfaces you code against, and the `EventStorage` SPI a backend implements |
| `sliceworkz-eventstore-impl` | The implementation: streams, serialization, upcasting, shredding, observations. Pulled in by every backend |
| `sliceworkz-eventstore-infra-postgres` | PostgreSQL storage |
| `sliceworkz-eventstore-infra-inmem` | In-memory storage |
| `sliceworkz-eventstore-infra-inmem-fs` | In-memory storage persisted to JSON files |
| `sliceworkz-eventstore-serialization-json` | JSON codecs for stored events and bookmarks, for building a file-based backend |
| `sliceworkz-eventstore-testing` | The test fixture and the backend compliance kit |
| `sliceworkz-eventstore-bom` | Bill of materials pinning all of the above |

Every jar declares an `Automatic-Module-Name` equal to its root package (`org.sliceworkz.eventstore`,
`org.sliceworkz.eventstore.infra.postgres`, …) for use on the module path.


# Learn more

- [Quickstart guide](https://sliceworkz.github.io/posts/eventstore-quickstart/) and
  [documentation](https://sliceworkz.github.io/categories/eventstore-documentation/)
- [Runnable examples](sliceworkz-eventstore-examples/src/main/java/org/sliceworkz/eventstore/examples):
  append and query, optimistic locking, aggregates, projections and savepoints, subscriptions, upcasting
- [PostgreSQL backend](sliceworkz-eventstore-infra-postgres/README.md): configuration, privileges,
  migrations, backup and restore
- [Benchmarks](sliceworkz-eventstore-benchmark/README.md): what reads and writes cost, and how stream design
  changes it
- [Design notes](docs/design) and [`CLAUDE.md`](CLAUDE.md): why each contract is what it is, and the
  alternatives that were rejected
- [DCB specification](https://dcb.events/specification/)


# Project status

Beta. The API is settling but can still change between
minor versions, and every change is listed in the [release notes](https://github.com/sliceworkz/eventstore/releases).

## Supply chain and security

Every release ships a CycloneDX SBOM — per module on Maven Central (`cyclonedx` classifier) and aggregated
on the [GitHub release](https://github.com/sliceworkz/eventstore/releases) — attested to the jars it
describes:

```bash
gh attestation verify sliceworkz-eventstore-api-<version>.jar --repo sliceworkz/eventstore --predicate-type https://cyclonedx.org/bom
```

Every build scans for known vulnerabilities, a release with a fixable High or Critical one is stopped, and
released versions are re-scanned daily. [SUPPLY-CHAIN.md](SUPPLY-CHAIN.md) explains how it fits together;
[SECURITY-RELEASES.md](SECURITY-RELEASES.md) is the procedure when a scan fails. Report vulnerabilities
privately, as described in [SECURITY.md](SECURITY.md).

## Contributing and license

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md). Licensed under the
[LGPL-3.0](LICENSE); third-party components are listed in [NOTICE](NOTICE).
