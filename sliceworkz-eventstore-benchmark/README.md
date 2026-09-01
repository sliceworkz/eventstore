# Benchmark suite

A capacity-characterisation harness for the eventstore. Its job is to answer *how does this store
behave at N events, with M concurrent writers, under these query shapes, in a store that also holds
other domains* — with numbers you can publish, reproduce, and point a user at when they ask how to
design their streams. Regression detection is a by-product, not the goal.

Nothing here runs during an ordinary build. The module is `src/main` only, so `mvn package` compiles
and shades it and runs no benchmark.

## Quick start

```bash
mvn -q clean install -DskipTests                    # from the repository root
cd sliceworkz-eventstore-benchmark

java -jar target/sliceworkz-eventstore-benchmark-*.jar list
java -jar target/sliceworkz-eventstore-benchmark-*.jar doctor --profile=smoke-postgres
java -jar target/sliceworkz-eventstore-benchmark-*.jar jmh --profile=smoke
```

`smoke` is seconds, in memory, and measures nothing worth quoting. It is the thing to run after
touching the suite: if smoke is not green, no number from a longer run is worth reading.

## Subcommands

| | |
|---|---|
| `list` | the profiles, their size, rough runtime, and whether they need Docker |
| `doctor [--profile=…]` | opens each target and reports whether this machine can run it |
| `workloads` | the workload catalogue a profile's `workloads:` draws on |
| `provision --profile=… [--force]` | builds (or reuses) the corpora a profile needs |
| `dry-run --profile=…` | invokes each workload once and checks it measures something |
| `jmh --profile=… [--out=…] [--yes]` | the operation-level benchmarks |
| `load --profile=…` | sustained load against a *growing* store |
| `report [--run=…] [--baseline=…] [--publish] [--force]` | render, diff against a baseline, publish |
| `compare --a=… --b=…` | diff two **configurations** measured here |

A profile is a YAML file. Pass a name to use one that ships on the classpath, or a path to use one of
your own — the file's basename must match the `name:` inside it.

## The two comparisons, and why they are separate commands

`report --baseline` asks *did this change?* It wants everything the same except time, and **refuses**
when the corpus, the targets or the environment differ. That refusal is the feature: a percentage
between two runs on different machines is not a statement about the store, and nothing about the two
numbers says so.

`compare --a --b` asks *which configuration is better?* The rules invert. It wants the corpus or the
targets to differ — that is the experiment — and refuses when the **environment** does. It names what
varied, and says so when more than one thing did, because a diff of two runs differing in five
properties is a shrug with numbers attached.

```bash
java -jar target/*.jar jmh --profile=stream-design-tagged     --out=target/benchmark/tagged
java -jar target/*.jar jmh --profile=stream-design-per-entity --out=target/benchmark/per-entity
java -jar target/*.jar compare --a=target/benchmark/tagged --b=target/benchmark/per-entity
```

## Profiles that ship

Paired profiles are listed together: run both, then `compare` them. They differ in one corpus
property and are otherwise identical — same seed, workloads and targets — which is what makes the
percentage between them mean something. Where the property under test *is* the volume, as in the
plan-cache pair, the entity count moves with it so that events-per-entity stays fixed: a tag has to
be as selective in the small corpus as in the large one, or the pair measures selectivity rather than
size.

A profile that only ever existed to decide something is deleted once it has. `cursor-boundary-form`
compared the two spellings of the cursor boundary, found the row comparison 2.9x faster on a cursor
walk, and was removed along with the losing spelling — a profile that cannot run is worse than no
profile, and the result lives in `CLAUDE.md` and in `PostgresCursorBoundaryTest`.

| profile | the question | runtime |
|---|---|---|
| `smoke`, `smoke-postgres` | does the harness work | seconds |
| `dcb-cost-curve` | what the DCB check costs, and how it grows with OR-ed facts | ~15 min |
| `dcb-plan-cache` ⇄ `dcb-plan-cache-small` | whether planning every conditional append pays for itself, and at what store size the answer flips | ~25 min / ~12 min |
| `write-contention-spread` / `-one-stream` / `-one-boundary` | where throughput saturates, and how much is the advisory lock versus conflict-retry | ~20 min each |
| `read-shapes` ⇄ `crowded-store` ⇄ `crowded-database` | what a store holding other domains costs, and separately what sharing a database costs | ~20 min each |
| `stream-design-tagged` ⇄ `stream-design-per-entity` | which stream design to pick | ~40 min each |
| `metrics-cost` | what the library's own meters cost — three targets in one run, no `compare` needed | ~10 min |
| `shredding-cost` | what crypto-shredding costs per event and per new data subject | ~8 min |
| `upcasting-cost` ⇄ `read-shapes` | what reading history through upcasters costs | ~10 min |
| `replay-throughput` | what a read-model rebuild costs | ~10 min |
| `live-latency` | append → subscriber, and append → committed read-model row | ~5 min |
| `ingest-saturation` | sustained appends against a store that is growing | ~10 min |
| `large-tier` ⇄ `read-shapes` | the same reads at ten million events, on an external server | ~17 min + provisioning |
| `large-tier-writes` | what an append costs at ten million events, with and without per-append planning | ~1¾ h + provisioning |

There is deliberately **no `full` profile**. A profile names one corpus, and a corpus has one volume,
so nothing can span the three tiers. The recommended sequence for an overnight run is the table above
in order, `large-tier` last, with `provision --profile=large-tier` done beforehand.

## Volume tiers, and where the time goes

10³ / 10⁵ / 10⁷ events. A corpus is **content-addressed**: the SHA-256 of its spec becomes its table
prefix, so a corpus *is* its prefix and six profiles asking for the same one share a single
provisioning. Ten million events is minutes of bulk import and is then measured against for days.

Reuse is also the most dangerous thing the suite does, since a wrongly reused corpus produces numbers
that are entirely plausible and describe the wrong data. Three checks stand between those outcomes,
and a corpus is rebuilt unless all three pass: the fingerprint is present (by construction), the
manifest was written by this generator version, and the manifest's event count matches the store's
actual row count. Bump `CorpusFingerprint.GENERATOR_VERSION` by hand whenever the generator changes
what it writes — otherwise "reusable" quietly means "generated by some older code".

In-memory corpora persist for exactly as long as the store is open, so `provision` against `inmem`
generates and discards, and says so. The large tier is a PostgreSQL proposition.

## Where the numbers go

Scratch runs land in `target/benchmark/` and are gitignored. Most runs are experiments and committing
them would bury the ones that matter.

A curated run is **published** into `results/<version>/<profile>/`, which is in the repository, so its
numbers are reviewable in a pull request and quotable from the docs — which the project's older ad-hoc
figures were not, having been measured once and lost.

```bash
java -jar target/*.jar report --run=target/benchmark/read-shapes --publish
```

Publishing refuses a run that was measured against a Testcontainers PostgreSQL (stock defaults on
whatever the host happened to be), whose store drifted past the profile's `maxDrift` (2% unless the
profile declares otherwise — see *How mutation is handled*), **that carries a measurement whose
relative error is above 10%**, or whose suite
version is unknown. `--force` overrides those, and the reasons stay recorded in the report, so a
caveated baseline stays caveated rather than becoming an unqualified number. A run that **failed a
correctness check** is never publishable under any flag: its numbers describe work that did not
happen.

## Adding a profile

Drop a YAML file in `src/main/resources/profiles/`. The basename must match the `name:` inside it.
`.yaml` is outside the licence plugin's include set, so there is no header to maintain.

```yaml
name: small-store-crowded-database
description: >
  A ten-thousand-event store sharing a database with three million-event stores.

corpus:
  volume: 10000
  streamDesign: TAGGED          # or PER_ENTITY
  composition: MULTI_STORE      # CLEAN | MULTI_DOMAIN | MULTI_STORE | BOTH
  payload: REALISTIC            # SLIM | REALISTIC | WIDE_TAGS | FAT | SHREDDED | LEGACY
  entityCount: 500
  neighbourVolumes: [ 1000000, 1000000, 1000000 ]
  seed: 20260823

targets:                        # several targets over one corpus; each measured separately
  - backend: POSTGRES           # INMEM | POSTGRES
    server: EXTERNAL            # TESTCONTAINERS | EXTERNAL
    metrics: OFF                # OFF | CAPPED | UNLIMITED
    schemaMode: VALIDATE        # ENSURE | VALIDATE | NONE
    shredding: false
    resultLimit: null

jmh:
  workloads: [ append-none, append-type-and-tag, query-by-tag-needle ]
  threads: [ 1, 8 ]
  collision: spread             # spread | one-stream | one-boundary
  forks: 3
  warmupIterations: 3
  measurementIterations: 5
  iterationSeconds: 10
  maxDrift: 0.02                # how much an append workload may grow the store; see below

load:                           # optional, and a list: the pair is the measurement
  - scenario: notify-latency    # write-saturation | mixed | notify-latency | end-to-end-latency
    writers: 1
    readers: 0
    collision: spread
    targetRatePerSecond: 200    # omit to saturate
    rampUpSeconds: 10
    durationSeconds: 120
```

`workloads: []` means every workload the corpus supports. `workloads` lists the catalogue; a workload
that needs a particular payload profile is rejected at setup rather than quietly measuring something
else.

**`MULTI_DOMAIN` and `MULTI_STORE` are different mechanisms.** `MULTI_DOMAIN` writes five other
bounded contexts into the *same table* at five times the volume, which is what moves index
selectivity, table size and heap correlation — i.e. what actually slows a query down. `MULTI_STORE`
creates other prefixed stores, which are *other tables* in the same database: shared buffers, WAL,
autovacuum and the cluster-wide notification queue, but not one row of extra work for any query. The
report does not conflate them and neither should a reading of it.

Measured, they land as far apart as that suggests: `crowded-store` moves two of twelve read shapes by
5.4× and 3.9×, `crowded-database` moves none of them outside the run-to-run band. Read the second as
*coexistence is free*, not as *sharing a database is free* — the neighbour stores are written once
during provisioning and idle for the whole run, so nothing of theirs competes for a buffer, dirties a
page, or holds a transaction id. A neighbour under load is a question neither profile asks, and the
one that would hurt most is not throughput at all: a long-running writing transaction anywhere in the
cluster stalls this store's reads outright, through `pg_snapshot_xmin`.

## PostgreSQL targets

`TESTCONTAINERS` needs a Docker daemon and nothing else. It is sound for comparing two runs on one
machine and weak as a published number, because it is a container running stock defaults.

`EXTERNAL` reaches a server configured outside the suite through `db.properties`, which the
`DataSourceFactory` looks for in the working directory and up to two parents (or at
`-Deventstore.db.config=<path>`). Published numbers come from there, because the settings that decide
them are then deliberate rather than inherited. The database needs the `btree_gin` extension, and
creating it requires `CREATE` on the **database**, not on the schema — see the postgres module's
README for the privilege table.

Every report records what makes its numbers mean anything: PostgreSQL version and about twenty-five
settings, CPU model, core count, RAM, and the JVM. Two runs whose environments differ are not
comparable, and the comparators say so rather than reporting a difference in hardware as a change in
the store.

## Reading a report

Each run writes `report.json` (the record) and `report.md` (a rendering of it) beside JMH's own JSON.

- **Derived tables come first**, one set per target, because those are the questions the suite exists
  to answer and a reader should not have to do the division. A derived table that cannot be computed
  from this run's rows is omitted, never estimated.
- **Read the `useful/s` column, not the score**, on any contended profile. A store can raise its
  operations per second while lowering the work done; only the conflict rate beside the throughput
  says which happened.
- **A relative error above about 10%** means the measurement is too noisy to compare against
  anything, and publishing now refuses a run carrying one, naming each offending row. The sentence
  had been in this report since the first render and nothing enforced it, because the publish gate
  lived on the manifest and a manifest has never seen a row: `large-tier-writes` was published with
  `append-type-and-tag` at **121%** — an error bar wider than the figure it qualifies — two lines
  above this rule. `--force` still publishes, and the reasons stay in the report.
- **Plans are captured on any server whose log can be read, external ones included.** A container's
  output needs nothing; an external server needs `logging_collector = on` plus the grants below,
  after which the suite reads the plans back through `pg_read_binary_file`. `doctor` runs the whole
  chain — resolve the log, enable `auto_explain`, issue a statement, read its plan back — so it says
  which of the two you will get, and names the missing piece, before you spend an hour finding out.

  ```sql
  GRANT pg_monitor TO <role>;                                    -- pg_current_logfile()
  GRANT EXECUTE ON FUNCTION pg_stat_file(text, boolean) TO <role>;
  GRANT EXECUTE ON FUNCTION pg_read_binary_file(text, bigint, bigint, boolean) TO <role>;
  GRANT pg_read_server_files TO <role>;   -- Debian/Ubuntu: log_directory is outside PGDATA
  GRANT SET ON PARAMETER session_preload_libraries, auto_explain.log_min_duration,
        auto_explain.log_analyze, auto_explain.log_buffers, auto_explain.log_timing,
        auto_explain.log_format, auto_explain.log_nested_statements TO <role>;
  ```

  Two of those mislead and are worth stating plainly. `pg_read_server_files` governs *which paths*
  may be read and not whether the functions may be called, so a role holding it still gets
  `permission denied for function pg_read_binary_file` without the `EXECUTE` grants. And
  `auto_explain.*` are placeholder settings until the module loads, so each wants its own
  `GRANT SET ON PARAMETER`; granting `session_preload_libraries` alone gets you to the next refusal.

  This used to be containers only, which had it exactly backwards: a container run is the one thing
  the publisher *refuses*, so every published baseline carried reconstructions alone. Capture then
  overturned the suite's largest finding: the real 190× a DCB check costs at ten million events is
  the *custom* plan bitmapping an entity's whole history, not the table scan the reconstruction
  showed. Evidence that load-bearing should not be the weaker kind — though capture is only as good
  as the workload it is attributed to, and reading one workload's plan as another's cost a whole
  storage setting before a third target caught it (see `large-tier-writes`).
- **Each plan carries a verdict.** A sequential scan, a bitmap that outgrew `work_mem`, a sort that
  spilled to disk, JIT charged to a query that did not need it — all four are recognisable by pattern,
  all four were present in this suite's own published plans, and all four went unremarked until
  somebody read forty lines of `EXPLAIN` by hand. The report now names them in the heading and says
  underneath what each means. They are observations about the plan, never guesses about why the
  planner chose it.
- **The reads' plans are real too, and the reconstructions are kept beside them.** Every read
  workload is re-run under the same `auto_explain` window as the appends and the plan the server
  logged for the store's own `SELECT` is captured; the hand-written statements matching each shape
  stay in the report as the shape of the predicate.

  This was the quiet half of the reconstruction problem, and it took a run to see. The read
  reconstructions inline their tag arrays as literals and are planned from real column statistics,
  so they can report an execution time the *whole measured operation* fits inside: `read-shapes` had
  the needle tag query reconstructing to **0.267ms of execution alone** against a **0.205ms**
  measured operation — statement, round trip and deserialisation included. A plan cannot explain
  something it is slower than. Nothing in the report could have caught that, because there was
  nothing captured to check the reconstruction against.

  Nothing is written, so unlike the append capture there is nothing to undo afterwards. A workload
  issuing several statements — `query-cursor-walk`, `replay-batches` — is explained by its *last*,
  which is the deepest cursor and the only one of the set worth a plan.
- **The DCB check's plans are real.** After the last measurement, the report turns on `auto_explain`,
  runs each conditional-append workload, and reads the plan the server logged for the statement the
  store itself issued — then deletes the events that capture appended, so the corpus stays the size
  its manifest records. On any target whose log can be read, per the grants above.

  **It runs the workload the way the profile does, collision mode included, on one thread.** That is
  what makes a contention profile's captured plan describe a statement that profile actually issues:
  addressed at the stream and the boundary its measured appends were. The capture used to be
  hardwired to `spread`, and the symptom was quiet — the three `write-contention-*` runs came back
  with byte-identical captured plans, same parameters and same cursor, while their measured
  throughputs differed fourfold. That reads as "the plans are the same, so the gap is elsewhere",
  and it was really "the capture reproduced none of the three arrangements". One thread is all it
  can be: contention between writers is not a property of a plan, so these say where a profile's
  appends go and never what they wait for.

  Worth knowing why this is not also a reconstruction. The hand-written version of these came out
  *inverted* against the measurements: a shape planning as a sub-millisecond index-only scan measured
  slowest, one planning as an eight-millisecond sequential scan measured nearly fastest. The
  predicate was right and the parameterisation was not — the store binds its tag arrays and cursor as
  JDBC parameters and re-uses the statement, so PostgreSQL settles on a plan built against default
  selectivity, while inlining the same values as literals earns one built from real statistics. That
  difference alone decides index-versus-scan, which is the only question the plans are for.
- **Each captured plan says whether it is the generic or the custom one, and both are shown.**
  PostgreSQL holds both for a re-used prepared statement: the *custom* plan is re-planned from the
  actual parameter values, the *generic* one is planned once against default selectivity. From the
  tenth execution it compares their **estimated** costs and adopts the generic plan if it looks no
  worse — so neither is automatically the one a benchmark loop runs on, and the report prints each
  plan's measured ms/op beside it so the pair can be matched up by `cost=`. Both are pinned with
  `plan_cache_mode` rather than reached by counting executions, which is how the capture got this
  wrong once already: eight warm-up invocations plus one left it one execution short of the switch,
  and it reported the last custom plan — 1.0ms — for an operation measuring 15.9ms.

  **What that comparison does to a DCB check is a finding in its own right**, and the reason both
  plans are kept. The expected result of the check is *no rows*, while PostgreSQL prices a
  `NOT EXISTS` by how soon it expects to find one. Adding OR-ed facts makes the generic plan expect
  a match sooner, so its estimate *falls* while the custom plan's — built from real tag
  statistics — rises. Measured on the 100k `dcb-cost-curve` corpus they cross between two and three
  facts: at two the server keeps a `BitmapOr` over the tag index (1.5ms/op), and at three it adopts
  a generic plan that sequentially scans all 100.000 rows for a row that is not there (17ms/op),
  and stays there at four, five and ten. An eleven-fold cliff, from one more fact in the decision.

  Where the flip lands past three facts is not stable across runs, and the two published
  `dcb-cost-curve-ext` runs prove it: same profile, corpus, server and settings, and widths four to
  ten came out on opposite sides — one run recovered to an index-scan generic plan, the other sat
  flat on the sequential-scan floor from two facts up, each internally stable. The estimated-cost
  comparison at those widths is close enough to the crossover that the statistics `ANALYZE` happens
  to sample decide the side. Treat any width past one fact as at risk rather than reading the band
  as fixed; the postgres module's `CLAUDE.md` records both regimes.
- **Load results carry correctness checks**, and a run that fails one is reported as unsound. Events
  in must equal events out; nothing may be projected twice.

## What the numbers do not mean

- **In-memory is not a deployment target.** It is there to answer "what does the library cost on top
  of the database", which no PostgreSQL measurement alone can separate out. Its append numbers in
  particular are a monitor, not a durable write — and it is *not* a fast-store baseline that
  PostgreSQL is expected to lose to. See the next section, which is the single easiest way to
  misread a report from this suite.
- **A conditional append that comes out faster than an unconditional one is noise**, whatever the
  backend: it does strictly more work. The report says so where it happens.
- **`.limit(n)` counts stored events, not returned ones.** Against a `LEGACY` corpus a page of 500
  comes back as ~750, because a `BasketCheckedOut` upcasts into two events. The throughput figure is
  per stored event.
- **A shredded corpus is reproducible in content but not byte-identical** across provisionings.
  Sealed envelopes can only come out of the store's own serializer, so that profile appends rather
  than bulk imports, and the store assigns the ids and timestamps.
- **Reads on PostgreSQL sit behind `pg_snapshot_xmin`.** A long-running *writing* transaction
  anywhere in the cluster stalls what this store can read, silently. If a run's numbers collapse with
  nothing else changing, check `pg_stat_activity` for `backend_xid IS NOT NULL` before blaming the
  store — the main `CLAUDE.md` has the query.

## The in-memory target has no index, and that decides half its numbers

**It is a linear scan.** `InMemoryEventStorageImpl` holds one `List` and answers a query by streaming
it and matching in Java (`eventlog.stream()`, then a `Stream.limit` on top). There is no index of any
kind, so its cost is **how far into the log the scan must walk**, and not how many events come back.

That makes the obvious sanity check — *"in-memory must beat PostgreSQL on every read, or the harness
is measuring itself"* — wrong, and it was written into the original plan for this suite. At 10⁵ events
inmem loses more than half the read shapes, and loses them by two orders of magnitude. From
`read-shapes` (PG18, Testcontainers, one thread, ops/ms):

| workload | limit? | inmem | postgres:18 | inmem ÷ pg |
|---|---|---|---|---|
| `query-by-entity-cold` | none | 0.104 | 8.064 | **0.013** |
| `query-by-tag-needle` | none | 0.155 | 4.886 | **0.032** |
| `query-last-event` | 1, backwards | 1.394 | 14.665 | 0.095 |
| `query-by-tag-swathe` | 500 | 0.124 | 0.591 | 0.21 |
| `query-by-multi-tag` | 500 | 0.098 | 0.370 | 0.26 |
| `query-by-entity-hot` | none | 0.054 | 0.046 | 1.17 |
| `query-cursor-walk` | 500 | 0.471 | 0.179 | 2.6 |
| `query-by-type` | 500 | 2.850 | 0.892 | 3.2 |
| `query-stream-page` | 500 | 3.321 | 0.873 | 3.8 |
| `query-by-or-groups` | 500 | 1.460 | 0.354 | 4.1 |
| `query-by-id` | map lookup | 1928 | 47.1 | 41 |
| `query-wildcard` | 500 | 5.013 | 0.054 | 93 |

The rule that fits every row: **inmem wins exactly where a limit fills before the scan gets far.**
`query-by-tag-needle` carries no limit and matches ten events, so it walks all 100.000 at ~64ns each;
`query-stream-page` fills its 500 immediately and stops. A limit is not enough on its own —
`query-by-tag-swathe` and `query-by-multi-tag` carry one and still lose, because their matches are too
sparse to fill it early. PostgreSQL's GIN index makes the selective end of that range *cheaper*, which
is the whole point of having one.

So the real check, and what a broken harness would look like:

- **inmem beating PostgreSQL on limited reads over dense matches, and losing on selective unlimited
  ones, is correct.** Both directions are expected.
- **inmem losing on `query-stream-page`, `query-by-id` or `query-wildcard` would be a fault** — those
  are a bounded walk from the head and a map lookup, with nothing for an index to improve.
- **The two backends landing within ~20% of each other on a read returning thousands of events is also
  correct**, and it is the clearest thing in the table: `query-by-entity-hot` returns 6.876 events and
  comes out 0.054 against 0.046, because at that size both are paying the same per-event
  deserialisation and the storage difference washes out. That per-event cost is ~2µs — see below.

**The practical consequence for anyone using the library**: prototyping tag-query cost against the
in-memory store tells you nothing about what it costs in production, and points the wrong way. The
in-memory backends are for tests and for separating library cost from database cost, which is what
this suite uses them for.

## The library's own per-event cost is roughly 2µs, and it is most of a page read

Subtracting the server's own execution time from the measured operation, `read-shapes` on PG18:

| workload | events | server (captured) | measured | per event |
|---|---|---|---|---|
| `query-by-entity-hot` | 6,876 | 5.68 ms | 21.32 ms | **2.27 µs** |
| `query-stream-page` | 500 | 0.42 ms | 1.05 ms | **1.25 µs** |

**Trust the first row and treat the second as a lower bound**, because the subtrahend is not as solid
as it looks. A captured plan's `actual time` is produced under `auto_explain` with timing and buffers
on, which costs the server real work per node per row — so it *overstates* the statement's true cost,
and subtracting it *understates* what is left. On `query-by-entity-hot` that hardly matters: the plan
sorts 6,876 rows and its own work dwarfs the instrumentation. On a 500-row page it matters a lot —
the same statement reconstructed with literals came back at 0.18 ms rather than 0.42 ms, which would
put the page's per-event cost at 1.95 µs instead of 1.25 µs.

So: **~2 µs per event**, and between **60% and 83%** of a 500-event page's wall time is JDBC plus
deserialisation rather than PostgreSQL, with the spread being how much of the server's reported time
is the observer. Either way the conclusion is the same and it is the useful one — bounding a read with
`EventQuery.limit(n)` is worth more than it looks, because the cost being bounded is mostly per-event
and downstream of the query.

It also confirms the harness is doing the one thing it must: `query()` returns a stream whose rows
storage has already read but whose deserialisation is lazy, so a workload consuming it without a
terminal operation would time the SQL and skip the serde. Two independent derivations landing on the
same order is what says `stream.forEach(bh::consume)` is really deserialising.

## How much a number can move between two runs of the same profile

**About 10–15%, which is larger than most of the error bars beside it.** Two `read-shapes` runs an
hour apart on the same machine, same corpus fingerprint, same code:

| | run 1 | run 2 | change | within-run error |
|---|---|---|---|---|
| `query-by-entity-hot` (inmem) | 0.054 | 0.065 | **+20%** | 2.0% / 2.4% |
| `query-by-entity-cold` (inmem) | 0.104 | 0.123 | +18% | 17% / 7.4% |
| `query-by-id` (pg) | 47.109 | 41.557 | −12% | 5.0% / 4.2% |
| `query-cursor-walk` (pg) | 0.179 | 0.154 | −14% | 5.0% / 4.6% |
| `query-stream-page` (inmem) | 3.321 | 3.322 | 0.0% | 4.4% / 1.3% |

JMH's error bar describes the spread *within* one run — across its forks and iterations — and a fresh
JVM on a machine whose caches, frequency and background load have moved on is a wider question than
that. Some workloads reproduce to three digits and others move a fifth.

The practical rule: **a difference under about 15% between two separate runs is not a finding.** That
is why `compare --a --b` exists for configurations measured *in the same run*, where both sides share
a machine state, and why `report --baseline` is for watching a number over releases rather than for
adjudicating a change worth a few percent. It is also why the profile pairs in this suite always carry
a control workload that must not move: the control absorbs exactly this drift, and a ratio taken
against it says more than either number alone.

## How mutation is handled

Append benchmarks grow the store, which breaks JMH's steady-state premise. The policy is per volume
tier, and the drift is reported rather than hidden:

| tier | policy |
|---|---|
| 10³, 10⁵ | restored from a template table before every iteration |
| 10⁷ | restored once per trial; intra-trial drift measured, and a run above the profile's cap is not publishable |

**The cap is a fraction, and a fraction is the wrong shape at the largest tier** — which is why
`maxDrift` is a profile setting rather than a constant, defaulting to `0.02`. Above a million events
the corpus is restored once per *trial*, so an append workload accumulates for a whole fork and its
budget is a fixed number of events while the fraction that number represents shrinks with every tier.
At ten million, 2% is 200.000 appends: roughly eighty seconds at one writer and under ten at eight,
which is less than a single JMH iteration. No cadence fits that, so `large-tier-writes` declares 10%
and says why — and the report prints the cap beside the drift, always, so a run measured under a
widened allowance cannot be mistaken for one that was not.

What a cap protects is the *label*, not the measurement: a few percent of growth does not change a
B-tree's depth or a GIN index's shape, but it does decide whether "measured over ten million events"
is still true.

**A breach invalidates its workload, not the run.** It used to throw from the trial teardown, and with
JMH's fail-on-error that ended everything — one `large-tier` run lost eleven clean read workloads and
forty minutes of an external server to a twelfth that drifted 2.5%, and wrote no JSON at all, because
JMH emits results only at the end. The breach is now logged at ERROR and carried into the manifest,
where `--publish` refuses it, which is where the refusal was always going to happen.

Restore truncates and refills from a template, resets the position sequence, clears bookmarks,
puts the shredding key store back where one exists — keys minted by a benchmark
(`append-crm-new-subject`) are growth like any other, and leaving them behind made later
"new subject" appends measure the known-subject path — and re-`ANALYZE`s — without that last step
the planner holds statistics for a table that no longer exists.
Deleting only the appended rows would be cheaper and leaves dead tuples for autovacuum to reclaim
*during the next measurement*, which is noise exactly where it does most damage.

A profile whose workloads are all read-only skips restoring entirely, and the report says so.

## How a read addresses an entity, and why some workloads come in pairs

A workload that reads "one entity's history" has to decide *how* to name that entity, and under
`PER_ENTITY` the two available answers do not cost the same. Naming it by tag means a wildcard
purpose — and `stream_purpose` is the second column of both `idx_events_stream_position` and
`idx_events_stream_tags`, so leaving it unbound takes the equality off column two: an ordered read
can no longer descend to a start condition and a `LIMIT` cannot be pushed into the scan. Naming it by
stream keeps that column pinned.

That is a real property of the design, but charging it to a read that names a single entity describes
an application nobody would write. So the entity-scoped reads ship as **pairs**:

| by tag | by stream |
|---|---|
| `query-by-entity-hot` | `query-by-stream-hot` |
| `query-by-entity-cold` | `query-by-stream-cold` |
| `query-last-event` | `query-last-event-by-stream` |

Each pair reads the same events — verified by `dry-run`, which reports the count per workload — so
**the gap within a pair is the cost of the addressing, and the gap between the two profiles on the
same workload is the cost of the design.** Under `TAGGED` a tag filter is the only way to isolate an
entity, so each pair is one query written twice and must report the same number; a gap there is a
harness fault rather than a finding, which makes the tagged half a free control.

`query-stream-page` deliberately has **no** stream-addressed sibling. Reading a context in order
genuinely is a cross-entity read under `PER_ENTITY`, so the wildcard is the question rather than an
artefact of how it is asked — and a per-entity page would return one entity's fifty events against
five hundred, which is a different measurement rather than the same one addressed differently.

**The write side has no such pair, and `decide-then-append` reads its boundary the tagged way on
every corpus.** Its decide half — and the once-per-boundary read a conditional append does on first
touch — queries by tag through the wildcard-purpose stream, which under `PER_ENTITY` is exactly the
unbound-column-two addressing described above; an application that chose per-entity streams would
read the entity's own stream instead. The append half is addressed correctly, but the read half
dominates `decide-then-append` (~99% of it on an uncontended boundary), so on the stream-design pair
that workload charges `PER_ENTITY` for an addressing its users would not write and **understates its
advantage**. Read its cross-profile ratio as a floor, not a measurement of the design; the
addressing pairs above are where the read-side difference is measured honestly.

## How a conditional append avoids conflicting with itself

An append that succeeds moves the boundary it was checked against, so a workload holding one reference
and reusing it would succeed once and raise `OptimisticLockingException` on every invocation after —
measuring the failure path while appearing to measure the success path. Each conditional workload
therefore threads the reference forward from its own append's return value, which is what a real decider
does anyway. Two details make that actually work, and both were wrong first:

- **The cache is keyed on the filter, not on the entity.** Keying on `workload|sku` assumes a boundary
  belongs to one entity. That is true for `append-type-and-tag` and false for `append-types`, whose
  filter carries no tag at all and whose boundary therefore moves on *every* stock append anywhere in
  the store. The signature of getting this wrong is unmistakable once you know it: the first rotation
  through the entity slice succeeds and caches, the second conflicts on every single invocation, the
  third re-reads and succeeds — alternating rotations, with successful appends pinned at exactly one
  entity-slice per iteration and a conflict counter to match. What was published as the cost of a
  types-only DCB check was mostly the cost of failing one.
- **`append-or-groups-N` scopes its extra items to reserved companion entities.** They used to be
  ordinary entities in the writable rotation, so appending to one moved the boundary for every other
  entity's cached reference. The companions sit an eighth of the way into the Zipf distribution — warm
  enough to match real events at every tier, not the hot entity the contention modes aim at — and
  nothing ever appends to them. The disjuncts are still real over tag values that really match, so the
  selectivity the planner sees is unchanged; what changed is that only the entity being appended to can
  move the boundary.

**The first invocation for a boundary reads it, and how often that happens depends on the entity
count.** The cache starts empty each iteration, so an entity's first conditional append in an
iteration includes a backwards-limit-1 boundary probe and every later one is append-only. At the
medium tier that read amortizes to a few percent of invocations — two thousand entities against tens
of thousands of invocations per iteration. At the large tier it does not: a hundred thousand
entities against ten thousand invocations means the rotation almost never revisits an entity within
an iteration, so nearly **every** measured conditional append is probe-plus-append. Both are
legitimate measurements — the second is simply a different operation mix — so do not read
`append-type-and-tag` at ten million events against the same workload at a hundred thousand as the
cost of volume alone; `decide-then-append`, which always includes its read, is the cross-tier
comparable one.

`append-empty-boundary` has a related requirement: its entity has to be genuinely unused, or the
boundary it declares empty is not, and the append correctly raises. Drawing at random from a
hundred-thousand space is not fresh in any sense that survives the birthday paradox — a few thousand
draws collide with near-certainty. It counts instead.

**Under `one-boundary` all of this is deliberately not enough**, because there the other threads move the
boundary too and the conflicts are the measurement. Read a conflict count under `spread` as a harness
fault; read one under `one-boundary` as the result.

### The collision modes only separate under `PER_ENTITY`

A mode says where writers meet, and on PostgreSQL where they meet is decided by the append advisory
lock, which is keyed on `(prefix, stream_context, stream_purpose)`. So the three modes are three
questions only when a stream can tell two entities apart:

| mode | stream | boundary | what it measures |
|---|---|---|---|
| `spread` | one per entity | one per entity | the ceiling: no shared lock, no shared boundary |
| `one-stream` | the hot entity's, for everyone | the same rotation `spread` draws | the lock alone |
| `one-boundary` | the hot entity's | the hot entity's | the lock *and* conflict-and-retry |

`one-stream` is deliberately artificial: it writes one entity's events into another's stream, which
no application would do. That is the only arrangement that holds the boundaries, the filters and the
tags identical to `spread` and varies nothing but the lock.

**Under `TAGGED` none of this is expressible**, and the three `write-contention-*` profiles were
originally written that way. One stream per context is one advisory lock for the whole context, so
`spread` spreads boundaries and not locks, and its gap to `one-stream` is zero by construction —
which reads as "contention is free" rather than as "this profile cannot ask that question". Worse,
`one-stream` and `one-boundary` both aimed every thread at the hot entity, so they were one
measurement under two names: identical throughput, identical conflict counts, two reports. Both
halves are fixed — the profiles are `PER_ENTITY`, `one-stream` now varies the stream rather than the
entity, and a run pairing `one-stream` with any other design logs a warning saying what it is
actually measuring.

## Layout

```
domain/     the webshop contexts, the legacy hierarchy, the payload profiles
corpus/     spec, fingerprint, generator, manifest, provisioner, facts
workload/   the Workload SPI and its implementations -- shared by both runners
env/        targets, the target factory, the environment report
jmh/        @State holders, the benchmark class, the restore policy
load/       the load runner, latency recording, correctness checks
report/     the run report, its Markdown rendering, the two comparators
```

One rule matters more than the rest in `workload/`: **every read workload must run a terminal
operation on the stream it queries**. `query()` returns rows storage has already read but whose
deserialization is lazy, so handing back an unconsumed `Stream` would time the SQL and skip the serde
— the single easiest way to publish a wrong number here.
