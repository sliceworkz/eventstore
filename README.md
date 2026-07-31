[![ci build - mvn package](https://github.com/sliceworkz/eventstore/actions/workflows/ci.yaml/badge.svg)](https://github.com/sliceworkz/eventstore/actions/workflows/ci.yaml)
[![Status: Beta](https://img.shields.io/badge/status-beta-orange)](#)
[![Quickstart](https://img.shields.io/badge/Quickstart%20Guide-blue)](https://sliceworkz.github.io/posts/eventstore-quickstart/)
[![Docs](https://img.shields.io/badge/Documentation-purple)](https://sliceworkz.github.io/categories/eventstore-documentation/)

# About Eventstore

A DCB (Dynamic Consistency Boundary) compliant EventStore implementation in Java

Persistence options: Postgres and In-Memory (for dev and demo purposes)

Supports all features described described by the [DCB Specification](https://dcb.events/specification/):
- Tagging of Events for dynamic retrieval
- Optimistic locking / conditional append via AppendCriteria


# Getting started

Step-by-step introduction with the [quickstart guide](https://sliceworkz.github.io/posts/eventstore-quickstart/) or the [documentation](https://sliceworkz.github.io/categories/eventstore-documentation/)


# Moving events between stores

`EventStoreImporter` copies events from one storage backend into another, keeping each event's id,
timestamp and idempotency key. Position and transaction are always reassigned by the target, so an
import reproduces the source *order* but not its ordering numbers.

```java
ImportReport report = EventStoreImporter.from(sourceStorage).to(targetStorage).run();
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

