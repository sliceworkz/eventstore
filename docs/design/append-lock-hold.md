# The append lock is held for the server's work, never across the network

**A conditional append on PostgreSQL sends its lock, its checked INSERT and its COMMIT to the server as
one pipeline, so the stream's advisory lock is held for what the server does and nothing else.** The
statement is `SET LOCAL lock_timeout = '<n>ms'; SELECT pg_advisory_xact_lock(?); INSERT … ; COMMIT`,
sent behind one sync (`PostgresEventStorageImpl.conditionalAppendStatement`). pgjdbc opens the
transaction with its own `BEGIN` ahead of it.

## Why

Every conditional append to a stream takes that stream's advisory lock (see the postgres module's
`CLAUDE.md`), so on a stream many writers append to, the lock is held back to back and the time it is held
*is* the stream's throughput ceiling. Sent as separate calls, the server grants the lock in the first round
trip, receives the INSERT in the second and the COMMIT in the third: it holds the lock for two round trips
on top of its own work, with the holder `idle in transaction` waiting on the client in between. Same host,
two round trips are a small share of the hold. A network hop away they are most of it, and the ceiling
becomes the network rather than the database.

## What it costs and buys, measured

`write-contention-one-stream` (100.000 events, `PER_ENTITY`, every writer appending to one stream with no
logical conflict), PostgreSQL 18 in a container on a four-core VM, the forks on the same machine, two forks
of five two-second iterations. Latency was added with `delay-proxy` (the benchmark's TCP proxy, 500µs each
way), measured at **1.7ms round trip** against 0.08ms direct. The server's CPU per decision is the
container's cgroup CPU over each measured iteration (`ServerCpuProfiler`), divided by the operations.

`append-type-and-tag`, decisions per millisecond, and the server's CPU per decision:

| threads | same host, before | same host, after | +1.7ms RTT, before | +1.7ms RTT, after |
|---|---|---|---|---|
| 1 | 0.459 · 1.36ms | 0.561 · 1.24ms | 0.133 · 2.46ms | 0.240 · 1.69ms |
| 4 | 0.571 · 1.39ms | 0.878 · 1.08ms | 0.231 · 1.71ms | 0.786 · 1.14ms |
| 8 | 0.562 · 1.39ms | 0.916 · 1.05ms | 0.228 · 1.79ms | 0.777 · 1.21ms |
| 16 | 0.541 · 1.44ms | 0.827 · 1.14ms | 0.224 · 1.98ms | 0.780 · 1.19ms |

- **The assumption holds.** Before, the hot stream is flat from 4 to 16 writers in both settings, and the
  added latency takes its ceiling from ~0.56 to ~0.23 decisions per millisecond: the hold grows from
  ~1.8ms to ~4.4ms, two round trips of 1.7ms.
- **After, the ceiling a network hop away is 3.4× higher**, and above what the same host managed before.
- **The server also works less per decision**, by a third with the latency. Part of that is protocol
  work no longer done (two syncs and their `ReadyForQuery` per append), part is that the statement is no
  longer described again on every execution (below).
- **`append-none` takes no lock and keeps its two round trips**, but binds its NULL typed too, so it is
  not a pure control: 2.47 → 2.74 same host at sixteen writers (server CPU 438 → 393µs), 2.59 → 2.66
  with the latency — within the error bars.
- `decide-then-append` gains less, since its unbounded decide read runs outside the lock: 0.20 → 0.40 at
  sixteen writers with the latency.
- A single writer gains too: one round trip per conditional append instead of three, so 7.5ms → 4.2ms per
  decision with the latency — a conditional append is now cheaper on the wire than an unconditional one,
  which still commits separately.
- Caveats, in the suite's own terms: a container on a developer-class VM, the client and the server
  sharing four cores, a userspace proxy rather than kernel shaping, and relative errors up to ~20% on some
  rows. Read the direction and the rough magnitude, which no error bar here comes near.

## What keeps it one round trip, and how it came apart

pgjdbc pipelines a statement string only while it can bind it without asking the server. Once a statement
is server-prepared — after the driver's `prepareThreshold` executions, five by default — a parameter bound
as of unknown type, which is what `setObject(i, null)` binds, makes it describe the statement again on
every execution and sync after each of its parts. The lock, the INSERT and the COMMIT then travel one round
trip each, exactly as separate calls. An event without an idempotency key binds a NULL, so a pipeline that
binds it with `setObject` measures the same as no pipeline at all past its fifth execution — 0.225 against
0.228 decisions per millisecond with the latency, sixteen writers — while a probe of its first few
executions shows one round trip. Every NULL is therefore bound typed (`setNull(i, Types.VARCHAR)`: the
values that can be absent are text).

`PostgresAppendLockHoldTest` keeps it so: it appends past the prepare threshold through a TCP proxy that
delays 40ms each way, watches `pg_locks` for the stream's append lock, and fails if the session holding it
is ever seen `idle in transaction`. It fails on separate calls and on the pipeline with an untyped NULL; a
control holds the lock in a session that waits, which the same observer must see.

## Why it means exactly what the separate calls meant

- **The lock is still its own statement before the INSERT.** The server executes a pipeline's statements
  in order, so the INSERT starts, and takes its READ COMMITTED snapshot, only after the lock statement
  returned — after the previous holder committed.
- **The wait is bounded as before**: `SET LOCAL lock_timeout` precedes the lock in the same transaction.
- **A conflict inserts no row**; committing a transaction that wrote nothing stores nothing, which is what
  the rollback that followed it did. The lock is released at that commit.
- **A failure** — the wait timing out, a duplicate idempotency key — aborts the transaction, the server
  skips the rest of the pipeline up to its sync (the COMMIT included) and releases the locks of the
  aborted transaction at the failure. The caller's catch rolls back and classifies it as before; the
  idempotency lookup runs on the same connection after the rollback.
- pgjdbc tracks the transaction state the server reports, so the connection's own `commit()` and
  `rollback()` after a pipelined COMMIT send nothing.

## Rejected alternatives

- **The same pipeline on an autocommit connection**, leaning on the server to run everything up to the
  sync as one implicit transaction. It would save pgjdbc's `BEGIN`, which already travels in the same
  pipeline, and nothing else; it makes `SET LOCAL` and a transaction-scoped lock depend on how the server
  scopes an implicit transaction in the extended protocol, where an explicit one states it.
- **Folding the lock into the INSERT** (a lock call in its `WHERE`): one statement, but the INSERT takes
  its snapshot before it blocks, which is the race the separate lock statement exists to prevent.
- **Keeping the statement off server preparation** (`prepareThreshold` 0), which would also keep pgjdbc
  from describing it again: it pipelines, but the cursor-bearing check then loses its cached probe plan,
  which `PostgresConditionalAppendCheckTest` pins as deliberate.
- **A stored function doing lock, check and insert**: one round trip as well, but it moves the check's SQL
  into the schema, where every change to the check becomes a migration, and still needs the COMMIT
  pipelined behind it.

## What it does not change

The lock is still held for the server's whole work on the append — the check, the insert, the trigger and
the commit's WAL flush — so a hot stream still has a ceiling: the server's own time per append, which on
this machine is ~1.2–1.4ms of CPU. That ceiling is bought off by stream layout, as before; this removes
the network from it.
