# File Storage for Eventstore

A single-process, embedded event storage backed by an append-only binary log. One JVM owns a
directory and is the only writer in it.

```java
try ( EventStore eventStore = FileEventStorage.newBuilder()
        .directory("eventstore-data")
        .buildStore() ) {
    ...
}
```

## When this is the right backend

Where a single process genuinely owns its data: embedded applications, edge deployments, desktop
tools, single-tenant installs, and tests that want durability without a container.

For anything else, use `sliceworkz-eventstore-infra-postgres`. See
[docs/postgres-as-an-event-store.md](../docs/postgres-as-an-event-store.md) for what each backend
gets by construction and what each gives up.

## What single-writer buys

PostgreSQL assigns `event_position` from a `bigserial`, so the number is handed out when the
`INSERT` runs rather than when the transaction commits. Positions therefore have gaps, and a lower
position can become visible after a higher one — which is why that backend needs a second ordering
column, a `pg_snapshot_xmin` visibility barrier, and a per-stream advisory lock so a consistency
check and its insert cannot interleave.

One writer assigns a position *at* commit. Positions are dense from one; every event of a call
shares a transaction number and holds consecutive positions; the transaction number strictly
increases between calls. So the transaction number is a monotone non-decreasing step function of the
position, and ordering by `(tx, position)` and by `position` are the same order. Nothing has to be
withheld from a reader, nothing is skipped, and the boundary check and the write are one step under
one lock.

References are still compared as the whole tuple, because a caller can hand this store a reference
it never issued — from another stream, or from another store via `EventStoreImporter`.

## What it will not do

- **Run in two processes.** The directory is locked exclusively; a second storage fails to open. No
  sidecar, no read replica, no rolling deploy with two instances overlapping.
- **Back itself up.** Stop the process and copy the directory. There is no PITR and no replication.
- **Answer ad-hoc queries.** No SQL, no `EXPLAIN`, no tooling.
- **Let you correct data out of band.** The log is append-only with no compaction, so retention
  pruning and removing a poison event are not possible. `Capability.RAW_STORAGE_ACCESS` is reported
  unsupported for exactly this reason.
- **Keep its indexes on disk.** They are in heap, rebuilt from the log at open. That is the ceiling
  on store size, and it shows up as open time and resident memory.
- **Authenticate anyone.** Anyone with the directory has every event.

## Configuration

```java
EventStorage storage = FileEventStorage.newBuilder()
        .directory(Path.of("/var/lib/myapp/events"))
        .durability(Durability.SYNC)          // default; one flush per append
        .segmentSize(128L * 1024 * 1024)      // default
        .resultLimit(10_000)
        .name("orders")
        .build();
```

`Durability.OS` skips the flush. A committed event then survives the JVM crashing but not the
machine losing power — and recovery is weaker in a way worth reading the javadoc for, because
batches can reach the device out of order, so a torn batch causes everything after it to be
discarded as well.

## Personal data

Pair a durable log with a durable key store, or every protected value reads as erased after a
restart:

```java
Path directory = Path.of("/var/lib/myapp/events");
EventStore eventStore = FileEventStorage.newBuilder()
        .directory(directory)
        .shredding(new FileShreddingKeyStore(directory))
        .buildStore();
```

That colocation puts the keys beside the ciphertext they protect, so anyone with the directory has
both. It is fine for development and tests; it is not a deployment posture. Where an erasure has to
hold up against someone holding the disk, keep keys in a KMS or an HSM behind `ShreddingKeyStore`.

## Directory layout

```
eventstore-data/
  LOCK                    held exclusively for the storage's lifetime
  MANIFEST                format version and hints; never the source of truth
  events/
    0000000000.seg        rolled segments of the append-only log
  bookmarks.log
  keys.bin                only when a FileShreddingKeyStore is opened here
```

## Durability and recovery

A batch is written as its event records followed by a **commit trailer**, and a batch with no valid
trailer never happened. The trailer carries a checksum over every byte of every record in the batch
plus the batch's own transaction number, first position and count.

That whole-batch checksum is why there is no separate "committed up to here" file. A trailer sitting
later in the file does not prove the earlier bytes landed — after a power loss the tail of a file
need not be a prefix of what was written. Per-record checksums catch a *garbled* record; only a
checksum spanning the batch catches a record that was never written but whose slot holds plausible
old bytes. So the trailer gives all-or-nothing with one file, one flush, and no ordering question
between two writes.

Recovery truncates to the end of the last committed batch and discards everything after it,
including later segments. `LogRecoveryTest` asserts this by truncating the log at every byte offset
in turn, and separately by flipping a bit at every offset in turn: the store must always report
exactly one of the prefixes that were committed, or refuse to open.
