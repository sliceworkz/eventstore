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
property and are otherwise identical — same volume, entity count, seed, workloads and targets — which
is what makes the percentage between them mean something.

| profile | the question | runtime |
|---|---|---|
| `smoke`, `smoke-postgres` | does the harness work | seconds |
| `dcb-cost-curve` | what the DCB check costs, and how it grows with OR-ed facts | ~15 min |
| `write-contention-spread` / `-one-stream` / `-one-boundary` | where throughput saturates, and how much is the advisory lock versus conflict-retry | ~20 min each |
| `read-shapes` ⇄ `crowded-store` ⇄ `crowded-database` | what a store holding other domains costs, and separately what sharing a database costs | ~20 min each |
| `stream-design-tagged` ⇄ `stream-design-per-entity` | which stream design to pick | ~30 min each |
| `metrics-cost` | what the library's own meters cost — three targets in one run, no `compare` needed | ~10 min |
| `shredding-cost` | what crypto-shredding costs per event and per new data subject | ~8 min |
| `upcasting-cost` ⇄ `read-shapes` | what reading history through upcasters costs | ~10 min |
| `replay-throughput` | what a read-model rebuild costs | ~10 min |
| `live-latency` | append → subscriber, and append → committed read-model row | ~5 min |
| `ingest-saturation` | sustained appends against a store that is growing | ~10 min |
| `large-tier` | the same reads at ten million events, on an external server | ~1.5 h + provisioning |

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
whatever the host happened to be), whose store drifted more than 2% during the run, or whose suite
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
  anything.
- **Query plans are representative, not real.** The backend builds its SQL internally and does not
  expose it, so the report captures `EXPLAIN` for statements matching the shapes it issues. Enough to
  answer *did the planner use an index*, and no substitute for the real thing if the query builder
  changes.
- **Load results carry correctness checks**, and a run that fails one is reported as unsound. Events
  in must equal events out; nothing may be projected twice.

## What the numbers do not mean

- **In-memory is not a deployment target.** It is there to answer "what does the library cost on top
  of the database", which no PostgreSQL measurement alone can separate out. Its append numbers in
  particular are a monitor, not a durable write.
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

## How mutation is handled

Append benchmarks grow the store, which breaks JMH's steady-state premise. The policy is per volume
tier, and the drift is reported rather than hidden:

| tier | policy |
|---|---|
| 10³, 10⁵ | restored from a template table before every iteration |
| 10⁷ | restored once per trial; intra-trial drift measured, and a run above 2% is not publishable |

Restore truncates and refills from a template, resets the position sequence, clears bookmarks and
re-`ANALYZE`s — without that last step the planner holds statistics for a table that no longer exists.
Deleting only the appended rows would be cheaper and leaves dead tuples for autovacuum to reclaim
*during the next measurement*, which is noise exactly where it does most damage.

A profile whose workloads are all read-only skips restoring entirely, and the report says so.

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

`append-empty-boundary` has a related requirement: its entity has to be genuinely unused, or the
boundary it declares empty is not, and the append correctly raises. Drawing at random from a
hundred-thousand space is not fresh in any sense that survives the birthday paradox — a few thousand
draws collide with near-certainty. It counts instead.

**Under `one-boundary` all of this is deliberately not enough**, because there the other threads move the
boundary too and the conflicts are the measurement. Read a conflict count under `spread` as a harness
fault; read one under `one-boundary` as the result.

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
