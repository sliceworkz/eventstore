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

**EventQuery:**
- Defines which events to retrieve from storage
- Can match all (`EventQuery.matchAll()`), none (`EventQuery.matchNone()`), or specific criteria
- Uses `EventTypesFilter` to filter by event types and `Tags` to filter by tags
- Supports an optional "until" reference to query up to a specific point in history
- Created via `EventQuery.forEvents(eventTypesFilter, tags)`

**AppendCriteria:**
- Controls optimistic locking when appending events
- Contains an `EventQuery` and an optional `EventReference` for the expected last event
- If new matching events are found after the reference, append fails with `OptimisticLockingException`
- Use `AppendCriteria.none()` for simple appends without locking
- Use `AppendCriteria.of(eventQuery, Optional.of(reference))` for conditional appends

**Projection:**
- Combines an `EventQuery` with an `EventHandler`
- Processes all events matching the query criteria
- Used for building read models from event streams

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
List<Event<CustomerEvent>> existingEvents = stream.query(
    EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123"))).toList();
EventReference lastRef = existingEvents.getLast().reference();

stream.append(
    AppendCriteria.of(
        EventQuery.forEvents(EventTypesFilter.any(), Tags.of("customer", "123")),
        Optional.of(lastRef)
    ),
    Event.of(new CustomerNameChanged("Jane"), Tags.of("customer", "123"))
);
```

## Testing

**Base Test Class:**
Tests should extend `AbstractEventStoreTest` which provides:
- `createEventStorage()`: Abstract method to provide storage implementation
- `eventStore()`: Returns the configured EventStore instance
- `waitBecauseOfEventualConsistency()`: Helper for async scenarios
- Automatic setup/teardown via JUnit 5 lifecycle

**Test Structure:**
The `sliceworkz-eventstore-tests` module contains shared test scenarios that run against all storage implementations (in-memory and PostgreSQL). Each infrastructure module includes these tests to verify compliance.

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
3. Append new events with `AppendCriteria` containing the same query and last reference
4. If new events matching the query exist after the reference, the append fails

## PostgreSQL Specific Notes

- Table schema can be prefixed (useful for multi-tenancy or isolation)
- Database initialization performed via `.initializeDatabase()` on builder
- Uses HikariCP for connection pooling
- Separate DataSource for monitoring queries (optional, defaults to main DataSource)
- Tests use Testcontainers for isolated PostgreSQL instances
