# Is PostgreSQL an event store?

There is a recurring argument that a relational database has no business under an event store: that
sequence numbers come out gapped and out of order, that optimistic concurrency is silently wrong,
that `LISTEN/NOTIFY` is too fragile to build subscriptions on, and that getting any of it right costs
so much accidental complexity that a purpose-built store would have been cheaper.

Most of that argument is **correct about the hazards and wrong about the conclusion** — at least for
this library, which has met each hazard and can show where. This page answers it hazard by hazard,
and then does the thing that settles it: points at a second backend, in this repository, that is a
purpose-built append-only log passing the same 28 compliance scenario classes. Where that backend
gets a property for free, this page says so plainly. Where it gives up something PostgreSQL was
quietly providing, it says that too, at more length.

## The short version

| The hazard | What PostgreSQL needs | What a single-writer log needs |
|---|---|---|
| Gapped positions, visible out of order | a second ordering column and a visibility barrier | nothing |
| Two appends racing one consistency boundary | a per-stream advisory lock | nothing beyond the writer lock |
| Notifying subscribers | `LISTEN/NOTIFY`, two monitor threads, a health gauge | a method call |
| Atomic multi-event append | a transaction | a commit trailer |
| More than one process | free | **impossible** |
| Backup, PITR, replication, HA | free | **absent** |
| Ad-hoc queries, tooling, operators who know it | free | **absent** |

The first four rows are the critique's case, and it wins them. The last three are why PostgreSQL is
still the recommended production backend.

---

## 1. Gapped positions, and events becoming visible out of order

**The hazard is real and it is the sharpest one.** `event_position` is a `bigserial`, so the number
is handed out when the `INSERT` runs, not when the transaction commits. Two things follow. Positions
have gaps wherever a transaction rolled back. Worse, a transaction that took a *lower* position can
commit *later*, so a reader tailing the log by position can pass a position that is not yet visible
and never come back for it. Events are not lost, but a subscriber silently skips them — the worst
failure mode there is, because nothing anywhere reports it.

**What this library does.** Every event carries a second ordering column, `event_tx`, which is
`pg_current_xact_id()`. Reads are bounded by a visibility barrier:

```sql
WHERE event_tx < pg_snapshot_xmin(pg_current_snapshot())
```

which withholds any event whose transaction is still in flight, so a reader can never pass one.
Every boundary in the backend — the cursor, the `until` bound, the optimistic-locking check —
compares the whole `(tx, position)` tuple rather than the position alone, because the two orders
genuinely disagree. `ConcurrentAppendVisibilityTest` in the TCK holds every backend to it.

**What it costs, and this is not a small footnote.** `pg_snapshot_xmin` is the oldest transaction id
still running *anywhere in the PostgreSQL cluster*. A long-running **writing** transaction — an ETL
job, a migration, an `idle in transaction` connection that wrote before going idle, even one in a
different database of the same cluster — freezes what this store can read, for as long as it lasts.
Nothing fails and nothing is logged: reads simply stop advancing, and everything appears at once when
the blocker ends. Read-only transactions are harmless at any isolation level, which makes the hazard
narrow but not theoretical. `PostgresVisibilityStallTest` demonstrates it end to end, and CLAUDE.md
carries the diagnostic query.

**What the log backend does.** Nothing, because there is nothing to do. One writer assigns a position
at commit, under the lock it already holds. Positions are dense and start at one. Every event of a
call shares a transaction number and holds consecutive positions, and the transaction number strictly
increases between calls — so **the transaction number is a monotone non-decreasing step function of
the position**, and ordering by `(tx, position)` and ordering by `position` are the same order. There
is no event to withhold from a reader, so there is no barrier, so there is no stall.

The tuple comparison stays anyway, because a caller can hand the store a reference it never issued —
from another stream, or from another store by way of `EventStoreImporter`. Monotonicity means such a
comparison is still upward-closed in the position, so a boundary remains a range over a sorted
sequence whoever minted the reference.

---

## 2. Optimistic concurrency that is silently wrong

**The hazard is real, and most implementations have it.** A DCB consistency check asks whether any
event matching a filter has arrived since a reference. Under PostgreSQL's default `READ COMMITTED`,
each statement fixes its snapshot when it starts, so two appends at the same boundary both find it
empty, both insert, and both commit. The invariant is gone and the store reported success to both
callers. No row lock can prevent it: at the moment of the check the conflicting row is a *phantom* —
it does not exist yet, and you cannot lock what is not there.

**What this library does.** A conditional append takes a transaction-scoped
`pg_advisory_xact_lock`, keyed on a hash of the table prefix and the stream, **as its own statement
before the INSERT**. That last part is load-bearing rather than stylistic: folded into the INSERT's
`WHERE`, the statement would block with its stale snapshot already taken and the check would still
miss the other appender's row — the same race with a lock in front of it.

Unconditional appends take no lock, so bulk ingestion stays parallel. Measured cost: about 5% against
8 concurrent writers over 1000 streams. `SERIALIZABLE` was tried and rejected on measurements, not
taste: 86% serialization failures and a third of the throughput, because a DCB boundary check is
always a scan of the log's tail, which is exactly where every writer writes.

**What the log backend does.** The check and the write happen under one lock, in one method, so they
are one indivisible step by construction. That is the same property the in-memory backends get from
`synchronized`, made durable.

`ConcurrentOptimisticLockingTest` holds every backend to it: several threads append at one boundary
from a common start signal, and exactly one must win. Note that the rest of `OptimisticLockingTest`
is single-threaded — it proves the check *reads* correctly and says nothing about atomicity, which is
how a backend can pass all of it and still violate the boundary in production.

---

## 3. `LISTEN/NOTIFY` is a weak foundation for subscriptions

**The hazard is real but it is the least serious of the four**, for a reason the critique usually
skips: in this library **a notification is an optimisation, never a source of truth**. `Projector`
reads from a bookmark it places itself. A lost notification costs latency; it cannot cost an event.
Anything that must not lose progress belongs behind a `Projector`, and that is documented rather than
implied.

What this library still had to do about it: a separate monitoring `DataSource`, because
`LISTEN/NOTIFY` does not survive a transaction pooler and "pooled works, direct is firewalled" is an
ordinary misconfiguration. Two monitor threads with exponential backoff. A `notifications.up` gauge
registered at construction, so the series exists reading zero from the moment the storage does — a
gauge that only appears once notifications work is no use for alerting on notifications not working.
A bounded startup wait that is fatal on expiry, because an event-sourced application that is not told
about appends serves stale data with nothing in its own logs to say so.

And a trigger that had to be moved from `FOR EACH ROW` to `FOR EACH STATEMENT`: a 1000-event append
was queueing 1000 notifications, all but one discarded after being built as JSON, written to the
cluster-wide queue, sent over the wire and parsed.

**What the log backend does.** Calls the listeners. There are no threads, no queue, no channel, no
startup wait, no health gauge, and no shutdown that could fail to notice it should stop.

---

## 4. Accidental complexity

This is where the critique lands hardest, and the honest answer is to show the bill rather than argue
about it. The PostgreSQL backend is roughly 4,500 lines of main source, over a third of it
explanatory comment. Some of what that comment has to explain:

- `btree_gin` is a *trusted* extension, but installing it needs `CREATE` on the **database**, which
  is not `CREATE` on the schema — so the ordinary locked-down role creates every table, index,
  function and trigger and then cannot create the extension, and because the scripts are one
  transaction the whole schema rolls back.
- Schema scripts need a per-prefix advisory lock, because `CREATE TABLE IF NOT EXISTS` is not atomic
  against a concurrent creator and 64 of 80 instances starting together failed to start.
- A duplicate must be recognised by the index name the server reports, never by matching message
  text — because the table prefix is caller-supplied, and one containing the word "idempotency" would
  make a substring match swallow every unique violation the table can raise.
- Identifier length is a coupling: PostgreSQL truncates at 63 bytes, so the prefix cap of 32 exists
  to keep the longest generated index name at 61.
- Trigger shape has to be compared against the catalog on startup, because a statement-level trigger
  bound to a stale row-level function body does not raise — it emits a notification with every field
  null, and live updates stop with nothing thrown and nothing logged.

None of this is gratuitous. Each item is a real failure that was found and fixed. But it is a fair
summary of the critique to say: **that is a lot of budget spent earning properties a purpose-built
log has by construction**, and pointing at the budget is a legitimate argument.

---

## 5. What the log backend gives up

Everything above is the case for the critique. This section is why PostgreSQL is still the
recommended production backend, and it is not a short list.

1. **More than one process. Ever.** The directory is locked exclusively. No sidecar, no reporting
   tool, no read replica, and no rolling deploy with two instances briefly overlapping. This is not a
   limitation to be engineered around later; it is the assumption the whole design rests on. An
   application that might ever need two instances needs a backend with a server behind it.
2. **Backup, point-in-time recovery, replication, standby, failover.** PostgreSQL gives `pg_dump`,
   WAL archiving, streaming replicas and a choice of managed providers. The log backend gives "stop
   the process and copy the directory".
3. **Ad-hoc queries.** No SQL, no joins against business tables, no `EXPLAIN`, no exploring a
   payload from a prompt at three in the morning.
4. **Out-of-band correction.** The log is append-only with no compaction, so pruning for retention or
   surgically removing a poison event is impossible. This is exactly `Capability.RAW_STORAGE_ACCESS`,
   and the backend reports it unsupported.
5. **Indexes on disk.** PostgreSQL's are paged and proportional to the data. The log backend's are in
   heap, which puts a real ceiling on store size and shows up as open time and resident memory.
6. **Operational surface.** No `pg_stat_*`, no connection pooling, no query planner to reason with,
   and — the part that is hardest to buy — no engineer who already knows how to run it.
7. **Security posture.** No authentication, no roles, no network boundary, no encryption at rest.
   Anyone with the directory has every event, and if the key store is colocated, every key too.
8. **Thirty years of hardening.** PostgreSQL has been beaten on by fsync bugs, torn pages,
   filesystem quirks and hostile hardware since before most event stores existed. The log backend has
   whatever its own tests cover — which is why those tests truncate the log at every byte offset in
   turn and flip a bit at every offset in turn, and why writing them found a real bug that a
   single-reopen assertion could not see.

---

## So which should you use?

**Use PostgreSQL** for anything with more than one instance, anything that needs backup and
replication you did not write, and anything an operations team has to run. The hazards above are
real, they are met, they are tested per backend, and the residual one — the visibility stall — is
documented with the query that diagnoses it.

**Use the file backend** where a single process genuinely owns its data: embedded applications, edge
deployments, desktop tools, single-tenant installs, and tests that want durability without a
container.

**And notice what makes the choice cheap.** Both are `EventStorage` implementations behind the same
SPI, held to the same 28 compliance scenario classes, and `EventStoreImporter` moves events between
them. The argument that a relational database is the wrong substrate for an event log is worth
taking seriously — and the way to take it seriously is to keep the substrate a decision you can
revisit, rather than one baked into the application.

## Where to look

| | |
|---|---|
| The barrier and the tuple comparison | `PostgresEventStorageImpl.java`, the `query` and cursor-boundary sections |
| The advisory lock and why it is its own statement | `PostgresEventStorageImpl.java`, the append-lock section |
| The visibility stall, end to end | `PostgresVisibilityStallTest` |
| The commit trailer and the recovery scan | `EventLog.java` |
| Recovery, truncated at every byte offset | `LogRecoveryTest` |
| The compliance scenarios both backends satisfy | `sliceworkz-eventstore-testing`, package `...testing.tck` |
