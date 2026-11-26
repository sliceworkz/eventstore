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

### On the reference machine

Published runs under `sliceworkz-eventstore-benchmark/results/0.15.0-SNAPSHOT/`: the pipeline as
`write-contention-one-stream-ext` and `write-contention-one-stream-ext-latency`, separate calls as the same
profiles with the suffix `-separate-calls`, and a back-to-back near pair with the suffix `-rerun` (the code as it is with `append` put back as separate calls,
kept beside the published runs as the measured alternative). 100.000 events, `PER_ENTITY`, every writer
appending to one stream with no logical conflict; an external PostgreSQL 18.6 on a 16-core Ryzen AI 7 350
with `fsync` and `synchronous_commit` on, the client on the same host; four forks of five two-second
iterations, relative errors 0.4–5%. The latency runs put `tc qdisc … netem delay 0.85ms` on loopback:
**1.86ms round trip** (`ping`, mean of 20). The server's CPU per decision is its cgroup CPU over each
measured iteration (`ServerCpuProfiler`), divided by the operations.

`append-type-and-tag`, decisions per millisecond · the server's CPU per decision:

| threads | near, separate calls | near, pipeline | +1.86ms, separate calls | +1.86ms, pipeline |
|---|---|---|---|---|
| 1 | 1.636 · 422µs | 1.813 · 376µs | 0.144 · 1,132µs | 0.312 · 710µs |
| 4 | 1.777 · 489µs | 1.857 · 505µs | 0.244 · 901µs | 1.326 · 425µs |
| 8 | 1.852 · 455µs | 1.655 · 585µs | 0.237 · 1,007µs | 1.975 · 441µs |
| 16 | 1.718 · 504µs | 1.580 · 623µs | 0.233 · 1,086µs | 1.882 · 494µs |

`append-none` (no lock, its path unchanged but for the typed NULL) is the control:

| threads | near, separate calls | near, pipeline | +1.86ms, separate calls | +1.86ms, pipeline |
|---|---|---|---|---|
| 1 | 3.386 · 131µs | 3.603 · 110µs | 0.260 · 524µs | 0.260 · 557µs |
| 4 | 5.064 · 131µs | 5.113 · 129µs | 1.057 · 187µs | 1.058 · 185µs |
| 8 | 5.086 · 130µs | 4.925 · 165µs | 2.105 · 151µs | 2.103 · 152µs |
| 16 | 4.948 · 149µs | 4.774 · 203µs | 4.139 · 151µs | 4.141 · 150µs |

- **A network hop away, the pipeline multiplies a hot stream's ceiling by eight.** Separate calls cap it
  at 0.23–0.24 decisions per millisecond from four writers up: a hold of ~4.2ms, two round trips of 1.86ms
  plus the server's ~0.5ms. The pipeline reaches 1.98 at eight writers — 8.3× — and 1.88 at sixteen.
  The control is identical in both runs to the third digit, so the whole difference is the append.
- **The server works less than half as hard per decision a hop away** (1.0–1.1ms → 0.44–0.49ms from
  four writers up). Part of that is the protocol work saved, part is per-decision background work spread
  over eight times as many decisions; the two are not separated here.
- **What is left of the hold is the server's own work, about 0.5ms per append**: sixteen writers a hop
  away settle at the pace of one writer near the server (0.55ms per decision).
- **`decide-then-append` gains as much a hop away once enough writers overlap their reads**: separate
  calls hold it to ~0.24 decisions per millisecond whatever the writer count, since every decision ends
  in the same 4.2ms hold; the pipeline lets it scale, 5.5× at sixteen writers (0.239 → 1.318).
- **One writer a hop away waits less for a conditional append (3.2ms) than for an unconditional one
  (3.85ms)**: the unconditional append still sends its INSERT and its COMMIT as two round trips.
- **Near the server the pipeline gains about 10% from four writers up**, measured as a back-to-back pair
  (`-rerun`, separate calls first, the pipeline straight after), in which the control agrees within 2% at
  every writer count:

  | threads | separate calls | pipeline | | control, separate calls | control, pipeline |
  |---|---|---|---|---|---|
  | 1 | 1.839 · 359µs | 1.916 · 350µs | +4% | 3.637 · 111µs | 3.648 · 108µs |
  | 4 | 1.877 · 449µs | 2.085 · 428µs | +11% | 5.028 · 128µs | 4.998 · 129µs |
  | 8 | 1.836 · 458µs | 2.018 · 446µs | +10% | 5.000 · 132µs | 5.019 · 132µs |
  | 16 | 1.730 · 493µs | 1.914 · 481µs | +11% | 5.009 · 148µs | 4.956 · 148µs |

  The round trips inside the hold are tens of microseconds here, and the gain is about what dropping two
  of them and the server's per-statement protocol work is worth on a ~0.5ms hold; the server's CPU per
  decision falls 2–5%. The first near pair, 3.5 hours apart, showed a loss of 8–11% at eight and sixteen
  writers; its control had drifted too (−3% and −4%, with its CPU per append up 27–36%), and the
  back-to-back pair does not reproduce it, so that pair measured the hours between its runs more than the
  code. Both pairs stay published; the back-to-back one is the comparison.
- `decide-then-append` near the server gains little, its unbounded read being 2.3–3.1ms of server CPU
  per decision: 0%, +2%, +5% and +12% at 1, 4, 8 and 16 writers in the back-to-back pair.
- The server's CPU per operation is only meaningful where the server is busy: at one writer a hop away it
  does a few hundred operations a second, and its background work (WAL writer, checkpointer, statistics)
  is spread over few operations — 524–557µs per unconditional append there against 110–131µs near.

### Before and after, on a four-core VM

`write-contention-one-stream` against PostgreSQL 18 in a container on a four-core VM, the forks on the same
machine, two forks of five two-second iterations. Latency was added with `delay-proxy` (the benchmark's TCP
proxy, 500µs each way), measured at **1.7ms round trip** against 0.08ms direct. Same CPU measurement.

`append-type-and-tag`, decisions per millisecond, and the server's CPU per decision:

| threads | same host, before | same host, after | +1.7ms RTT, before | +1.7ms RTT, after |
|---|---|---|---|---|
| 1 | 0.459 · 1.36ms | 0.561 · 1.24ms | 0.133 · 2.46ms | 0.240 · 1.69ms |
| 4 | 0.571 · 1.39ms | 0.878 · 1.08ms | 0.231 · 1.71ms | 0.786 · 1.14ms |
| 8 | 0.562 · 1.39ms | 0.916 · 1.05ms | 0.228 · 1.79ms | 0.777 · 1.21ms |
| 16 | 0.541 · 1.44ms | 0.827 · 1.14ms | 0.224 · 1.98ms | 0.780 · 1.19ms |

- **Separate calls put the network in the hold.** Before, the hot stream is flat from 4 to 16 writers in
  both settings, and the added latency takes its ceiling from ~0.56 to ~0.23 decisions per millisecond:
  the hold grows from ~1.8ms to ~4.4ms, two round trips of 1.7ms.
- **After, the ceiling a network hop away is 3.4× higher**, and above what the same host managed before.
- **The server also works less per decision**, by a third with the latency. Part of that is protocol
  work no longer done (two syncs and their `ReadyForQuery` per append), part is that the statement is no
  longer described again on every execution (below).
- **`append-none` takes no lock and keeps its two round trips**, but binds its NULL typed too, so it is
  not a pure control: 2.47 → 2.74 same host at sixteen writers (server CPU 438 → 393µs), 2.59 → 2.66
  with the latency — within the error bars.
- `decide-then-append` gains less, since its unbounded decide read runs outside the lock: 0.20 → 0.40 at
  sixteen writers with the latency.
- **Which part buys what**, at eight writers, decisions per millisecond · server CPU per decision. Two
  further runs separate the pieces: the pipeline bound with an untyped NULL (so pgjdbc splits it again),
  and separate calls with the NULL bound typed.

  | | same host | +1.7ms RTT |
  |---|---|---|
  | separate calls, untyped NULL (before) | 0.562 · 1.39ms | 0.228 · 1.79ms |
  | separate calls, typed NULL | 0.605 · 1.30ms | 0.235 · 1.76ms |
  | pipeline, untyped NULL | 0.617 · 1.26ms | 0.225 · 1.86ms |
  | pipeline, typed NULL (after) | 0.916 · 1.05ms | 0.777 · 1.21ms |

  Neither half alone moves the latency case, and each moves the same host by under 10%: the typed NULL
  saves the server a describe per execution, the untyped pipeline a little protocol work. Together they
  are the one round trip, and the gain is the two together.
- Caveats: a container on a developer-class VM, the client and the server sharing four cores, a
  userspace proxy rather than kernel shaping, and relative errors up to ~20% on some rows. Not published;
  read the direction and the rough magnitude, which no error bar here comes near.

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
the commit's WAL flush — so a hot stream still has a ceiling: the server's own time per append, about
0.5ms on the reference machine. That ceiling is bought off by stream layout, as before; this removes the
network from it.

The unconditional append is untouched: it takes no lock, and still sends its INSERT and its COMMIT as two
round trips, so a single writer a hop away waits two round trips for it against one for a conditional
append.
